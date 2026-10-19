vi.mock('../../shared/api/csrf', () => ({ csrfHeaders: async () => ({ 'X-CSRF-TOKEN': 'component-test-token' }), resetCsrf: vi.fn() }));
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { OrdersPage } from './OrdersPage';

const customer = { id: 'c1', referenceNumber: 'SYNTH-01', name: 'Synthetic business', status: 'ACTIVE' };
const product = { id: 'p1', name: 'Synthetic item', status: 'ACTIVE', skus: [{ id: 's1', code: 'SYNTH-SKU' }] };
const order = { id: 'o1', orderNumber: 'OF-0000000001', customerName: customer.name, status: 'RESERVED', totalUnits: 2, createdAt: '2026-01-01T00:00:00Z', customer: { ...customer, addressLine: 'Example street 1', city: 'Berlin', postalCode: '10115', countryCode: 'DE' }, lines: [{ skuId: 's1', skuCode: 'SYNTH-SKU', productName: 'Synthetic item', quantity: 2 }], allocations: [{ skuId: 's1', warehouseId: 'w1', warehouseCode: 'BER-01', quantity: 2 }], reservationId: 'r1', cancellationReason: null };
const page = (content: unknown[] = []) => ({ content, totalElements: content.length, totalPages: content.length ? 1 : 0 });
const response = (body: unknown, status = 200) => ({ ok: status < 400, status, json: async () => body });
function mockApi(mutation?: (url: string, options: RequestInit) => unknown) {
  const mock = vi.fn().mockImplementation(async (url: string, options?: RequestInit) => {
    if (options?.method === 'POST' && mutation) return mutation(url, options);
    if (url.endsWith('/timeline')) return response({ orderId: 'o1', status: 'RESERVED', milestones: [] });
    if (url.includes('/inventory/availability?')) return response({ skuId: 's1', totalAvailable: 8, balances: page([{ warehouseId: 'w1', warehouseCode: 'BER-01', onHand: 10, reserved: 2, available: 8 }]) });
    if (url.includes('/customers?')) return response(page([customer]));
    if (url.includes('/products?')) return response(page([product]));
    return response(url.includes('?') ? page([order]) : order);
  });
  vi.stubGlobal('fetch', mock); return mock;
}
async function draft() {
  fireEvent.click(screen.getByRole('button', { name: '+ New order' }));
  await screen.findByRole('option', { name: 'SYNTH-01 — Synthetic business' });
  fireEvent.change(screen.getByLabelText('Customer'), { target: { value: 'c1' } });
  fireEvent.change(screen.getByLabelText('SKU'), { target: { value: 's1' } });
  fireEvent.change(screen.getByLabelText('Quantity'), { target: { value: '2' } });
  fireEvent.click(screen.getByRole('button', { name: 'Add line' }));
}
afterEach(() => sessionStorage.clear());
describe('orders', () => {
  it('shows loading, connection errors, retry and an honest empty state', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValueOnce(new TypeError('offline')).mockResolvedValue(response(page())));
    render(<OrdersPage />); expect(screen.getByText('Loading orders…')).toBeInTheDocument();
    expect(await screen.findByRole('alert')).toHaveTextContent('Cannot reach the API');
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('No orders found')).toBeInTheDocument();
    expect(screen.getByText(/Pricing, currency, tax and monetary totals are not yet supported/)).toBeInTheDocument();
  });
  it('submits customer and SKU quantities with an idempotency key and no client totals', async () => {
    const mock = mockApi(() => response(order, 201)); render(<OrdersPage />); await draft();
    fireEvent.click(screen.getByRole('button', { name: 'Create & reserve order' }));
    await screen.findByText(/created. Stock reserved/);
    const write = mock.mock.calls.find(([, options]) => options?.method === 'POST');
    expect(write?.[0]).toBe('/api/v1/orders'); expect(JSON.parse(write?.[1].body)).toEqual({ customerId: 'c1', lines: [{ skuId: 's1', quantity: 2 }] });
    expect(new Headers(write?.[1].headers).get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/); expect(sessionStorage.length).toBe(0);
  });
  it('retains exactly the same key and payload across ambiguous failure and remount', async () => {
    let writes = 0;
    const mock = mockApi(() => { if (++writes === 1) throw new TypeError('lost response'); return response(order, 201); });
    const first = render(<OrdersPage />); await draft(); fireEvent.click(screen.getByRole('button', { name: 'Create & reserve order' }));
    await screen.findByRole('button', { name: 'Retry same order' }); await screen.findByRole('alert'); first.unmount();
    render(<OrdersPage />); fireEvent.click(screen.getByRole('button', { name: 'Retry same order' }));
    await screen.findByText(/created. Stock reserved/);
    const calls = mock.mock.calls.filter(([, options]) => options?.method === 'POST');
    expect(calls).toHaveLength(2); expect(calls[1][1].body).toBe(calls[0][1].body); expect(calls[1][1].headers).toEqual(calls[0][1].headers);
  });
  it('keeps entered lines after definite insufficient-stock rejection', async () => {
    mockApi(() => response({ detail: 'Insufficient available stock. No stock was reserved.' }, 409)); render(<OrdersPage />); await draft();
    fireEvent.click(screen.getByRole('button', { name: 'Create & reserve order' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Insufficient available stock');
    expect(screen.getByRole('button', { name: 'Remove SYNTH-SKU' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Retry same order' })).not.toBeInTheDocument(); expect(sessionStorage.length).toBe(0);
  });
  it('shows snapshots and allocation and requires cancellation confirmation with reason', async () => {
    const mock = mockApi(() => response({ ...order, status: 'CANCELLED', cancellationReason: 'Synthetic cancellation' })); render(<OrdersPage />);
    fireEvent.click(await screen.findByRole('button', { name: 'View OF-0000000001' }));
    expect(await screen.findByText('Shipping snapshot')).toBeInTheDocument(); expect(screen.getByText('BER-01')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Cancel order' })); expect(mock.mock.calls.filter(([, o]) => o?.method === 'POST')).toHaveLength(0);
    fireEvent.change(screen.getByLabelText('Cancellation reason'), { target: { value: 'Synthetic cancellation' } });
    fireEvent.click(screen.getByRole('button', { name: 'Confirm cancellation' }));
    await screen.findByText(/Order cancelled. Reserved stock released/);
    expect(mock.mock.calls.find(([url]) => url.endsWith('/cancel'))?.[1].body).toBe('{"reason":"Synthetic cancellation"}');
    expect(screen.queryByRole('button', { name: 'Cancel order' })).not.toBeInTheDocument();
  });
  it('previews live SKU availability without reserving stock', async () => {
    const mock = mockApi(); render(<OrdersPage />);
    fireEvent.click(screen.getByRole('button', { name: '+ New order' }));
    await screen.findByRole('option', { name: 'SYNTH-SKU — Synthetic item' });
    fireEvent.change(screen.getByLabelText('SKU'), { target: { value: 's1' } });
    expect(await screen.findByText('8 available units')).toBeInTheDocument();
    expect(screen.getByText('8 available · 2 reserved · 10 on-hand')).toBeInTheDocument();
    expect(screen.getByText(/adding a line does not reserve it/)).toBeInTheDocument();
    expect(mock.mock.calls.some(([url]) => url.includes('inventory/availability?skuId=s1'))).toBe(true);
    expect(mock.mock.calls.filter(([, o]) => o?.method === 'POST')).toHaveLength(0);
  });
  it('retries failed availability and displays a zero-stock result honestly', async () => {
    let attempts = 0;
    const mock = mockApi();
    const original = mock.getMockImplementation()!;
    mock.mockImplementation(async (url: string, options?: RequestInit) => {
      if (!url.includes('/inventory/availability?')) return original(url, options);
      if (++attempts === 1) throw new TypeError('offline');
      return response({ skuId: 's1', totalAvailable: 0, balances: page() });
    });
    render(<OrdersPage />); fireEvent.click(screen.getByRole('button', { name: '+ New order' }));
    await screen.findByRole('option', { name: 'SYNTH-SKU — Synthetic item' });
    fireEvent.change(screen.getByLabelText('SKU'), { target: { value: 's1' } });
    expect(await screen.findByRole('alert')).toHaveTextContent('Cannot reach the API');
    fireEvent.click(screen.getByRole('button', { name: 'Retry availability' }));
    expect(await screen.findByText('0 available units')).toBeInTheDocument();
    expect(screen.getByText('No recorded stock at active warehouses.')).toBeInTheDocument();
  });
  it('sends search and status filters to the API', async () => {
    const mock = mockApi(); render(<OrdersPage />); await screen.findByText('OF-0000000001');
    fireEvent.change(screen.getByLabelText('Search orders'), { target: { value: 'SYNTH' } }); fireEvent.click(screen.getByRole('button', { name: 'Search' }));
    fireEvent.change(screen.getByLabelText('Order status'), { target: { value: 'CANCELLED' } });
    await waitFor(() => expect(mock.mock.calls.at(-1)?.[0]).toContain('status=CANCELLED')); expect(mock.mock.calls.at(-1)?.[0]).toContain('query=SYNTH');
  });
});
