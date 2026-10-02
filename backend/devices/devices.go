// Package devices remembers an account's Connect devices between visits, and
// wakes one that is asleep.
//
// A Connect party only holds the devices connected to it right now. To offer
// "play on my laptop" while the laptop's app is closed, the server has to know
// the laptop exists and how to reach it, which is what this keeps: one entry
// per device and build, with the UnifiedPush endpoint the device gave it. It is
// the only state this server writes to disk, because unlike a party it has to
// survive a redeploy to be any use.
package devices

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

// Device is one build of the app on one phone that has signed into an account.
type Device struct {
	DeviceId     string `json:"deviceId"`
	DeviceKey    string `json:"deviceKey"`
	App          string `json:"app"`
	DeviceName   string `json:"deviceName"`
	PushEndpoint string `json:"pushEndpoint,omitempty"`
	LastSeenMs   int64  `json:"lastSeenMs"`
}

// Wakeable reports whether this device can be woken with a push.
func (d Device) Wakeable() bool { return d.PushEndpoint != "" }

// ErrNotWakeable means the device never gave a usable push endpoint.
var ErrNotWakeable = errors.New("that device cannot be woken remotely")

// Registry is every account's known devices, saved to one JSON file.
type Registry struct {
	path      string
	pushHosts []string
	forgetMs  int64
	client    *http.Client
	mu        sync.Mutex
	accounts  map[string]map[string]*Device
}

// Open loads the registry at path (missing is fine) and only ever pushes to
// endpoints on pushHosts. An empty path keeps everything in memory.
func Open(path string, pushHosts []string, forgetAfter time.Duration) *Registry {
	r := &Registry{
		path:      path,
		pushHosts: pushHosts,
		forgetMs:  forgetAfter.Milliseconds(),
		client:    &http.Client{Timeout: 8 * time.Second},
		accounts:  make(map[string]map[string]*Device),
	}
	if path != "" {
		if raw, err := os.ReadFile(path); err == nil {
			_ = json.Unmarshal(raw, &r.accounts)
		}
	}
	return r
}

// AllowedEndpoint reports whether the server may POST to this endpoint.
//
// The endpoint comes from the device, so it is a URL somebody else chose. Left
// open, "wake my device" would be "make the party server send a request
// anywhere", so it has to be https and on one of the configured push servers.
func (r *Registry) AllowedEndpoint(endpoint string) bool {
	u, err := url.Parse(endpoint)
	if err != nil || u.Scheme != "https" || u.User != nil {
		return false
	}
	host := strings.ToLower(u.Hostname())
	for _, allowed := range r.pushHosts {
		if host == strings.ToLower(strings.TrimSpace(allowed)) {
			return true
		}
	}
	return false
}

// Seen records a device signing in. An endpoint the server may not use is
// dropped rather than stored; an empty one leaves the stored one alone, so a
// device whose push registration is still in flight does not lose the last one.
func (r *Registry) Seen(account string, d Device, now int64) {
	if d.PushEndpoint != "" && !r.AllowedEndpoint(d.PushEndpoint) {
		d.PushEndpoint = ""
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	devs := r.accounts[account]
	if devs == nil {
		devs = make(map[string]*Device)
		r.accounts[account] = devs
	}
	if prev, ok := devs[d.DeviceId]; ok && d.PushEndpoint == "" {
		d.PushEndpoint = prev.PushEndpoint
	}
	d.LastSeenMs = now
	devs[d.DeviceId] = &d
	r.forget(now)
	r.save()
}

// Get returns one device of an account.
func (r *Registry) Get(account, deviceId string) (Device, bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	d, ok := r.accounts[account][deviceId]
	if !ok {
		return Device{}, false
	}
	return *d, true
}

// List returns an account's devices, most recently seen first.
func (r *Registry) List(account string) []Device {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := make([]Device, 0, len(r.accounts[account]))
	for _, d := range r.accounts[account] {
		out = append(out, *d)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].LastSeenMs > out[j].LastSeenMs })
	return out
}

// Wake sends a device a push asking it to come and take playback.
//
// The body says what for and nothing else: who is asking and what is playing
// are the party's business, which the device learns on joining. A UnifiedPush
// endpoint takes a plain POST whatever the distributor.
func (r *Registry) Wake(ctx context.Context, d Device) error {
	if !d.Wakeable() || !r.AllowedEndpoint(d.PushEndpoint) {
		return ErrNotWakeable
	}
	body, _ := json.Marshal(map[string]string{"type": "takeover"})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, d.PushEndpoint, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("User-Agent", "BitChordJam/1.0")
	// WebPush's urgency header, which ntfy honours: deliver now, not batched.
	req.Header.Set("Urgency", "high")
	req.Header.Set("TTL", "60")
	res, err := r.client.Do(req)
	if err != nil {
		return err
	}
	defer res.Body.Close()
	if res.StatusCode >= 300 {
		return errors.New("push server answered " + res.Status)
	}
	return nil
}

// forget drops devices not seen for a long time. Caller holds mu.
func (r *Registry) forget(now int64) {
	if r.forgetMs <= 0 {
		return
	}
	for account, devs := range r.accounts {
		for id, d := range devs {
			if now-d.LastSeenMs > r.forgetMs {
				delete(devs, id)
			}
		}
		if len(devs) == 0 {
			delete(r.accounts, account)
		}
	}
}

// save writes the registry atomically. Caller holds mu. A failed write is not
// fatal: the registry still works from memory until the next redeploy.
func (r *Registry) save() {
	if r.path == "" {
		return
	}
	raw, err := json.Marshal(r.accounts)
	if err != nil {
		return
	}
	tmp := r.path + ".tmp"
	if err := os.MkdirAll(filepath.Dir(r.path), 0o700); err != nil {
		return
	}
	if err := os.WriteFile(tmp, raw, 0o600); err != nil {
		return
	}
	_ = os.Rename(tmp, r.path)
}
