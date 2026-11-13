import type { Track } from '@/data/music/types';
import type { QueueTier } from '@/playback/queue/timeline';
import { trackLog } from '@/playback/trackLog';
import { getAudio, getPlayhead, partyPlayer, remotePlayer, setPartyHooks, usePlayer, type RemoteControl } from '@/state/player';
import {
  controlsLocked,
  msUntilStart,
  party,
  partyPositionMs,
  setConnectPlaying,
  setMeasuredReader,
  useParty,
  type PartyState,
} from './listenTogether';
import { connectRole, outputOf, type ConnectRole } from './connectRole';
import type { PartyTrack } from './models';

export { connectRole, outputOf, type ConnectRole } from './connectRole';
import { autoplaySupplierId, detectSingleMove, partyQueueIndexOf, partyUpcomingAfter } from './queueReading';

/**
 * Makes the player obey the party, and the party obey this player: a port of
 * PartySync.kt.
 *
 * The two directions come through different doors (state/player.ts):
 * - outbound, [onIntent], is called by every user action in the player store,
 *   and by the queue moving on by itself;
 * - inbound, [reconcile], writes through `partyPlayer`, which reports nothing,
 *   so nothing done here can come back as an intent.
 *
 * Following in time: a resume waits for the server's scheduled start, a new
 * control is landed on exactly, and drift between controls is corrected only
 * when it's real (twice in a row past 1.2 s, then left alone for 6 s).
 *
 * Not ported: losing audio focus to another app, which a browser tab doesn't
 * have. And the audio element's output latency can't be read (there's no
 * AudioContext in the player), so it isn't subtracted; it's the same few tens
 * of milliseconds on every device of one kind.
 */

const TICK_MS = 700;
const DRIFT_LIMIT_MS = 1_200;
const DRIFT_STRIKES = 2;
const DRIFT_COOLDOWN_MS = 6_000;
const PAUSED_TOLERANCE_MS = 400;
const ALIGN_TOLERANCE_MS = 120;
const SEEK_REPORT_FLOOR_MS = 1_000;
const DEFERRED_PLAY_TIMEOUT_MS = 1_800;
const PUBLISH_DEBOUNCE_MS = 120;
const INTENT_QUIET_MS = 4_500;
const MAX_UPCOMING = 25;
/** Autoplay.MAX_QUEUED_AUTOPLAY. */
const MAX_QUEUED_AUTOPLAY = 10;
const QUEUE_ECHO_TIMEOUT_MS = 5_000;
/** Fork: how long a publish waits for a picked song to finish loading. */
const PUBLISH_LOAD_WAIT_MS = 8_000;
let publishWaitingSince: number | null = null;
/** Fork, Connect: a playlist's worth may follow the playing track (JAM_CONNECT_MAX_UPCOMING). */
const CONNECT_MAX_UPCOMING = 200;
/** Fork, Connect: how much of what already played the output shares. */
const CONNECT_HISTORY = 25;

// ---- Connect (fork): which of the account's devices this tab is ------------------------
//
// In a Connect party exactly one member, the output, plays; it is the party's clock and
// everything below that follows a party applies to it. Every other device is a remote for
// it while it's connected, and shows and drives it instead of playing (remotePlayer). With
// no output, or the output away, a device plays on its own, and pressing play there makes
// it the output (PartySync.kt's publish, fork).

/** A jam, where every member plays in time; Connect is handled apart. */
const inJam = (state: PartyState = useParty.getState()) => !!state.code && state.kind === 'jam';

// ---- Tracks across the boundary ----------------------------------------------------

export function toPartyTrack(track: Track, tier: QueueTier, playerDurationMs = 0): PartyTrack {
  return {
    videoId: track.videoId,
    title: track.title,
    artist: track.artists.map((a) => a.name).join(', '),
    thumbnailUrl: track.thumbnail,
    // The decoder's figure when there is one, else what the row claimed.
    durationMs: playerDurationMs > 0 ? Math.round(playerDurationMs) : track.durationSec ? track.durationSec * 1000 : null,
    fromAutoplay: tier === 'autoplay',
  };
}

export function fromPartyTrack(track: PartyTrack): Track {
  return {
    videoId: track.videoId,
    title: track.title,
    artists: track.artist ? [{ name: track.artist, id: null }] : [],
    album: null,
    durationSec: track.durationMs ? Math.round(track.durationMs / 1000) : null,
    explicit: false,
    thumbnail: track.thumbnailUrl,
    index: null,
    plays: null,
  };
}

const tierOf = (t: PartyTrack): 'user' | 'autoplay' => (t.fromAutoplay ? 'autoplay' : 'user');

// ---- Reading the player -------------------------------------------------------------

function current() {
  const { queue, index } = usePlayer.getState();
  return index >= 0 ? (queue[index] ?? null) : null;
}

const positionMs = () => Math.max(0, (getAudio()?.currentTime ?? 0) * 1000);

/** Whether the player means to be playing: going, stalled, or loading a track it will start. */
function playWhenReady(): boolean {
  const { status } = usePlayer.getState();
  return status === 'playing' || status === 'buffering' || (status === 'loading' && loadingVideoId === null);
}

// ---- State --------------------------------------------------------------------------

let reconcileQuietUntil = 0;
let awaitPlaybackSeq = Number.MAX_SAFE_INTEGER;
let awaitQueueSeq = Number.MAX_SAFE_INTEGER;
let alignedSeq = -1;
let lastAnchorMs = 0;
let lastPositionMs = 0;
let lastTrackId: string | null = null;
let lastIsPlaying = false;
let loadingVideoId: string | null = null;
let locallyPaused = false;
let driftStrikes = 0;
let driftCooldownUntil = 0;
let publishTimer: ReturnType<typeof setTimeout> | undefined;
let startTimer: ReturnType<typeof setTimeout> | undefined;
let lastPartyCode: string | null = null;
/** The personal queue, put back after the party (PartyPersonalQueueStash). */
let stash: { queue: ReturnType<typeof usePlayer.getState>['queue']; index: number; positionSec: number; wasPlaying: boolean } | null = null;

const setAwaiting = (value: boolean): void => {
  if (useParty.getState().awaitingStart !== value) useParty.setState({ awaitingStart: value });
};

// ---- Outbound ------------------------------------------------------------------------

function onIntent(): void {
  const state = useParty.getState();
  if (!state.code || controlsLocked(state)) return;
  if (connectRole(state) === 'remote') return;
  reconcileQuietUntil = performance.now() + INTENT_QUIET_MS;
  // Nothing published yet: no seq to wait for, and someone else's control mustn't clear the window.
  awaitPlaybackSeq = Number.MAX_SAFE_INTEGER;
  awaitQueueSeq = Number.MAX_SAFE_INTEGER;
  clearTimeout(publishTimer);
  publishTimer = setTimeout(publish, PUBLISH_DEBOUNCE_MS);
}

function publish(): void {
  const state = useParty.getState();
  if (!state.code) return;
  // Fork: a song picked a moment ago is still loading, and the element still holds the previous
  // one, so its position was published as the new song's: the party then started the song that
  // far in (a minute, half of it) and moved the player there. Wait for the load, and keep the
  // party's own corrections off this player meanwhile.
  if (usePlayer.getState().status === 'loading') {
    // At most a few seconds: a load whose 'playing' never reports back must not leave the party
    // without a word from this device for the rest of the song (the element has the new song by
    // then, so its position is right anyway).
    publishWaitingSince ??= performance.now();
    if (performance.now() - publishWaitingSince < PUBLISH_LOAD_WAIT_MS) {
      reconcileQuietUntil = performance.now() + INTENT_QUIET_MS;
      clearTimeout(publishTimer);
      publishTimer = setTimeout(publish, 250);
      return;
    }
    trackLog.w('party: publishing while the song still says loading');
  }
  publishWaitingSince = null;
  if (state.kind === 'connect') return publishConnect(state);
  const { queue, index } = usePlayer.getState();
  const entry = current();
  if (!entry) return;
  const audio = getAudio();
  const track = toPartyTrack(entry.track, entry.tier, audio && Number.isFinite(audio.duration) ? audio.duration * 1000 : 0);
  const position = positionMs();
  // During a deferred resume the player isn't going yet; what the user asked for is.
  const wantsPlaying = state.awaitingStart || playWhenReady();
  const basePlayback = state.playback.seq;
  const baseQueue = state.queue.seq;
  let playbackControls = 0;
  let queueControls = 0;

  // The party's queue is the server's: past and current, then what's queued by hand or by AutoPlay.
  const localItems = [...queue.slice(0, index + 1), ...queue.slice(index + 1).filter((e) => e.tier !== 'context')];
  const localIds = localItems.map((e) => e.track.videoId);
  const trackIndex = index;
  const clampedIds = localIds.slice(0, Math.min(localIds.length, trackIndex + 1 + MAX_UPCOMING));

  const partyIndex = partyQueueIndexOf(state.queue, state.playback, state.playback.track?.videoId);
  const upcomingParty = partyIndex >= 0 ? state.queue.items.slice(partyIndex + 1) : [];
  const partyIds = state.queue.items.map((t) => t.videoId);

  if (state.playback.track?.videoId !== track.videoId && clampedIds.length === 1 && upcomingParty.length) {
    // A new song on its own: the party's hand-queued songs carry on after it.
    const keep = upcomingParty.filter((t) => !t.fromAutoplay).slice(0, MAX_UPCOMING);
    if (queue.length === 1 && keep.length) partyPlayer.setUpcoming(keep.map(fromPartyTrack), keep.map(tierOf));
    party.setQueue([track, ...keep], 0);
    queueControls++;
  } else if (clampedIds.length !== partyIds.length || clampedIds.some((id, i) => id !== partyIds[i])) {
    const move =
      partyIds.length === clampedIds.length && trackIndex < partyIds.length && partyIds[trackIndex] === clampedIds[trackIndex]
        ? detectSingleMove(partyIds, clampedIds)
        : null;
    if (move && move.fromIndex > trackIndex && move.toIndex > trackIndex) {
      party.queueMove(move.fromIndex, move.toIndex, move.videoId);
    } else {
      party.setQueue(
        localItems.slice(0, clampedIds.length).map((e) => toPartyTrack(e.track, e.tier)),
        trackIndex,
      );
    }
    queueControls++;
  }

  if (state.playback.track?.videoId !== track.videoId) {
    // After the queue, never before: a track pointing into a running order nobody has yet plays the wrong song.
    party.setTrack(track, position, wantsPlaying);
    playbackControls++;
  } else if (state.playback.isPlaying !== wantsPlaying) {
    if (wantsPlaying) party.play(position);
    else party.pause(position);
    playbackControls++;
  } else {
    // Same track, same state: a seek, unless it barely moved (two devices finishing a track together).
    const partyPosition = partyPositionMs();
    if (partyPosition === null || Math.abs(position - partyPosition) > SEEK_REPORT_FLOOR_MS) {
      party.seek(position);
      playbackControls++;
    }
  }

  awaitPlaybackSeq = basePlayback + playbackControls;
  awaitQueueSeq = baseQueue + queueControls;
  if (!playbackControls && !queueControls) reconcileQuietUntil = 0;
}

/**
 * Fork, Connect: the output (or a device about to become it) publishing what its listener did.
 * The whole running order goes, the album or playlist included, so the account's other devices
 * show the same Up Next the phone does.
 */
function publishConnect(state: PartyState): void {
  const role = connectRole(state);
  if (role === 'remote' || role === 'none') return;
  const { queue, index } = usePlayer.getState();
  const entry = current();
  if (!entry) return;
  // Pressing play here while another device isn't playing makes this one the output. Sent ahead
  // of the state it's about, on the one ordered socket, so the party takes this song as the output's.
  const me = state.you?.memberId;
  let playbackControls = 0;
  let queueControls = 0;
  if (role === 'solo' && me) {
    party.transfer(me);
    playbackControls++;
  }
  const audio = getAudio();
  const track = toPartyTrack(entry.track, entry.tier, audio && Number.isFinite(audio.duration) ? audio.duration * 1000 : 0);
  const position = positionMs();
  const wantsPlaying = playWhenReady();
  const from = Math.max(0, index - CONNECT_HISTORY);
  const window = queue.slice(from, index + 1 + CONNECT_MAX_UPCOMING);
  const windowIndex = index - from;
  const ids = window.map((e) => e.track.videoId);
  const partyIds = state.queue.items.map((t) => t.videoId);
  const basePlayback = state.playback.seq;
  const baseQueue = state.queue.seq;
  if (ids.length !== partyIds.length || ids.some((id, i) => id !== partyIds[i])) {
    party.setQueue(window.map((e) => toPartyTrack(e.track, e.tier)), windowIndex);
    queueControls++;
  }
  if (role === 'solo' || state.playback.track?.videoId !== track.videoId) {
    party.setTrack(track, position, wantsPlaying, windowIndex);
    playbackControls++;
  } else if (state.playback.isPlaying !== wantsPlaying) {
    if (wantsPlaying) party.play(position);
    else party.pause(position);
    playbackControls++;
  } else {
    const partyPosition = partyPositionMs();
    if (partyPosition === null || Math.abs(position - partyPosition) > SEEK_REPORT_FLOOR_MS) {
      party.seek(position);
      playbackControls++;
    }
  }
  awaitPlaybackSeq = basePlayback + playbackControls;
  awaitQueueSeq = baseQueue + queueControls;
  if (!playbackControls && !queueControls) reconcileQuietUntil = 0;
}

/** A play to hold back for the party's scheduled instant; partySync starts it. */
function shouldDeferPlay(): boolean {
  const state = useParty.getState();
  // Fork: Connect's output plays at once; only a jam schedules a start for everyone.
  if (!inJam(state)) return false;
  if (!state.code || state.connection !== 'live' || !state.clockSynced) return false;
  if (playWhenReady()) return false;
  setAwaiting(true);
  clearTimeout(startTimer);
  // The press must do something even if the party never answers.
  startTimer = setTimeout(() => {
    if (!useParty.getState().awaitingStart) return;
    setAwaiting(false);
    if (getAudio()?.paused) partyPlayer.play();
  }, DEFERRED_PLAY_TIMEOUT_MS);
  return true;
}

/** A listener in a locked party: their own pause, and a resume that rejoins where the party is. */
function onLockedTransport(playing: boolean): boolean {
  const state = useParty.getState();
  if (!controlsLocked(state)) return false;
  if (playing) {
    locallyPaused = false;
    if (!state.playback.isPlaying) return true;
    const want = partyPositionMs();
    if (want !== null && state.clockSynced) partyPlayer.seekTo(want / 1000);
    partyPlayer.play();
  } else {
    locallyPaused = true;
    partyPlayer.pause();
  }
  return true;
}

// ---- Inbound -------------------------------------------------------------------------

function reconcile(): void {
  const state = useParty.getState();
  const role = connectRole(state);
  if (role === 'remote') return mirrorOutput(state);
  // Fork: a remote that has just become the output (Play here) loads the party's song itself:
  // the store only showed it, and the element still holds whatever played here before.
  const wasRemote = remotePlayer.active;
  if (wasRemote) remotePlayer.release();
  if (role !== 'none') reconcileVolume(state, role);
  if (role === 'output' && wasRemote && state.playback.track) {
    reconcileQuietUntil = 0;
    return load(state, true);
  }
  if (role === 'solo') {
    loadingVideoId = null;
    setAwaiting(false);
    return;
  }
  if (role === 'output') return followOtherDevices(state);
  if (!state.code) {
    loadingVideoId = null;
    locallyPaused = false;
    lastAnchorMs = 0;
    lastPositionMs = 0;
    lastTrackId = null;
    lastIsPlaying = false;
    setAwaiting(false);
    return;
  }
  if (locallyPaused && !controlsLocked(state)) locallyPaused = false;
  if (performance.now() < reconcileQuietUntil) return;
  const target = state.playback;
  const track = target.track;
  if (!track) return seedEmptyParty(state);

  const entry = current();
  if (entry?.track.videoId !== track.videoId) return load(state);
  if (loadingVideoId === track.videoId && usePlayer.getState().status === 'loading') return;
  loadingVideoId = null;
  reconcileQueue(state);

  if (locallyPaused) {
    setAwaiting(false);
    return;
  }

  if (!target.isPlaying) {
    setAwaiting(false);
    if (playWhenReady()) partyPlayer.pause();
    if (Math.abs(positionMs() - target.positionMs) > PAUSED_TOLERANCE_MS) partyPlayer.seekTo(target.positionMs / 1000);
    return;
  }
  // A playing party can't be joined in time without a measured clock.
  if (!state.clockSynced) return;

  const want = partyPositionMs();
  if (!playWhenReady()) {
    const wait = msUntilStart();
    if (wait > 0) {
      // Woken for the scheduled instant, not the next tick: late is what this avoids.
      clearTimeout(startTimer);
      startTimer = setTimeout(reconcile, wait);
      return;
    }
    if (want !== null) partyPlayer.seekTo(want / 1000);
    setAwaiting(false);
    clearTimeout(startTimer);
    partyPlayer.play();
    alignedSeq = target.seq;
    return;
  }
  setAwaiting(false);
  if (want === null) return;
  // Only a settled player can be measured.
  if (usePlayer.getState().status !== 'playing') {
    driftStrikes = 0;
    return;
  }

  const drift = positionMs() - want;
  if (target.seq !== alignedSeq) {
    // A control is landed on exactly, even by the device that sent it; a queue-only change isn't one.
    const anchorChanged =
      lastAnchorMs !== target.anchorMs || lastPositionMs !== target.positionMs || lastTrackId !== track.videoId || lastIsPlaying !== target.isPlaying;
    alignedSeq = target.seq;
    lastAnchorMs = target.anchorMs;
    lastPositionMs = target.positionMs;
    lastTrackId = track.videoId;
    lastIsPlaying = target.isPlaying;
    if (anchorChanged && Math.abs(drift) > ALIGN_TOLERANCE_MS) {
      partyPlayer.seekTo(want / 1000);
      return;
    }
  }
  if (Math.abs(drift) <= DRIFT_LIMIT_MS) {
    driftStrikes = 0;
    return;
  }
  // A seek costs a re-buffer; asked for twice in a row, then left alone to settle.
  const now = performance.now();
  if (now < driftCooldownUntil) return;
  if (++driftStrikes < DRIFT_STRIKES) return;
  driftStrikes = 0;
  driftCooldownUntil = now + DRIFT_COOLDOWN_MS;
  partyPlayer.seekTo(want / 1000);
}

/**
 * Fork, Connect: the output is the party's clock. Everyone else follows its measured reports, so
 * it never steers itself toward the party's extrapolation of where it is: that's a jam member's
 * job, and on the output every buffering stall became a seek (a looping segment, a gap of
 * silence). It acts only on a change another device made (a remote's play, pause, seek or song),
 * each once, and a seek only when that change put it more than 2 s away.
 */
let followedSeq = -1;
let followedQueueSeq = -1;
const FOLLOW_SEEK_MS = 2_000;
function followOtherDevices(state: PartyState): void {
  setAwaiting(false);
  if (performance.now() < reconcileQuietUntil) return;
  const target = state.playback;
  if (!target.track) return seedEmptyParty(state);
  // Mid-load, the player can't take a play, pause or seek yet: leave whatever arrived meanwhile
  // (the app sends play right after the song) for when it's done; load() looks again then.
  if (loadingVideoId !== null || usePlayer.getState().status === 'loading') return;
  // The party's queue onto this player only when it changed and another device changed it (the
  // app's rule). Applied on every tick, it cut this player's queue to the jam's 25 while publish
  // sent the Connect window of 200, and the two undid each other: queue storms, the same
  // songs again.
  if (state.queue.seq > followedQueueSeq) {
    followedQueueSeq = state.queue.seq;
    if (target.updatedBy && target.updatedBy !== state.you?.memberId) reconcileQueue(state, CONNECT_MAX_UPCOMING);
  }
  if (target.seq <= followedSeq) return;
  followedSeq = target.seq;
  // This player's own control, back from the server: nothing to do.
  if (target.updatedBy && target.updatedBy === state.you?.memberId) return;
  const entry = current();
  if (entry?.track.videoId !== target.track.videoId) return load(state, true);
  const want = partyPositionMs() ?? target.positionMs;
  if (!target.isPlaying) {
    if (playWhenReady()) partyPlayer.pause();
  } else if (!playWhenReady()) {
    partyPlayer.play();
  }
  if (Math.abs(positionMs() - want) > FOLLOW_SEEK_MS) partyPlayer.seekTo(want / 1000);
}

/** Fork, Connect: this tab as a remote, showing what the output plays. */
let mirroredFromQueue = false;
function mirrorOutput(state: PartyState): void {
  const track = state.playback.track;
  loadingVideoId = null;
  setAwaiting(false);
  if (!track) {
    mirroredFromQueue = false;
    remotePlayer.mirror({ tracks: [], tiers: [], index: -1, isPlaying: false, positionMs: 0, durationMs: 0, volume: state.playback.volume });
    return;
  }
  const at = partyQueueIndexOf(state.queue, state.playback, track.videoId);
  mirroredFromQueue = at >= 0;
  const items = at >= 0 ? state.queue.items : [track];
  remotePlayer.mirror({
    tracks: items.map(fromPartyTrack),
    tiers: items.map(tierOf),
    index: Math.max(0, at),
    isPlaying: state.playback.isPlaying,
    positionMs: partyPositionMs() ?? state.playback.positionMs,
    durationMs: track.durationMs ?? 0,
    volume: state.playback.volume,
  });
}

/** Fork, Connect: what a remote's buttons do; the store hands every action here while there's an output elsewhere. */
const remoteControl: RemoteControl = {
  playList(tracks, startIndex) {
    const items = tracks.slice(0, startIndex + 1 + CONNECT_MAX_UPCOMING).map((t) => toPartyTrack(t, 'user'));
    const at = Math.min(startIndex, items.length - 1);
    if (at < 0) return;
    party.setQueue(items, at);
    party.setTrack(items[at]!, 0, true, at);
  },
  add(tracks, next) {
    party.queueAdd(tracks.map((t) => toPartyTrack(t, 'user')), next);
  },
  jump(index) {
    const track = mirroredFromQueue ? useParty.getState().queue.items[index] : undefined;
    if (track) party.setTrack(track, 0, true, index);
  },
  remove(index) {
    const track = mirroredFromQueue ? useParty.getState().queue.items[index] : undefined;
    if (track) party.queueRemove(track.videoId);
  },
  move(from, to) {
    const track = mirroredFromQueue ? useParty.getState().queue.items[from] : undefined;
    if (track) party.queueMove(from, to, track.videoId);
  },
  clearUpNext: () => party.queueClear(),
  next: () => party.next(),
  previous: () => party.previous(),
  setPlaying: (playing) => (playing ? party.play() : party.pause()),
  seek: (seconds) => party.seek(seconds * 1000),
  volume: (volume) => party.setVolume(volume),
};

/**
 * Fork, Connect: the output's volume. It says what it is (on becoming the output and when its
 * listener turns it), and takes a level a remote asks for: each request counted once.
 */
let volumeAnnouncedFor: string | null = null;
let lastVolumeReq = -1;
let lastAnnouncedVolume = -1;
function reconcileVolume(state: PartyState, role: ConnectRole): void {
  if (role !== 'output') {
    volumeAnnouncedFor = null;
    return;
  }
  const { volume } = usePlayer.getState();
  if (volumeAnnouncedFor !== state.code) {
    volumeAnnouncedFor = state.code;
    lastVolumeReq = state.playback.volumeReqSeq;
    lastAnnouncedVolume = volume;
    party.volumeState(volume);
    return;
  }
  if (state.playback.volumeReqSeq > lastVolumeReq) {
    lastVolumeReq = state.playback.volumeReqSeq;
    const target = state.playback.volumeTarget;
    if (target !== null) {
      usePlayer.getState().setVolume(target);
      lastAnnouncedVolume = target;
      party.volumeState(target);
      return;
    }
  }
  if (Math.abs(volume - lastAnnouncedVolume) > 0.005) {
    lastAnnouncedVolume = volume;
    party.volumeState(volume);
  }
}

/** The party's running order onto the player, at its position; started only if the party is going now. */
function load(state: PartyState, asOutput = false): void {
  const track = state.playback.track;
  if (!track || loadingVideoId === track.videoId) return;
  loadingVideoId = track.videoId;
  followedSeq = Math.max(followedSeq, state.playback.seq);
  // The load puts the party's whole running order on the player already.
  followedQueueSeq = Math.max(followedQueueSeq, state.queue.seq);
  // A not-found index plays the track alone, never queue[0].
  const at = partyQueueIndexOf(state.queue, state.playback, track.videoId);
  const items = at >= 0 ? state.queue.items : [track];
  const startMs = partyPositionMs() ?? state.playback.positionMs;
  // Fork: Connect's output starts at once. The server puts a playing song's start a moment
  // ahead, which a jam waits out so everyone starts together; the output waiting for it loaded
  // the song paused, and a tap on the phone seemed to do nothing until the second.
  const play = asOutput ? state.playback.isPlaying : state.playback.isPlaying && state.clockSynced && msUntilStart() <= 0;
  void partyPlayer
    .load(items.map(fromPartyTrack), items.map(tierOf), Math.max(0, at), startMs / 1000, play)
    .finally(() => {
      loadingVideoId = null;
      reconcile();
    });
}

/** Keeps the player's upcoming tracks equal to the party's, without touching the playing one. */
function reconcileQueue(state: PartyState, maxUpcoming = MAX_UPCOMING): void {
  if (!state.queue.items.length) return;
  const entry = current();
  if (!entry) return;
  const at = partyQueueIndexOf(state.queue, state.playback, entry.track.videoId);
  if (at < 0) return;
  const desired = state.queue.items.slice(at + 1, at + 1 + maxUpcoming);
  const { queue, index } = usePlayer.getState();
  const local = queue.slice(index + 1).map((e) => e.track.videoId);
  if (local.length === desired.length && local.every((id, i) => id === desired[i]!.videoId)) return;
  partyPlayer.setUpcoming(desired.map(fromPartyTrack), desired.map(tierOf));
}

/** A new party plays nothing until someone presses something; the host hands it what's already playing. */
function seedEmptyParty(state: PartyState): void {
  if (state.connection !== 'live' || state.you?.isHost !== true || !current()) return;
  if (state.kind === 'connect') return publishConnect(state);
  const { queue, index } = usePlayer.getState();
  const keep = queue.slice(index + 1).filter((e) => e.tier !== 'context');
  if (keep.length !== queue.length - index - 1) partyPlayer.setUpcoming(keep.map((e) => e.track), keep.map((e) => (e.tier === 'autoplay' ? 'autoplay' : 'user')));
  publish();
}

function onEnteredParty(): void {
  const { queue, index, status } = usePlayer.getState();
  stash = queue.length ? { queue, index, positionSec: getAudio()?.currentTime ?? 0, wasPlaying: status === 'playing' } : null;
}

function onLeftParty(): void {
  loadingVideoId = null;
  const saved = stash;
  stash = null;
  if (!saved || saved.index < 0) return;
  const entries = saved.queue;
  void partyPlayer.load(
    entries.map((e) => e.track),
    entries.map((e) => (e.tier === 'autoplay' ? 'autoplay' : 'user')),
    saved.index,
    saved.positionSec,
    saved.wasPlaying,
  );
}

// ---- AutoPlay: one supplier for the whole party ------------------------------------------

let autoplaySeed: string | null = null;
let topping: Promise<void> | null = null;

/**
 * Tops the party up to 10 AutoPlay tracks after the playing one, from the
 * elected supplier only, counted against the server's queue (never the local
 * copy, which lags while reconcile is held off) and one batch at a time,
 * waiting for its echo: otherwise the same station went out twice.
 */
function maybeTopUpAutoplay(): void {
  const state = useParty.getState();
  const playing = state.playback.track;
  if (!inJam(state) || !playing || !state.playback.autoplayEnabled) return;
  if (autoplaySupplierId(state.members, state.hostOnlyControl) !== state.you?.memberId) return;
  if (partyQueueIndexOf(state.queue, state.playback, playing.videoId) < 0) return;
  const waiting = partyUpcomingAfter(state.queue, state.playback, playing.videoId);
  const needed = MAX_QUEUED_AUTOPLAY - waiting.filter((t) => t.fromAutoplay).length;
  if (needed <= 0 || autoplaySeed === playing.videoId || topping) return;
  autoplaySeed = playing.videoId;
  topping = (async () => {
    try {
      const [{ fetchRadio }, { extendStation }] = await Promise.all([import('@/data/music/api'), import('@/playback/queue/builder')]);
      const { candidates, seedIsVideo } = await fetchRadio(playing.videoId);
      const latest = useParty.getState();
      if (latest.playback.track?.videoId !== playing.videoId) return;
      const existing = [...usePlayer.getState().queue.map((e) => e.track), ...latest.queue.items.map(fromPartyTrack)];
      const extra = extendStation(existing, candidates, needed, seedIsVideo);
      const waitingIds = new Set(partyUpcomingAfter(latest.queue, latest.playback, playing.videoId).map((t) => t.videoId));
      const fresh = extra.filter((t) => !waitingIds.has(t.videoId));
      if (!fresh.length) return;
      party.queueAdd(fresh.map((t) => ({ ...toPartyTrack(t, 'autoplay') })));
      const added = fresh[0]!.videoId;
      const landed = await new Promise<boolean>((resolve) => {
        const timer = setTimeout(() => (off(), resolve(false)), QUEUE_ECHO_TIMEOUT_MS);
        const off = useParty.subscribe((s) => {
          if (s.queue.items.some((t) => t.videoId === added)) {
            clearTimeout(timer);
            off();
            resolve(true);
          }
        });
      });
      // Refused (a full queue, a locked party): free the seed to try again.
      if (!landed) autoplaySeed = null;
    } catch {
      autoplaySeed = null;
    } finally {
      topping = null;
    }
  })();
}

// ---- Wiring ---------------------------------------------------------------------------

/** Starts following the party; returns the stop. Mounted once, in the shell. */
export function startPartySync(): () => void {
  setPartyHooks({
    onIntent,
    shouldDeferPlay,
    onLockedTransport,
    // Fork: a jam's AutoPlay and mixing are the party's; Connect's output is one player like any other.
    inParty: () => inJam(),
    remote: () => (connectRole(useParty.getState()) === 'remote' ? remoteControl : null),
  });
  // Fork, Connect: music coming out of this tab at sign-in keeps it the output after a server
  // restart; and the output reports its real playhead, which every other device follows.
  setConnectPlaying(() => playWhenReady() && !remotePlayer.active);
  setMeasuredReader(() => {
    if (connectRole(useParty.getState()) !== 'output') return null;
    const entry = current();
    const audio = getAudio();
    const head = getPlayhead();
    if (!entry || !audio || !head || head.paused) return null;
    return { videoId: entry.track.videoId, positionMs: head.sec * 1000, durationMs: Number.isFinite(audio.duration) ? audio.duration * 1000 : 0 };
  });
  let key = '';
  const off = useParty.subscribe((state) => {
    const output = outputOf(state);
    const next = `${state.playback.seq}|${state.queue.seq}|${state.code}|${state.clockSynced}|${output?.memberId}|${output?.connected}|${state.you?.memberId}`;
    if (next === key) return;
    key = next;
    // Fork: the personal queue is put aside for a jam only; Connect is where the account always is.
    const jamCode = inJam(state) ? state.code : null;
    if (jamCode !== lastPartyCode) {
      const was = lastPartyCode !== null;
      lastPartyCode = jamCode;
      if (!was && jamCode) onEnteredParty();
      else if (was && !jamCode) onLeftParty();
    }
    // Every control this device sent has come back around (or someone moved the party past ours).
    if (state.playback.seq >= awaitPlaybackSeq && state.queue.seq >= awaitQueueSeq) reconcileQuietUntil = 0;
    reconcile();
    maybeTopUpAutoplay();
  });
  const tick = setInterval(() => {
    reconcile();
    maybeTopUpAutoplay();
  }, TICK_MS);
  // Fork: the output's own volume changes are announced as they happen, not on the next tick.
  const offVolume = usePlayer.subscribe((s, before) => {
    if (s.volume !== before.volume && connectRole(useParty.getState()) === 'output') reconcileVolume(useParty.getState(), 'output');
  });
  return () => {
    off();
    offVolume();
    clearInterval(tick);
    clearTimeout(publishTimer);
    clearTimeout(startTimer);
    setPartyHooks(null);
    setMeasuredReader(null);
  };
}
