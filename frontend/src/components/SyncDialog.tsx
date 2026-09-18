import { useEffect, useState } from 'react';
import { api, type SyncResult, type SyncSettings } from '../api';
import { isNative } from '../native';
import { Modal } from './Dialogs';

interface Props {
  onClose: () => void;
  /** Runs a sync through the app, so open editors are saved first and reloaded afterwards. */
  onSync: () => Promise<SyncResult>;
}

const TOKEN_HELP = {
  github: {
    url: 'https://github.com/settings/personal-access-tokens/new',
    text: 'Fine-grained token with "Contents: Read and write" on the repository (or a classic token with the "repo" scope).',
  },
  gitlab: {
    url: 'https://gitlab.com/-/user_settings/personal_access_tokens',
    // the Android app syncs through the REST API, which "write_repository" (Git over HTTPS only) does not cover
    text: isNative
      ? 'Personal or project access token with the "api" scope.'
      : 'Personal or project access token with the "write_repository" scope.',
  },
};

export function SyncDialog({ onClose, onSync }: Props) {
  const [settings, setSettings] = useState<SyncSettings | null>(null);
  const [token, setToken] = useState('');
  const [busy, setBusy] = useState<string | null>(null);
  const [result, setResult] = useState<SyncResult | null>(null);

  useEffect(() => {
    api.syncSettings().then(setSettings).catch((e: Error) => setResult({ ok: false, message: e.message, conflicts: [], time: '' }));
    api.syncStatus().then((status) => setResult((current) => current ?? status.lastResult)).catch(() => undefined);
  }, []);

  if (!settings) {
    return (
      <Modal title="Sync" onClose={onClose}>
        <p>{result?.message ?? 'Loading…'}</p>
      </Modal>
    );
  }

  const set = <K extends keyof SyncSettings>(key: K, value: SyncSettings[K]) => setSettings({ ...settings, [key]: value });

  const run = async (label: string, action: () => Promise<SyncResult | null>) => {
    setBusy(label);
    setResult(null);
    try {
      const saved = await api.saveSyncSettings({ ...settings, token });
      setSettings(saved);
      setToken('');
      setResult(await action());
    } catch (e) {
      setResult({ ok: false, message: (e as Error).message, conflicts: [], time: '' });
    } finally {
      setBusy(null);
    }
  };

  const help = TOKEN_HELP[settings.provider];
  const ready = settings.remoteUrl.trim() !== '' && (token !== '' || settings.tokenSet);

  return (
    <Modal title="Sync with GitHub or GitLab" onClose={onClose} wide>
      {isNative ? (
        <p className="modal-note">
          The vault is synced with a Git repository through the {settings.provider === 'gitlab' ? 'GitLab' : 'GitHub'} API,
          using an access token. Use the repository your other devices sync with, or create an empty private one. The
          token stays in the app's private storage on this phone.
        </p>
      ) : (
        <p className="modal-note">
          The vault is committed and synced to a Git repository over HTTPS using an access token. Create an empty private
          repository first. The token is stored in <code>.kbmd/sync.json</code> inside the vault, which is never uploaded.
        </p>
      )}
      <div className="form-grid">
        <label>Provider</label>
        <select value={settings.provider} onChange={(e) => set('provider', e.target.value as SyncSettings['provider'])}>
          <option value="github">GitHub</option>
          <option value="gitlab">GitLab</option>
        </select>

        <label>Repository</label>
        <input
          className="text-input"
          placeholder={`owner/repo  or  https://${settings.provider}.com/owner/repo.git`}
          value={settings.remoteUrl}
          onChange={(e) => set('remoteUrl', e.target.value)}
        />

        <label>Access token</label>
        <div>
          <input
            className="text-input"
            type="password"
            autoComplete="off"
            placeholder={settings.tokenSet ? 'Saved. Enter a new one to replace it' : 'Paste the token'}
            value={token}
            onChange={(e) => setToken(e.target.value)}
          />
          <div className="field-help">
            {help.text}{' '}
            <a href={help.url} target="_blank" rel="noopener noreferrer">Create one</a>
          </div>
        </div>

        <label>Branch</label>
        <input className="text-input" value={settings.branch} onChange={(e) => set('branch', e.target.value)} />

        <label>Commit author</label>
        <div className="form-row">
          <input className="text-input" placeholder="Name" value={settings.authorName} onChange={(e) => set('authorName', e.target.value)} />
          <input className="text-input" placeholder="Email" value={settings.authorEmail} onChange={(e) => set('authorEmail', e.target.value)} />
        </div>

        <label>Auto sync</label>
        <div className="form-row">
          <input
            className="text-input narrow"
            type="number"
            min={0}
            value={settings.autoSyncMinutes}
            onChange={(e) => set('autoSyncMinutes', Math.max(0, Number(e.target.value) || 0))}
          />
          <span className="field-help">minutes between automatic syncs (0 = only when you press Sync)</span>
        </div>
      </div>

      {result && (
        <div className={`sync-result ${result.ok ? 'ok' : 'failed'}`}>
          {result.message}
          {result.conflicts.length > 0 && (
            <ul>
              {result.conflicts.map((path) => (
                <li key={path}>{path}</li>
              ))}
            </ul>
          )}
        </div>
      )}

      <div className="modal-actions">
        <button disabled={busy !== null} onClick={() => run('save', async () => null)}>
          {busy === 'save' ? 'Saving…' : 'Save'}
        </button>
        <button disabled={busy !== null || !ready} onClick={() => run('test', api.syncTest)}>
          {busy === 'test' ? 'Testing…' : 'Test connection'}
        </button>
        <button className="primary" disabled={busy !== null || !ready} onClick={() => run('sync', onSync)}>
          {busy === 'sync' ? 'Syncing…' : 'Sync now'}
        </button>
      </div>
    </Modal>
  );
}
