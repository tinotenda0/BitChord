package devices

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"path/filepath"
	"testing"
	"time"
)

func TestOnlyConfiguredHttpsPushServersAreUsed(t *testing.T) {
	r := Open("", []string{"ntfy.tinotenda.co"}, 0)
	for endpoint, want := range map[string]bool{
		"https://ntfy.tinotenda.co/upAbc123?up=1":    true,
		"https://NTFY.tinotenda.co/upAbc123":         true,
		"http://ntfy.tinotenda.co/upAbc123":          false,
		"https://evil.example/upAbc123":              false,
		"https://ntfy.tinotenda.co.evil.example/up1": false,
		"https://user:pw@ntfy.tinotenda.co/up1":      false,
		"https://127.0.0.1/up1":                      false,
		"not a url":                                  false,
	} {
		if got := r.AllowedEndpoint(endpoint); got != want {
			t.Errorf("AllowedEndpoint(%q) = %v, want %v", endpoint, got, want)
		}
	}
}

func TestSeenKeepsTheLastGoodEndpointAndDropsBadOnes(t *testing.T) {
	r := Open("", []string{"ntfy.tinotenda.co"}, 0)
	good := "https://ntfy.tinotenda.co/upAbc"
	r.Seen("tino", Device{DeviceId: "k:prod", PushEndpoint: good}, 1)
	r.Seen("tino", Device{DeviceId: "k:prod"}, 2)
	if d, _ := r.Get("tino", "k:prod"); d.PushEndpoint != good {
		t.Fatalf("a sign-in without an endpoint must keep the last one, got %q", d.PushEndpoint)
	}
	r.Seen("tino", Device{DeviceId: "x:prod", PushEndpoint: "https://evil.example/up"}, 3)
	if d, _ := r.Get("tino", "x:prod"); d.Wakeable() {
		t.Fatalf("an endpoint the server may not use must not be stored")
	}
	if _, ok := r.Get("someone", "k:prod"); ok {
		t.Fatalf("devices belong to one account")
	}
}

func TestTheListSurvivesARestartAndForgetsOldDevices(t *testing.T) {
	path := filepath.Join(t.TempDir(), "devices.json")
	day := int64(24 * 60 * 60 * 1000)
	r := Open(path, []string{"ntfy.tinotenda.co"}, 30*24*time.Hour)
	r.Seen("tino", Device{DeviceId: "old:prod"}, 0)
	r.Seen("tino", Device{DeviceId: "k:prod", DeviceName: "Pixel 9", PushEndpoint: "https://ntfy.tinotenda.co/up1"}, 40*day)

	again := Open(path, []string{"ntfy.tinotenda.co"}, 30*24*time.Hour)
	list := again.List("tino")
	if len(list) != 1 || list[0].DeviceName != "Pixel 9" || !list[0].Wakeable() {
		t.Fatalf("the list must come back after a restart, without the stale device, got %+v", list)
	}
}

func TestWakePostsToTheEndpoint(t *testing.T) {
	var got *http.Request
	var body string
	ts := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		got = req
		b, _ := io.ReadAll(req.Body)
		body = string(b)
	}))
	defer ts.Close()
	host, _ := url.Parse(ts.URL)

	r := Open("", []string{host.Hostname()}, 0)
	r.client = ts.Client()
	d := Device{DeviceId: "k:prod", PushEndpoint: ts.URL + "/upAbc"}
	if err := r.Wake(context.Background(), d); err != nil {
		t.Fatalf("wake failed: %v", err)
	}
	if got == nil || got.Method != http.MethodPost || got.URL.Path != "/upAbc" {
		t.Fatalf("expected a POST to the endpoint, got %+v", got)
	}
	if got.Header.Get("Urgency") != "high" || body != `{"type":"takeover"}` {
		t.Errorf("unexpected push: urgency %q body %q", got.Header.Get("Urgency"), body)
	}

	if err := r.Wake(context.Background(), Device{DeviceId: "n:prod"}); err != ErrNotWakeable {
		t.Errorf("a device without an endpoint must not be woken, got %v", err)
	}
}

func TestAJamStatusLastsUntilTheDeviceComesBack(t *testing.T) {
	r := Open("", nil, 0)
	r.Seen("tino", Device{DeviceId: "k:prod"}, 1)
	r.SetStatus("tino", "k:prod", "jam", 2)
	if d, _ := r.Get("tino", "k:prod"); d.Status != "jam" {
		t.Fatalf("status not set: %+v", d)
	}
	r.Seen("tino", Device{DeviceId: "k:prod"}, 3)
	if d, _ := r.Get("tino", "k:prod"); d.Status != "" {
		t.Fatalf("signing back in must clear it: %+v", d)
	}
}
