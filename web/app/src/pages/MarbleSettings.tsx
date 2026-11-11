import { useState, type FormEvent } from 'react';
import { MarbleError, signInToMarble, signOutOfMarble, useMarbleAccount } from '@/marble/account';
import { useParty } from '@/party/listenTogether';
import { settingsUi } from './settingsStyles';

/**
 * Fork: Settings › Marble. The marble login is for Connect (play on, and from,
 * the account's own devices) and listening stats only; music comes from YouTube
 * Music through the extension.
 */
export function MarbleSettings() {
  const account = useMarbleAccount((s) => s.account);
  const connect = useParty((s) => (s.kind === 'connect' && s.code ? s.connection : null));
  const connectError = useParty((s) => s.connectError);
  const inJam = useParty((s) => s.kind === 'jam' && !!s.code);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await signInToMarble(username, password);
      setPassword('');
    } catch (e) {
      setError(e instanceof MarbleError ? e.message : 'Couldn’t sign in.');
    } finally {
      setBusy(false);
    }
  };

  if (account) {
    const status = inJam
      ? 'Paused during the jam'
      : connect === 'live'
        ? 'Connected'
        : connect === 'connecting'
          ? 'Connecting…'
          : (connectError ?? 'Connecting…');
    return (
      <>
        <section className={settingsUi.group}>
          <div className={settingsUi.integrationPlain}>
            <span className={settingsUi.integrationName}>Signed in as {account.username}</span>
            <span className={settingsUi.value}>
              <button className={settingsUi.inlineButton} onClick={signOutOfMarble}>
                Sign Out
              </button>
            </span>
          </div>
          <div className={settingsUi.integrationPlain}>
            <span className={settingsUi.integrationName}>Connect</span>
            <span className={settingsUi.value}>
              {connect === 'live' && !inJam && <span className={settingsUi.dot} />}
              {status}
            </span>
          </div>
        </section>
        <p className={settingsUi.footer}>
          Play here or on your other devices from the speaker button in the player. What plays in this browser counts in your
          listening stats.
        </p>
      </>
    );
  }

  return (
    <>
      <form className={settingsUi.group} onSubmit={submit}>
        <label className={settingsUi.controlRow}>
          <span>Username</span>
          <input
            className={settingsUi.input}
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoComplete="username"
            autoCapitalize="none"
            spellCheck={false}
          />
        </label>
        <label className={settingsUi.controlRow}>
          <span>Password</span>
          <input
            className={settingsUi.input}
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
          />
        </label>
        <div className={settingsUi.row}>
          <span className="type-callout text-destructive">{error}</span>
          <button className={settingsUi.inlineButton} type="submit" disabled={busy || !username || !password}>
            {busy ? 'Signing In…' : 'Sign In'}
          </button>
        </div>
      </form>
      <p className={settingsUi.footer}>
        Your marble account, for Connect (playing on and from your phone, tablet and TV) and listening stats. It stays in this
        browser.
      </p>
    </>
  );
}
