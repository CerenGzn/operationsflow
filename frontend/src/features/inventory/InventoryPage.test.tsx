import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { InventoryPage } from './InventoryPage';

const product = { id: 'p1', name: 'Synthetic chair', status: 'ACTIVE', skus: [{ id: 's1', code: 'CHAIR-01' }] };
const warehouse = { id: 'w1', name: 'Synthetic Berlin', code: 'BER-01', status: 'ACTIVE' };
const balance = { warehouseId: 'w1', warehouseCode: 'BER-01', skuId: 's1', skuCode: 'CHAIR-01', productName: 'Synthetic chair', onHand: 10, reserved: 4, available: 6 };
const reservation = { id: 'r1', ownerReference: 'demo:order-1', state: 'ACTIVE', lines: [{ warehouseId: 'w1', skuId: 's1', quantity: 4 }], createdAt: '2026-10-14T12:00:00Z' };
const page = (content: unknown[]) => ({ content, page: 0, size: 20, totalElements: content.length, totalPages: content.length ? 1 : 0 });
const response = (body: unknown, status = 200) => ({ ok: status < 400, status, json: async () => body });
function defaults(url: string) {
  if (url.startsWith('/api/v1/products')) return response(page([product]));
  if (url.startsWith('/api/v1/warehouses')) return response(page([warehouse]));
  if (url.includes('/reservations')) return response(page([reservation]));
  return response(page([balance]));
}
async function fillReceipt() {
  await screen.findByRole('option', { name: 'BER-01 — Synthetic Berlin' });
  fireEvent.change(screen.getByLabelText('Warehouse'), { target: { value: 'w1' } });
  fireEvent.change(screen.getByLabelText('SKU'), { target: { value: 's1' } });
  fireEvent.change(screen.getByLabelText('Quantity'), { target: { value: '3' } });
  fireEvent.change(screen.getByLabelText('Reason'), { target: { value: 'Synthetic delivery' } });
}

describe('inventory workspace', () => {
  it('renders actual on-hand, reserved and available quantities', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async url => defaults(url)));
    render(<InventoryPage />);
    expect(screen.getByText('Loading balances…')).toBeInTheDocument();
    expect(await screen.findByRole('cell', { name: '10' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: '4' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: '6' })).toBeInTheDocument();
  });
  it('shows empty data and retries a failed inventory query', async () => {
    let failed = false;
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async url => {
      if (!url.includes('/inventory/')) return defaults(url);
      if (!failed) { failed = true; throw new TypeError('offline'); }
      return response(page([]));
    }));
    render(<InventoryPage />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Cannot reach the API');
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('No balances found')).toBeInTheDocument();
  });
  it('retries an ambiguous receipt with the same key and exact payload', async () => {
    let attempts = 0;
    const fetchMock = vi.fn().mockImplementation(async (url, options) => {
      if (options?.method === 'POST') { if (++attempts === 1) throw new TypeError('Lost response'); return response({ id: 'm1' }, 201); }
      return defaults(url);
    });
    vi.stubGlobal('fetch', fetchMock);
    render(<InventoryPage />); await fillReceipt();
    fireEvent.click(screen.getByRole('button', { name: 'Receive stock' }));
    await screen.findByRole('button', { name: 'Retry same request' });
    expect(screen.getByLabelText('Quantity')).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Retry same request' }));
    await screen.findByText('Stock operation recorded. Balances and history refreshed.');
    const posts = fetchMock.mock.calls.filter(([, opts]) => opts?.method === 'POST');
    expect(posts).toHaveLength(2);
    expect(posts[0][1].headers['Idempotency-Key']).toBe(posts[1][1].headers['Idempotency-Key']);
    expect(posts[0][1].body).toBe(posts[1][1].body);
    expect(JSON.parse(posts[0][1].body)).toEqual({ warehouseId: 'w1', skuId: 's1', quantity: 3, reason: 'Synthetic delivery' });
  });
  it('submits a signed adjustment and allows correction after a definite rejection', async () => {
    const fetchMock = vi.fn().mockImplementation(async (url, options) => options?.method === 'POST' ? response({ detail: 'Reserved stock prevents adjustment.' }, 409) : defaults(url));
    vi.stubGlobal('fetch', fetchMock); render(<InventoryPage />); await fillReceipt();
    fireEvent.change(screen.getByLabelText('Operation'), { target: { value: 'adjustments' } });
    fireEvent.change(screen.getByLabelText('Signed quantity change'), { target: { value: '-8' } });
    fireEvent.click(screen.getByRole('button', { name: 'Adjust stock' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Reserved stock prevents adjustment.');
    expect(screen.getByLabelText('Signed quantity change')).not.toBeDisabled();
    const post = fetchMock.mock.calls.find(([, opts]) => opts?.method === 'POST');
    expect(post?.[0]).toBe('/api/v1/inventory/adjustments'); expect(JSON.parse(post?.[1].body).delta).toBe(-8);
  });
  it('creates an explicitly owned reservation and confirms release before sending it', async () => {
    const fetchMock = vi.fn().mockImplementation(async (url, options) => options?.method === 'POST' ? response(reservation, 201) : defaults(url));
    vi.stubGlobal('fetch', fetchMock); render(<InventoryPage />); await fillReceipt();
    fireEvent.change(screen.getByLabelText('Operation'), { target: { value: 'reservations' } });
    fireEvent.change(screen.getByLabelText('Quantity'), { target: { value: '4' } });
    fireEvent.change(screen.getByLabelText('Owner reference'), { target: { value: 'demo:order-1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Reserve stock' }));
    await screen.findByText('Stock operation recorded. Balances and history refreshed.');
    const created = fetchMock.mock.calls.find(([, opts]) => opts?.method === 'POST');
    expect(JSON.parse(created?.[1].body)).toMatchObject({ ownerReference: 'demo:order-1', lines: [{ warehouseId: 'w1', skuId: 's1', quantity: 4 }] });
    fireEvent.click(screen.getByRole('button', { name: 'Reservations' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Release' }));
    expect(fetchMock.mock.calls.some(([url]) => url.endsWith('/release'))).toBe(false);
    fireEvent.change(screen.getByLabelText('Transition reason'), { target: { value: 'Synthetic cancellation' } });
    fireEvent.click(screen.getByRole('button', { name: 'Confirm release' }));
    await screen.findByText('Reservation released. On-hand stock is unchanged.');
    expect(fetchMock.mock.calls.find(([url]) => url.endsWith('/release'))?.[1].body).toBe('{"reason":"Synthetic cancellation"}');
  });
  it('shows movement provenance and sends filter parameters', async () => {
    const movement = { id: 'm1', warehouseCode: 'BER-01', skuCode: 'CHAIR-01', type: 'RECEIPT', onHandDelta: 10, reservedDelta: 0, onHandAfter: 10, reservedAfter: 0, actor: 'UNAUTHENTICATED', reason: 'Synthetic delivery', operationId: 'op1', createdAt: '2026-10-14T12:00:00Z' };
    const fetchMock = vi.fn().mockImplementation(async url => url.includes('/movements') ? response(page([movement])) : defaults(url));
    vi.stubGlobal('fetch', fetchMock); render(<InventoryPage />); await fillReceipt();
    fireEvent.click(screen.getByRole('button', { name: 'Movements' }));
    expect(await screen.findByText('Synthetic delivery')).toBeInTheDocument();
    expect(screen.getByText('Actor: UNAUTHENTICATED')).toBeInTheDocument();
    await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => url.includes('/movements?') && url.includes('warehouseId=w1') && url.includes('skuId=s1'))).toBe(true));
  });
  it('disables writes for inactive catalog references', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async url => url.startsWith('/api/v1/products') ? response(page([{ ...product, status: 'INACTIVE' }])) : defaults(url)));
    render(<InventoryPage />); await fillReceipt();
    expect(screen.getByRole('button', { name: 'Receive stock' })).toBeDisabled();
  });
});
