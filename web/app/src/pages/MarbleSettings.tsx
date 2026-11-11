import { X } from 'lucide-react';
import { useEffect, useState, type FormEvent } from 'react';
import { createPortal } from 'react-dom';
import { MarbleError, signInToMarble, signOutOfMarble, useMarbleAccount } from '@/marble/account';
import { useParty } from '@/party/listenTogether';
import { settingsUi } from './settingsStyles';

/**
 * Fork: marble in Settings › Account & Integrations, a row like Spotify's. The
 * login is for Connect (play on, and from, the account's own devices) and
 * listening stats only; music comes from YouTube Music through the extension.
 */
export function MarbleIntegration() {
  const account = useMarbleAccount((s) => s.account);
  const connection = useParty((s) => (s.kind === 'connect' && s.code ? s.connection : null));
  const connectError = useParty((s) => s.connectError);
  const inJam = useParty((s) => s.kind === 'jam' && !!s.code);
  const [signingIn, setSigningIn] = useState(false);

  if (!account) {
    return (
      <span className={settingsUi.value}>
        Not signed in
        <button className={settingsUi.inlineButton} onClick={() => setSigningIn(true)}>
          Sign In
        </button>
        {signingIn && <MarbleSignIn onClose={() => setSigningIn(false)} />}
      </span>
    );
  }

  const live = connection === 'live' && !inJam;
  const detail = inJam ? 'Connect paused during the jam' : live ? 'Connected' : connection === 'connecting' || !connectError ? 'Connecting…' : connectError;
  return (
    <span className={settingsUi.value}>
      <span title={detail}>Signed in as {account.username}</span>
      {live && <span className={settingsUi.dot} aria-label="Connected" />}
      <button className={settingsUi.inlineButton} onClick={signOutOfMarble}>
        Sign Out
      </button>
    </span>
  );
}

const field = 'w-full rounded-[8px] border-0 bg-quaternary px-2.5 py-[7px] type-body text-primary';
const label = 'mb-1.5 block type-subhead-emphasized text-secondary';

/** The sign-in popup, in the app's modal style (LyricsSettings). */
function MarbleSignIn({ onClose }: { onClose: () => void }) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await signInToMarble(username, password);
      onClose();
    } catch (e) {
      setError(e instanceof MarbleError ? e.message : 'Couldn’t sign in.');
      setBusy(false);
    }
  };

  return createPortal(
    <div
      className="fixed inset-0 z-(--z-modal) grid animate-fade place-items-center bg-scrim"
      onPointerDown={(e) => e.target === e.currentTarget && onClose()}
    >
      <form
        className="w-[min(400px,calc(100vw-32px))] animate-zoom rounded-[14px] bg-page shadow-glass"
        role="dialog"
        aria-modal="true"
        aria-label="Sign in to marble"
        onSubmit={submit}
      >
        <header className="flex items-center justify-between px-5 pt-[18px] pb-2">
          <h2 className="type-title-2-emphasized">Sign in to marble</h2>
          <button type="button" className="grid size-[30px] place-items-center rounded-full bg-[#333336] text-secondary" aria-label="Close" onClick={onClose}>
            <X size={16} />
          </button>
        </header>
        <div className="px-5 pb-5">
          <p className="mb-4 type-callout text-secondary">
            For Connect (playing on and from your phone, tablet and TV) and your listening stats. It stays in this browser.
          </p>
          <label className={label} htmlFor="marble-username">
            Username
          </label>
          <input
            id="marble-username"
            className={`${field} mb-3`}
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoComplete="username"
            autoCapitalize="none"
            spellCheck={false}
            autoFocus
          />
          <label className={label} htmlFor="marble-password">
            Password
          </label>
          <input
            id="marble-password"
            className={`${field} mb-4`}
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
          />
          {error && <p className="mb-3 type-callout text-destructive">{error}</p>}
          <button
            type="submit"
            className="w-full rounded-[8px] bg-key py-2 type-body-emphasized text-white disabled:opacity-50"
            disabled={busy || !username || !password}
          >
            {busy ? 'Signing In…' : 'Sign In'}
          </button>
        </div>
      </form>
    </div>,
    document.body,
  );
}
