import { describe, expect, it } from 'vitest';
import { memberOf } from './models';
import { connectRole } from './connectRole';
import type { PartyState } from './listenTogether';

const member = (id: string, role: 'speaker' | 'remote', connected = true) =>
  memberOf({ memberId: id, role, connected, isHost: role === 'speaker' });

const base = (members: ReturnType<typeof member>[], you = 'me', kind: 'connect' | 'jam' = 'connect'): PartyState =>
  ({ kind, code: 'ABC123', you: members.find((m) => m.memberId === you) ?? member(you, 'remote'), members }) as unknown as PartyState;

describe('connectRole', () => {
  it('is the output when this tab is the speaker', () => {
    expect(connectRole(base([member('me', 'speaker'), member('phone', 'remote')]))).toBe('output');
  });

  it('is a remote while another connected device plays', () => {
    expect(connectRole(base([member('me', 'remote'), member('phone', 'speaker')]))).toBe('remote');
  });

  it('plays on its own while the output is away', () => {
    expect(connectRole(base([member('me', 'remote'), member('phone', 'speaker', false)]))).toBe('solo');
  });

  it('plays on its own with no output at all', () => {
    expect(connectRole(base([member('me', 'remote'), member('phone', 'remote')]))).toBe('solo');
  });

  it('leaves a jam to the jam rules', () => {
    expect(connectRole(base([member('me', 'speaker'), member('friend', 'speaker')], 'me', 'jam'))).toBe('none');
  });
});
