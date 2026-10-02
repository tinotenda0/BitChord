package party

import (
	"fmt"
	"testing"

	"github.com/KabirSinghBhatia/BitChord/backend/clock"
	"github.com/KabirSinghBhatia/BitChord/backend/config"
	"github.com/KabirSinghBhatia/BitChord/backend/protocol"
)

func TestRemotesDoNotTakeSpeakerSeats(t *testing.T) {
	p := NewParty("ROLE01")
	for i := 0; i < p.MaxMembers; i++ {
		if _, err := p.Join(fmt.Sprintf("u%d", i), fmt.Sprintf("d%d", i), "Speaker", nil); err != nil {
			t.Fatalf("speaker %d refused: %v", i, err)
		}
	}
	if _, err := p.Join("u_extra", "d_extra", "Extra", nil); err == nil {
		t.Fatalf("a full party must refuse another speaker")
	}
	r, err := p.JoinAs("u_r", "d_r", "Remote", nil, protocol.RoleRemote)
	if err != nil {
		t.Fatalf("a full party must still take a remote: %v", err)
	}
	if r.IsHost {
		t.Errorf("a remote joining a hosted party must not become host")
	}
	if r.ToWire()["role"] != protocol.RoleRemote {
		t.Errorf("the role must travel on the wire, got %v", r.ToWire()["role"])
	}
}

func TestRemotesAreLimitedSeparately(t *testing.T) {
	p := NewParty("ROLE02")
	_, _ = p.Join("host", "dh", "Host", nil)
	for i := 0; i < config.MaxRemotes; i++ {
		if _, err := p.JoinAs(fmt.Sprintf("u%d", i), fmt.Sprintf("d%d", i), "Remote", nil, protocol.RoleRemote); err != nil {
			t.Fatalf("remote %d refused: %v", i, err)
		}
	}
	_, err := p.JoinAs("u_x", "d_x", "Remote", nil, protocol.RoleRemote)
	if pe, ok := err.(*PartyError); !ok || pe.Code != "remotes_full" {
		t.Fatalf("expected remotes_full, got %v", err)
	}
}

func TestHostPassesToASpeakerBeforeARemote(t *testing.T) {
	p := NewParty("ROLE03")
	host, _ := p.Join("u1", "d1", "Host", nil)
	// The remote joined first, so plain join order would pick it.
	remote, _ := p.JoinAs("u2", "d2", "Remote", nil, protocol.RoleRemote)
	remote.JoinedAtMs = host.JoinedAtMs - 1
	speaker, _ := p.Join("u3", "d3", "Speaker", nil)

	p.Remove(host.MemberId)
	if !speaker.IsHost || remote.IsHost {
		t.Fatalf("host should pass to the speaker, got speaker=%v remote=%v", speaker.IsHost, remote.IsHost)
	}
	if p.ClockMember() != speaker {
		t.Errorf("the new host must become the clock")
	}
}

func TestOnlyRemotesLeftStillHaveAHostButNoClock(t *testing.T) {
	p := NewParty("ROLE04")
	host, _ := p.Join("u1", "d1", "Host", nil)
	remote, _ := p.JoinAs("u2", "d2", "Remote", nil, protocol.RoleRemote)

	p.Remove(host.MemberId)
	if !remote.IsHost {
		t.Fatalf("a party of remotes must still have somebody able to steer it")
	}
	if p.ClockMember() != nil {
		t.Errorf("a remote plays nothing, so it cannot be the clock")
	}
	if p.PlaybackToWire(clock.NowMs())["clockMemberId"] != nil {
		t.Errorf("no clock must read as null on the wire")
	}

	// The first speaker to arrive takes the party over, and becomes its clock.
	speaker, _ := p.Join("u3", "d3", "Speaker", nil)
	if !speaker.IsHost || remote.IsHost {
		t.Errorf("a speaker joining a party held by a remote must take it over")
	}
	if p.ClockMember() != speaker {
		t.Errorf("the speaker that took over must be the clock")
	}
}

func TestHostSwitchingToRemoteHandsOverTheParty(t *testing.T) {
	p := NewParty("ROLE05")
	host, _ := p.Join("u1", "d1", "Host", nil)
	speaker, _ := p.Join("u2", "d2", "Speaker", nil)

	again, err := p.JoinAs("u1", "d1", "Host", nil, protocol.RoleRemote)
	if err != nil {
		t.Fatalf("rejoining as a remote was refused: %v", err)
	}
	if again != host || host.Role != protocol.RoleRemote {
		t.Fatalf("a rejoin must switch the existing member's role")
	}
	if host.IsHost || !speaker.IsHost {
		t.Errorf("the host role must move to the remaining speaker")
	}
}

// playingParty is a party playing one track, anchored long enough ago that a
// report is not mistaken for a control still settling.
func playingParty(t *testing.T) (*Party, *Member, *Member) {
	t.Helper()
	p := NewParty("ROLE06")
	host, _ := p.Join("u1", "d1", "Host", nil)
	remote, _ := p.JoinAs("u2", "d2", "Remote", nil, protocol.RoleRemote)
	track := &Track{VideoId: "song"}
	p.Playback.SetTrack(&host.MemberId, track, 0, true, nil, nil)
	now := clock.NowMs()
	p.Playback.PositionMs = 10_000
	p.Playback.AnchorMs = now - 10_000
	return p, host, remote
}

func TestReanchorFollowsTheClock(t *testing.T) {
	p, host, _ := playingParty(t)
	now := clock.NowMs()
	seq := p.Playback.Seq
	updatedBy := p.Playback.UpdatedBy

	// The party thinks 20s; the host has stalled and is really at 17s.
	if !p.Reanchor(host, "song", 17_000, now, now) {
		t.Fatalf("a 3s stall on the clock must move the party")
	}
	if got := p.Playback.PositionAt(now); got != 17_000 {
		t.Errorf("party should now be at 17000, got %d", got)
	}
	if p.Playback.Seq != seq+1 {
		t.Errorf("a re-anchor must bump seq so devices apply it")
	}
	if p.Playback.UpdatedBy != updatedBy {
		t.Errorf("a re-anchor is nobody's action and must not change updatedBy")
	}

	// Straight after, the cooldown holds even for a large difference.
	if p.Reanchor(host, "song", 5_000, now+100, now+100) {
		t.Errorf("a second re-anchor inside the cooldown must be refused")
	}
}

func TestReanchorIgnoresWhatItShould(t *testing.T) {
	p, host, remote := playingParty(t)
	now := clock.NowMs()
	cases := []struct {
		name    string
		member  *Member
		videoId string
		pos, at int64
	}{
		{"small drift", host, "song", 20_000 - config.ReanchorThresholdMs + 1, now},
		{"not the clock", remote, "song", 1_000, now},
		{"another track", host, "other", 1_000, now},
		{"stale report", host, "song", 1_000, now - 10_000},
		{"report from the future", host, "song", 1_000, now + 10_000},
	}
	for _, c := range cases {
		if p.Reanchor(c.member, c.videoId, c.pos, c.at, now) {
			t.Errorf("%s: must not re-anchor", c.name)
		}
	}

	// Just after a control the device is still starting; that is not drift.
	p.Playback.Seek(&host.MemberId, 30_000)
	if p.Reanchor(host, "song", 1_000, now, now) {
		t.Errorf("a report right after a control must not undo it")
	}

	// Paused parties have nothing running to drift.
	p.Playback.Pause(&host.MemberId, nil)
	later := now + 10_000
	if p.Reanchor(host, "song", 1_000, later, later) {
		t.Errorf("a paused party must not re-anchor")
	}
}

func TestCapacityCannotShrinkBelowSpeakers(t *testing.T) {
	p := NewParty("ROLE07")
	host, _ := p.Join("u1", "d1", "Host", nil)
	_, _ = p.Join("u2", "d2", "Speaker", nil)
	_, _ = p.JoinAs("u3", "d3", "Remote", nil, protocol.RoleRemote)
	_, _ = p.JoinAs("u4", "d4", "Remote", nil, protocol.RoleRemote)
	if err := p.SetMaxMembers(host, 2); err != nil {
		t.Errorf("two speakers fit in a party of two, remotes aside: %v", err)
	}
}
