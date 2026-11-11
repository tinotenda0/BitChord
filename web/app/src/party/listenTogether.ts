import { create } from 'zustand';
import { config } from '@/config';
import { ytAuthSnapshot } from '@/extension/useYtAuth';
import { marbleAccount, tokenLogin, useMarbleAccount } from '@/marble/account';
import { ServerClock } from './clock';
import { cleanCode, CODE_LENGTH, sanitizeServerUrl } from './invite';
import {
  deviceOf,
  EMPTY_PLAYBACK,
  EMPTY_QUEUE,
  memberOf,
  playbackOf,
  queueOf,
  snapshotOf,
  type ConnectDevice,
  type JoinRequest,
  type PartyActivity,
  type PartyMember,
  type PartyMembership,
  type PartyPlayback,
  type PartyPreview,
  type PartyQueue,
  type PartyTrack,
} from './models';

/**
 * Listen Together, the client half (ListenTogether.kt): membership, the
 * socket, the clock, and the controls any member may send. It doesn't touch
 * the player. What it publishes is [partyPositionMs], where this device ought
 * to be right now; partySync.ts binds that to the player.
 *
 * Nothing acts on "play now". The server holds a position and the server time
 * it was true at; [clock] measures this device's offset; the playhead is
 * arithmetic. Sequence numbers drop stale states, the server re-states the
 * truth every few seconds, and the socket loop assumes the socket will die.
 *
 * Unlike the phone, a reload doesn't end the party: the code and token are
 * kept for the tab's session (sessionStorage), and the reconnect lands inside
 * the server's 45 s grace, so a refresh is invisible to everyone else.
 */

/** Empty only in a dev build run without VITE_PARTY_SERVER_URL; parties then need a custom server. */
export const DEFAULT_SERVER = sanitizeServerUrl(config.partyServerUrl) ?? '';
export const MAX_UPCOMING = 25;
const PING_INTERVAL_MS = 15_000;
const REPORT_INTERVAL_MS = 10_000;
const KICK_BLOCK_MS = 24 * 60 * 60 * 1000;
const ACTIVITY_LIMIT = 100;
/** Failed connects in a row before asking the server whether the membership still exists. */
const CHECK_AFTER_FAILURES = 3;
const UNREACHABLE = 'Couldn’t reach the party server.';

export type Connection = 'offline' | 'connecting' | 'live';

export interface PartyState {
  /** Fork: "connect" while this is the account's own devices, "jam" for a party joined by code. */
  kind: 'jam' | 'connect';
  /** Fork, Connect: the account's devices the server remembers, connected or asleep. */
  devices: ConnectDevice[];
  /** Fork, Connect: why signing in to the account's devices last failed. */
  connectError: string | null;
  code: string | null;
  you: PartyMember | null;
  members: PartyMember[];
  maxMembers: number;
  hostOnlyControl: boolean;
  playback: PartyPlayback;
  /** Held apart from [playback] because it arrives apart; replaced only when it changed. */
  queue: PartyQueue;
  connection: Connection;
  /** False until the first round trip; the playhead is a guess until then. */
  clockSynced: boolean;
  roundTripMs: number;
  error: string | null;
  /** The last 100 actions this session. */
  activity: PartyActivity[];
  /** A resume this device accepted but may not perform yet (partySync owns it). */
  awaitingStart: boolean;
}

const IDLE: PartyState = {
  kind: 'jam',
  devices: [],
  connectError: null,
  code: null,
  you: null,
  members: [],
  maxMembers: 5,
  hostOnlyControl: false,
  playback: EMPTY_PLAYBACK,
  queue: EMPTY_QUEUE,
  connection: 'offline',
  clockSynced: false,
  roundTripMs: 0,
  error: null,
  activity: [],
  awaitingStart: false,
};

export const useParty = create<PartyState>()(() => IDLE);

/** True for a listener in a party whose host has taken control of it. */
export function controlsLocked(state: PartyState = useParty.getState()): boolean {
  return !!state.code && state.hostOnlyControl && state.you?.isHost !== true;
}

export class PartyError extends Error {
  constructor(
    readonly code: string,
    message: string,
    readonly status?: number,
  ) {
    super(message);
  }
}

// ---- Settings: the server, a nickname -------------------------------------------------

const SETTINGS_KEY = 'bitchord:party';
const SESSION_KEY = 'bitchord:party-session';

interface Settings {
  customServer: string;
  nickname: string;
}

export function partySettings(): Settings {
  try {
    return { customServer: '', nickname: '', ...(JSON.parse(localStorage.getItem(SETTINGS_KEY) ?? '{}') as Partial<Settings>) };
  } catch {
    return { customServer: '', nickname: '' };
  }
}

export function savePartySettings(change: Partial<Settings>): void {
  localStorage.setItem(SETTINGS_KEY, JSON.stringify({ ...partySettings(), ...change }));
}

/** The server parties are made on: the custom one if set, else BitChord's. */
export function idleServer(): string {
  return sanitizeServerUrl(partySettings().customServer) ?? DEFAULT_SERVER;
}

// ---- Membership --------------------------------------------------------------------

let token: string | null = null;
let server: string | null = null;
export const clock = new ServerClock();

function deviceId(): string {
  let id = localStorage.getItem('bitchord:deviceId');
  if (!id) {
    id = crypto.randomUUID();
    localStorage.setItem('bitchord:deviceId', id);
  }
  return id;
}

async function sha256(value: string): Promise<string> {
  const bytes = new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value)));
  return [...bytes].map((b) => b.toString(16).padStart(2, '0')).join('');
}

/**
 * Who this device jams as: the YouTube Music account it's signed in with
 * (prompt §8; the phone requires a signed-in account too). The id is a hash of
 * the login and channel, never either of them.
 */
async function identity(): Promise<Omit<JoinRequest, 'maxMembers' | 'autoplayEnabled'>> {
  const auth = ytAuthSnapshot();
  const active = auth.signedIn ? auth.active : null;
  if (!active) throw new PartyError('not_signed_in', 'Sign in to YouTube Music to listen together.');
  const name = partySettings().nickname.trim() || active.name;
  return {
    userId: (await sha256(`${active.authUser}:${active.id}`)).slice(0, 32),
    deviceId: deviceId(),
    displayName: name,
    avatarUrl: active.avatarUrl?.startsWith('http') ? active.avatarUrl : null,
  };
}

async function errorOf(response: Response): Promise<PartyError> {
  const body = (await response.json().catch(() => null)) as { error?: string; message?: string } | null;
  return new PartyError(
    body?.error || `http_${response.status}`,
    body?.message || (response.status === 422 ? 'This account can’t be used to jam.' : `The party server said ${response.status}.`),
    response.status,
  );
}

async function request<T>(url: string, init: RequestInit = {}): Promise<T> {
  let response: Response;
  try {
    response = await fetch(url, { ...init, headers: { 'Content-Type': 'application/json', ...init.headers } });
  } catch {
    throw new PartyError('unreachable', UNREACHABLE);
  }
  if (!response.ok) throw await errorOf(response);
  return (await response.json()) as T;
}

function adoptMembership(base: string, membership: PartyMembership): string {
  token = membership.token;
  server = base;
  clock.reset();
  const party = snapshotOf(membership.party);
  // Fork: Connect isn't resumed from the tab's session; it signs in again, and the server
  // recognises this browser by its device key.
  if (party.kind === 'jam') sessionStorage.setItem(SESSION_KEY, JSON.stringify({ code: membership.code, token, server }));
  else sessionStorage.removeItem(SESSION_KEY);
  useParty.setState({
    ...IDLE,
    kind: party.kind,
    devices: party.devices,
    code: membership.code,
    you: memberOf(membership.you),
    members: party.members,
    maxMembers: party.maxMembers,
    hostOnlyControl: party.hostOnlyControl,
    playback: party.playback,
    queue: party.queue,
    connection: 'connecting',
    activity: useParty.getState().activity,
  });
  connect();
  return membership.code;
}

export async function createParty(options: { maxMembers?: number; autoplayEnabled: boolean }): Promise<string> {
  if (useParty.getState().kind === 'connect') leaveConnectForJam();
  else if (useParty.getState().code) await leaveParty();
  const base = idleServer();
  try {
    const membership = await request<PartyMembership>(`${base}/api/parties`, {
      method: 'POST',
      body: JSON.stringify({ ...(await identity()), maxMembers: options.maxMembers ?? 5, autoplayEnabled: options.autoplayEnabled }),
    });
    return adoptMembership(base, membership);
  } catch (error) {
    useParty.setState({ error: messageOf(error) });
    throw error;
  }
}

/**
 * Joins [code], on [serverUrl] when an invite named one. Target first, as
 * switchPartyWithRecovery does: the party this device is already in is only
 * given up once the new one has said yes.
 */
export async function joinParty(rawCode: string, serverUrl?: string | null): Promise<string> {
  const code = cleanCode(rawCode);
  if (!code) throw new PartyError('bad_code', `A party code is ${CODE_LENGTH} letters or digits.`);
  refuseIfRecentlyKicked(code);
  const base = sanitizeServerUrl(serverUrl) ?? idleServer();
  const current = useParty.getState();
  if (current.code === code && server === base) return code;
  try {
    const membership = await request<PartyMembership>(`${base}/api/parties/${code}/join`, {
      method: 'POST',
      body: JSON.stringify(await identity()),
    });
    const previous = current.code && token && server ? { code: current.code, token, server } : null;
    clearTimeout(connectRetry);
    disconnect();
    // Fork: the account's other devices then show this one as in a jam rather than gone.
    if (previous) void releaseSlot(previous.server, previous.code, previous.token, current.kind === 'connect' ? 'jam' : undefined);
    return adoptMembership(base, membership);
  } catch (error) {
    useParty.setState({ error: messageOf(error) });
    throw error;
  }
}

export async function previewParty(rawCode: string, serverUrl?: string | null): Promise<PartyPreview> {
  const code = cleanCode(rawCode);
  if (!code) throw new PartyError('bad_code', `A party code is ${CODE_LENGTH} letters or digits.`);
  refuseIfRecentlyKicked(code);
  const base = sanitizeServerUrl(serverUrl) ?? idleServer();
  try {
    return await request<PartyPreview>(`${base}/api/parties/${code}/preview`);
  } catch (error) {
    // A server that predates /preview answers its router's plain-text 404: still joinable.
    if (error instanceof PartyError && error.code === 'http_404') {
      return { code, hostName: '', memberCount: 0, maxMembers: 5, isFull: false, members: [] };
    }
    throw error;
  }
}

function releaseSlot(base: string, code: string, held: string, reason?: string): Promise<void> {
  const why = reason ? `?reason=${encodeURIComponent(reason)}` : '';
  return fetch(`${base}/api/parties/${code}/leave${why}`, { method: 'POST', headers: { Authorization: `Bearer ${held}` } })
    .then(() => undefined)
    .catch(() => undefined);
}

export async function leaveParty(): Promise<void> {
  const { code, kind } = useParty.getState();
  const held = token;
  const base = server;
  disconnect();
  token = null;
  server = null;
  sessionStorage.removeItem(SESSION_KEY);
  clock.reset();
  useParty.setState({ ...IDLE, activity: useParty.getState().activity });
  if (code && held && base) await releaseSlot(base, code, held);
  // Fork: out of a jam, back to the account's own devices.
  if (kind === 'jam') void signInConnect();
}

/** Picks a party back up after a reload, inside the server's grace period. */
export function resumeSession(): void {
  if (useParty.getState().code) return;
  try {
    const saved = JSON.parse(sessionStorage.getItem(SESSION_KEY) ?? 'null') as { code: string; token: string; server: string } | null;
    if (!saved?.code || !saved.token || !saved.server) return;
    token = saved.token;
    server = saved.server;
    useParty.setState({ ...IDLE, code: saved.code, connection: 'connecting' });
    connect();
  } catch {
    sessionStorage.removeItem(SESSION_KEY);
  }
}

export function partyServer(): string | null {
  return server;
}

// ---- Connect (fork): this account's own devices -----------------------------------------
//
// A port of the fork's ListenTogether.signInConnect: the browser signs in with the marble
// login and lands in the account's Connect party, where exactly one member, the output,
// plays and the rest are remotes (partySync.ts decides which this tab is). A jam holds the
// device's one socket, so Connect steps aside for it and signs in again after.

const CONNECT_KEY = 'bitchord:connect-device-key';
let connectRetry: ReturnType<typeof setTimeout> | undefined;
let connectBackoff = 1000;
let playingNow: () => boolean = () => false;

/** A stable random key for this browser: the server knows it as the same device across reloads. */
function connectDeviceKey(): string {
  let key = localStorage.getItem(CONNECT_KEY);
  if (!key || !/^[0-9a-f]{24}$/.test(key)) {
    key = Array.from(crypto.getRandomValues(new Uint8Array(12)), (b) => b.toString(16).padStart(2, '0')).join('');
    localStorage.setItem(CONNECT_KEY, key);
  }
  return key;
}

/** "Chrome on Windows": how this tab shows in the account's device list. */
export function webDeviceName(): string {
  const ua = navigator.userAgent;
  const browser = /Edg\//.test(ua) ? 'Edge' : /Firefox\//.test(ua) ? 'Firefox' : /Chrome\//.test(ua) ? 'Chrome' : /Safari\//.test(ua) ? 'Safari' : 'Browser';
  const os = /Windows/.test(ua) ? 'Windows' : /Mac OS X/.test(ua) ? 'Mac' : /Android/.test(ua) ? 'Android' : /CrOS/.test(ua) ? 'ChromeOS' : /Linux/.test(ua) ? 'Linux' : '';
  return os ? `${browser} on ${os}` : browser;
}

/** Whether music is coming out of this tab: a sign-in after a server restart then comes back as the output. */
export function setConnectPlaying(fn: () => boolean): void {
  playingNow = fn;
}

const inJam = () => {
  const state = useParty.getState();
  return !!state.code && state.kind === 'jam';
};

/** Signs this browser in to the account's Connect party; retries on its own until it's in. */
export async function signInConnect(): Promise<void> {
  clearTimeout(connectRetry);
  const account = marbleAccount();
  if (!account || !DEFAULT_SERVER || inJam()) return;
  const { user, token: gatewayToken, salt } = tokenLogin(account);
  try {
    const membership = await request<PartyMembership>(`${DEFAULT_SERVER}/api/connect`, {
      method: 'POST',
      body: JSON.stringify({
        gatewayUser: user,
        gatewayToken,
        gatewaySalt: salt,
        deviceKey: connectDeviceKey(),
        app: 'web',
        deviceName: webDeviceName(),
        displayName: account.username,
        playing: playingNow(),
      }),
    });
    // Signed out, or into a jam, while that was in flight.
    if (!marbleAccount() || inJam()) {
      void releaseSlot(DEFAULT_SERVER, membership.code, membership.token);
      return;
    }
    connectBackoff = 1000;
    adoptMembership(DEFAULT_SERVER, membership);
  } catch (error) {
    if (!marbleAccount() || inJam()) return;
    const code = error instanceof PartyError ? error.code : 'unreachable';
    useParty.setState({ connectError: messageOf(error) });
    // Not a blip: no Connect on this server, or a login it refuses. Asked again only now and then.
    const wait = code === 'bad_login' || code === 'connect_disabled' ? 30 * 60_000 : connectBackoff;
    connectBackoff = Math.min(connectBackoff * 2, 30_000);
    connectRetry = setTimeout(() => void signInConnect(), wait);
  }
}

/** Steps out of the account's party so a jam can have the socket; partySync then follows the jam. */
function leaveConnectForJam(): void {
  const { code } = useParty.getState();
  const held = token;
  const base = server;
  clearTimeout(connectRetry);
  disconnect();
  token = null;
  server = null;
  clock.reset();
  useParty.setState({ ...IDLE, activity: useParty.getState().activity });
  if (code && held && base) void releaseSlot(base, code, held, 'jam');
}

/** Signed out of marble: off the account's party, if that's where this tab is. */
function stopConnect(): void {
  clearTimeout(connectRetry);
  const state = useParty.getState();
  if (state.kind !== 'connect' || !state.code) return;
  const held = token;
  const base = server;
  disconnect();
  token = null;
  server = null;
  clock.reset();
  useParty.setState({ ...IDLE, activity: state.activity });
  if (held && base && state.code) void releaseSlot(base, state.code, held);
}

/** Mounted once, in the shell: signs in now, and again whenever the marble account changes. */
export function startConnect(): () => void {
  void signInConnect();
  const off = useMarbleAccount.subscribe((now, before) => {
    if (now.account === before.account) return;
    if (now.account) void signInConnect();
    else stopConnect();
  });
  return () => {
    off();
    clearTimeout(connectRetry);
  };
}

/** The membership is gone on the server's side: a jam is over, Connect signs in again. */
function membershipLost(message: string | null): void {
  if (useParty.getState().kind === 'connect') {
    clearTimeout(connectRetry);
    connectRetry = setTimeout(() => void signInConnect(), 500);
    return;
  }
  void leaveParty().then(() => message && useParty.setState({ error: message }));
}

// ---- Recent kicks (a door that stays shut on this device for a day) ------------------------

const KICKS_KEY = 'bitchord:party-kicked';

function recentKicks(): Record<string, number> {
  try {
    const all = JSON.parse(localStorage.getItem(KICKS_KEY) ?? '{}') as Record<string, number>;
    return Object.fromEntries(Object.entries(all).filter(([, until]) => until > Date.now()));
  } catch {
    return {};
  }
}

function recordKick(code: string): void {
  localStorage.setItem(KICKS_KEY, JSON.stringify({ ...recentKicks(), [code]: Date.now() + KICK_BLOCK_MS }));
}

function refuseIfRecentlyKicked(code: string): void {
  if (recentKicks()[code]) throw new PartyError('kicked', 'You were removed from this party. You can rejoin tomorrow.');
}

// ---- Controls (any member, unless the host has taken control) -------------------------------

let socket: WebSocket | null = null;

function send(frame: Record<string, unknown>): void {
  if (socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify(frame));
}

function control(action: string, body: Record<string, unknown> = {}): void {
  send({ type: 'control', action, ...body });
}

export const party = {
  play: (positionMs?: number) => control('play', positionMs !== undefined ? { positionMs: Math.round(positionMs) } : {}),
  pause: (positionMs?: number) => control('pause', positionMs !== undefined ? { positionMs: Math.round(positionMs) } : {}),
  seek: (positionMs: number) => control('seek', { positionMs: Math.round(positionMs) }),
  next: () => control('next'),
  previous: () => control('previous'),
  setTrack: (track: PartyTrack, positionMs = 0, isPlaying = true, queueIndex?: number) =>
    control('setTrack', { track, positionMs: Math.round(positionMs), isPlaying, ...(queueIndex !== undefined && { queueIndex }) }),
  setQueue: (queue: PartyTrack[], index: number) => control('setQueue', { queue, queueIndex: index }),
  queueAdd: (tracks: PartyTrack[], playNext = false) => control('queueAdd', { tracks, playNext }),
  queueRemove: (videoId: string) => control('queueRemove', { videoId }),
  queueClear: () => control('queueClear'),
  queueMove: (fromIndex: number, toIndex: number, videoId?: string) =>
    control('queueMove', { fromIndex, toIndex, ...(videoId && { videoId }) }),
  setMaxMembers: (maxMembers: number) => control('setMaxMembers', { maxMembers }),
  kick: (memberId: string) => control('kick', { memberId }),
  setAutoplay: (enabled: boolean) => control('setAutoplay', { enabled }),
  setHostOnlyControl: (enabled: boolean) => control('setHostOnlyControl', { enabled }),
  // Fork, Connect.
  /** Moves the account's playback onto another of its devices (or this one: "play here"). */
  transfer: (memberId: string) => control('transfer', { memberId }),
  /** Moves it onto a device that's asleep, by waking it. */
  wake: (deviceId: string) => control('wake', { deviceId }),
  /** Asks the output to play at [volume], 0 to 1. */
  setVolume: (volume: number) => control('setVolume', { volume }),
  /** The output saying what its volume really is, and that the others may change it. */
  volumeState: (volume: number) => control('volumeState', { volume, control: true, steps: 100 }),
};

/** Fork, Connect: where the output really is, for its measured reports; null when it isn't playing. */
export type MeasuredReading = { videoId: string; positionMs: number; durationMs: number } | null;
let measuredReader: (() => MeasuredReading) | null = null;
export function setMeasuredReader(read: (() => MeasuredReading) | null): void {
  measuredReader = read;
}

// ---- The playhead ------------------------------------------------------------------

/**
 * Where this device should be in the current track, right now, in ms; null
 * without a track. Before the first pong it's the server's own reading at send
 * time: a starting point, not a thing to seek to (wait for clockSynced).
 */
export function partyPositionMs(): number | null {
  const { playback } = useParty.getState();
  const track = playback.track;
  if (!track) return null;
  if (!playback.isPlaying) return playback.positionMs;
  const serverNow = clock.serverNowMs();
  if (serverNow === null) return playback.effectivePositionMs;
  const position = playback.positionMs + Math.max(0, serverNow - playback.anchorMs);
  return track.durationMs ? Math.min(position, track.durationMs) : position;
}

/** How far ahead the party's next resume is scheduled (the play lead), or 0 if it's under way. */
export function msUntilStart(): number {
  const { playback } = useParty.getState();
  if (!playback.isPlaying) return 0;
  const serverNow = clock.serverNowMs();
  return serverNow === null ? 0 : Math.max(0, playback.anchorMs - serverNow);
}

// ---- The socket ----------------------------------------------------------------------

let loopId = 0;
let timers: ReturnType<typeof setTimeout>[] = [];

function clearTimers() {
  timers.forEach(clearTimeout);
  timers = [];
}

function disconnect(): void {
  loopId++;
  clearTimers();
  const s = socket;
  socket = null;
  if (s && s.readyState <= WebSocket.OPEN) s.close(1000, 'left');
}

/** Connect, pump frames until the socket goes, back off with jitter, go round again. */
function connect(): void {
  disconnect();
  const id = ++loopId;
  let backoff = 1000;
  let failures = 0;

  const open = () => {
    const { code } = useParty.getState();
    if (id !== loopId || !code || !token || !server) return;
    useParty.setState({ connection: 'connecting' });
    const ws = new WebSocket(`${server.replace(/^http/, 'ws')}/ws/parties/${code}`, ['bitchord.v1', `bitchord.token.${token}`]);
    socket = ws;
    let opened = false;
    ws.onopen = () => {
      if (id !== loopId) return;
      opened = true;
      failures = 0;
      backoff = 1000;
      useParty.setState({ connection: 'live', error: null });
      // A burst on arrival makes the first seconds accurate, then a trickle keeps up with drift.
      for (let i = 0; i < 4; i++) timers.push(setTimeout(ping, i * 300));
      timers.push(setInterval(ping, PING_INTERVAL_MS) as unknown as ReturnType<typeof setTimeout>);
      timers.push(setInterval(report, REPORT_INTERVAL_MS) as unknown as ReturnType<typeof setTimeout>);
    };
    ws.onmessage = (event) => {
      if (id === loopId) onFrame(String(event.data));
    };
    ws.onclose = () => {
      if (id !== loopId || socket !== ws) return;
      socket = null;
      clearTimers();
      clock.reset();
      useParty.setState({ connection: 'connecting', clockSynced: false });
      if (!opened && ++failures >= CHECK_AFTER_FAILURES) {
        failures = 0;
        void checkMembership();
      }
      const wait = backoff / 2 + Math.random() * (backoff + 1);
      backoff = Math.min(backoff * 2, 30_000);
      timers.push(setTimeout(open, wait));
    };
  };
  open();
}

/**
 * A browser can't read why a handshake failed, so after a few failed opens
 * the server is asked whether this membership still exists. A party the
 * server has forgotten (a restart, a sweep) is left rather than redialled forever.
 */
async function checkMembership(): Promise<void> {
  const { code } = useParty.getState();
  if (!code || !token || !server) return;
  try {
    const response = await fetch(`${server}/api/parties/${code}`, { headers: { Authorization: `Bearer ${token}` } });
    if (response.status === 401 || response.status === 404) {
      const why = await errorOf(response);
      membershipLost(why.status === 404 ? 'That party has ended.' : why.message);
    }
  } catch {
    // Unreachable: keep trying.
  }
}

function ping(): void {
  send({ type: 'ping', clientMs: clock.nowMs() });
}

function report(): void {
  if (useParty.getState().kind === 'connect') {
    const reading = measuredReader?.();
    const atMs = clock.serverNowMs();
    if (!reading || atMs === null) return;
    send({
      type: 'report',
      measured: true,
      videoId: reading.videoId,
      positionMs: Math.round(reading.positionMs),
      atMs: Math.round(atMs),
      isPlaying: true,
      ...(reading.durationMs > 0 && { durationMs: Math.round(reading.durationMs) }),
    });
    return;
  }
  const position = partyPositionMs();
  if (position === null) return;
  send({ type: 'report', positionMs: Math.round(position), isPlaying: useParty.getState().playback.isPlaying });
}

function recordActivity(entry: PartyActivity): void {
  useParty.setState((s) => ({ activity: [entry, ...s.activity].slice(0, ACTIVITY_LIMIT) }));
}

function onFrame(text: string): void {
  const received = clock.nowMs();
  let frame: Record<string, unknown>;
  try {
    frame = JSON.parse(text) as Record<string, unknown>;
  } catch {
    return;
  }
  const state = useParty.getState();
  switch (frame.type) {
    case 'welcome': {
      if (!frame.party) return;
      const party = snapshotOf(frame.party as never);
      useParty.setState({
        kind: party.kind,
        devices: party.devices,
        connectError: null,
        code: party.code || state.code,
        you: frame.you ? memberOf(frame.you as never) : state.you,
        members: party.members,
        maxMembers: party.maxMembers,
        hostOnlyControl: party.hostOnlyControl,
        playback: party.playback,
        // The one message that always carries the queue: a new arrival has no other way to learn it.
        queue: party.queue,
        connection: 'live',
        error: null,
      });
      break;
    }
    case 'pong': {
      const sentAt = Number(frame.clientMs);
      const serverMs = Number(frame.serverMs);
      if (!Number.isFinite(sentAt) || !Number.isFinite(serverMs)) return;
      clock.record(sentAt, serverMs, received);
      useParty.setState({ clockSynced: clock.synced, roundTripMs: clock.roundTripMs });
      break;
    }
    case 'state': {
      const playback = playbackOf(frame.playback as never);
      // Older than what's applied: two controllers pressing at once settle instead of fighting.
      if (playback.seq < state.playback.seq) return;
      useParty.setState({ playback });
      // A missed queue frame is healed on the next heartbeat rather than lived with.
      if (playback.queueSeq !== useParty.getState().queue.seq) send({ type: 'syncQueue' });
      break;
    }
    case 'queue': {
      const queue = queueOf(frame.queue as never);
      if (queue.seq < state.queue.seq) return;
      useParty.setState({ queue });
      break;
    }
    case 'members': {
      const members = ((frame.members as unknown[]) ?? []).map((m) => memberOf(m as never));
      const maxMembers = typeof frame.maxMembers === 'number' ? frame.maxMembers : state.maxMembers;
      // Absent on an older server: keep what's held, so an upgrade doesn't silently unlock a party.
      const hostOnlyControl = typeof frame.hostOnlyControl === 'boolean' ? frame.hostOnlyControl : state.hostOnlyControl;
      // The only place a promotion is announced.
      const you = (state.you && members.find((m) => m.memberId === state.you!.memberId)) ?? state.you;
      // Fork, Connect: the device list rides along with the members.
      const devices = Array.isArray(frame.devices) ? (frame.devices as unknown[]).map((d) => deviceOf(d as never)) : state.devices;
      useParty.setState({ members, maxMembers, hostOnlyControl, you, devices });
      break;
    }
    case 'activity': {
      const action = typeof frame.action === 'string' ? frame.action : null;
      const by = typeof frame.by === 'string' && frame.by ? frame.by : null;
      if (!action || !by) return;
      recordActivity({ action, by, atMs: typeof frame.atMs === 'number' ? frame.atMs : Date.now(), detail: String(frame.detail ?? '') });
      break;
    }
    case 'error': {
      const reason = String(frame.error ?? '');
      useParty.setState({ error: typeof frame.message === 'string' ? frame.message : reason });
      // Terminal: the party no longer knows this device.
      if (reason === 'bad_token' || reason === 'no_such_party') membershipLost(null);
      break;
    }
    case 'bye': {
      // Fork: Connect has no host to kick anyone; a bye there is the server letting go.
      if (state.kind === 'connect') {
        membershipLost(null);
        break;
      }
      if (frame.reason === 'kicked' && state.code) recordKick(state.code);
      const kicked = frame.reason === 'kicked';
      void leaveParty().then(() => kicked && useParty.setState({ error: 'The host removed you from the party.' }));
      break;
    }
  }
}

export function messageOf(error: unknown): string {
  return error instanceof PartyError ? error.message : UNREACHABLE;
}

export const QUEUE_EMPTY = EMPTY_QUEUE;
