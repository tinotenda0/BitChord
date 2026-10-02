package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"testing"
	"time"

	"github.com/gorilla/websocket"

	"github.com/KabirSinghBhatia/BitChord/backend/clock"
	"github.com/KabirSinghBhatia/BitChord/backend/protocol"
)

// unlimitedCreates lifts the per-IP create limit for one test. Every test here
// creates from the same address, and the limit is not what these test.
func unlimitedCreates(t *testing.T) {
	saved := createLimiter
	createLimiter = newIPRateLimiter(time.Minute, 0, 0)
	t.Cleanup(func() { createLimiter = saved })
}

func postJSON(t *testing.T, target string, body map[string]interface{}) (int, map[string]interface{}) {
	t.Helper()
	b, _ := json.Marshal(body)
	res, err := http.Post(target, "application/json", bytes.NewReader(b))
	if err != nil {
		t.Fatalf("POST %s: %v", target, err)
	}
	defer res.Body.Close()
	var out map[string]interface{}
	_ = json.NewDecoder(res.Body).Decode(&out)
	return res.StatusCode, out
}

func dialParty(t *testing.T, base, code, token string) *websocket.Conn {
	t.Helper()
	u, _ := url.Parse(base)
	u.Scheme = "ws"
	u.Path = fmt.Sprintf("/ws/parties/%s", code)
	headers := http.Header{}
	headers.Set("Authorization", "Bearer "+token)
	ws, _, err := websocket.DefaultDialer.Dial(u.String(), headers)
	if err != nil {
		t.Fatalf("WebSocket connection failed: %v", err)
	}
	return ws
}

// nextFrame reads until a frame of this type arrives, or fails the test.
func nextFrame(t *testing.T, ws *websocket.Conn, kind string) map[string]interface{} {
	t.Helper()
	for i := 0; i < 20; i++ {
		_ = ws.SetReadDeadline(time.Now().Add(2 * time.Second))
		var f map[string]interface{}
		if err := ws.ReadJSON(&f); err != nil {
			t.Fatalf("waiting for %s: %v", kind, err)
		}
		if f["type"] == kind {
			return f
		}
	}
	t.Fatalf("no %s frame arrived", kind)
	return nil
}

func TestRemoteCannotStartAParty(t *testing.T) {
	unlimitedCreates(t)
	ts := setupTestServer()
	defer ts.Close()
	status, body := postJSON(t, ts.URL+"/api/parties", map[string]interface{}{
		"userId": "u", "deviceId": "d_remote_create", "displayName": "R", "role": "remote",
	})
	if status != http.StatusUnprocessableEntity || body["error"] != "remote_cannot_host" {
		t.Fatalf("expected 422 remote_cannot_host, got %d %v", status, body)
	}
	status, _ = postJSON(t, ts.URL+"/api/parties", map[string]interface{}{
		"userId": "u", "deviceId": "d_bad_role", "displayName": "R", "role": "dj",
	})
	if status != http.StatusUnprocessableEntity {
		t.Fatalf("an unknown role must be refused, got %d", status)
	}
}

func TestRemoteDrivesTheHostAndTheHostIsTheClock(t *testing.T) {
	unlimitedCreates(t)
	ts := setupTestServer()
	defer ts.Close()

	status, created := postJSON(t, ts.URL+"/api/parties", map[string]interface{}{
		"userId": "u_host", "deviceId": "d_host_clock", "displayName": "Host", "maxMembers": 2,
	})
	if status != http.StatusCreated {
		t.Fatalf("create failed: %d %v", status, created)
	}
	code := created["code"].(string)
	hostToken := created["token"].(string)
	hostId := created["you"].(map[string]interface{})["memberId"].(string)

	// Fill the only other speaker seat, then join as a remote anyway.
	if status, _ := postJSON(t, ts.URL+"/api/parties/"+code+"/join", map[string]interface{}{
		"userId": "u_s", "deviceId": "d_speaker_clock", "displayName": "Speaker",
	}); status != http.StatusOK {
		t.Fatalf("speaker join failed: %d", status)
	}
	status, joined := postJSON(t, ts.URL+"/api/parties/"+code+"/join", map[string]interface{}{
		"userId": "u_r", "deviceId": "d_remote_clock", "displayName": "Remote", "role": "remote",
	})
	if status != http.StatusOK {
		t.Fatalf("a remote must get into a full party, got %d %v", status, joined)
	}
	if joined["you"].(map[string]interface{})["role"] != "remote" {
		t.Fatalf("the join must echo the remote role, got %v", joined["you"])
	}
	remoteToken := joined["token"].(string)

	host := dialParty(t, ts.URL, code, hostToken)
	defer host.Close()
	nextFrame(t, host, protocol.FrameWelcome)
	remote := dialParty(t, ts.URL, code, remoteToken)
	defer remote.Close()
	welcome := nextFrame(t, remote, protocol.FrameWelcome)
	pb := welcome["party"].(map[string]interface{})["playback"].(map[string]interface{})
	if pb["clockMemberId"] != hostId {
		t.Fatalf("the welcome must name the host as clock, got %v", pb["clockMemberId"])
	}

	// The remote picks a song; the host hears about it like any control.
	_ = remote.WriteJSON(map[string]interface{}{
		"type": protocol.FrameControl, "action": protocol.ActionSetTrack,
		"track": map[string]interface{}{"videoId": "song", "title": "Song"}, "positionMs": 0, "isPlaying": true,
	})
	state := nextFrame(t, host, protocol.FrameState)
	playback := state["playback"].(map[string]interface{})
	if track, _ := playback["track"].(map[string]interface{}); track == nil || track["videoId"] != "song" {
		t.Fatalf("the remote's control must reach the host, got %v", playback["track"])
	}
	seq := playback["seq"].(float64)

	// Pretend the song has been playing for ten seconds, so a report is not
	// mistaken for the control still settling.
	p := store.Find(code)
	p.Lock()
	p.Playback.PositionMs = 0
	p.Playback.AnchorMs = clock.NowMs() - 10_000
	p.Unlock()

	// An unmeasured report, which every older client sends, moves nothing.
	_ = host.WriteJSON(map[string]interface{}{"type": protocol.FrameReport, "positionMs": 3_000, "isPlaying": true})
	// The host stalled: it is really at 7s, not 10s.
	_ = host.WriteJSON(map[string]interface{}{
		"type": protocol.FrameReport, "measured": true, "videoId": "song",
		"positionMs": 7_000, "atMs": clock.NowMs(), "isPlaying": true,
	})
	moved := nextFrame(t, remote, protocol.FrameState)["playback"].(map[string]interface{})
	for moved["seq"].(float64) <= seq {
		moved = nextFrame(t, remote, protocol.FrameState)["playback"].(map[string]interface{})
	}
	if moved["positionMs"].(float64) != 7_000 {
		t.Fatalf("the party must follow the clock to 7000, got %v", moved["positionMs"])
	}
	if moved["seq"].(float64) != seq+1 {
		t.Errorf("only the measured report may move the party, seq went %v -> %v", seq, moved["seq"])
	}
}
