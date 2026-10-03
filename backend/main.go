package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"html"
	"html/template"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"

	"github.com/KabirSinghBhatia/BitChord/backend/clock"
	"github.com/KabirSinghBhatia/BitChord/backend/codes"
	"github.com/KabirSinghBhatia/BitChord/backend/config"
	"github.com/KabirSinghBhatia/BitChord/backend/devices"
	"github.com/KabirSinghBhatia/BitChord/backend/gateway"
	"github.com/KabirSinghBhatia/BitChord/backend/hub"
	"github.com/KabirSinghBhatia/BitChord/backend/party"
	"github.com/KabirSinghBhatia/BitChord/backend/protocol"
)

var (
	registry *devices.Registry
	store    = party.NewPartyStore()
	hubInst  = hub.NewHub()
	createLimiter = newIPRateLimiter(time.Minute, config.CreateRatePerMinute, config.RateLimitMaxEntries)
	connectLimiter = newIPRateLimiter(time.Minute, config.ConnectRatePerMinute, config.RateLimitMaxEntries)
	verifier = gateway.New(config.GatewayURL)
	upgrader = websocket.Upgrader{
		CheckOrigin: func(r *http.Request) bool {
			origin := r.Header.Get("Origin")
			return origin == "" || config.IsAllowedOrigin(origin)
		},
		// Answered to a browser that offers it (see tokenFromSubprotocol), so the
		// handshake completes without echoing the token-bearing protocol back.
		Subprotocols: []string{wsSubprotocol},
	}
)

func main() {
	go startHeartbeatTicker()

	mux := http.NewServeMux()

	// REST endpoints
	mux.HandleFunc("GET /{$}", handleRoot)
	mux.HandleFunc("GET /healthz", handleHealthz)
	mux.HandleFunc("GET /api/time", handleTime)
	mux.HandleFunc("POST /api/parties", handleCreateParty)
	mux.HandleFunc("POST /api/parties/{code}/join", handleJoinParty)
	mux.HandleFunc("GET /api/parties/{code}", handleGetParty)
	mux.HandleFunc("GET /api/parties/{code}/preview", handlePreviewParty)
	mux.HandleFunc("POST /api/parties/{code}/leave", handleLeaveParty)
	mux.HandleFunc("POST /api/connect", handleConnect)

	// Web invite endpoint
	mux.HandleFunc("GET /invite/{code}", handleInviteLanding)

	// WebSocket endpoint
	mux.HandleFunc("GET /ws/parties/{code}", handleWebSocket)

	handler := corsMiddleware(mux)

	addr := fmt.Sprintf("0.0.0.0:%d", config.Port)
	log.Printf("BitChord Listen Together (Go) starting on %s...", addr)
	server := &http.Server{
		Addr:              addr,
		Handler:           handler,
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       20 * time.Second,
		WriteTimeout:      20 * time.Second,
		IdleTimeout:       60 * time.Second,
	}
	if err := server.ListenAndServe(); err != nil {
		log.Fatalf("Server stopped: %v", err)
	}
}

func corsMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		origin := r.Header.Get("Origin")
		if origin != "" && config.IsAllowedOrigin(origin) {
			w.Header().Set("Access-Control-Allow-Origin", origin)
			w.Header().Set("Vary", "Origin")
			w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
			w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization")
		}
		if r.Method == "OPTIONS" {
			if origin != "" && !config.IsAllowedOrigin(origin) {
				http.Error(w, "origin not allowed", http.StatusForbidden)
				return
			}
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next.ServeHTTP(w, r)
	})
}

type ipRateLimiter struct {
	mu      sync.Mutex
	window  time.Duration
	limit   int
	maxKeys int
	entries map[string]ipRateEntry
}

type ipRateEntry struct {
	started time.Time
	count   int
}

func newIPRateLimiter(window time.Duration, limit, maxKeys int) *ipRateLimiter {
	return &ipRateLimiter{window: window, limit: limit, maxKeys: maxKeys, entries: make(map[string]ipRateEntry)}
}

func (l *ipRateLimiter) Allow(ip string) bool {
	if l.limit <= 0 {
		return true
	}
	now := time.Now()
	l.mu.Lock()
	defer l.mu.Unlock()
	for key, entry := range l.entries {
		if now.Sub(entry.started) >= l.window {
			delete(l.entries, key)
		}
	}
	entry := l.entries[ip]
	if entry.started.IsZero() || now.Sub(entry.started) >= l.window {
		if entry.started.IsZero() && l.maxKeys > 0 && len(l.entries) >= l.maxKeys {
			return false
		}
		l.entries[ip] = ipRateEntry{started: now, count: 1}
		return true
	}
	if entry.count >= l.limit {
		return false
	}
	entry.count++
	l.entries[ip] = entry
	return true
}

func clientIP(r *http.Request) string {
	if config.TrustProxy {
		if forwarded := strings.TrimSpace(strings.Split(r.Header.Get("X-Forwarded-For"), ",")[0]); forwarded != "" {
			return forwarded
		}
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err == nil {
		return host
	}
	return r.RemoteAddr
}

func decodeJSONBody(w http.ResponseWriter, r *http.Request, dst interface{}) bool {
	r.Body = http.MaxBytesReader(w, r.Body, config.RequestMaxBytes)
	defer r.Body.Close()
	decoder := json.NewDecoder(r.Body)
	if err := decoder.Decode(dst); err != nil {
		var maxErr *http.MaxBytesError
		if errors.As(err, &maxErr) {
			jsonError(w, http.StatusRequestEntityTooLarge, "request_too_large", "Request body is too large.")
		} else {
			jsonError(w, http.StatusUnprocessableEntity, "invalid_json", "Malformed JSON body.")
		}
		return false
	}
	if err := decoder.Decode(&struct{}{}); err != io.EOF {
		jsonError(w, http.StatusUnprocessableEntity, "invalid_json", "Request body must contain one JSON object.")
		return false
	}
	return true
}

func jsonResponse(w http.ResponseWriter, status int, data interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(data)
}

func jsonError(w http.ResponseWriter, status int, errCode, msg string) {
	jsonResponse(w, status, map[string]string{
		"error":   errCode,
		"message": msg,
	})
}

// wsSubprotocol is the WebSocket subprotocol a browser offers alongside its
// token, and the one the server answers with.
const wsSubprotocol = "bitchord"

// wsTokenPrefix marks the offered subprotocol that carries a member token.
const wsTokenPrefix = "bitchord.token."

// tokenFromSubprotocol reads a member token from the WebSocket subprotocols a
// browser offered, for the browser's sake: a page cannot set an Authorization
// header on a WebSocket, and a token in the URL would be written into proxy and
// server logs. The browser offers ["bitchord", "bitchord.token.<token>"]; the
// server picks "bitchord", so the token never comes back in the response.
//
// Native clients keep sending Authorization, which wins when both are present.
func tokenFromSubprotocol(r *http.Request) string {
	for _, p := range websocket.Subprotocols(r) {
		if strings.HasPrefix(p, wsTokenPrefix) {
			return strings.TrimPrefix(p, wsTokenPrefix)
		}
	}
	return ""
}

func parseBearerToken(r *http.Request) string {
	auth := r.Header.Get("Authorization")
	parts := strings.SplitN(auth, " ", 2)
	if len(parts) == 2 && strings.EqualFold(parts[0], "Bearer") {
		return strings.TrimSpace(parts[1])
	}
	return ""
}

// REST Handlers

func handleRoot(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/" {
		http.NotFound(w, r)
		return
	}
	jsonResponse(w, http.StatusOK, map[string]interface{}{
		"service":    "bitchord-listen-together",
		"members":    store.MemberCount(),
		"parties":    store.Len(),
		"serverMs":   clock.NowMs(),
	})
}

func handleHealthz(w http.ResponseWriter, r *http.Request) {
	jsonResponse(w, http.StatusOK, map[string]interface{}{
		"ok":       true,
		"serverMs": clock.NowMs(),
	})
}

func handleTime(w http.ResponseWriter, r *http.Request) {
	jsonResponse(w, http.StatusOK, map[string]interface{}{
		"serverMs": clock.NowMs(),
	})
}

func handleCreateParty(w http.ResponseWriter, r *http.Request) {
	if !createLimiter.Allow(clientIP(r)) {
		jsonError(w, http.StatusTooManyRequests, "create_rate_limited", fmt.Sprintf("You can create up to %d parties per minute. Please try again shortly.", config.CreateRatePerMinute))
		return
	}
	var req protocol.JoinRequest
	if !decodeJSONBody(w, r, &req) {
		return
	}
	if err := req.Validate(); err != nil {
		jsonError(w, http.StatusUnprocessableEntity, "validation_error", err.Error())
		return
	}
	if req.Role == protocol.RoleRemote {
		jsonError(w, http.StatusUnprocessableEntity, "remote_cannot_host", "A party has to be started from the device that plays it.")
		return
	}

	maxMembers := 5
	if req.MaxMembers != nil { maxMembers = *req.MaxMembers }
	p, err := store.CreateWithLimits(config.MaxParties, maxMembers)
	if err != nil {
		if pe, ok := err.(*party.PartyError); ok {
			jsonError(w, pe.Status, pe.Code, pe.Message)
			return
		}
		jsonError(w, http.StatusInternalServerError, "server_error", "Failed to create party.")
		return
	}
	// This must be present in the creation response. Waiting for the first
	// WebSocket control can lose the setting before that socket is established.
	if req.AutoplayEnabled != nil {
		p.Playback.AutoplayEnabled = *req.AutoplayEnabled
	}

	p.Lock()
	m, err := p.Join(req.UserId, req.DeviceId, req.DisplayName, req.AvatarUrl)
	if err != nil {
		p.Unlock()
		store.Drop(p.Code)
		if pe, ok := err.(*party.PartyError); ok {
			jsonError(w, pe.Status, pe.Code, pe.Message)
			return
		}
		jsonError(w, http.StatusInternalServerError, "server_error", err.Error())
		return
	}
	partyWire := p.ToWire()
	youWire := m.ToWire()
	token := m.Token
	code := p.Code
	p.Unlock()

	jsonResponse(w, http.StatusCreated, map[string]interface{}{
		"code":  code,
		"token": token,
		"you":   youWire,
		"party": partyWire,
	})
}

func handleJoinParty(w http.ResponseWriter, r *http.Request) {
	code := r.PathValue("code")
	var req protocol.JoinRequest
	if !decodeJSONBody(w, r, &req) {
		return
	}
	if err := req.Validate(); err != nil {
		jsonError(w, http.StatusUnprocessableEntity, "validation_error", err.Error())
		return
	}

	if party.IsAccountCode(code) {
		jsonError(w, http.StatusNotFound, "no_such_party", "No party with that code.")
		return
	}
	p, err := store.Get(code)
	if err != nil {
		if pe, ok := err.(*party.PartyError); ok {
			jsonError(w, pe.Status, pe.Code, pe.Message)
			return
		}
		jsonError(w, http.StatusNotFound, "no_such_party", "No party with that code.")
		return
	}

	p.Lock()
	m, err := p.JoinAs(req.UserId, req.DeviceId, req.DisplayName, req.AvatarUrl, req.Role)
	if err != nil {
		p.Unlock()
		if pe, ok := err.(*party.PartyError); ok {
			jsonError(w, pe.Status, pe.Code, pe.Message)
			return
		}
		jsonError(w, http.StatusInternalServerError, "server_error", err.Error())
		return
	}
	partyWire := p.ToWire()
	youWire := m.ToWire()
	token := m.Token
	pCode := p.Code
	p.Unlock()

	jsonResponse(w, http.StatusOK, map[string]interface{}{
		"code":  pCode,
		"token": token,
		"you":   youWire,
		"party": partyWire,
	})
}

func init() {
	registry = devices.Open(devicesPath(), config.PushHosts, time.Duration(config.DeviceForgetMs)*time.Millisecond)
}

func devicesPath() string {
	if config.DataDir == "" {
		return ""
	}
	return config.DataDir + "/devices.json"
}

// withDevices adds an account's known devices to a Connect party's snapshot
// or members frame: what the devices sheet lists beside the ones connected,
// including the ones asleep that a push can wake.
func withDevices(p *party.Party, wire map[string]interface{}) map[string]interface{} {
	if !p.IsConnect() {
		return wire
	}
	known := registry.List(p.Account)
	list := make([]map[string]interface{}, 0, len(known))
	now := clock.NowMs()
	for _, d := range known {
		status := d.Status
		if status != "" && now-d.StatusAtMs > config.JamStatusMs {
			status = ""
		}
		list = append(list, map[string]interface{}{
			"status":     status,
			"deviceId":   d.DeviceId,
			"deviceKey":  d.DeviceKey,
			"app":        d.App,
			"deviceName": d.DeviceName,
			"wakeable":   d.Wakeable(),
			"lastSeenMs": d.LastSeenMs,
			"connected":  p.HasDevice(d.DeviceId),
		})
	}
	wire["devices"] = list
	return wire
}

// handleConnect signs one of an account's devices into its Connect party.
//
// The answer has the same shape as a join, so the app drives a Connect party
// with exactly the socket, state and controls it uses for a jam.
func handleConnect(w http.ResponseWriter, r *http.Request) {
	if !verifier.Enabled() {
		jsonError(w, http.StatusServiceUnavailable, "connect_disabled", gateway.ErrDisabled.Error())
		return
	}
	if !connectLimiter.Allow(clientIP(r)) {
		jsonError(w, http.StatusTooManyRequests, "connect_rate_limited", "Too many sign-ins from here. Please try again shortly.")
		return
	}
	var req protocol.ConnectRequest
	if !decodeJSONBody(w, r, &req) {
		return
	}
	if err := req.Validate(); err != nil {
		jsonError(w, http.StatusUnprocessableEntity, "validation_error", err.Error())
		return
	}
	account, err := verifier.Verify(r.Context(), req.GatewayUser, req.GatewayToken, req.GatewaySalt)
	switch {
	case errors.Is(err, gateway.ErrRejected):
		jsonError(w, http.StatusUnauthorized, "bad_login", err.Error())
		return
	case err != nil:
		jsonError(w, http.StatusBadGateway, "gateway_unreachable", err.Error())
		return
	}

	registry.Seen(account, devices.Device{
		DeviceId:     req.DeviceKey + ":" + req.App,
		DeviceKey:    req.DeviceKey,
		App:          req.App,
		DeviceName:   req.DeviceName,
		PushEndpoint: req.PushEndpoint,
	}, clock.NowMs())

	p := store.Account(account)
	p.Lock()
	m, err := p.JoinConnect("gw:"+account, req.DeviceKey, req.App, req.DeviceName, req.DisplayName, req.AvatarUrl, req.Playing)
	if err != nil {
		p.Unlock()
		if pe, ok := err.(*party.PartyError); ok {
			jsonError(w, pe.Status, pe.Code, pe.Message)
			return
		}
		jsonError(w, http.StatusInternalServerError, "server_error", err.Error())
		return
	}
	partyWire := withDevices(p, p.ToWire())
	youWire := m.ToWire()
	token := m.Token
	code := p.Code
	// Joining can move the output, which every device already here must hear.
	mFrame := membersFrame(p)
	sFrame := stateFrame(p)
	p.Unlock()
	hubInst.Broadcast(code, mFrame, "")
	hubInst.Broadcast(code, sFrame, "")

	jsonResponse(w, http.StatusOK, map[string]interface{}{
		"code":  code,
		"token": token,
		"you":   youWire,
		"party": partyWire,
	})
}

// handlePreviewParty answers who is in a party, without a token and without
// joining it.
//
// Deliberately unauthenticated: the whole point is to let somebody who has been
// handed a code see who they would be joining before they commit a device slot
// to it. What it discloses — display names, avatars, how full the party is — is
// exactly what joining would disclose a second later, and anyone holding a code
// can join. What it does not disclose is what the party is playing, its queue,
// member or user ids, or anything that would let a caller act on the party.
//
// Not rate-limited beyond the service-wide limits: it takes no locks it does
// not release, allocates a short slice, and creates nothing.
func handlePreviewParty(w http.ResponseWriter, r *http.Request) {
	code := r.PathValue("code")
	if party.IsAccountCode(code) {
		jsonError(w, http.StatusNotFound, "no_such_party", "No party with that code.")
		return
	}
	p, err := store.Get(code)
	if err != nil {
		if pe, ok := err.(*party.PartyError); ok {
			jsonError(w, pe.Status, pe.Code, pe.Message)
			return
		}
		jsonError(w, http.StatusNotFound, "no_such_party", "No party with that code.")
		return
	}

	p.Lock()
	// Joined order, as the snapshot uses: the caller draws the first few faces
	// and counts the rest, so which faces those are must not change between two
	// reads of an unchanged party.
	ordered := make([]*party.Member, 0, len(p.Members))
	for _, m := range p.Members {
		ordered = append(ordered, m)
	}
	sort.Slice(ordered, func(i, j int) bool {
		return ordered[i].JoinedAtMs < ordered[j].JoinedAtMs
	})
	members := make([]map[string]interface{}, 0, len(ordered))
	hostName := ""
	for _, m := range ordered {
		members = append(members, map[string]interface{}{
			"displayName": m.DisplayName,
			"avatarUrl":   m.AvatarUrl,
			"isHost":      m.IsHost,
			"role":        m.Role,
		})
		if m.IsHost {
			hostName = m.DisplayName
		}
	}
	preview := map[string]interface{}{
		"code":        p.Code,
		"hostName":    hostName,
		"memberCount": len(p.Members),
		"maxMembers":  p.MaxMembers,
		"isFull":      p.SpeakerCount() >= p.MaxMembers,
		"members":     members,
	}
	p.Unlock()

	jsonResponse(w, http.StatusOK, preview)
}

func handleGetParty(w http.ResponseWriter, r *http.Request) {
	code := r.PathValue("code")
	token := parseBearerToken(r)
	if token == "" {
		jsonError(w, http.StatusUnauthorized, "unauthorized", "Missing bearer token.")
		return
	}

	p, err := store.Get(code)
	if err != nil {
		if pe, ok := err.(*party.PartyError); ok {
			jsonError(w, pe.Status, pe.Code, pe.Message)
			return
		}
		jsonError(w, http.StatusNotFound, "no_such_party", "No party with that code.")
		return
	}

	p.Lock()
	_, err = p.Authenticate(token)
	if err != nil {
		p.Unlock()
		if pe, ok := err.(*party.PartyError); ok {
			jsonError(w, pe.Status, pe.Code, pe.Message)
			return
		}
		jsonError(w, http.StatusUnauthorized, "unauthorized", "Bad token.")
		return
	}
	partyWire := p.ToWire()
	p.Unlock()

	jsonResponse(w, http.StatusOK, partyWire)
}

func handleLeaveParty(w http.ResponseWriter, r *http.Request) {
	code := r.PathValue("code")
	token := parseBearerToken(r)
	if token == "" {
		jsonError(w, http.StatusUnauthorized, "unauthorized", "Missing bearer token.")
		return
	}

	p, err := store.Get(code)
	if err != nil {
		if pe, ok := err.(*party.PartyError); ok {
			jsonError(w, pe.Status, pe.Code, pe.Message)
			return
		}
		jsonError(w, http.StatusNotFound, "no_such_party", "No party with that code.")
		return
	}

	p.Lock()
	member, err := p.Authenticate(token)
	if err != nil {
		p.Unlock()
		if pe, ok := err.(*party.PartyError); ok {
			jsonError(w, pe.Status, pe.Code, pe.Message)
			return
		}
		jsonError(w, http.StatusUnauthorized, "unauthorized", "Bad token.")
		return
	}

	memberId := member.MemberId
	if p.IsConnect() && r.URL.Query().Get("reason") == "jam" {
		registry.SetStatus(p.Account, member.DeviceId, "jam", clock.NowMs())
	}
	p.Remove(memberId)
	membersFrame := membersFrame(p)
	p.Unlock()

	// Notify room and drop leaving member socket
	hubInst.Send(p.Code, memberId, map[string]interface{}{
		"type":   protocol.FrameBye,
		"reason": "left",
	})
	hubInst.Broadcast(p.Code, membersFrame, memberId)

	jsonResponse(w, http.StatusOK, map[string]bool{"ok": true})
}

// Web Invite Landing Handler

type invitePageData struct {
	Code              string
	DeepLink          string
	IntentURI         template.URL
	SafeDeepLink      template.URL
	ServerOrigin      string
	CurrentSongTitle  string
	CurrentSongArtist string
	MemberCount       int
	IsActive          bool
}

var inviteTemplate = template.Must(template.New("invite").Parse(`<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>BitChord Listen Together - Party {{.Code}}</title>
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            background-color: #0b0b0e;
            color: #f3f3f7;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
            min-height: 100vh;
            display: flex;
            align-items: center;
            justify-content: center;
            padding: 24px;
        }
        .container {
            background: rgba(22, 22, 30, 0.85);
            backdrop-filter: blur(24px);
            -webkit-backdrop-filter: blur(24px);
            border: 1px solid rgba(255, 255, 255, 0.08);
            border-radius: 28px;
            max-width: 440px;
            width: 100%;
            padding: 36px 28px;
            text-align: center;
            box-shadow: 0 20px 50px rgba(0, 0, 0, 0.5), 0 0 40px rgba(124, 77, 255, 0.1);
        }
        .badge {
            display: inline-flex;
            align-items: center;
            gap: 6px;
            background: rgba(124, 77, 255, 0.15);
            color: #b388ff;
            font-size: 13px;
            font-weight: 600;
            padding: 6px 14px;
            border-radius: 9999px;
            margin-bottom: 20px;
            letter-spacing: 0.5px;
        }
        .badge-dot {
            width: 8px;
            height: 8px;
            background: #00e676;
            border-radius: 50%;
            box-shadow: 0 0 8px #00e676;
        }
        .badge-dot.offline {
            background: #ff5252;
            box-shadow: 0 0 8px #ff5252;
        }
        h1 {
            font-size: 24px;
            font-weight: 700;
            margin-bottom: 8px;
            letter-spacing: -0.5px;
        }
        .subtitle {
            color: #9e9ea7;
            font-size: 15px;
            line-height: 1.5;
            margin-bottom: 28px;
        }
        .code-box {
            background: rgba(255, 255, 255, 0.04);
            border: 1px solid rgba(255, 255, 255, 0.1);
            border-radius: 18px;
            padding: 16px 20px;
            margin-bottom: 28px;
        }
        .code-label {
            font-size: 12px;
            color: #71717a;
            text-transform: uppercase;
            letter-spacing: 1.5px;
            margin-bottom: 6px;
        }
        .code-val {
            font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
            font-size: 32px;
            font-weight: 800;
            letter-spacing: 6px;
            color: #ffffff;
        }
        .now-playing {
            font-size: 14px;
            color: #d1d1d6;
            margin-top: 10px;
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
        }
        .btn {
            display: block;
            width: 100%;
            padding: 16px 24px;
            background: linear-gradient(135deg, #7c4dff 0%, #3d5afe 100%);
            color: #ffffff;
            font-size: 16px;
            font-weight: 600;
            text-decoration: none;
            border-radius: 16px;
            border: none;
            cursor: pointer;
            transition: transform 0.15s ease, opacity 0.15s ease;
            box-shadow: 0 8px 24px rgba(124, 77, 255, 0.35);
        }
        .btn:hover {
            transform: translateY(-1px);
            opacity: 0.95;
        }
        .btn:active {
            transform: scale(0.98);
        }
        .footer-note {
            margin-top: 24px;
            font-size: 13px;
            color: #71717a;
            line-height: 1.5;
        }
        .footer-note a {
            color: #b388ff;
            text-decoration: none;
        }
        .footer-note a:hover {
            text-decoration: underline;
        }
    </style>
</head>
<body>
    <div class="container">
        {{if .IsActive}}
            <div class="badge">
                <span class="badge-dot"></span>
                <span>Listen Together • {{.MemberCount}} in party</span>
            </div>
            <h1>Join the Music Party</h1>
            <p class="subtitle">Opening BitChord to sync playback in real time.</p>
            <div class="code-box">
                <div class="code-label">Party Code</div>
                <div class="code-val">{{.Code}}</div>
                {{if .CurrentSongTitle}}
                    <div class="now-playing">🎵 {{.CurrentSongTitle}} - {{.CurrentSongArtist}}</div>
                {{end}}
            </div>
            <a id="joinBtn" href="{{.IntentURI}}" class="btn">Join Party in BitChord</a>
            <p class="footer-note">
                Didn’t open automatically? Tap the button above.<br>
                Don't have BitChord yet? <a href="https://github.com/kushagrasinghx/BitChord/releases" target="_blank" rel="noopener">Download it here</a>.
            </p>
            <script>
                var intentUri = {{.IntentURI}};
                var deepLink = {{.SafeDeepLink}};
                function launch() {
                    if (/Android/i.test(navigator.userAgent)) {
                        window.location.href = intentUri;
                    } else {
                        window.location.href = deepLink;
                    }
                }
                setTimeout(launch, 100);
            </script>
        {{else}}
            <div class="badge">
                <span class="badge-dot offline"></span>
                <span>Party Inactive</span>
            </div>
            <h1>Party Not Found</h1>
            <p class="subtitle">This party code has expired or does not exist on this server.</p>
            <div class="code-box">
                <div class="code-label">Party Code</div>
                <div class="code-val" style="color: #a1a1aa;">{{.Code}}</div>
            </div>
            <p class="footer-note">
                Please ask the host for a new invite link or check your server configuration.
            </p>
        {{end}}
    </div>
</body>
</html>`))

func requestOrigin(r *http.Request) string {
	proto := "http"
	if r.TLS != nil {
		proto = "https"
	}
	if config.TrustProxy {
		if forwardedProto := r.Header.Get("X-Forwarded-Proto"); forwardedProto != "" {
			proto = strings.TrimSpace(strings.Split(forwardedProto, ",")[0])
		}
	}
	host := r.Host
	if host == "" {
		host = "localhost"
	}
	return fmt.Sprintf("%s://%s", proto, host)
}

func handleInviteLanding(w http.ResponseWriter, r *http.Request) {
	code := codes.Normalise(r.PathValue("code"))
	origin := requestOrigin(r)
	w.Header().Set("Content-Type", "text/html; charset=utf-8")

	if len(code) != codes.CodeLength {
		w.WriteHeader(http.StatusBadRequest)
		_ = inviteTemplate.Execute(w, invitePageData{
			Code:     html.EscapeString(r.PathValue("code")),
			IsActive: false,
		})
		return
	}

	p := store.Find(code)
	if p == nil {
		w.WriteHeader(http.StatusNotFound)
		_ = inviteTemplate.Execute(w, invitePageData{
			Code:     code,
			IsActive: false,
		})
		return
	}

	deepLink := fmt.Sprintf("bitchord://party/%s?server=%s", url.PathEscape(code), url.QueryEscape(origin))
	intentURI := fmt.Sprintf("intent://party/%s?server=%s#Intent;scheme=bitchord;end", url.PathEscape(code), url.QueryEscape(origin))

	currentSongTitle := ""
	currentSongArtist := ""
	p.Lock()
	if p.Playback != nil && p.Playback.Track != nil {
		currentSongTitle = p.Playback.Track.Title
		currentSongArtist = p.Playback.Track.Artist
	}
	memberCount := len(p.Members)
	p.Unlock()

	w.WriteHeader(http.StatusOK)
	_ = inviteTemplate.Execute(w, invitePageData{
		Code:              code,
		DeepLink:          deepLink,
		IntentURI:         template.URL(intentURI),
		SafeDeepLink:      template.URL(deepLink),
		ServerOrigin:      origin,
		CurrentSongTitle:  currentSongTitle,
		CurrentSongArtist: currentSongArtist,
		MemberCount:       memberCount,
		IsActive:          true,
	})
}

// WebSocket Handler

func handleWebSocket(w http.ResponseWriter, r *http.Request) {
	code := r.PathValue("code")
	token := parseBearerToken(r)
	if token == "" {
		token = tokenFromSubprotocol(r)
	}
	if token == "" {
		http.Error(w, "missing token", http.StatusUnauthorized)
		return
	}

	p, err := store.Get(code)
	if err != nil {
		http.Error(w, "no such party", http.StatusNotFound)
		return
	}

	p.Lock()
	member, err := p.Authenticate(token)
	if err != nil {
		p.Unlock()
		http.Error(w, "bad token", http.StatusUnauthorized)
		return
	}
	p.Unlock()

	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("WebSocket upgrade failed: %v", err)
		return
	}
	conn.SetReadLimit(config.WebSocketMaxBytes)
	_ = conn.SetReadDeadline(time.Now().Add(time.Duration(config.ConnectionIdleMs) * time.Millisecond))

	p.Lock()
	p.MarkConnected(member, true)
	p.Unlock()

	sc := hubInst.Attach(p.Code, member.MemberId, conn)

	// Send welcome frame
	p.Lock()
	welcome := map[string]interface{}{
		"type":     protocol.FrameWelcome,
		"you":      member.ToWire(),
		"party":    withDevices(p, p.ToWire()),
		"serverMs": clock.NowMs(),
	}
	membersF := membersFrame(p)
	p.Unlock()

	_ = sc.WriteJSON(welcome)
	hubInst.Broadcast(p.Code, membersF, "")

	// Read loop
	defer func() {
		hubInst.Detach(p.Code, member.MemberId, sc)
		_ = sc.Close()

		p.Lock()
		p.MarkConnected(member, false)
		mFrame := membersFrame(p)
		p.Unlock()

		hubInst.Broadcast(p.Code, mFrame, "")
	}()

	for {
		var raw map[string]interface{}
		if err := conn.ReadJSON(&raw); err != nil {
			break
		}
		_ = conn.SetReadDeadline(time.Now().Add(time.Duration(config.ConnectionIdleMs) * time.Millisecond))
		handleSocketFrame(p, member, sc, raw)
	}
}

func handleSocketFrame(p *party.Party, member *party.Member, sc *hub.SafeConn, frame map[string]interface{}) {
	kind, _ := frame["type"].(string)
	if kind == "" {
		return
	}

	p.Lock()
	defer p.Unlock()
	if !p.SpendFrameBudget(member) {
		_ = sc.WriteJSON(map[string]interface{}{
			"type": protocol.FrameError, "error": "rate_limited", "message": "Too many messages at once.",
		})
		return
	}

	member.LastSeenMs = clock.NowMs()

	switch kind {
	case protocol.FramePing:
		_ = sc.WriteJSON(map[string]interface{}{
			"type":     protocol.FramePong,
			"clientMs": frame["clientMs"],
			"serverMs": clock.NowMs(),
		})

	case protocol.FrameSync:
		_ = sc.WriteJSON(stateFrame(p))

	case protocol.FrameSyncQueue:
		_ = sc.WriteJSON(queueFrame(p))

	case protocol.FrameReport:
		posNum, ok := frame["positionMs"].(float64)
		if !ok || !p.Playback.IsPlaying {
			break
		}
		now := clock.NowMs()
		// A measured report is the device's real playhead, at a server time it
		// stamps itself. Older clients send the position they computed from the
		// party instead, which can only ever agree with it, so only a report
		// that says it was measured is allowed to move anything.
		if measured, _ := frame["measured"].(bool); measured {
			atMs := now
			if at, ok := frame["atMs"].(float64); ok {
				atMs = int64(at)
			}
			videoId, _ := frame["videoId"].(string)
			if dur, ok := frame["durationMs"].(float64); ok && p.LearnDuration(member, videoId, int64(dur)) {
				hubInst.Broadcast(p.Code, stateFrame(p), "")
			}
			if p.Reanchor(member, videoId, int64(posNum), atMs, now) {
				log.Printf("party %s: re-anchored onto %s at %dms", p.Code, member.DisplayName, int64(posNum))
				hubInst.Broadcast(p.Code, stateFrame(p), "")
			}
			break
		}
		drift := int64(posNum) - p.Playback.PositionAt(now)
		if drift > 1500 || drift < -1500 {
			log.Printf("party %s: %s drifted %dms", p.Code, member.DisplayName, drift)
		}

	case protocol.FrameControl:
		if !p.SpendControlBudget(member) {
			_ = sc.WriteJSON(map[string]interface{}{
				"type":    protocol.FrameError,
				"error":   "rate_limited",
				"message": "Too many controls at once.",
			})
			return
		}

		queueBefore := p.Playback.QueueSeq
		action, _ := frame["action"].(string)
		// One person's own devices: which of them did what is the first thing
		// to look at when playback does something nobody asked for.
		if p.IsConnect() && !protocol.QuietActions[action] {
			log.Printf("connect %s: %s (%s) sent %s", p.Code, member.DeviceName, member.App, action)
		}
		success, errCode, errMsg := applyControl(p, member, action, frame)
		if success {
			p.Touch()
			// If queue changed, broadcast queue first
			if p.Playback.QueueSeq != queueBefore {
				hubInst.Broadcast(p.Code, queueFrame(p), "")
			}
			// Members before state on a transfer: a device has to know it is
			// now the one playing before the state that asks it to start.
			if action == protocol.ActionTransfer {
				hubInst.Broadcast(p.Code, membersFrame(p), "")
			}
			hubInst.Broadcast(p.Code, stateFrame(p), "")
			if action == protocol.ActionKick ||
				action == protocol.ActionSetMaxMembers ||
				action == protocol.ActionSetHostOnlyControl {
				hubInst.Broadcast(p.Code, membersFrame(p), "")
			}
			if !protocol.QuietActions[action] {
				hubInst.Broadcast(p.Code, activityFrame(member, action, frame), "")
			}
		} else if errCode != "" {
			_ = sc.WriteJSON(map[string]interface{}{
				"type":    protocol.FrameError,
				"error":   errCode,
				"message": errMsg,
			})
		}
	}
}

func applyControl(p *party.Party, member *party.Member, action string, frame map[string]interface{}) (bool, string, string) {
	// Enforced here rather than left to the clients. An app that hides its own
	// next button is a courtesy; this is what actually stops a listener's
	// device — a stale build, a backgrounded one still echoing an old intent,
	// or something else entirely — from moving the music for everybody.
	if protocol.ControlActions[action] && !p.MayControl(member) {
		return false, "host_only", "Only the host can control the music in this party."
	}

	var posPtr *int64
	if pos, ok := frame["positionMs"].(float64); ok {
		pVal := int64(pos)
		posPtr = &pVal
	}

	switch action {
	case protocol.ActionPlay:
		p.Playback.Play(&member.MemberId, posPtr)
		return true, "", ""

	case protocol.ActionPause:
		p.Playback.Pause(&member.MemberId, posPtr)
		return true, "", ""

	case protocol.ActionSeek:
		if posPtr == nil {
			return false, "missing_position", "seek requires positionMs"
		}
		p.Playback.Seek(&member.MemberId, *posPtr)
		return true, "", ""

	case protocol.ActionSetTrack:
		trackMap, _ := frame["track"].(map[string]interface{})
		t := party.TrackFromWire(trackMap)
		if t == nil {
			return false, "invalid_track", "Track must include videoId"
		}
		pos := int64(0)
		if posPtr != nil {
			pos = *posPtr
		}
		isPlaying := true
		if pVal, ok := frame["isPlaying"].(bool); ok {
			isPlaying = pVal
		}
		var qIndexPtr *int
		if qIndex, ok := frame["queueIndex"].(float64); ok {
			qi := int(qIndex)
			qIndexPtr = &qi
		}
		p.Playback.SetTrack(&member.MemberId, t, pos, isPlaying, qIndexPtr, &member.DisplayName)
		return true, "", ""

	case protocol.ActionSetQueue:
		itemsRaw, _ := frame["queue"].([]interface{})
		var tracks []*party.Track
		for _, item := range itemsRaw {
			if m, ok := item.(map[string]interface{}); ok {
				if tr := party.TrackFromWire(m); tr != nil {
					tracks = append(tracks, tr)
				}
			}
		}
		qIdx := -1
		if idxNum, ok := frame["queueIndex"].(float64); ok {
			qIdx = int(idxNum)
		}
		p.Playback.SetQueue(&member.MemberId, tracks, qIdx)
		return true, "", ""

	case protocol.ActionQueueAdd:
		var tracksToAdd []*party.Track
		if singleMap, ok := frame["track"].(map[string]interface{}); ok {
			if tr := party.TrackFromWire(singleMap); tr != nil {
				tracksToAdd = append(tracksToAdd, tr)
			}
		} else if itemsRaw, ok := frame["tracks"].([]interface{}); ok {
			for _, item := range itemsRaw {
				if m, ok := item.(map[string]interface{}); ok {
					if tr := party.TrackFromWire(m); tr != nil {
						tracksToAdd = append(tracksToAdd, tr)
					}
				}
			}
		}
		playNext, _ := frame["playNext"].(bool)
		ok, reason := p.Playback.AddUpcoming(&member.MemberId, tracksToAdd, playNext)
		if !ok {
			if reason == "queue_full" {
				return false, "queue_full", fmt.Sprintf("Queue is full (maximum %d upcoming songs).", p.Playback.MaxUpcoming)
			}
			return false, reason, "Could not add track to queue."
		}
		return true, "", ""

	case protocol.ActionQueueRemove:
		vid, _ := frame["videoId"].(string)
		if vid == "" {
			if idxNum, ok := frame["index"].(float64); ok {
				idx := int(idxNum)
				if idx >= 0 && idx < len(p.Playback.Queue) {
					vid = p.Playback.Queue[idx].VideoId
				}
			}
		}
		if vid == "" {
			return false, "missing_track", "queueRemove requires videoId or index"
		}
		if !p.Playback.RemoveUpcoming(&member.MemberId, vid) {
			return false, "not_found", "Track is not in the upcoming queue"
		}
		return true, "", ""

	case protocol.ActionQueueClear:
		if !p.Playback.ClearUpcoming(&member.MemberId) {
			return false, "no_upcoming", "No upcoming tracks to clear"
		}
		return true, "", ""

	case protocol.ActionQueueMove:
		fromNum, okFrom := frame["fromIndex"].(float64)
		toNum, okTo := frame["toIndex"].(float64)
		if !okFrom || !okTo {
			return false, "missing_indices", "queueMove requires fromIndex and toIndex"
		}
		var videoId string
		if v, ok := frame["videoId"].(string); ok {
			videoId = v
		}
		if !p.Playback.MoveUpcoming(&member.MemberId, int(fromNum), int(toNum), videoId) {
			return false, "invalid_move", "Invalid queue move indices"
		}
		return true, "", ""

	case protocol.ActionNext:
		if !p.Playback.Step(&member.MemberId, 1, &member.DisplayName) {
			return false, "end_of_queue", "Already at the end of the queue."
		}
		return true, "", ""

	case protocol.ActionPrevious:
		if !p.Playback.Step(&member.MemberId, -1, &member.DisplayName) {
			return false, "start_of_queue", "Already at the beginning of the queue."
		}
		return true, "", ""

	case protocol.ActionSetMaxMembers:
		value, ok := frame["maxMembers"].(float64)
		if !ok { return false, "invalid_capacity", "Choose a party size between 2 and 10." }
		if err := p.SetMaxMembers(member, int(value)); err != nil {
			pe := err.(*party.PartyError)
			return false, pe.Code, pe.Message
		}
		return true, "", ""

	case protocol.ActionSetAutoplay:
		enabled, ok := frame["enabled"].(bool)
		if !ok {
			return false, "invalid_autoplay", "AutoPlay must be enabled or disabled."
		}
		p.Playback.SetAutoplay(&member.MemberId, enabled)
		return true, "", ""

	case protocol.ActionSetHostOnlyControl:
		enabled, ok := frame["enabled"].(bool)
		if !ok {
			return false, "invalid_control_policy", "Host-only control must be enabled or disabled."
		}
		if err := p.SetHostOnlyControl(member, enabled); err != nil {
			pe := err.(*party.PartyError)
			return false, pe.Code, pe.Message
		}
		return true, "", ""

	case protocol.ActionTransfer:
		targetID, _ := frame["memberId"].(string)
		if err := p.Transfer(targetID); err != nil {
			pe := err.(*party.PartyError)
			return false, pe.Code, pe.Message
		}
		return true, "", ""

	case protocol.ActionWake:
		deviceId, _ := frame["deviceId"].(string)
		if !p.IsConnect() {
			return false, "not_connect", "Playback can only be moved between your own devices."
		}
		d, ok := registry.Get(p.Account, deviceId)
		if !ok || !d.Wakeable() {
			return false, "not_wakeable", devices.ErrNotWakeable.Error()
		}
		if err := p.ExpectOutput(deviceId); err != nil {
			pe := err.(*party.PartyError)
			return false, pe.Code, pe.Message
		}
		// Off the party lock: the push server is another round trip, and
		// nothing about the party waits on it. A push that fails is told to the
		// device that asked, which is the only one that can do anything about it.
		code, asker := p.Code, member.MemberId
		go func() {
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			defer cancel()
			if err := registry.Wake(ctx, d); err != nil {
				log.Printf("party %s: could not wake %s: %v", code, deviceId, err)
				hubInst.Send(code, asker, map[string]interface{}{
					"type": protocol.FrameError, "error": "wake_failed",
					"message": "Couldn’t reach that device. Open BitChord on it and try again.",
				})
			}
		}()
		return true, "", ""

	case protocol.ActionSetVolume:
		v, ok := frame["volume"].(float64)
		if !ok {
			return false, "invalid_volume", "setVolume requires volume"
		}
		if err := p.SetVolume(member, v); err != nil {
			pe := err.(*party.PartyError)
			return false, pe.Code, pe.Message
		}
		return true, "", ""

	case protocol.ActionVolumeState:
		v, ok := frame["volume"].(float64)
		if !ok {
			return false, "invalid_volume", "volumeState requires volume"
		}
		control, _ := frame["control"].(bool)
		steps := 15
		if s, ok := frame["steps"].(float64); ok {
			steps = int(s)
		}
		if err := p.VolumeState(member, v, control, steps); err != nil {
			pe := err.(*party.PartyError)
			return false, pe.Code, pe.Message
		}
		return true, "", ""

	case protocol.ActionKick:
		targetID, _ := frame["memberId"].(string)
		if !member.IsHost { return false, "host_only", "Only the host can remove listeners." }
		if targetID == "" || targetID == member.MemberId { return false, "invalid_member", "Choose another listener to remove." }
		if p.Remove(targetID) == nil { return false, "not_found", "That listener is no longer in this party." }
		hubInst.Send(p.Code, targetID, map[string]interface{}{
			"type":    protocol.FrameBye,
			"reason":  "kicked",
			"message": "The host removed you from this party.",
		})
		hubInst.CloseMember(p.Code, targetID)
		return true, "", ""

	default:
		return false, "unknown_action", fmt.Sprintf("Unknown control action '%s'", action)
	}
}

// Frame builders

func stateFrame(p *party.Party) map[string]interface{} {
	return map[string]interface{}{
		"type":     protocol.FrameState,
		"playback": p.PlaybackToWire(clock.NowMs()),
		"serverMs": clock.NowMs(),
	}
}

func activityFrame(member *party.Member, action string, frame map[string]interface{}) map[string]interface{} {
	detail := action
	trackTitle := func(raw interface{}) string {
		track, _ := raw.(map[string]interface{})
		title, _ := track["title"].(string)
		return title
	}
	switch action {
	case protocol.ActionSetTrack:
		if title := trackTitle(frame["track"]); title != "" { detail = "Changed the song to \"" + title + "\"" }
	case protocol.ActionQueueAdd:
		if tracks, ok := frame["tracks"].([]interface{}); ok && len(tracks) > 0 {
			title := trackTitle(tracks[0])
			if title != "" {
				if len(tracks) == 1 { detail = "Added \"" + title + "\" to the queue" } else { detail = fmt.Sprintf("Added \"%s\" and %d more to the queue", title, len(tracks)-1) }
			}
		}
	case protocol.ActionSetQueue:
		if tracks, ok := frame["queue"].([]interface{}); ok { detail = fmt.Sprintf("Replaced the queue with %d tracks", len(tracks)) }
	case protocol.ActionQueueRemove: detail = "Removed a track from the queue"
	case protocol.ActionQueueClear: detail = "Cleared upcoming tracks"
	case protocol.ActionQueueMove: detail = "Reordered the upcoming queue"
	case protocol.ActionNext: detail = "Skipped to the next song"
	case protocol.ActionPrevious: detail = "Went back to the previous song"
	case protocol.ActionPlay: detail = "Started playback"
	case protocol.ActionPause: detail = "Paused playback"
	case protocol.ActionSeek: detail = "Changed the playback position"
	case protocol.ActionKick: detail = "Removed a listener from the party"
	case protocol.ActionSetMaxMembers: detail = "Changed the party size"
	case protocol.ActionSetAutoplay: detail = "Changed AutoPlay"
	case protocol.ActionTransfer: detail = "Moved playback to another device"
	case protocol.ActionWake: detail = "Woke a device to play on it"
	}
	return map[string]interface{}{"type": protocol.FrameActivity, "action": action, "by": member.DisplayName, "detail": detail, "atMs": clock.NowMs()}
}

func queueFrame(p *party.Party) map[string]interface{} {
	return map[string]interface{}{
		"type":     protocol.FrameQueue,
		"queue":    p.Playback.QueueToWire(),
		"serverMs": clock.NowMs(),
	}
}

func membersFrame(p *party.Party) map[string]interface{} {
	membersList := make([]map[string]interface{}, 0, len(p.Members))
	for _, m := range p.Members {
		membersList = append(membersList, m.ToWire())
	}
	return withDevices(p, map[string]interface{}{
		"type":            protocol.FrameMembers,
		"members":         membersList,
		"maxMembers":      p.MaxMembers,
		"hostOnlyControl": p.HostOnlyControl,
		"serverMs":        clock.NowMs(),
	})
}

// Background Heartbeat Ticker

func startHeartbeatTicker() {
	interval := time.Duration(config.StateHeartbeatMs) * time.Millisecond
	if interval < 1*time.Second {
		interval = 1 * time.Second
	}
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	for range ticker.C {
		now := clock.NowMs()
		for _, p := range store.All() {
			if len(hubInst.MembersOnline(p.Code)) > 0 {
				p.Lock()
				sf := stateFrame(p)
				p.Unlock()
				hubInst.Broadcast(p.Code, sf, "")
			}
		}

		changed := store.Sweep(now)
		for _, p := range changed {
			p.Lock()
			mf := membersFrame(p)
			p.Unlock()
			hubInst.Broadcast(p.Code, mf, "")
		}

		activeCodes := hubInst.ActiveCodes()
		liveParties := store.All()
		liveMap := make(map[string]bool)
		for _, lp := range liveParties {
			liveMap[lp.Code] = true
		}
		for _, code := range activeCodes {
			if !liveMap[code] {
				hubInst.DropParty(code)
			}
		}
	}
}
