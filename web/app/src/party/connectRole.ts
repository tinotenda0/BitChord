import type { PartyState } from './listenTogether';
import type { PartyMember } from './models';

/**
 * Fork, Connect: which of the account's devices this tab is (see partySync.ts). Kept apart
 * from the sync itself so it reads the party alone, without the player.
 */
export type ConnectRole = 'none' | 'output' | 'remote' | 'solo';

export function outputOf(state: PartyState): PartyMember | null {
  return state.members.find((m) => m.role === 'speaker') ?? null;
}

export function connectRole(state: PartyState): ConnectRole {
  if (!state.code || state.kind !== 'connect') return 'none';
  const me = state.you?.memberId;
  const output = outputOf(state);
  if (output && me && output.memberId === me) return 'output';
  if (output?.connected) return 'remote';
  return 'solo';
}

