import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { App } from './App';
const user = { id: 'test', email: 'admin@example.test', displayName: 'Test Admin', role: 'ADMIN', active: true, mustChangePassword: false, version: 0 };
const ok = (body: unknown) => ({ ok: true, status: 200, json: async () => body });
function setup(role = 'ADMIN', system: unknown = ok({ application: 'OperationsFlow', apiVersion: 'v1' }), mustChangePassword = false) {
  const fetchMock = vi.fn(async (url: string) => {
    if (url.endsWith('auth/session')) return ok({ ...user, role, mustChangePassword });
    if (url.endsWith('/system')) { if (system instanceof Error) throw system; return system; }
    return ok({ content: [], totalElements: 0, totalPages: 0 });
  }); vi.stubGlobal('fetch', fetchMock); return fetchMock;
}
describe('authenticated workspace', () => {
  it('shows the API identity after checking the session', async () => { const fetchMock = setup(); render(<App />); expect(screen.getByRole('status')).toHaveTextContent('Checking'); expect(await screen.findByText('Connected to OperationsFlow API v1')).toBeInTheDocument(); expect(fetchMock).toHaveBeenCalledWith('/api/v1/system', { signal: expect.any(AbortSignal) }); });
  it.each([{ ok: false }, ok({ unexpected: true }), new TypeError('Network error')])('handles unavailable or invalid system responses', async response => { setup('ADMIN', response); render(<App />); expect(await screen.findByText(/Backend unavailable/)).toBeInTheDocument(); });
  it('requires login before loading operational data', async () => { const fetchMock = vi.fn().mockResolvedValue({ ok: false, status: 401, json: async () => ({ detail: 'Sign in required' }) }); vi.stubGlobal('fetch', fetchMock); render(<App />); expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument(); expect(fetchMock).toHaveBeenCalledTimes(1); });
  it.each(['WAREHOUSE_OPERATOR', 'SALES_OPERATOR'])('restricts navigation for %s', async role => { setup(role); render(<App />); await screen.findByRole('navigation'); expect(screen.queryByRole('button', { name: 'Users', exact: true })).not.toBeInTheDocument(); expect(screen.queryByRole('button', { name: 'Customers', exact: true }) !== null).toBe(role === 'SALES_OPERATOR'); expect(screen.queryByRole('button', { name: /New product/ })).not.toBeInTheDocument(); });
  it('blocks operations until a required password change', async () => { const fetchMock = setup('SALES_OPERATOR', undefined, true); render(<App />); expect(await screen.findByRole('heading', { name: 'Change password' })).toBeInTheDocument(); expect(screen.queryByRole('navigation')).not.toBeInTheDocument(); expect(fetchMock).toHaveBeenCalledTimes(1); });
  it('removes operational screens after session revocation', async () => { setup(); render(<App />); await screen.findByRole('navigation'); fireEvent(window, new Event('operationsflow:session-expired')); expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument(); expect(screen.getByText(/session expired or was revoked/)).toBeInTheDocument(); });
});
