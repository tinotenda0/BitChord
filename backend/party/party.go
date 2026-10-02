package party

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"fmt"
	"sort"
	"strings"
	"sync"

	"github.com/KabirSinghBhatia/BitChord/backend/clock"
	"github.com/KabirSinghBhatia/BitChord/backend/codes"
	"github.com/KabirSinghBhatia/BitChord/backend/config"
	"github.com/KabirSinghBhatia/BitChord/backend/protocol"
)

// PartyError represents an error with an HTTP status code and wire error code.
type PartyError struct {
	Status  int    `json:"status"`
	Code    string `json:"error"`
	Message string `json:"message"`
}

func (e *PartyError) Error() string {
	return e.Message
}

func NewPartyError(status int, code, message string) *PartyError {
	return &PartyError{Status: status, Code: code, Message: message}
}

// Track represents a song with metadata needed for display and synchronization.
type Track struct {
	VideoId      string  `json:"videoId"`
	Title        string  `json:"title"`
	Artist       string  `json:"artist"`
	ThumbnailUrl *string `json:"thumbnailUrl,omitempty"`
	DurationMs   *int64  `json:"durationMs,omitempty"`
	FromAutoplay bool    `json:"fromAutoplay"`
}

func TrackFromWire(raw map[string]interface{}) *Track {
	if raw == nil {
		return nil
	}
	vid, _ := raw["videoId"].(string)
	if vid == "" {
		return nil
	}
	if len(vid) > 128 {
		vid = vid[:128]
	}

	title, _ := raw["title"].(string)
	if len(title) > 300 {
		title = title[:300]
	}

	artist, _ := raw["artist"].(string)
	if len(artist) > 300 {
		artist = artist[:300]
	}

	var thumbPtr *string
	if t, ok := raw["thumbnailUrl"].(string); ok && t != "" {
		if len(t) > 1000 {
			t = t[:1000]
		}
		thumbPtr = &t
	}

	var durPtr *int64
	if d, ok := raw["durationMs"].(float64); ok && d > 0 {
		dur := int64(d)
		durPtr = &dur
	}

	fromAutoplay, _ := raw["fromAutoplay"].(bool)

	return &Track{
		VideoId:      vid,
		Title:        title,
		Artist:       artist,
		ThumbnailUrl: thumbPtr,
		DurationMs:   durPtr,
		FromAutoplay: fromAutoplay,
	}
}

// PlaybackState tracks playhead, playback mode, anchor timestamps, and queue sequencing.
type PlaybackState struct {
	Track         *Track   `json:"track"`
	Queue         []*Track `json:"-"`
	QueueIndex    int      `json:"queueIndex"`
	IsPlaying     bool     `json:"isPlaying"`
	PositionMs    int64    `json:"positionMs"`
	AnchorMs      int64    `json:"anchorMs"`
	Seq           int      `json:"seq"`
	QueueSeq      int      `json:"queueSeq"`
	UpdatedBy     *string  `json:"updatedBy"`
	UpdatedAtMs   int64    `json:"updatedAtMs"`
	StartedBy     *string  `json:"startedBy"`
	StartedByName *string  `json:"startedByName"`
	AutoplayEnabled bool   `json:"autoplayEnabled"`
}

func NewPlaybackState() *PlaybackState {
	now := clock.NowMs()
	return &PlaybackState{
		Queue:       make([]*Track, 0),
		QueueIndex:  -1,
		AnchorMs:    now,
		UpdatedAtMs: now,
	}
}

func (p *PlaybackState) PositionAt(serverMs int64) int64 {
	if !p.IsPlaying {
		return p.PositionMs
	}
	elapsed := serverMs - p.AnchorMs
	if elapsed < 0 {
		elapsed = 0
	}
	pos := p.PositionMs + elapsed
	if p.Track != nil && p.Track.DurationMs != nil && *p.Track.DurationMs > 0 {
		if pos > *p.Track.DurationMs {
			return *p.Track.DurationMs
		}
	}
	return pos
}

func (p *PlaybackState) touch(memberId *string) {
	p.Seq++
	p.UpdatedBy = memberId
	p.UpdatedAtMs = clock.NowMs()
}

func (p *PlaybackState) touchQueue(memberId *string) {
	p.QueueSeq++
	p.UpdatedBy = memberId
	p.UpdatedAtMs = clock.NowMs()
}

func (p *PlaybackState) Play(memberId *string, positionMs *int64) {
	now := clock.NowMs()
	startAt := p.PositionAt(now)
	if positionMs != nil {
		startAt = *positionMs
	}
	if startAt < 0 {
		startAt = 0
	}
	p.PositionMs = startAt
	p.IsPlaying = true
	p.AnchorMs = now + int64(config.PlayLeadMs)
	p.touch(memberId)
}

func (p *PlaybackState) Pause(memberId *string, positionMs *int64) {
	now := clock.NowMs()
	pauseAt := p.PositionAt(now)
	if positionMs != nil {
		pauseAt = *positionMs
	}
	if pauseAt < 0 {
		pauseAt = 0
	}
	p.PositionMs = pauseAt
	p.IsPlaying = false
	p.AnchorMs = now
	p.touch(memberId)
}

func (p *PlaybackState) Seek(memberId *string, positionMs int64) {
	if positionMs < 0 {
		positionMs = 0
	}
	p.PositionMs = positionMs
	lead := int64(0)
	if p.IsPlaying {
		lead = int64(config.PlayLeadMs)
	}
	p.AnchorMs = clock.NowMs() + lead
	p.touch(memberId)
}

func (p *PlaybackState) SetTrack(
	memberId *string,
	track *Track,
	positionMs int64,
	isPlaying bool,
	queueIndex *int,
	memberName *string,
) {
	p.Track = track
	if positionMs < 0 {
		positionMs = 0
	}
	p.PositionMs = positionMs
	p.IsPlaying = isPlaying && track != nil
	lead := int64(0)
	if p.IsPlaying {
		lead = int64(config.PlayLeadMs)
	}
	p.AnchorMs = clock.NowMs() + lead

	if queueIndex != nil {
		p.QueueIndex = *queueIndex
	} else if track != nil {
		match := -1
		for i, item := range p.Queue {
			if item.VideoId == track.VideoId {
				match = i
				break
			}
		}
		p.QueueIndex = match
	}

	p.StartedBy = memberId
	p.StartedByName = memberName
	p.touch(memberId)
}

func (p *PlaybackState) SetQueue(memberId *string, queue []*Track, queueIndex int) {
	if p.Track != nil {
		for i, item := range queue {
			if item.VideoId == p.Track.VideoId {
				queueIndex = i
				break
			}
		}
	}

	if queueIndex >= 0 && queueIndex < len(queue) {
		pastAndCurrent := queue[:queueIndex+1]
		endUpcoming := queueIndex + 1 + config.MaxUpcomingQueue
		if endUpcoming > len(queue) {
			endUpcoming = len(queue)
		}
		upcoming := queue[queueIndex+1 : endUpcoming]
		newQ := make([]*Track, 0, len(pastAndCurrent)+len(upcoming))
		newQ = append(newQ, pastAndCurrent...)
		newQ = append(newQ, upcoming...)
		p.Queue = newQ
		p.QueueIndex = queueIndex
	} else {
		maxLen := config.MaxQueueLength
		if len(queue) < maxLen {
			maxLen = len(queue)
		}
		p.Queue = queue[:maxLen]
		if queueIndex >= 0 && queueIndex < len(p.Queue) {
			p.QueueIndex = queueIndex
		} else {
			p.QueueIndex = -1
		}
	}
	p.touchQueue(memberId)
}

func (p *PlaybackState) AddUpcoming(memberId *string, tracks []*Track, playNext bool) (bool, string) {
	currentUpcoming := 0
	if p.QueueIndex >= 0 {
		currentUpcoming = len(p.Queue) - 1 - p.QueueIndex
		if currentUpcoming < 0 {
			currentUpcoming = 0
		}
	} else {
		currentUpcoming = len(p.Queue)
	}

	slotsLeft := config.MaxUpcomingQueue - currentUpcoming
	if slotsLeft <= 0 {
		return false, "queue_full"
	}

	toAdd := tracks
	if len(toAdd) > slotsLeft {
		toAdd = toAdd[:slotsLeft]
	}
	if len(toAdd) == 0 {
		return false, "no_tracks"
	}

	if playNext && p.QueueIndex >= 0 && p.QueueIndex < len(p.Queue) {
		insertAt := p.QueueIndex + 1
		newQ := make([]*Track, 0, len(p.Queue)+len(toAdd))
		newQ = append(newQ, p.Queue[:insertAt]...)
		newQ = append(newQ, toAdd...)
		newQ = append(newQ, p.Queue[insertAt:]...)
		p.Queue = newQ
	} else {
		p.Queue = append(p.Queue, toAdd...)
	}

	p.touchQueue(memberId)
	return true, ""
}

func (p *PlaybackState) RemoveUpcoming(memberId *string, videoId string) bool {
	match := -1
	for i, item := range p.Queue {
		if item.VideoId == videoId {
			match = i
			break
		}
	}
	if match == -1 || match == p.QueueIndex {
		return false
	}

	p.Queue = append(p.Queue[:match], p.Queue[match+1:]...)
	if p.QueueIndex > match {
		p.QueueIndex--
	}
	p.touchQueue(memberId)
	return true
}

func (p *PlaybackState) ClearUpcoming(memberId *string) bool {
	if p.QueueIndex >= 0 {
		if len(p.Queue) <= p.QueueIndex+1 {
			return false
		}
		p.Queue = p.Queue[:p.QueueIndex+1]
	} else {
		if len(p.Queue) == 0 {
			return false
		}
		p.Queue = p.Queue[:0]
	}
	p.touchQueue(memberId)
	return true
}

func (p *PlaybackState) MoveUpcoming(memberId *string, fromIdx, toIdx int, videoId string) bool {
	if videoId != "" {
		match := -1
		for i, item := range p.Queue {
			if item.VideoId == videoId {
				match = i
				break
			}
		}
		if match == -1 {
			return false
		}
		fromIdx = match
	}

	if fromIdx < 0 || fromIdx >= len(p.Queue) || toIdx < 0 || toIdx >= len(p.Queue) {
		return false
	}
	if fromIdx <= p.QueueIndex || toIdx <= p.QueueIndex {
		return false
	}
	if fromIdx == toIdx {
		return true
	}

	item := p.Queue[fromIdx]
	p.Queue = append(p.Queue[:fromIdx], p.Queue[fromIdx+1:]...)
	// Insert at toIdx
	newQ := make([]*Track, 0, len(p.Queue)+1)
	newQ = append(newQ, p.Queue[:toIdx]...)
	newQ = append(newQ, item)
	newQ = append(newQ, p.Queue[toIdx:]...)
	p.Queue = newQ

	p.touchQueue(memberId)
	return true
}

func (p *PlaybackState) Step(memberId *string, delta int, memberName *string) bool {
	target := p.QueueIndex + delta
	if target < 0 || target >= len(p.Queue) {
		return false
	}
	p.SetTrack(memberId, p.Queue[target], 0, true, &target, memberName)
	return true
}

func (p *PlaybackState) ToWire(serverMs int64) map[string]interface{} {
	var trackWire interface{}
	if p.Track != nil {
		trackWire = p.Track
	}

	return map[string]interface{}{
		"seq":                 p.Seq,
		"track":               trackWire,
		"queueSeq":            p.QueueSeq,
		"queueIndex":          p.QueueIndex,
		"queueLength":         len(p.Queue),
		"isPlaying":           p.IsPlaying,
		"positionMs":          p.PositionMs,
		"anchorMs":            p.AnchorMs,
		"effectivePositionMs": p.PositionAt(serverMs),
		"updatedBy":           p.UpdatedBy,
		"startedBy":           p.StartedBy,
		"startedByName":       p.StartedByName,
		"autoplayEnabled":     p.AutoplayEnabled,
		"updatedAtMs":         p.UpdatedAtMs,
	}
}

// SetAutoplay changes the party-wide AutoPlay choice. It belongs to playback
// state so a replacement AutoPlay supplier can continue after the host leaves.
func (p *PlaybackState) SetAutoplay(memberId *string, enabled bool) {
	if p.AutoplayEnabled == enabled {
		return
	}
	p.AutoplayEnabled = enabled
	p.UpdatedBy = memberId
	p.UpdatedAtMs = clock.NowMs()
}

func (p *PlaybackState) QueueToWire() map[string]interface{} {
	return map[string]interface{}{
		"seq":   p.QueueSeq,
		"index": p.QueueIndex,
		"items": p.Queue,
	}
}

// Member represents a joined device.
type Member struct {
	MemberId           string  `json:"memberId"`
	// Role is protocol.RoleSpeaker or protocol.RoleRemote.
	Role               string  `json:"role"`
	// DeviceKey, App and DeviceName say which phone and which build of the app
	// this is, for Connect: one phone running the stable and the dev build is
	// two members that share a DeviceKey, and two phones never share one.
	DeviceKey          string  `json:"deviceKey,omitempty"`
	App                string  `json:"app,omitempty"`
	DeviceName         string  `json:"deviceName,omitempty"`
	UserId             string  `json:"userId"`
	DeviceId           string  `json:"-"`
	DisplayName        string  `json:"displayName"`
	AvatarUrl          *string `json:"avatarUrl,omitempty"`
	Token              string  `json:"-"`
	IsHost             bool    `json:"isHost"`
	Connected          bool    `json:"connected"`
	JoinedAtMs         int64   `json:"joinedAtMs"`
	LastSeenMs         int64   `json:"lastSeenMs"`
	ControlBudget      float64 `json:"-"`
	ControlBudgetAtMs  int64   `json:"-"`
	FrameBudget        float64 `json:"-"`
	FrameBudgetAtMs    int64   `json:"-"`
}

func (m *Member) ToWire() map[string]interface{} {
	return map[string]interface{}{
		"memberId":    m.MemberId,
		"userId":      m.UserId,
		"displayName": m.DisplayName,
		"avatarUrl":   m.AvatarUrl,
		"role":        m.Role,
		"deviceKey":   m.DeviceKey,
		"app":         m.App,
		"deviceName":  m.DeviceName,
		"isHost":      m.IsHost,
		"connected":   m.Connected,
		"joinedAtMs":  m.JoinedAtMs,
		"lastSeenMs":  m.LastSeenMs,
	}
}

func (m *Member) IsRemote() bool { return m.Role == protocol.RoleRemote }

// Party kinds. A jam is joined with a code; a Connect party belongs to one
// account, is joined by signing in, and has exactly one device playing it.
const (
	KindJam     = "jam"
	KindConnect = "connect"
)

// Party holds members and playback state for a single room code.
type Party struct {
	mu         sync.Mutex
	Code       string
	Kind       string
	// Account is the gateway account a Connect party belongs to.
	Account    string
	Members    map[string]*Member
	Playback   *PlaybackState
	MaxMembers int
	// HostOnlyControl restricts every playback and queue action to the host.
	//
	// Deliberately on Party rather than on PlaybackState: it changes rarely and
	// PlaybackState.ToWire rides the heartbeat to every device every few
	// seconds. It travels with MaxMembers on the members frame instead, which
	// is only sent when something about the membership actually changes.
	HostOnlyControl bool
	CreatedAtMs     int64
	TouchedAtMs     int64
	EmptySinceMs    *int64
	// LastReanchorMs is when Reanchor last moved the party. See ReanchorCooldownMs.
	LastReanchorMs int64
}

func NewParty(code string) *Party {
	return NewPartyWithMaxMembers(code, config.MaxMembers)
}

func NewPartyWithMaxMembers(code string, maxMembers int) *Party {
	if maxMembers < 2 || maxMembers > 10 {
		maxMembers = 5
	}
	now := clock.NowMs()
	empty := now
	return &Party{
		Code:         code,
		Kind:         KindJam,
		Members:      make(map[string]*Member),
		Playback:     NewPlaybackState(),
		MaxMembers:   maxMembers,
		CreatedAtMs:  now,
		TouchedAtMs:  now,
		EmptySinceMs: &empty,
	}
}

func (p *Party) IsConnect() bool { return p.Kind == KindConnect }

func (p *Party) Lock()   { p.mu.Lock() }
func (p *Party) Unlock() { p.mu.Unlock() }

func (p *Party) Touch() {
	p.TouchedAtMs = clock.NowMs()
}

func (p *Party) Host() *Member {
	for _, m := range p.Members {
		if m.IsHost {
			return m
		}
	}
	return nil
}

func (p *Party) Join(userId, deviceId, displayName string, avatarUrl *string) (*Member, error) {
	return p.JoinAs(userId, deviceId, displayName, avatarUrl, protocol.RoleSpeaker)
}

// JoinAs joins with a role. Speakers are limited by MaxMembers and remotes by
// config.MaxRemotes, separately: a remote adds no stream to the party, so it
// should not cost anybody a seat at it.
func (p *Party) JoinAs(userId, deviceId, displayName string, avatarUrl *string, role string) (*Member, error) {
	if role != protocol.RoleRemote {
		role = protocol.RoleSpeaker
	}
	now := clock.NowMs()
	// Rejoining device check
	for _, m := range p.Members {
		if m.DeviceId == deviceId {
			if m.Role != role {
				if err := p.checkRoom(role, m); err != nil {
					return nil, err
				}
				m.Role = role
				// The host is the party's clock, and a remote cannot be one.
				if m.IsHost && role == protocol.RoleRemote {
					m.IsHost = false
					p.electHost(m)
					if p.Host() == nil {
						m.IsHost = true
					}
				}
			}
			m.DisplayName = displayName
			m.AvatarUrl = avatarUrl
			m.UserId = userId
			m.LastSeenMs = now
			m.Token = randomToken(24)
			p.Touch()
			return m, nil
		}
	}

	if err := p.checkRoom(role, nil); err != nil {
		return nil, err
	}

	m := &Member{
		MemberId:          randomHex(8),
		Role:              role,
		UserId:            userId,
		DeviceId:          deviceId,
		DisplayName:       displayName,
		AvatarUrl:         avatarUrl,
		Token:             randomToken(24),
		// A remote never becomes host on arrival. A party held by a remote
		// has nobody playing it, so the first speaker to join takes it over.
		IsHost:            role == protocol.RoleSpeaker && (p.Host() == nil || p.Host().IsRemote()),
		Connected:         false,
		JoinedAtMs:        now,
		LastSeenMs:        now,
		ControlBudget:     config.ControlRatePerSecond,
		ControlBudgetAtMs: now,
		FrameBudget:       config.FrameRatePerSecond,
		FrameBudgetAtMs:   now,
	}
	if m.IsHost {
		if previous := p.Host(); previous != nil {
			previous.IsHost = false
		}
	}
	p.Members[m.MemberId] = m
	p.Touch()
	return m, nil
}

// JoinConnect brings one of an account's devices into its Connect party, and
// decides whether it is the device that plays or a remote for the one that does.
//
// A device is its deviceKey and app together, never anything chosen fresh per
// process: so a phone that restarts, reinstalls or reconnects comes back as the
// member it was instead of piling up as another, and the stable and dev builds
// on one phone are two members of the same phone rather than two phones.
//
// It plays when nothing else is: no output, an output that is not connected, or
// one that has been paused for longer than ConnectHandoverMs. Otherwise it joins
// as a remote for what is already playing, the way opening Spotify on a laptop
// shows the music coming out of the phone.
func (p *Party) JoinConnect(userId, deviceKey, app, deviceName, displayName string, avatarUrl *string) (*Member, error) {
	deviceId := deviceKey + ":" + app
	now := clock.NowMs()
	var m *Member
	for _, existing := range p.Members {
		if existing.DeviceId == deviceId {
			m = existing
			break
		}
	}
	if m == nil {
		if err := p.checkRoom(protocol.RoleRemote, nil); err != nil {
			return nil, err
		}
		m = &Member{
			MemberId:          randomHex(8),
			Role:              protocol.RoleRemote,
			DeviceId:          deviceId,
			JoinedAtMs:        now,
			ControlBudget:     config.ControlRatePerSecond,
			ControlBudgetAtMs: now,
			FrameBudget:       config.FrameRatePerSecond,
			FrameBudgetAtMs:   now,
		}
		p.Members[m.MemberId] = m
	}
	m.UserId = userId
	m.DeviceKey = deviceKey
	m.App = app
	m.DeviceName = deviceName
	m.DisplayName = displayName
	m.AvatarUrl = avatarUrl
	m.LastSeenMs = now
	m.Token = randomToken(24)

	host := p.Host()
	idle := !p.Playback.IsPlaying && now-p.Playback.UpdatedAtMs > config.ConnectHandoverMs
	if host == nil || host == m || host.IsRemote() || !host.Connected || idle {
		// Taking over from an output that is not there: whatever it was
		// "playing" stopped when it went, so this device starts paused there.
		if host != m && p.Playback.IsPlaying && (host == nil || !host.Connected) {
			p.Playback.Pause(nil, nil)
		}
		p.setOutput(m)
	} else {
		m.Role = protocol.RoleRemote
		m.IsHost = false
	}
	p.Touch()
	return m, nil
}

// setOutput makes m the one device playing a Connect party. The output is the
// host and so the clock; whoever held it becomes a remote.
func (p *Party) setOutput(m *Member) {
	for _, other := range p.Members {
		if other != m && other.IsHost {
			other.IsHost = false
			other.Role = protocol.RoleRemote
		}
	}
	m.IsHost = true
	m.Role = protocol.RoleSpeaker
	p.LastReanchorMs = 0
}

// Transfer moves a Connect party's playback to another of its devices.
//
// The party carries on from where it is, but a playing party is restarted a
// moment ahead (TransferLeadMs) rather than left running: the new device has to
// fetch and start a song from cold, and without the gap it would come in that
// far into it, having skipped what everyone was listening to.
func (p *Party) Transfer(to string) error {
	if !p.IsConnect() {
		return NewPartyError(409, "not_connect", "Playback can only be moved between your own devices.")
	}
	target, ok := p.Members[to]
	if !ok {
		return NewPartyError(404, "not_found", "That device is no longer connected.")
	}
	if !target.Connected {
		return NewPartyError(409, "device_away", "That device is not connected right now.")
	}
	if target.IsHost && !target.IsRemote() {
		return nil
	}
	p.setOutput(target)
	pb := p.Playback
	now := clock.NowMs()
	if pb.IsPlaying {
		pb.PositionMs = pb.PositionAt(now)
		pb.AnchorMs = now + config.TransferLeadMs
	}
	pb.Seq++
	pb.UpdatedAtMs = now
	p.Touch()
	return nil
}

// checkRoom refuses a member of this role once that role is full. except is a
// rejoining member switching role, who must not be counted against themselves.
func (p *Party) checkRoom(role string, except *Member) error {
	if p.IsConnect() {
		n := len(p.Members)
		if except != nil {
			n--
		}
		if n >= config.ConnectMaxDevices {
			return NewPartyError(409, "too_many_devices", fmt.Sprintf("This account already has %d devices connected.", config.ConnectMaxDevices))
		}
		return nil
	}
	if role == protocol.RoleRemote {
		if p.countRole(protocol.RoleRemote, except) >= config.MaxRemotes {
			return NewPartyError(409, "remotes_full", fmt.Sprintf("This party already has %d remotes.", config.MaxRemotes))
		}
		return nil
	}
	if p.countRole(protocol.RoleSpeaker, except) >= p.MaxMembers {
		return NewPartyError(409, "party_full", fmt.Sprintf("This party is full (%d devices).", p.MaxMembers))
	}
	return nil
}

func (p *Party) countRole(role string, except *Member) int {
	n := 0
	for _, m := range p.Members {
		if m != except && m.Role == role {
			n++
		}
	}
	return n
}

// SpeakerCount is how many members are playing the party out loud, which is
// the number MaxMembers limits.
func (p *Party) SpeakerCount() int { return p.countRole(protocol.RoleSpeaker, nil) }

// electHost hands the host role on. A speaker is preferred, connected first and
// then longest in the party, so the music stays with somebody who is playing it.
// A party left with only remotes gives it to a remote rather than to nobody, so
// a host-only party can still be steered until a speaker arrives.
func (p *Party) electHost(except *Member) {
	var best *Member
	better := func(a, b *Member) bool {
		if b == nil {
			return true
		}
		if a.IsRemote() != b.IsRemote() {
			return !a.IsRemote()
		}
		if a.Connected != b.Connected {
			return a.Connected
		}
		return a.JoinedAtMs < b.JoinedAtMs
	}
	for _, m := range p.Members {
		if m != except && better(m, best) {
			best = m
		}
	}
	if best != nil {
		best.IsHost = true
	}
}

// ClockMember is the device whose real playhead the party follows, or nil.
//
// That is the host, as long as the host is playing the party out loud. The
// server's timeline is an ideal that a real player only approximates: a stall,
// a slow start or a re-buffer each leave the device behind it. With every
// member playing, closing that gap is each device's own business. With remotes
// in the party it is not: a remote shows a progress bar for audio coming out
// of the host, and the host skipping its own music forward to match an ideal
// nobody is hearing is exactly backwards. So the host reports what it is really
// doing and the party moves to it (Reanchor), rather than the other way round.
func (p *Party) ClockMember() *Member {
	if h := p.Host(); h != nil && !h.IsRemote() {
		return h
	}
	return nil
}

// PlaybackToWire is the playback state as every device sees it, with the
// clock member named so that member knows to report and not to correct.
func (p *Party) PlaybackToWire(serverMs int64) map[string]interface{} {
	wire := p.Playback.ToWire(serverMs)
	if c := p.ClockMember(); c != nil {
		wire["clockMemberId"] = c.MemberId
	} else {
		wire["clockMemberId"] = nil
	}
	return wire
}

// Reanchor moves a playing party onto a measured playhead from its clock
// member: positionMs is where that device really was at atMs on the server's
// clock. Reports true when it moved the party, in which case the caller must
// broadcast the new state.
//
// Small differences are left alone (ReanchorThresholdMs): every device already
// tolerates more than that, and a broadcast is not free. So is anything that
// arrives while a control is still settling, because just after a play or seek
// the device is still starting, and reading that as drift would undo the control.
func (p *Party) Reanchor(member *Member, videoId string, positionMs, atMs, now int64) bool {
	clockMember := p.ClockMember()
	pb := p.Playback
	if clockMember == nil || clockMember != member || !pb.IsPlaying || pb.Track == nil {
		return false
	}
	if videoId == "" || videoId != pb.Track.VideoId {
		return false
	}
	// A report stamped well away from now is either stale or from a device
	// whose clock offset is wrong; neither is something to move a party onto.
	if atMs > now+1000 || atMs < now-5000 || positionMs < 0 {
		return false
	}
	if now < pb.AnchorMs+1500 {
		return false
	}
	if now-p.LastReanchorMs < config.ReanchorCooldownMs {
		return false
	}
	drift := positionMs - pb.PositionAt(atMs)
	if drift < 0 {
		drift = -drift
	}
	if drift <= config.ReanchorThresholdMs {
		return false
	}
	pb.PositionMs = positionMs
	pb.AnchorMs = atMs
	// A re-anchor is the party catching up with itself, not anybody's action,
	// so it bumps seq to be applied but leaves updatedBy naming whoever last
	// actually did something.
	pb.Seq++
	pb.UpdatedAtMs = now
	p.LastReanchorMs = now
	return true
}

func (p *Party) SetMaxMembers(member *Member, maxMembers int) error {
	if !member.IsHost {
		return NewPartyError(403, "host_only", "Only the host can change the party size.")
	}
	if maxMembers < 2 || maxMembers > 10 {
		return NewPartyError(422, "invalid_capacity", "Party size must be between 2 and 10.")
	}
	if maxMembers < p.SpeakerCount() {
		return NewPartyError(409, "party_too_small", "Party size cannot be smaller than the current member count.")
	}
	p.MaxMembers = maxMembers
	p.Touch()
	return nil
}

// SetHostOnlyControl restricts the music to the host, or hands it back to
// everyone. Host-only, like SetMaxMembers: a listener who could turn this off
// is not restricted by it.
func (p *Party) SetHostOnlyControl(member *Member, enabled bool) error {
	if !member.IsHost {
		return NewPartyError(403, "host_only", "Only the host can change who controls the music.")
	}
	if p.HostOnlyControl == enabled {
		return nil
	}
	p.HostOnlyControl = enabled
	p.Touch()
	return nil
}

// MayControl reports whether this member is allowed to drive playback and the
// queue right now.
//
// The host always may. Everyone else may until the host says otherwise — which
// is the behaviour this feature shipped with and stays the default for a party
// that never touches the setting.
func (p *Party) MayControl(member *Member) bool {
	return !p.HostOnlyControl || member.IsHost
}

func (p *Party) Authenticate(token string) (*Member, error) {
	for _, m := range p.Members {
		if subtle.ConstantTimeCompare([]byte(m.Token), []byte(token)) == 1 {
			return m, nil
		}
	}
	return nil, NewPartyError(401, "bad_token", "This device is not a member of that party.")
}

func (p *Party) Remove(memberId string) *Member {
	m, ok := p.Members[memberId]
	if !ok {
		return nil
	}
	delete(p.Members, memberId)
	if m.IsHost {
		p.electHost(nil)
		if p.IsConnect() {
			// Nobody is playing it any more, so the party stops where the
			// output did rather than running on, on paper, for whichever device
			// opens next to land far into the song. Playback moves to a device
			// that is still here, paused, for its owner to resume.
			if p.Playback.IsPlaying {
				p.Playback.Pause(nil, nil)
			}
			if h := p.Host(); h != nil && h.IsRemote() {
				h.Role = protocol.RoleSpeaker
			}
		}
	}
	p.Touch()
	p.refreshEmptiness()
	return m
}

func (p *Party) SpendControlBudget(member *Member) bool {
	now := clock.NowMs()
	elapsedS := float64(now-member.ControlBudgetAtMs) / 1000.0
	if elapsedS < 0 {
		elapsedS = 0
	}
	member.ControlBudget = member.ControlBudget + elapsedS*config.ControlRatePerSecond
	if member.ControlBudget > config.ControlRatePerSecond {
		member.ControlBudget = config.ControlRatePerSecond
	}
	member.ControlBudgetAtMs = now
	if member.ControlBudget < 1.0 {
		return false
	}
	member.ControlBudget -= 1.0
	return true
}

// SpendFrameBudget limits every received WebSocket frame, not just controls.
// This prevents ping/sync frames from bypassing the control rate limit.
func (p *Party) SpendFrameBudget(member *Member) bool {
	now := clock.NowMs()
	elapsedS := float64(now-member.FrameBudgetAtMs) / 1000.0
	if elapsedS < 0 {
		elapsedS = 0
	}
	member.FrameBudget += elapsedS * config.FrameRatePerSecond
	if member.FrameBudget > config.FrameRatePerSecond {
		member.FrameBudget = config.FrameRatePerSecond
	}
	member.FrameBudgetAtMs = now
	if member.FrameBudget < 1.0 {
		return false
	}
	member.FrameBudget -= 1.0
	return true
}

func (p *Party) MarkConnected(member *Member, connected bool) {
	member.Connected = connected
	member.LastSeenMs = clock.NowMs()
	p.Touch()
	p.refreshEmptiness()
}

func (p *Party) refreshEmptiness() {
	anyConn := false
	for _, m := range p.Members {
		if m.Connected {
			anyConn = true
			break
		}
	}
	if anyConn {
		p.EmptySinceMs = nil
	} else if p.EmptySinceMs == nil {
		now := clock.NowMs()
		p.EmptySinceMs = &now
	}
}

func (p *Party) ExpiredMembers(now int64) []*Member {
	var expired []*Member
	for _, m := range p.Members {
		if !m.Connected && now-m.LastSeenMs > config.DisconnectGraceMs {
			expired = append(expired, m)
		}
	}
	return expired
}

func (p *Party) IsExpired(now int64) bool {
	if p.IsConnect() {
		return p.EmptySinceMs != nil && now-*p.EmptySinceMs > config.ConnectIdleTTLMs
	}
	if now-p.CreatedAtMs > config.PartyMaxAgeMs {
		return true
	}
	if p.EmptySinceMs != nil {
		return now-*p.EmptySinceMs > config.EmptyPartyTTLMs
	}
	return false
}

func (p *Party) ToWire() map[string]interface{} {
	now := clock.NowMs()
	membersList := make([]*Member, 0, len(p.Members))
	for _, m := range p.Members {
		membersList = append(membersList, m)
	}
	sort.Slice(membersList, func(i, j int) bool {
		return membersList[i].JoinedAtMs < membersList[j].JoinedAtMs
	})

	membersWire := make([]map[string]interface{}, 0, len(membersList))
	for _, m := range membersList {
		membersWire = append(membersWire, m.ToWire())
	}

	return map[string]interface{}{
		"code":            p.Code,
		"kind":            p.Kind,
		"createdAtMs":     p.CreatedAtMs,
		"maxMembers":      p.MaxMembers,
		"hostOnlyControl": p.HostOnlyControl,
		"members":         membersWire,
		"playback":        p.PlaybackToWire(now),
		"queue":           p.Playback.QueueToWire(),
		"serverMs":        now,
	}
}

// PartyStore holds all live parties in process memory.
type PartyStore struct {
	mu      sync.RWMutex
	parties map[string]*Party
}

func NewPartyStore() *PartyStore {
	return &PartyStore{
		parties: make(map[string]*Party),
	}
}

func (s *PartyStore) Len() int {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return len(s.parties)
}

// MemberCount is the number of members across every live party. Parties are
// copied out first so no party lock is ever taken under the store lock.
func (s *PartyStore) MemberCount() int {
	s.mu.RLock()
	parties := make([]*Party, 0, len(s.parties))
	for _, p := range s.parties {
		parties = append(parties, p)
	}
	s.mu.RUnlock()
	total := 0
	for _, p := range parties {
		p.mu.Lock()
		total += len(p.Members)
		p.mu.Unlock()
	}
	return total
}

func (s *PartyStore) Create() (*Party, error) {
	return s.CreateWithLimit(0)
}

// CreateWithLimit atomically rejects creation once the service-wide room limit
// is reached. A non-positive limit means unlimited (used by unit tests).
func (s *PartyStore) CreateWithLimit(maxParties int) (*Party, error) {
	return s.CreateWithLimits(maxParties, config.MaxMembers)
}

func (s *PartyStore) CreateWithLimits(maxParties, maxMembers int) (*Party, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if maxParties > 0 && len(s.parties) >= maxParties {
		return nil, NewPartyError(503, "server_full", "The party server is busy. Please try again in a few minutes.")
	}

	for i := 0; i < 12; i++ {
		code := codes.NewCode()
		if _, exists := s.parties[code]; !exists {
		p := NewPartyWithMaxMembers(code, maxMembers)
			s.parties[code] = p
			return p, nil
		}
	}
	return nil, NewPartyError(503, "code_exhausted", "Could not allocate a party code.")
}

// AccountCode is the store key of an account's Connect party. It begins with
// a character no typed code can contain, so it can never be reached by
// guessing a code: the join and preview endpoints refuse it outright.
func AccountCode(account string) string {
	sum := sha256.Sum256([]byte("bitchord-connect:" + account))
	return "~" + hex.EncodeToString(sum[:8])
}

// IsAccountCode reports whether code names a Connect party.
func IsAccountCode(code string) bool { return strings.HasPrefix(code, "~") }

// Account returns an account's Connect party, making it on first use. It is
// not subject to the service-wide party limit: it is a household's own
// session, not a room anybody can open.
func (s *PartyStore) Account(account string) *Party {
	code := AccountCode(account)
	s.mu.Lock()
	defer s.mu.Unlock()
	if p, ok := s.parties[code]; ok {
		return p
	}
	p := NewPartyWithMaxMembers(code, config.ConnectMaxDevices)
	p.Kind = KindConnect
	p.Account = account
	s.parties[code] = p
	return p
}

func (s *PartyStore) Get(code string) (*Party, error) {
	if IsAccountCode(code) {
		s.mu.RLock()
		p, ok := s.parties[code]
		s.mu.RUnlock()
		if !ok {
			return nil, NewPartyError(404, "no_such_party", "No party with that code.")
		}
		return p, nil
	}
	norm := codes.Normalise(code)
	s.mu.RLock()
	p, ok := s.parties[norm]
	s.mu.RUnlock()
	if !ok {
		return nil, NewPartyError(404, "no_such_party", "No party with that code.")
	}
	return p, nil
}

func (s *PartyStore) Find(code string) *Party {
	if IsAccountCode(code) {
		return nil
	}
	norm := codes.Normalise(code)
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.parties[norm]
}

func (s *PartyStore) Drop(code string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.parties, code)
}

func (s *PartyStore) All() []*Party {
	s.mu.RLock()
	defer s.mu.RUnlock()
	res := make([]*Party, 0, len(s.parties))
	for _, p := range s.parties {
		res = append(res, p)
	}
	return res
}

func (s *PartyStore) Sweep(now int64) []*Party {
	s.mu.Lock()
	defer s.mu.Unlock()

	var changed []*Party
	for code, party := range s.parties {
		party.Lock()
		gone := party.ExpiredMembers(now)
		for _, m := range gone {
			party.Remove(m.MemberId)
		}
		expired := party.IsExpired(now)
		party.Unlock()

		if expired {
			delete(s.parties, code)
			continue
		}
		if len(gone) > 0 {
			changed = append(changed, party)
		}
	}
	return changed
}

func randomHex(n int) string {
	b := make([]byte, n)
	rand.Read(b)
	return hex.EncodeToString(b)
}

func randomToken(n int) string {
	b := make([]byte, n)
	rand.Read(b)
	return hex.EncodeToString(b)
}
