package protocol

import (
	"errors"
	"strings"
)

// Server -> Client frame types
const (
	FrameWelcome = "welcome"
	FrameState   = "state"
	FrameQueue   = "queue"
	FrameMembers = "members"
	FramePong    = "pong"
	FrameError   = "error"
	FrameBye     = "bye"
	FrameActivity = "activity"
)

// Client -> Server frame types
const (
	FramePing      = "ping"
	FrameControl   = "control"
	FrameSync      = "sync"
	FrameSyncQueue = "syncQueue"
	FrameReport    = "report"
)

// Control actions
const (
	ActionPlay        = "play"
	ActionPause       = "pause"
	ActionSeek        = "seek"
	ActionSetTrack    = "setTrack"
	ActionSetQueue    = "setQueue"
	ActionQueueAdd    = "queueAdd"
	ActionQueueRemove = "queueRemove"
	ActionQueueClear  = "queueClear"
	ActionQueueMove   = "queueMove"
	ActionNext        = "next"
	ActionPrevious    = "previous"
	ActionKick        = "kick"
	ActionSetMaxMembers = "setMaxMembers"
	ActionSetAutoplay = "setAutoplay"
	ActionSetHostOnlyControl = "setHostOnlyControl"
	// ActionTransfer moves a Connect party's playback to another of its devices.
	ActionTransfer = "transfer"
	// ActionWake moves it to one that is asleep, by waking it with a push.
	ActionWake = "wake"
)

// ControlActions are the actions a party's HostOnlyControl setting restricts to
// the host. Membership actions are not here: kick and setMaxMembers are already
// host-only unconditionally, and setHostOnlyControl has to stay reachable by
// the host to be turned back off.
var ControlActions = map[string]bool{
	ActionPlay:        true,
	ActionPause:       true,
	ActionSeek:        true,
	ActionSetTrack:    true,
	ActionSetQueue:    true,
	ActionQueueAdd:    true,
	ActionQueueRemove: true,
	ActionQueueClear:  true,
	ActionQueueMove:   true,
	ActionNext:        true,
	ActionPrevious:    true,
	ActionSetAutoplay: true,
	ActionTransfer:    true,
	ActionWake:        true,
}

// ConnectRequest is how one of an account's devices joins its Connect party:
// a gateway login (Subsonic token auth) and which device and build this is.
type ConnectRequest struct {
	GatewayUser  string  `json:"gatewayUser"`
	GatewayToken string  `json:"gatewayToken"`
	GatewaySalt  string  `json:"gatewaySalt"`
	DeviceKey    string  `json:"deviceKey"`
	App          string  `json:"app"`
	DeviceName   string  `json:"deviceName"`
	DisplayName  string  `json:"displayName"`
	AvatarUrl    *string `json:"avatarUrl,omitempty"`
	// PushEndpoint is the device's UnifiedPush endpoint, for waking it later.
	PushEndpoint string  `json:"pushEndpoint,omitempty"`
}

func isWord(s string, min, max int) bool {
	if len(s) < min || len(s) > max {
		return false
	}
	for _, r := range s {
		if !(r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9' || r == '-' || r == '_') {
			return false
		}
	}
	return true
}

// Validate checks the device half; the login is the gateway's to judge.
func (r *ConnectRequest) Validate() error {
	r.DeviceKey = strings.TrimSpace(r.DeviceKey)
	if !isWord(r.DeviceKey, 8, 64) {
		return errors.New("deviceKey must be 8 to 64 letters or digits")
	}
	r.App = strings.ToLower(strings.TrimSpace(r.App))
	if !isWord(r.App, 1, 16) {
		return errors.New("app must be 1 to 16 letters or digits")
	}
	r.PushEndpoint = strings.TrimSpace(r.PushEndpoint)
	if len(r.PushEndpoint) > 1000 {
		r.PushEndpoint = ""
	}
	r.DeviceName = strings.TrimSpace(r.DeviceName)
	if len(r.DeviceName) > 80 {
		r.DeviceName = r.DeviceName[:80]
	}
	if r.DeviceName == "" {
		r.DeviceName = "Unknown device"
	}
	r.DisplayName = strings.TrimSpace(r.DisplayName)
	if r.DisplayName == "" || len(r.DisplayName) > 80 {
		r.DisplayName = strings.TrimSpace(r.GatewayUser)
	}
	if len(r.DisplayName) > 80 {
		r.DisplayName = r.DisplayName[:80]
	}
	if r.AvatarUrl != nil {
		t := strings.TrimSpace(*r.AvatarUrl)
		if t == "" || len(t) > 1000 || !(strings.HasPrefix(t, "http://") || strings.HasPrefix(t, "https://")) {
			r.AvatarUrl = nil
		} else {
			r.AvatarUrl = &t
		}
	}
	return nil
}

// JoinRequest is the identity submitted when creating or joining a party.
type JoinRequest struct {
	UserId      string  `json:"userId"`
	DeviceId    string  `json:"deviceId"`
	DisplayName string  `json:"displayName"`
	AvatarUrl   *string `json:"avatarUrl,omitempty"`
	MaxMembers  *int    `json:"maxMembers,omitempty"`
	AutoplayEnabled *bool `json:"autoplayEnabled,omitempty"`
	// Role is RoleSpeaker or RoleRemote. Absent means speaker, which is what
	// every member was before remotes existed.
	Role string `json:"role,omitempty"`
}

// Member roles. A speaker plays the party out loud; a remote only drives it,
// like a phone controlling somebody else's speaker.
const (
	RoleSpeaker = "speaker"
	RoleRemote  = "remote"
)

// Validate ensures all required identity fields are present and safe.
func (r *JoinRequest) Validate() error {
	r.UserId = strings.TrimSpace(r.UserId)
	if r.UserId == "" || len(r.UserId) > 128 {
		return errors.New("userId must be between 1 and 128 characters")
	}

	r.DeviceId = strings.TrimSpace(r.DeviceId)
	if r.DeviceId == "" || len(r.DeviceId) > 128 {
		return errors.New("deviceId must be between 1 and 128 characters")
	}

	r.DisplayName = strings.TrimSpace(r.DisplayName)
	if r.DisplayName == "" || len(r.DisplayName) > 80 {
		return errors.New("displayName must be between 1 and 80 characters")
	}

	if r.AvatarUrl != nil {
		trimmed := strings.TrimSpace(*r.AvatarUrl)
		if trimmed == "" {
			r.AvatarUrl = nil
		} else if len(trimmed) > 1000 {
			return errors.New("avatarUrl must not exceed 1000 characters")
		} else if !strings.HasPrefix(trimmed, "http://") && !strings.HasPrefix(trimmed, "https://") {
			return errors.New("avatarUrl must be an http(s) URL")
		} else {
			r.AvatarUrl = &trimmed
		}
	}
	switch strings.TrimSpace(r.Role) {
	case "", RoleSpeaker:
		r.Role = RoleSpeaker
	case RoleRemote:
		r.Role = RoleRemote
	default:
		return errors.New("role must be speaker or remote")
	}
	if r.MaxMembers != nil && (*r.MaxMembers < 2 || *r.MaxMembers > 10) {
		return errors.New("maxMembers must be between 2 and 10")
	}
	return nil
}
