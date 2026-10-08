package config

import (
	"log"
	"net/url"
	"os"
	"strconv"
	"strings"
)

func getInt(key string, fallback int) int {
	val := os.Getenv(key)
	if val == "" {
		return fallback
	}
	parsed, err := strconv.Atoi(strings.TrimSpace(val))
	if err != nil {
		return fallback
	}
	return parsed
}

func getString(key string, fallback string) string {
	if val := strings.TrimSpace(os.Getenv(key)); val != "" {
		return val
	}
	return fallback
}

func getCSV(key string, fallback string) []string {
	val := os.Getenv(key)
	if val == "" {
		val = fallback
	}
	if val == "" {
		return nil
	}
	var res []string
	for _, item := range strings.Split(val, ",") {
		trimmed := strings.TrimSpace(item)
		if trimmed != "" {
			res = append(res, trimmed)
		}
	}
	return res
}

func getBool(key string, fallback bool) bool {
	val := os.Getenv(key)
	if val == "" {
		return fallback
	}
	parsed, err := strconv.ParseBool(strings.TrimSpace(val))
	if err != nil {
		return fallback
	}
	return parsed
}

func getOrigin(key string) string {
	val := strings.TrimRight(strings.TrimSpace(os.Getenv(key)), "/")
	if val == "" {
		return ""
	}
	u, err := url.Parse(val)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" || u.Path != "" || u.RawQuery != "" || u.Fragment != "" || u.User != nil {
		log.Printf("%s %q is not an http(s) origin; ignoring", key, val)
		return ""
	}
	return val
}

// IsAllowedOrigin deliberately does not support a wildcard. Browser clients
// must be explicitly named; native clients send no Origin header.
func IsAllowedOrigin(origin string) bool {
	for _, allowed := range AllowedOrigins {
		if origin == allowed {
			return true
		}
	}
	return false
}

var (
	MaxMembers           = getInt("JAM_MAX_MEMBERS", 5)
	// Remotes play nothing, so they are counted apart from MaxMembers: a full
	// party can still be driven from another phone.
	MaxRemotes           = getInt("JAM_MAX_REMOTES", 5)
	// How far the clock device's measured playhead may stray from the party
	// before the party is re-anchored onto it. See Party.ClockMember.
	ReanchorThresholdMs  = int64(getInt("JAM_REANCHOR_THRESHOLD_MS", 1000))
	// The least time between two re-anchors of one party, so a device that is
	// stalling repeatedly cannot drag everybody else along at every report.
	ReanchorCooldownMs   = int64(getInt("JAM_REANCHOR_COOLDOWN_MS", 3000))

	// Connect: an account's own devices, in one code-less party. Off unless a
	// gateway is configured to check logins against.
	GatewayURL           = getString("JAM_GATEWAY_URL", "")
	ConnectMaxDevices    = getInt("JAM_CONNECT_MAX_DEVICES", 10)
	// How long an account's party outlives its last connected device.
	ConnectIdleTTLMs     = int64(getInt("JAM_CONNECT_IDLE_TTL_MS", 6*60*60*1000))
	// How long the output may sit paused before a device that opens takes
	// playback over, instead of joining as a remote for a speaker nobody is using.
	ConnectHandoverMs    = int64(getInt("JAM_CONNECT_HANDOVER_MS", 5*60*1000))
	// How far ahead a transferred song restarts, for the new device to load it.
	TransferLeadMs       = int64(getInt("JAM_TRANSFER_LEAD_MS", 1500))
	// Connect holds a whole playlist, not a jam's handful of picks: it is one
	// person's own listening, and a queue cut to 25 turned the rest of an album
	// into AutoPlay.
	ConnectMaxUpcoming   = getInt("JAM_CONNECT_MAX_UPCOMING", 200)
	// How long a device that left Connect for a jam is shown as being in one.
	JamStatusMs          = int64(getInt("JAM_STATUS_MS", 12*60*60*1000))
	ConnectRatePerMinute = getInt("JAM_CONNECT_RATE_PER_MINUTE", 30)
	// Where the account device list is saved; empty keeps it in memory only.
	DataDir              = getString("JAM_DATA_DIR", "")
	// The only hosts a wake-up push may be sent to. A device's push endpoint is
	// a URL it chose, so without this list "wake my laptop" would let anyone
	// make this server POST anywhere.
	PushHosts            = getCSV("JAM_PUSH_HOSTS", "")
	// A device not seen for this long drops off the list.
	DeviceForgetMs       = int64(getInt("JAM_DEVICE_FORGET_DAYS", 60)) * 24 * 60 * 60 * 1000
	// How long a woken device has to arrive and still be handed playback.
	WakeWindowMs         = int64(getInt("JAM_WAKE_WINDOW_MS", 90000))
	StateHeartbeatMs     = getInt("JAM_STATE_HEARTBEAT_MS", 5000)
	PlayLeadMs           = getInt("JAM_PLAY_LEAD_MS", 350)
	DisconnectGraceMs    = int64(getInt("JAM_DISCONNECT_GRACE_MS", 45000))
	EmptyPartyTTLMs      = int64(getInt("JAM_EMPTY_PARTY_TTL_MS", 120000))
	PartyMaxAgeMs        = int64(getInt("JAM_PARTY_MAX_AGE_MS", 12*60*60*1000))
	ControlRatePerSecond = float64(getInt("JAM_CONTROL_RATE_PER_SECOND", 25))
	MaxUpcomingQueue     = getInt("JAM_MAX_UPCOMING_QUEUE", 25)
	MaxQueueLength       = getInt("JAM_MAX_QUEUE_LENGTH", 1+MaxUpcomingQueue)
	// Render Free has 0.1 CPU. Fifty rooms (at most 250 sockets) is a safe
	// starting ceiling; raise it only after measuring CPU and memory usage.
	MaxParties           = getInt("JAM_MAX_PARTIES", 50)
	CreateRatePerMinute  = getInt("JAM_CREATE_RATE_PER_MINUTE", 2)
	RateLimitMaxEntries  = getInt("JAM_RATE_LIMIT_MAX_ENTRIES", 10000)
	RequestMaxBytes      = int64(getInt("JAM_REQUEST_MAX_BYTES", 16*1024))
	// A setQueue carries the whole running order, history included, and a
	// Connect party allows 200 upcoming songs. At roughly 400 bytes a track
	// (title, artist and a cover URL), 16 KiB held about forty: playing a
	// 110-song playlist got the socket closed, the queue never arrived, and
	// the device fell back to the party's one-song state on reconnect.
	WebSocketMaxBytes    = int64(getInt("JAM_WEBSOCKET_MAX_BYTES", 1024*1024))
	ConnectionIdleMs     = int64(getInt("JAM_CONNECTION_IDLE_MS", 15*60*1000))
	FrameRatePerSecond   = float64(getInt("JAM_FRAME_RATE_PER_SECOND", 30))
	AllowedOrigins       = getCSV("JAM_ALLOWED_ORIGINS", "")
	TrustProxy           = getBool("JAM_TRUST_PROXY", false)
	PublicOrigin         = getOrigin("JAM_PUBLIC_ORIGIN")
	Port                 = getInt("PORT", 8000)
)

// Open-app heartbeats. The interval is handed back on every ping, so it can be
// raised here under load without an app update. An install stays counted for
// one interval plus the grace period after its last ping.
var (
	PresenceIntervalSec     = getInt("JAM_PRESENCE_INTERVAL_SEC", 300)
	PresenceGraceSec        = getInt("JAM_PRESENCE_GRACE_SEC", 90)
	PresenceMaxEntries      = getInt("JAM_PRESENCE_MAX_ENTRIES", 200000)
	PresencePingsPerMinute  = getInt("JAM_PRESENCE_PINGS_PER_MINUTE", 20)
	PresenceRateLimitMaxIPs = getInt("JAM_PRESENCE_RATE_LIMIT_MAX_IPS", 100000)
)
