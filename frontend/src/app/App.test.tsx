import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { App } from './App';
const user = { id: 'test', email: 'admin@example.test', displayName: 'Test Admin', role: 'ADMIN', active: true, mustChangePassword: false, version: 0 };
const ok = (body: unknown) => ({ ok: true, status: 200, json: async () => body });
function setup(role = 'ADMIN', system: unknown = ok({ application: 'OperationsFlow', apiVersion: 'v1' }), mustChangePassword = false) {
  const fetchMock = vi.fn(async (url: string) => {
    if (url.endsWith('auth/session')) return ok({ ...user, role, mustChangePassword });
    if (url.includes('/reports/')) return ok({ kind: 'orders', columns: [], content: [], totalElements: 0, totalPages: 0, page: 0, size: 20, asOf: '2026-10-18T12:00:00Z', range: { from: '2026-10-01', to: '2026-10-18', zone: 'UTC' }, dateBasis: 'Current status' });
    if (url.endsWith('/system')) { if (system instanceof Error) throw system; return system; }
    return ok({ content: [], totalElements: 0, totalPages: 0 });
  }); vi.stubGlobal('fetch', fetchMock); return fetchMock;
}
describe('authenticated workspace', () => {
  it('shows the API identity after checking the session', async () => { const fetchMock = setup(); render(<App />); expect(screen.getByRole('status')).toHaveTextContent('Checking'); expect(await screen.findByText('Connected to OperationsFlow API v1')).toBeInTheDocument(); expect(fetchMock).toHaveBeenCalledWith('/api/v1/system', { signal: expect.any(AbortSignal) }); });
  it.each([{ ok: false }, ok({ unexpected: true }), new TypeError('Network error')])('handles unavailable or invalid system responses', async response => { setup('ADMIN', response); render(<App />); expect(await screen.findByText(/Backend unavailable/)).toBeInTheDocument(); });
  it('requires login before loading operational data', async () => { const fetchMock = vi.fn().mockResolvedValue({ ok: false, status: 401, json: async () => ({ detail: 'Sign in required' }) }); vi.stubGlobal('fetch', fetchMock); render(<App />); expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument(); expect(fetchMock).toHaveBeenCalledTimes(1); });
  it.each(['WAREHOUSE_OPERATOR', 'SALES_OPERATOR'])('restricts navigation for %s', async role => { setup(role); render(<App />); await screen.findByRole('navigation'); expect(screen.queryByRole('button', { name: 'Users' })).not.toBeInTheDocument(); expect(screen.queryByRole('button', { name: 'Customers' }) !== null).toBe(role === 'SALES_OPERATOR'); expect(screen.queryByRole('button', { name: /New product/ })).not.toBeInTheDocument(); });
  it('blocks operations until a required password change', async () => { const fetchMock = setup('SALES_OPERATOR', undefined, true); render(<App />); expect(await screen.findByRole('heading', { name: 'Change password' })).toBeInTheDocument(); expect(screen.queryByRole('navigation')).not.toBeInTheDocument(); expect(fetchMock).toHaveBeenCalledTimes(1); });
  it('removes operational screens after session revocation', async () => { setup(); render(<App />); await screen.findByRole('navigation'); fireEvent(window, new Event('operationsflow:session-expired')); expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument(); expect(screen.getByText(/session expired or was revoked/)).toBeInTheDocument(); });
});

describe('demo viewer workspace', () => {
  it('uses field-limited reports for orders and fulfillment without requesting raw details', async () => {
    const fetchMock = setup('DEMO_VIEWER'); render(<App />);
    expect(await screen.findByText(/Read-only demo/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Users' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Customers' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Orders' }));
    expect(await screen.findByRole('heading', { name: 'Order status' })).toBeInTheDocument();
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('/reports/orders?'), expect.anything()));
    expect(screen.queryByRole('button', { name: /Export|Create order|Cancel order/ })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Fulfillment' }));
    expect(await screen.findByRole('heading', { name: 'Fulfillment status' })).toBeInTheDocument();
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('/reports/fulfillment?'), expect.anything()));
    expect(fetchMock.mock.calls.some(([url]) => /\/api\/v1\/(orders|fulfillment)(\?|\/|$)/.test(url))).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: 'Reports' }));
    expect(await screen.findByRole('heading', { name: 'Operational reports' })).toBeInTheDocument();
    expect(screen.getAllByRole('option').some(option => option.textContent === 'Shipments' || option.textContent === 'Audit')).toBe(false);
  });
  it('keeps catalog and inventory screens read-only and allows personal password change', async () => {
    setup('DEMO_VIEWER'); render(<App />); await screen.findByRole('navigation');
    for (const section of ['Products', 'Warehouses', 'Inventory']) {
      fireEvent.click(screen.getByRole('button', { name: section }));
      expect(screen.queryByRole('button', { name: /New product|New warehouse|Receive stock|Adjust stock|Reserve stock/ })).not.toBeInTheDocument();
    }
    expect(screen.queryByRole('button', { name: 'Reservations' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Change password' }));
    expect(await screen.findByRole('heading', { name: 'Change password' })).toBeInTheDocument();
  });
});
