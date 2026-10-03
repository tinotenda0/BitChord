package main

import (
	"crypto/md5"
	"encoding/hex"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"
	"time"

	"github.com/KabirSinghBhatia/BitChord/backend/config"
	"github.com/KabirSinghBhatia/BitChord/backend/gateway"
	"github.com/KabirSinghBhatia/BitChord/backend/protocol"
	"github.com/gorilla/websocket"
)

// fakeGateway accepts tino/secret with Subsonic token auth, as the real one does.
func fakeGateway(t *testing.T) {
	t.Helper()
	gw := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		q := r.URL.Query()
		sum := md5.Sum([]byte("secret" + q.Get("s")))
		status := "failed"
		if gateway.Account(q.Get("u")) == "tino" && q.Get("t") == hex.EncodeToString(sum[:]) {
			status = "ok"
		}
		fmt.Fprintf(w, `{"subsonic-response":{"status":%q}}`, status)
	}))
	saved := verifier
	verifier = gateway.New(gw.URL)
	t.Cleanup(func() { verifier = saved; gw.Close() })
}

func login(user, password, salt string) map[string]interface{} {
	sum := md5.Sum([]byte(password + salt))
	return map[string]interface{}{"gatewayUser": user, "gatewayToken": hex.EncodeToString(sum[:]), "gatewaySalt": salt}
}

func connectBody(user, password, key, app string) map[string]interface{} {
	b := login(user, password, fmt.Sprintf("s%d", time.Now().UnixNano()))
	b["deviceKey"] = key
	b["app"] = app
	b["deviceName"] = "Pixel 9"
	return b
}

func TestConnectNeedsAGatewayLogin(t *testing.T) {
	ts := setupTestServer()
	defer ts.Close()
	saved := verifier
	verifier = gateway.New("")
	status, body := postJSON(t, ts.URL+"/api/connect", connectBody("tino", "secret", "phone0001", "prod"))
	verifier = saved
	if status != http.StatusServiceUnavailable {
		t.Fatalf("with no gateway configured Connect must be off, got %d %v", status, body)
	}

	fakeGateway(t)
	status, body = postJSON(t, ts.URL+"/api/connect", connectBody("tino", "wrong", "phone0001", "prod"))
	if status != http.StatusUnauthorized || body["error"] != "bad_login" {
		t.Fatalf("a wrong password must be refused, got %d %v", status, body)
	}
}

func TestConnectAccountDevicesFindEachOther(t *testing.T) {
	ts := setupTestServer()
	defer ts.Close()
	fakeGateway(t)

	// Capitalised by a phone keyboard: still the same account.
	status, phone := postJSON(t, ts.URL+"/api/connect", connectBody("Tino", "secret", "phone0001", "prod"))
	if status != http.StatusOK {
		t.Fatalf("connect failed: %d %v", status, phone)
	}
	code := phone["code"].(string)
	if phone["party"].(map[string]interface{})["kind"] != "connect" {
		t.Fatalf("the party must say it is a Connect party")
	}
	if phone["you"].(map[string]interface{})["role"] != protocol.RoleSpeaker {
		t.Fatalf("the first device must be the one that plays")
	}

	// The account party cannot be reached by code.
	if status, _ := postJSON(t, ts.URL+"/api/parties/"+code+"/join", map[string]interface{}{
		"userId": "x", "deviceId": "x", "displayName": "X",
	}); status != http.StatusNotFound {
		t.Fatalf("joining an account party by code must look like no party, got %d", status)
	}

	ws := dialParty(t, ts.URL, code, phone["token"].(string))
	defer ws.Close()
	nextFrame(t, ws, protocol.FrameWelcome)
	_ = ws.WriteJSON(map[string]interface{}{
		"type": protocol.FrameControl, "action": protocol.ActionSetTrack,
		"track": map[string]interface{}{"videoId": "song"}, "positionMs": 0, "isPlaying": true,
	})
	nextFrame(t, ws, protocol.FrameState)

	// The same account on a laptop, while the phone plays: a remote.
	status, laptop := postJSON(t, ts.URL+"/api/connect", connectBody("tino", "secret", "laptop001", "prod"))
	if status != http.StatusOK || laptop["code"] != code {
		t.Fatalf("the laptop must land in the same party, got %d %v", status, laptop["code"])
	}
	if laptop["you"].(map[string]interface{})["role"] != protocol.RoleRemote {
		t.Fatalf("a device opening while another plays must be a remote")
	}
	members := nextFrame(t, ws, protocol.FrameMembers)["members"].([]interface{})
	if len(members) != 2 {
		t.Fatalf("the phone must hear the laptop arrive, got %d members", len(members))
	}

	// The same phone again, reinstalled: not a third device.
	status, again := postJSON(t, ts.URL+"/api/connect", connectBody("tino", "secret", "phone0001", "prod"))
	if status != http.StatusOK || again["you"].(map[string]interface{})["memberId"] != phone["you"].(map[string]interface{})["memberId"] {
		t.Fatalf("the same device must come back as the same member")
	}
}

// A browser can't set an Authorization header on a WebSocket, so it offers its
// token as a subprotocol; the server must accept it, answer with the plain
// "bitchord" protocol (never the one holding the token), and still refuse an
// origin it hasn't been told about.
func TestBrowserJoinsWithItsTokenInTheSubprotocol(t *testing.T) {
	ts := setupTestServer()
	defer ts.Close()
	fakeGateway(t)
	saved := config.AllowedOrigins
	config.AllowedOrigins = []string{"https://music.example"}
	t.Cleanup(func() { config.AllowedOrigins = saved })

	status, web := postJSON(t, ts.URL+"/api/connect", connectBody("tino", "secret", "browser0001", "web"))
	if status != http.StatusOK {
		t.Fatalf("connect failed: %d %v", status, web)
	}
	u, _ := url.Parse(ts.URL)
	u.Scheme = "ws"
	u.Path = "/ws/parties/" + web["code"].(string)
	dial := func(origin string, protocols ...string) (*websocket.Conn, *http.Response, error) {
		d := websocket.Dialer{Subprotocols: protocols}
		h := http.Header{}
		h.Set("Origin", origin)
		return d.Dial(u.String(), h)
	}

	ws, resp, err := dial("https://music.example", "bitchord", "bitchord.token."+web["token"].(string))
	if err != nil {
		t.Fatalf("a browser offering its token as a subprotocol must get in: %v", err)
	}
	defer ws.Close()
	if got := resp.Header.Get("Sec-WebSocket-Protocol"); got != "bitchord" {
		t.Fatalf("the server must answer with the plain protocol, not the token; got %q", got)
	}
	welcome := nextFrame(t, ws, protocol.FrameWelcome)
	if welcome["you"].(map[string]interface{})["memberId"] != web["you"].(map[string]interface{})["memberId"] {
		t.Fatalf("the socket must belong to the member that signed in")
	}

	if _, resp, err := dial("https://music.example", "bitchord", "bitchord.token.wrong"); err == nil || resp == nil || resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("a wrong token must be refused with 401")
	}
	if _, resp, err := dial("https://elsewhere.example", "bitchord", "bitchord.token."+web["token"].(string)); err == nil || resp == nil || resp.StatusCode != http.StatusForbidden {
		t.Fatalf("an origin that isn't allowed must be refused")
	}
}
