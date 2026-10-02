package party

import (
	"testing"

	"github.com/KabirSinghBhatia/BitChord/backend/clock"
	"github.com/KabirSinghBhatia/BitChord/backend/config"
	"github.com/KabirSinghBhatia/BitChord/backend/protocol"
)

func connectParty(t *testing.T) *Party {
	t.Helper()
	return NewPartyStore().Account("tino")
}

func join(t *testing.T, p *Party, key, app string) *Member {
	t.Helper()
	m, err := p.JoinConnect("gw:tino", key, app, "Pixel", "Tino", nil)
	if err != nil {
		t.Fatalf("join %s:%s: %v", key, app, err)
	}
	m.Connected = true
	return m
}

func TestOneAccountOneParty(t *testing.T) {
	s := NewPartyStore()
	if s.Account("tino") != s.Account("tino") {
		t.Fatalf("an account must always land in the same party")
	}
	if s.Account("tino") == s.Account("someone") {
		t.Fatalf("two accounts must never share a party")
	}
	if !IsAccountCode(s.Account("tino").Code) {
		t.Errorf("an account party's code must be unreachable by typing")
	}
	if s.Find(s.Account("tino").Code) != nil {
		t.Errorf("Find serves invite pages and must never return an account party")
	}
}

func TestTheSameDeviceComesBackAsItself(t *testing.T) {
	p := connectParty(t)
	first := join(t, p, "phone0001", "prod")
	firstToken := first.Token
	again := join(t, p, "phone0001", "prod")
	if again != first || len(p.Members) != 1 {
		t.Fatalf("a reconnecting device must reclaim its member, got %d members", len(p.Members))
	}
	if again.Token == firstToken {
		t.Errorf("a rejoin must mint a fresh token")
	}
}

func TestDevAndProdOnOnePhoneAreTwoBuildsOfOneDevice(t *testing.T) {
	p := connectParty(t)
	prod := join(t, p, "phone0001", "prod")
	dev := join(t, p, "phone0001", "dev")
	other := join(t, p, "phone0002", "prod")
	if prod == dev || len(p.Members) != 3 {
		t.Fatalf("each build must be its own member, got %d members", len(p.Members))
	}
	if prod.DeviceKey != dev.DeviceKey {
		t.Errorf("both builds on one phone must share its device key")
	}
	if other.DeviceKey == prod.DeviceKey {
		t.Errorf("two phones must never share a device key")
	}
}

func TestFirstDevicePlaysAndOthersRemoteWhilePlaying(t *testing.T) {
	p := connectParty(t)
	phone := join(t, p, "phone0001", "prod")
	if !phone.IsHost || phone.IsRemote() {
		t.Fatalf("the first device must be the output")
	}
	p.Playback.SetTrack(&phone.MemberId, &Track{VideoId: "song"}, 0, true, nil, nil)

	laptop := join(t, p, "laptop001", "prod")
	if !laptop.IsRemote() || laptop.IsHost {
		t.Fatalf("a device opening while another plays must be a remote")
	}
	if p.ClockMember() != phone {
		t.Errorf("the playing device must stay the clock")
	}
}

func TestAnIdleOutputIsTakenOver(t *testing.T) {
	p := connectParty(t)
	phone := join(t, p, "phone0001", "prod")
	p.Playback.SetTrack(&phone.MemberId, &Track{VideoId: "song"}, 0, false, nil, nil)

	// Paused a moment ago: somebody may be about to press play there again.
	laptop := join(t, p, "laptop001", "prod")
	if !laptop.IsRemote() {
		t.Fatalf("a freshly paused output must not be taken over")
	}

	// Paused for longer than the handover window: nobody is using it.
	p.Playback.UpdatedAtMs = clock.NowMs() - config.ConnectHandoverMs - 1
	laptop = join(t, p, "laptop001", "prod")
	if laptop.IsRemote() || !laptop.IsHost || !phone.IsRemote() {
		t.Fatalf("a device opening on an idle output must take it over")
	}
}

func TestADisconnectedOutputIsTakenOver(t *testing.T) {
	p := connectParty(t)
	phone := join(t, p, "phone0001", "prod")
	p.Playback.SetTrack(&phone.MemberId, &Track{VideoId: "song"}, 0, true, nil, nil)
	phone.Connected = false
	laptop := join(t, p, "laptop001", "prod")
	if laptop.IsRemote() {
		t.Fatalf("a device must not be made a remote for an output that is gone")
	}
	if p.Playback.IsPlaying {
		t.Errorf("taking over from a vanished output must start paused, not far into the song")
	}
}

func TestTheLastDeviceLeavingStopsTheParty(t *testing.T) {
	p := connectParty(t)
	phone := join(t, p, "phone0001", "prod")
	p.Playback.SetTrack(&phone.MemberId, &Track{VideoId: "song"}, 0, true, nil, nil)
	p.Remove(phone.MemberId)
	if p.Playback.IsPlaying {
		t.Fatalf("an account party with nobody playing it must not keep running")
	}
	back := join(t, p, "phone0001", "prod")
	if !back.IsHost || p.Playback.Track == nil || p.Playback.Track.VideoId != "song" {
		t.Errorf("the next device must pick the session up where it stopped")
	}
}

func TestTransferMovesTheOutputAndLeavesRoomToLoad(t *testing.T) {
	p := connectParty(t)
	phone := join(t, p, "phone0001", "prod")
	p.Playback.SetTrack(&phone.MemberId, &Track{VideoId: "song"}, 0, true, nil, nil)
	p.Playback.PositionMs = 30_000
	p.Playback.AnchorMs = clock.NowMs() - 10_000
	laptop := join(t, p, "laptop001", "prod")
	seq := p.Playback.Seq

	if err := p.Transfer(laptop.MemberId); err != nil {
		t.Fatalf("transfer refused: %v", err)
	}
	if !laptop.IsHost || laptop.IsRemote() || !phone.IsRemote() || phone.IsHost {
		t.Fatalf("transfer must swap who plays")
	}
	if p.ClockMember() != laptop {
		t.Errorf("the new output must be the clock")
	}
	if p.Playback.Seq != seq+1 {
		t.Errorf("transfer must bump seq so devices act on it")
	}
	now := clock.NowMs()
	if got := p.Playback.PositionAt(now); got < 39_900 || got > 40_100 {
		t.Errorf("transfer must carry on from where the party was (~40000), got %d", got)
	}
	if p.Playback.AnchorMs < now+config.TransferLeadMs-50 {
		t.Errorf("transfer must restart a moment ahead for the new device to load")
	}

	phone.Connected = false
	if err := p.Transfer(phone.MemberId); err == nil {
		t.Errorf("playback must not be moved to a device that is not connected")
	}
}

func TestTransferIsOnlyForConnect(t *testing.T) {
	p := NewParty("JAM001")
	host, _ := p.Join("u1", "d1", "Host", nil)
	other, _ := p.Join("u2", "d2", "Other", nil)
	other.Connected = true
	if err := p.Transfer(other.MemberId); err == nil {
		t.Fatalf("a jam has no single output to move")
	}
	if !host.IsHost {
		t.Errorf("a refused transfer must change nothing")
	}
}

func TestLosingTheOutputParksPlaybackOnADeviceStillThere(t *testing.T) {
	p := connectParty(t)
	phone := join(t, p, "phone0001", "prod")
	p.Playback.SetTrack(&phone.MemberId, &Track{VideoId: "song"}, 0, true, nil, nil)
	laptop := join(t, p, "laptop001", "prod")

	p.Remove(phone.MemberId)
	if !laptop.IsHost || laptop.IsRemote() {
		t.Fatalf("playback must move to the device still connected")
	}
	if p.Playback.IsPlaying {
		t.Errorf("it must arrive paused, not start blaring from a phone in a pocket")
	}
}

func TestConnectPartiesOutliveTheirDevices(t *testing.T) {
	p := connectParty(t)
	now := clock.NowMs()
	p.CreatedAtMs = now - config.PartyMaxAgeMs - 1
	p.EmptySinceMs = nil
	if p.IsExpired(now) {
		t.Errorf("an account's party has no maximum age")
	}
	empty := now - config.EmptyPartyTTLMs - 1
	p.EmptySinceMs = &empty
	if p.IsExpired(now) {
		t.Errorf("an account's party must outlive a jam's empty timeout")
	}
	long := now - config.ConnectIdleTTLMs - 1
	p.EmptySinceMs = &long
	if !p.IsExpired(now) {
		t.Errorf("an account's party must eventually be swept")
	}
}

func TestConnectLimitsDevicesNotRoles(t *testing.T) {
	p := connectParty(t)
	for i := 0; i < config.ConnectMaxDevices; i++ {
		join(t, p, "device"+string(rune('a'+i))+"xxxx", "prod")
	}
	if _, err := p.JoinConnect("gw:tino", "onetoomany", "prod", "X", "Tino", nil); err == nil {
		t.Fatalf("an account must be limited to ConnectMaxDevices")
	}
	if _, err := p.JoinConnect("gw:tino", "devicea"+"xxxx", "prod", "X", "Tino", nil); err != nil {
		t.Errorf("a device already in must always be able to come back: %v", err)
	}
	_ = protocol.RoleRemote
}

func TestAWokenDeviceTakesOverEvenWhileAnotherPlays(t *testing.T) {
	p := connectParty(t)
	phone := join(t, p, "phone0001", "prod")
	p.Playback.SetTrack(&phone.MemberId, &Track{VideoId: "song"}, 0, true, nil, nil)
	if err := p.ExpectOutput("laptop001:prod"); err != nil {
		t.Fatalf("expect refused: %v", err)
	}

	// A different device arriving meanwhile is still a remote.
	tablet := join(t, p, "tablet001", "prod")
	if !tablet.IsRemote() {
		t.Fatalf("only the woken device is handed playback")
	}

	laptop := join(t, p, "laptop001", "prod")
	if laptop.IsRemote() || !laptop.IsHost || !phone.IsRemote() {
		t.Fatalf("the woken device must take playback over on arrival")
	}
	if !p.Playback.IsPlaying || p.Playback.AnchorMs <= clock.NowMs() {
		t.Errorf("playback must carry on, restarted a moment ahead for the new device")
	}
	if p.PendingOutput != "" {
		t.Errorf("the wake must be used up")
	}

	// Coming back a second time is an ordinary arrival.
	join(t, p, "phone0001", "prod")
	if p.ClockMember() != laptop {
		t.Errorf("a used-up wake must not hand playback over again")
	}
}

func TestAnExpiredWakeIsIgnored(t *testing.T) {
	p := connectParty(t)
	phone := join(t, p, "phone0001", "prod")
	p.Playback.SetTrack(&phone.MemberId, &Track{VideoId: "song"}, 0, true, nil, nil)
	_ = p.ExpectOutput("laptop001:prod")
	p.PendingUntilMs = clock.NowMs() - 1
	if laptop := join(t, p, "laptop001", "prod"); !laptop.IsRemote() {
		t.Fatalf("a wake that came too late must not hijack playback")
	}
}
