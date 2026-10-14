import { useState, type FormEvent } from 'react';
import { request } from '../../shared/api/management';
import { resetCsrf } from '../../shared/api/csrf';
export type Role = 'ADMIN' | 'WAREHOUSE_OPERATOR' | 'SALES_OPERATOR';
export interface SessionUser { id: string; email: string; displayName: string; role: Role; active: boolean; mustChangePassword: boolean; version: number }

export function Login({ onLogin, notice }: { onLogin: (user: SessionUser) => void; notice: string }) {
  const [email, setEmail] = useState(''); const [password, setPassword] = useState(''); const [busy, setBusy] = useState(false); const [error, setError] = useState('');
  async function submit(event: FormEvent) {
    event.preventDefault(); if (busy) return; setBusy(true); setError('');
    try { const user = await request<SessionUser>('auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email, password }) }); resetCsrf(); onLogin(user); }
    catch (err) { setError((err as Error).message); }
    finally { setPassword(''); setBusy(false); }
  }
  return <main className="auth-shell"><section className="panel auth-card"><span className="brand-icon">OF</span><p className="eyebrow">OperationsFlow / Secure workspace</p><h1>Sign in</h1><p className="muted">Use your administrator-provided business account.</p>{notice && <p className="notice" role="status">{notice}</p>}{error && <p className="error" role="alert">{error}</p>}<form onSubmit={submit}><fieldset disabled={busy}><label>Email<input type="email" required maxLength={254} autoComplete="username" value={email} onChange={e => setEmail(e.target.value)} /></label><label>Password<input type="password" required maxLength={72} autoComplete="current-password" value={password} onChange={e => setPassword(e.target.value)} /></label><button className="primary">{busy ? 'Signing in…' : 'Sign in'}</button></fieldset></form><p className="muted">No public signup. Contact your administrator if you need an account or password reset.</p></section></main>;
}
export function ChangePassword({ required, onChanged, onCancel }: { required: boolean; onChanged: () => void; onCancel: () => void }) {
  const [currentPassword, setCurrent] = useState(''); const [newPassword, setNew] = useState(''); const [confirm, setConfirm] = useState(''); const [error, setError] = useState(''); const [busy, setBusy] = useState(false);
  async function submit(event: FormEvent) {
    event.preventDefault(); if (busy) return; if (newPassword !== confirm) { setError('The new passwords must match.'); return; } setBusy(true); setError('');
    try { await request<void>('auth/change-password', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ currentPassword, newPassword }) }); resetCsrf(); onChanged(); }
    catch (err) { setError((err as Error).message); }
    finally { setCurrent(''); setNew(''); setConfirm(''); setBusy(false); }
  }
  return <section className="panel auth-card"><h1>Change password</h1><p className="muted">{required ? 'Your administrator requires a new password before you access operations.' : 'Changing your password signs out all your existing sessions.'} Use at least 12 characters, up to 72 UTF-8 bytes.</p>{error && <p className="error" role="alert">{error}</p>}<form onSubmit={submit}><fieldset disabled={busy}><label>Current password<input required type="password" maxLength={72} autoComplete="current-password" value={currentPassword} onChange={e => setCurrent(e.target.value)} /></label><label>New password<input required type="password" minLength={12} maxLength={72} autoComplete="new-password" value={newPassword} onChange={e => setNew(e.target.value)} /></label><label>Confirm new password<input required type="password" minLength={12} maxLength={72} autoComplete="new-password" value={confirm} onChange={e => setConfirm(e.target.value)} /></label><button className="primary">Change password</button></fieldset></form>{!required && <button disabled={busy} onClick={onCancel}>Back to workspace</button>}</section>;
}
