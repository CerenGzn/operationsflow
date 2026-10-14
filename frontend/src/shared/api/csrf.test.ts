import { beforeEach, describe, expect, it, vi } from 'vitest';
import { resetCsrf } from './csrf';
import { request } from './management';
const ok = (body: unknown, status = 200) => ({ ok: true, status, json: async () => body });
beforeEach(resetCsrf);
describe('cookie and CSRF transport', () => {
  it('gets a session token before writes and sends cookies without persisting credentials', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(ok({ headerName: 'X-CSRF-TOKEN', token: 'masked-token' })).mockResolvedValueOnce(ok(null, 204)); vi.stubGlobal('fetch', fetchMock);
    await request('auth/logout', { method: 'POST' });
    expect(fetchMock.mock.calls[0]).toEqual(['/api/v1/auth/csrf', { credentials: 'same-origin', cache: 'no-store' }]);
    expect(new Headers(fetchMock.mock.calls[1][1].headers).get('X-CSRF-TOKEN')).toBe('masked-token');
    expect(fetchMock.mock.calls[1][1].credentials).toBe('same-origin');
  });
  it('never automatically retries rejected mutations and refreshes a stale CSRF token', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(ok({ headerName: 'X-CSRF-TOKEN', token: 'old' })).mockResolvedValueOnce({ ok: false, status: 403, json: async () => ({ code: 'CSRF_INVALID', detail: 'Refresh session' }) }).mockResolvedValueOnce(ok({ headerName: 'X-CSRF-TOKEN', token: 'new' })).mockResolvedValueOnce(ok(null, 204)); vi.stubGlobal('fetch', fetchMock);
    await expect(request('inventory/receipts', { method: 'POST' })).rejects.toThrow('Refresh session'); expect(fetchMock).toHaveBeenCalledTimes(2);
    await request('auth/logout', { method: 'POST' }); expect(new Headers(fetchMock.mock.calls[3][1].headers).get('X-CSRF-TOKEN')).toBe('new');
  });
  it('does not send mutations if obtaining CSRF fails', async () => { const fetchMock = vi.fn().mockResolvedValue({ ok: false }); vi.stubGlobal('fetch', fetchMock); await expect(request('orders', { method: 'POST' })).rejects.toThrow(); expect(fetchMock).toHaveBeenCalledTimes(1); });
});
