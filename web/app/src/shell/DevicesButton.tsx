import { Laptop, MonitorSpeaker, Smartphone, Speaker, Tv, Watch } from 'lucide-react';
import { useCallback, useState, type ReactNode } from 'react';
import { Menu, MenuHeader, MenuItem, MenuSeparator, type MenuPlacement } from '@/components/Menu';
import { party, useParty } from '@/party/listenTogether';
import type { ConnectDevice, PartyMember } from '@/party/models';
import { connectRole, outputOf } from '@/party/partySync';

/**
 * Fork, Connect: where the account's music plays. The phone's devices sheet, as
 * a menu: this browser, the account's other connected devices, and the ones
 * asleep that can be woken. Picking one moves playback there; the one playing
 * is ticked. Only while Connect is signed in (Settings › Marble account).
 */

function iconFor(app: string | null | undefined, name: string): ReactNode {
  const lower = `${app ?? ''} ${name}`.toLowerCase();
  if (app === 'web') return <Laptop size={16} />;
  if (app === 'tv' || /\btv\b|shield|chromecast/.test(lower)) return <Tv size={16} />;
  if (app === 'wear' || /watch/.test(lower)) return <Watch size={16} />;
  if (/tablet|tab |pad/.test(lower)) return <MonitorSpeaker size={16} />;
  return <Smartphone size={16} />;
}

const nameOf = (m: PartyMember) => m.deviceName || m.displayName || 'Device';

/** The device the account's music is playing on, when it isn't this browser; for the player to say so. */
export function usePlayingElsewhere(): string | null {
  return useParty((s) => {
    if (connectRole(s) !== 'remote') return null;
    const output = outputOf(s);
    return output ? nameOf(output) : null;
  });
}

export function DevicesButton({ className, placement = 'above-end' }: { className: string; placement?: MenuPlacement }) {
  const state = useParty();
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  const close = useCallback(() => setAnchor(null), []);
  if (state.kind !== 'connect' || !state.code) return null;

  const me = state.you?.memberId ?? null;
  const output = outputOf(state);
  const role = connectRole(state);
  const others = state.members.filter((m) => m.memberId !== me && m.connected);
  const present = new Set(state.members.filter((m) => m.connected).map((m) => `${m.deviceKey}:${m.app}`));
  const sleeping = state.devices.filter((d: ConnectDevice) => !d.connected && d.wakeable && !present.has(d.deviceId));
  const live = state.connection === 'live';
  const pick = (action: () => void) => () => {
    action();
    close();
  };

  return (
    <>
      <button
        className={className}
        aria-label={role === 'remote' && output ? `Playing on ${nameOf(output)}` : 'Devices'}
        title={role === 'remote' && output ? `Playing on ${nameOf(output)}` : 'Devices'}
        aria-haspopup="menu"
        aria-expanded={anchor !== null}
        aria-pressed={role === 'remote'}
        onClick={(event) => {
          event.stopPropagation();
          setAnchor(anchor ? null : event.currentTarget);
        }}
      >
        <Speaker size={18} strokeWidth={2} />
      </button>
      {anchor && (
        <Menu anchor={anchor} placement={placement} onClose={close} label="Devices">
          <MenuHeader>{live ? 'Play on' : 'Connecting…'}</MenuHeader>
          <MenuItem
            icon={<Laptop size={16} />}
            checked={role === 'output' || role === 'solo'}
            disabled={!live || !me || role === 'output'}
            trailing={role === 'output' ? 'Playing' : undefined}
            onSelect={pick(() => me && party.transfer(me))}
          >
            This browser
          </MenuItem>
          {others.map((m) => (
            <MenuItem
              key={m.memberId}
              icon={iconFor(m.app, nameOf(m))}
              checked={output?.memberId === m.memberId}
              disabled={!live || output?.memberId === m.memberId}
              trailing={output?.memberId === m.memberId ? 'Playing' : undefined}
              onSelect={pick(() => party.transfer(m.memberId))}
            >
              {nameOf(m)}
            </MenuItem>
          ))}
          {sleeping.length > 0 && <MenuSeparator />}
          {sleeping.map((d) => (
            <MenuItem
              key={d.deviceId}
              icon={iconFor(d.app, d.deviceName)}
              checked={null}
              disabled={!live}
              trailing="Wake"
              onSelect={pick(() => party.wake(d.deviceId))}
            >
              {d.deviceName || 'Device'}
            </MenuItem>
          ))}
        </Menu>
      )}
    </>
  );
}
