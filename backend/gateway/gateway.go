// Package gateway checks a family-gateway login, which is what lets a device
// into its account's Connect party without a code.
//
// The gateway (api.tinotenda.co) speaks the Subsonic API, where a client proves
// itself with u, t = md5(password + s) and a fresh salt s. Nothing here ever
// sees the password: the device sends that triple, and the gateway is asked
// whether it is right. That is the whole trust model, and it is the gateway's,
// so a login that works for the rest of the app works here and one that does
// not, does not.
package gateway

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

var (
	// ErrDisabled means no gateway is configured, so Connect is off.
	ErrDisabled = errors.New("connect is not configured on this server")
	// ErrUnreachable means the gateway could not be asked.
	ErrUnreachable = errors.New("could not reach the account server")
	// ErrRejected means the gateway said the login is wrong.
	ErrRejected = errors.New("the account server did not accept that login")
)

// Verifier asks the gateway about logins, remembering the ones it accepted for
// a while so a reconnecting phone does not cost a round trip every time.
type Verifier struct {
	base   string
	client *http.Client
	ttl    time.Duration

	mu    sync.Mutex
	cache map[string]time.Time
	nowFn func() time.Time
}

// New returns a verifier for the gateway at base, or one that refuses
// everything with ErrDisabled when base is empty.
func New(base string) *Verifier {
	return &Verifier{
		base:   strings.TrimRight(strings.TrimSpace(base), "/"),
		client: &http.Client{Timeout: 8 * time.Second},
		ttl:    10 * time.Minute,
		cache:  make(map[string]time.Time),
		nowFn:  time.Now,
	}
}

// Enabled reports whether a gateway is configured.
func (v *Verifier) Enabled() bool { return v.base != "" }

// Account is the account key for a gateway username. The gateway matches
// usernames without regard to case or surrounding space, so this does too:
// otherwise "Tino" and "tino" would be the same login and two accounts.
func Account(user string) string { return strings.ToLower(strings.TrimSpace(user)) }

// Verify checks a Subsonic token login and returns the account it belongs to.
func (v *Verifier) Verify(ctx context.Context, user, token, salt string) (string, error) {
	if !v.Enabled() {
		return "", ErrDisabled
	}
	account := Account(user)
	if account == "" || token == "" || salt == "" || len(user) > 128 || len(token) > 128 || len(salt) > 128 {
		return "", ErrRejected
	}
	key := cacheKey(user, token, salt)
	now := v.nowFn()

	v.mu.Lock()
	if until, ok := v.cache[key]; ok && now.Before(until) {
		v.mu.Unlock()
		return account, nil
	}
	v.mu.Unlock()

	q := url.Values{}
	q.Set("u", user)
	q.Set("t", token)
	q.Set("s", salt)
	q.Set("v", "1.16.1")
	q.Set("c", "BitChordJam")
	q.Set("f", "json")
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, v.base+"/rest/ping.view?"+q.Encode(), nil)
	if err != nil {
		return "", ErrUnreachable
	}
	// Go's default agent is one that bot filters in front of the gateway refuse.
	req.Header.Set("User-Agent", "BitChordJam/1.0")
	res, err := v.client.Do(req)
	if err != nil {
		return "", ErrUnreachable
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return "", ErrUnreachable
	}
	var body struct {
		Response struct {
			Status string `json:"status"`
		} `json:"subsonic-response"`
	}
	if err := json.NewDecoder(res.Body).Decode(&body); err != nil {
		return "", ErrUnreachable
	}
	if body.Response.Status != "ok" {
		return "", ErrRejected
	}

	v.mu.Lock()
	// Keep the cache from growing without bound on a long-lived process.
	if len(v.cache) > 1000 {
		for k, until := range v.cache {
			if now.After(until) {
				delete(v.cache, k)
			}
		}
	}
	v.cache[key] = now.Add(v.ttl)
	v.mu.Unlock()
	return account, nil
}

func cacheKey(user, token, salt string) string {
	sum := sha256.Sum256([]byte(user + "\x00" + token + "\x00" + salt))
	return hex.EncodeToString(sum[:])
}
