import { getPlayhead, remotePlayer, usePlayer } from '@/state/player';
import { gatewayCall, marbleAccount, useMarbleAccount } from './account';

/**
 * Fork: listening stats on marble, the web half of the app's GatewayListening.
 *
 * A stretch of one track is counted only while audio actually comes out of this
 * tab (never while it shows another device, whose own app counts it), in steps
 * of at most 8 s so a stalled tab never arrives as listening. A stretch ends
 * when the track changes; a pause and resume is the same listen. Anything that
 * can't be sent waits in this browser's storage: recent listens go out one by
 * one (reportListeningEvent), a backlog older than half an hour in bulk
 * (importListeningEvents), as the phone does.
 */

interface Session {
  videoId: string;
  title: string;
  artist: string;
  album: string;
  cover: string;
  trackDurationMs: number;
  listenedMs: number;
  lastSampleAt: number;
  paused: boolean;
}

interface ListeningEvent {
  eventId: string;
  videoId: string;
  title: string;
  artist: string;
  album: string;
  cover: string;
  durationMs: number;
  startTime: number;
  endTime: number;
  trackDurationMs: number;
}

const SESSION_KEY = 'bitchord:marble-listening-session';
const OUTBOX_KEY = 'bitchord:marble-listening-outbox';
const SAMPLE_MS = 1_000;
const MIN_LISTEN_MS = 5_000;
const MAX_STEP_MS = 8_000;
const LIVE_WINDOW_MS = 30 * 60 * 1000;
const IMPORT_BATCH = 500;
const MAX_QUEUED = 2_000;

let session: Session | null = null;
let draining = false;

function readJson<T>(key: string, fallback: T): T {
  try {
    return (JSON.parse(localStorage.getItem(key) ?? 'null') as T | null) ?? fallback;
  } catch {
    return fallback;
  }
}

const readOutbox = () => readJson<ListeningEvent[]>(OUTBOX_KEY, []);
const writeOutbox = (events: ListeningEvent[]) => localStorage.setItem(OUTBOX_KEY, JSON.stringify(events.slice(-MAX_QUEUED)));
const saveSession = () => (session ? localStorage.setItem(SESSION_KEY, JSON.stringify(session)) : localStorage.removeItem(SESSION_KEY));

/** Audio is coming out of this tab right now. */
function audible(): boolean {
  if (remotePlayer.active) return false;
  const { status } = usePlayer.getState();
  return status === 'playing' && getPlayhead()?.paused === false;
}

function sample(): void {
  if (!marbleAccount()) return;
  if (!audible()) {
    if (session && !session.paused) {
      session = { ...session, paused: true };
      saveSession();
    }
    return;
  }
  const { nowPlaying, queue, index } = usePlayer.getState();
  if (!nowPlaying) return;
  const now = Date.now();
  if (!session || session.videoId !== nowPlaying.videoId) {
    if (session) finish(session);
    const entry = queue[index]?.track;
    // A new stretch anchors the clock and counts nothing yet: the time before the first tick is unknown.
    session = {
      videoId: nowPlaying.videoId,
      title: nowPlaying.title,
      artist: nowPlaying.artist,
      album: entry?.videoId === nowPlaying.videoId ? (entry.album?.name ?? '') : '',
      cover: nowPlaying.artworkUrl ?? '',
      trackDurationMs: Math.max(0, Math.round(nowPlaying.durationSec * 1000)),
      listenedMs: 0,
      lastSampleAt: now,
      paused: false,
    };
    return;
  }
  session = session.paused
    ? // The time spent paused isn't listening, so the clock re-anchors.
      { ...session, paused: false, lastSampleAt: now }
    : {
        ...session,
        listenedMs: session.listenedMs + Math.min(MAX_STEP_MS, Math.max(0, now - session.lastSampleAt)),
        lastSampleAt: now,
        trackDurationMs: session.trackDurationMs || Math.max(0, Math.round(nowPlaying.durationSec * 1000)),
      };
}

function finish(finished: Session): void {
  if (session === finished) session = null;
  saveSession();
  if (finished.listenedMs < MIN_LISTEN_MS) return;
  const end = finished.lastSampleAt;
  writeOutbox([
    ...readOutbox(),
    {
      eventId: crypto.randomUUID(),
      videoId: finished.videoId,
      title: finished.title,
      artist: finished.artist,
      album: finished.album,
      cover: finished.cover,
      durationMs: finished.listenedMs,
      startTime: end - finished.listenedMs,
      endTime: end,
      trackDurationMs: finished.trackDurationMs,
    },
  ]);
  void drain();
}

const songId = (videoId: string) => `yt-${videoId}`;

/** Sends whatever is waiting; what can't go now stays for the next try. */
async function drain(): Promise<void> {
  if (draining || !marbleAccount()) return;
  draining = true;
  try {
    const queued = readOutbox();
    const staleBefore = Date.now() - LIVE_WINDOW_MS;
    const stale = queued.filter((e) => e.endTime < staleBefore);
    const recent = queued.filter((e) => e.endTime >= staleBefore);
    const sent = new Set<string>();
    for (let i = 0; i < stale.length; i += IMPORT_BATCH) {
      const batch = stale.slice(i, i + IMPORT_BATCH);
      try {
        await gatewayCall('importListeningEvents', {}, {
          body: batch.map((e) => ({
            eventId: e.eventId,
            songId: songId(e.videoId),
            title: e.title,
            artist: e.artist,
            album: e.album,
            cover: e.cover,
            durationMs: e.durationMs,
            startTime: e.startTime,
            endTime: e.endTime,
          })),
        });
        batch.forEach((e) => sent.add(e.eventId));
      } catch {
        break;
      }
    }
    for (const e of recent) {
      try {
        await gatewayCall('reportListeningEvent', {
          eventId: e.eventId,
          songId: songId(e.videoId),
          title: e.title,
          artist: e.artist,
          album: e.album,
          cover: e.cover,
          durationMs: String(e.durationMs),
          startTime: String(e.startTime),
          endTime: String(e.endTime),
          trackDurationMs: String(e.trackDurationMs),
        });
        sent.add(e.eventId);
      } catch {
        break;
      }
    }
    if (sent.size) writeOutbox(readOutbox().filter((e) => !sent.has(e.eventId)));
  } finally {
    draining = false;
  }
}

/** Mounted once, in the shell. */
export function startListeningStats(): () => void {
  // A stretch left by a tab that closed is over: finished now, once.
  const left = readJson<Session | null>(SESSION_KEY, null);
  if (left) finish(left);
  void drain();
  const tick = setInterval(sample, SAMPLE_MS);
  const onHide = () => {
    if (document.visibilityState === 'hidden') saveSession();
  };
  document.addEventListener('visibilitychange', onHide);
  const onOnline = () => void drain();
  window.addEventListener('online', onOnline);
  const offAccount = useMarbleAccount.subscribe((s, before) => {
    if (s.account && !before.account) void drain();
  });
  return () => {
    clearInterval(tick);
    document.removeEventListener('visibilitychange', onHide);
    window.removeEventListener('online', onOnline);
    offAccount();
  };
}
