import { create } from 'zustand';
import { config } from '@/config';
import { md5 } from './md5';

/**
 * Fork: the marble account, used for two things only: Connect (this account's
 * own devices, on the party server) and listening stats. The library, search
 * and everything else come from YouTube Music through the extension.
 *
 * The login is kept in this browser, as the phone keeps it, because both of
 * marble's checks want a fresh md5(password + salt) each time. The password
 * itself never goes into a URL: every call carries the salted token.
 */

export interface MarbleAccount {
  username: string;
  password: string;
}

interface AccountState {
  account: MarbleAccount | null;
}

const KEY = 'bitchord:marble-account';

function load(): MarbleAccount | null {
  try {
    const raw = JSON.parse(localStorage.getItem(KEY) ?? 'null') as Partial<MarbleAccount> | null;
    return raw?.username && raw.password ? { username: raw.username, password: raw.password } : null;
  } catch {
    return null;
  }
}

export const useMarbleAccount = create<AccountState>()(() => ({ account: load() }));

export const marbleAccount = (): MarbleAccount | null => useMarbleAccount.getState().account;

export function randomSalt(): string {
  return Array.from(crypto.getRandomValues(new Uint8Array(8)), (b) => b.toString(16).padStart(2, '0')).join('');
}

/** The salted Subsonic login: what marble's gateway and the party server's Connect both check. */
export function tokenLogin(account: MarbleAccount): { user: string; token: string; salt: string } {
  const salt = randomSalt();
  return { user: account.username, token: md5(account.password + salt), salt };
}

export class MarbleError extends Error {}

/**
 * Calls a gateway endpoint (`/rest/<endpoint>.view`) as [account]. [body], when
 * given, is POSTed as JSON; the login always stays in the query, as a token.
 */
export async function gatewayCall<T = Record<string, unknown>>(
  endpoint: string,
  params: Record<string, string> = {},
  options: { account?: MarbleAccount; body?: unknown } = {},
): Promise<T> {
  const account = options.account ?? marbleAccount();
  if (!account) throw new MarbleError('Not signed in to marble.');
  const { user, token, salt } = tokenLogin(account);
  const query = new URLSearchParams({ u: user, t: token, s: salt, v: '1.16.1', c: 'BitChord-web', f: 'json', ...params });
  let response: Response;
  try {
    response = await fetch(`${config.marbleGatewayUrl}/rest/${endpoint}.view?${query}`, {
      method: options.body === undefined ? 'GET' : 'POST',
      // A simple request (no preflight) for the GETs; the JSON body needs its type.
      ...(options.body !== undefined && { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(options.body) }),
    });
  } catch {
    throw new MarbleError('Couldn’t reach marble.');
  }
  const json = (await response.json().catch(() => null)) as { 'subsonic-response'?: { status?: string; error?: { message?: string } } } | null;
  const root = json?.['subsonic-response'];
  if (!response.ok || !root || root.status !== 'ok') {
    throw new MarbleError(root?.error?.message || `Marble said ${response.status}.`);
  }
  return root as T;
}

/** Checks the login against marble, and keeps it only if marble accepts it. */
export async function signInToMarble(username: string, password: string): Promise<void> {
  const account = { username: username.trim(), password };
  if (!account.username || !account.password) throw new MarbleError('Enter your marble username and password.');
  await gatewayCall('ping', {}, { account });
  localStorage.setItem(KEY, JSON.stringify(account));
  useMarbleAccount.setState({ account });
}

export function signOutOfMarble(): void {
  localStorage.removeItem(KEY);
  useMarbleAccount.setState({ account: null });
}
