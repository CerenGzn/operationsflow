import { afterEach, expect, it, vi } from 'vitest';
import { exportCsv } from './exportCsv';
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.useRealTimers(); });
it('downloads the server CSV using session cookies and cleans up the temporary URL', async () => {
  vi.useFakeTimers(); const create = vi.fn().mockReturnValue('blob:synthetic'); const revoke = vi.fn();
  Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: create }); Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: revoke });
  const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
  const fetch = vi.fn().mockResolvedValue(new Response('"Order"\r\n"OF-1"\r\n', { headers: { 'Content-Type': 'text/csv;charset=UTF-8' } })); vi.stubGlobal('fetch', fetch);
  await exportCsv('orders', new URLSearchParams({ status: 'SHIPPED' })); expect(fetch).toHaveBeenCalledWith('/api/v1/reports/orders/export?status=SHIPPED', { credentials: 'same-origin', cache: 'no-store' }); expect(click).toHaveBeenCalledOnce(); expect(document.querySelector('a[download]')).toBeNull(); await vi.runAllTimersAsync(); expect(revoke).toHaveBeenCalledWith('blob:synthetic');
});
it('signals an expired session and never downloads an authentication response', async () => {
  const listener = vi.fn(); window.addEventListener('operationsflow:session-expired', listener);
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ detail: 'Session expired' }), { status: 401, headers: { 'Content-Type': 'application/problem+json' } })));
  try { await expect(exportCsv('orders', new URLSearchParams())).rejects.toThrow('Session expired'); expect(listener).toHaveBeenCalledOnce(); } finally { window.removeEventListener('operationsflow:session-expired', listener); }
});
it('rejects non-CSV success responses', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('<html>Login</html>', { headers: { 'Content-Type': 'text/html' } })));
  await expect(exportCsv('orders', new URLSearchParams())).rejects.toThrow('Unexpected export response');
});
