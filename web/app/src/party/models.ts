/**
 * The party server's wire format, one-for-one (PartyModels.kt). The server
 * speaks camelCase and `backend/README.md` is the contract: rename a field on
 * both sides, never paper over it here.
 */

/** A song as the party knows it: enough to name and show it. How each device plays it is its own business. */
export interface PartyTrack {
  videoId: string;
  title: string;
  artist: string;
  thumbnailUrl: string | null;
  durationMs: number | null;
  /** Preserves the queue's shared manual/AutoPlay boundary. */
  fromAutoplay: boolean;
}

export interface PartyMember {
  memberId: string;
  userId: string;
  displayName: string;
  avatarUrl: string | null;
  isHost: boolean;
  /** Holding a socket right now, not whether they are still in. */
  connected: boolean;
  joinedAtMs: number;
  lastSeenMs: number;
  /** Fork: "speaker" plays, "remote" only drives. In Connect, the one speaker is the output. */
  role: 'speaker' | 'remote';
  /** Fork, Connect: the device behind the member, and the app on it ("android", "tv", "web"). */
  deviceKey: string | null;
  app: string | null;
  deviceName: string | null;
}

/** Fork, Connect: one of the account's devices the server remembers, connected or asleep. */
export interface ConnectDevice {
  deviceId: string;
  deviceKey: string;
  app: string;
  deviceName: string;
  wakeable: boolean;
  connected: boolean;
  lastSeenMs: number;
}

/**
 * Where the party is, as of a server timestamp: [positionMs] at [anchorMs].
 * It becomes a current position only once the reader adds the time since.
 */
export interface PartyPlayback {
  /** Only climbs; a state not newer than the one applied is dropped. */
  seq: number;
  track: PartyTrack | null;
  queueSeq: number;
  queueLength: number;
  queueIndex: number;
  isPlaying: boolean;
  positionMs: number;
  anchorMs: number;
  /** The server's own reading at the instant it sent the frame. */
  effectivePositionMs: number;
  updatedBy: string | null;
  startedBy: string | null;
  startedByName: string | null;
  autoplayEnabled: boolean;
  updatedAtMs: number;
  /** Fork, Connect: the output's volume (0-1) and whether other devices may change it. */
  volume: number | null;
  volumeControl: boolean;
  /** Fork, Connect: the volume a remote asked for, and a count of those requests. */
  volumeTarget: number | null;
  volumeReqSeq: number;
}

export interface PartyQueue {
  seq: number;
  index: number;
  items: PartyTrack[];
}

export interface PartySnapshot {
  /** Fork: "connect" for an account's own devices, "jam" for a party joined by code. */
  kind: 'jam' | 'connect';
  /** Fork, Connect: the account's remembered devices. */
  devices: ConnectDevice[];
  code: string;
  createdAtMs: number;
  maxMembers: number;
  hostOnlyControl: boolean;
  members: PartyMember[];
  playback: PartyPlayback;
  queue: PartyQueue;
  serverMs: number;
}

export interface PartyPreview {
  code: string;
  hostName: string;
  memberCount: number;
  maxMembers: number;
  isFull: boolean;
  members: { displayName: string; avatarUrl: string | null; isHost: boolean }[];
}

export interface PartyMembership {
  code: string;
  token: string;
  you: PartyMember;
  party: PartySnapshot;
  serverMs: number;
}

/** A compact local-only record of a live party action. */
export interface PartyActivity {
  action: string;
  by: string;
  atMs: number;
  detail: string;
}

export interface JoinRequest {
  userId: string;
  deviceId: string;
  displayName: string;
  avatarUrl?: string | null;
  maxMembers?: number;
  autoplayEnabled?: boolean;
}

export const EMPTY_PLAYBACK: PartyPlayback = {
  seq: 0,
  track: null,
  queueSeq: 0,
  queueLength: 0,
  queueIndex: -1,
  isPlaying: false,
  positionMs: 0,
  anchorMs: 0,
  effectivePositionMs: 0,
  updatedBy: null,
  startedBy: null,
  startedByName: null,
  autoplayEnabled: false,
  updatedAtMs: 0,
  volume: null,
  volumeControl: false,
  volumeTarget: null,
  volumeReqSeq: 0,
};

export const EMPTY_QUEUE: PartyQueue = { seq: 0, index: -1, items: [] };

/** Fills what an older server leaves out, as kotlinx's defaults do. */
export function playbackOf(raw: Partial<PartyPlayback> | null | undefined): PartyPlayback {
  return { ...EMPTY_PLAYBACK, ...raw, track: raw?.track ? trackOf(raw.track) : null };
}

export function queueOf(raw: Partial<PartyQueue> | null | undefined): PartyQueue {
  return { ...EMPTY_QUEUE, ...raw, items: (raw?.items ?? []).map(trackOf) };
}

export function trackOf(raw: Partial<PartyTrack>): PartyTrack {
  return {
    videoId: raw.videoId ?? '',
    title: raw.title ?? '',
    artist: raw.artist ?? '',
    thumbnailUrl: raw.thumbnailUrl ?? null,
    durationMs: raw.durationMs ?? null,
    fromAutoplay: raw.fromAutoplay ?? false,
  };
}

export function memberOf(raw: Partial<PartyMember>): PartyMember {
  return {
    memberId: raw.memberId ?? '',
    userId: raw.userId ?? '',
    displayName: raw.displayName ?? '',
    avatarUrl: raw.avatarUrl ?? null,
    isHost: raw.isHost ?? false,
    connected: raw.connected ?? false,
    joinedAtMs: raw.joinedAtMs ?? 0,
    lastSeenMs: raw.lastSeenMs ?? 0,
    role: raw.role === 'remote' ? 'remote' : 'speaker',
    deviceKey: raw.deviceKey ?? null,
    app: raw.app ?? null,
    deviceName: raw.deviceName ?? null,
  };
}

export function deviceOf(raw: Partial<ConnectDevice>): ConnectDevice {
  return {
    deviceId: raw.deviceId ?? '',
    deviceKey: raw.deviceKey ?? '',
    app: raw.app ?? '',
    deviceName: raw.deviceName ?? '',
    wakeable: raw.wakeable ?? false,
    connected: raw.connected ?? false,
    lastSeenMs: raw.lastSeenMs ?? 0,
  };
}

export function snapshotOf(raw: Partial<PartySnapshot>): PartySnapshot {
  return {
    kind: raw.kind === 'connect' ? 'connect' : 'jam',
    devices: (raw.devices ?? []).map(deviceOf),
    code: raw.code ?? '',
    createdAtMs: raw.createdAtMs ?? 0,
    maxMembers: raw.maxMembers ?? 5,
    hostOnlyControl: raw.hostOnlyControl ?? false,
    members: (raw.members ?? []).map(memberOf),
    playback: playbackOf(raw.playback),
    queue: queueOf(raw.queue),
    serverMs: raw.serverMs ?? 0,
  };
}
