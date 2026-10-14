import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { FulfillmentPage, type Task } from './FulfillmentPage';
import { request, ApiError } from '../../shared/api/management';
vi.mock('../../shared/api/management', async importOriginal => ({ ...await importOriginal<typeof import('../../shared/api/management')>(), request: vi.fn() }));
const task: Task = { id: 'task-1', orderId: 'order-1', orderNumber: 'OF-101', warehouseId: 'warehouse-1', warehouseCode: 'BER-01', state: 'PENDING', version: 0, lines: [{ skuId: 'sku-1', skuCode: 'CHAIR-01', productName: 'Synthetic chair', quantity: 5, pickedQuantity: 0 }], packageRecord: null, shipment: null };
let current: Task; let status: string;
function setup() {
  vi.mocked(request).mockImplementation(async (path, options) => {
    if (options?.method) return current;
    if (path.startsWith('warehouses?')) return { content: [{ id: 'warehouse-1', code: 'BER-01', name: 'Synthetic warehouse' }], totalElements: 1, totalPages: 1 };
    if (path.startsWith('fulfillment/orders/')) return { orderId: 'order-1', orderNumber: 'OF-101', status, tasks: [current] };
    return { content: [current], totalElements: 1, totalPages: 1 };
  });
}
beforeEach(() => { current = structuredClone(task); status = 'RESERVED'; vi.mocked(request).mockReset(); setup(); });
async function open() { fireEvent.click(await screen.findByRole('button', { name: 'Open OF-101 BER-01' })); await screen.findByRole('region', { name: 'Fulfillment order details' }); }
describe('fulfillment workspace', () => {
  it('loads the live picking queue and confirms an absolute versioned quantity', async () => {
    render(<FulfillmentPage />); await open(); fireEvent.change(screen.getByLabelText('Picked CHAIR-01'), { target: { value: '3' } }); fireEvent.click(screen.getByRole('button', { name: 'Confirm CHAIR-01' }));
    await waitFor(() => expect(request).toHaveBeenCalledWith('fulfillment/tasks/task-1/picks/sku-1', expect.objectContaining({ method: 'PUT', body: JSON.stringify({ quantity: 3, version: 0 }) })));
  });
  it('rejects picked quantities exceeding the allocation before calling the API', async () => {
    render(<FulfillmentPage />); await open(); fireEvent.change(screen.getByLabelText('Picked CHAIR-01'), { target: { value: '6' } }); fireEvent.click(screen.getByRole('button', { name: 'Confirm CHAIR-01' })); expect(await screen.findByRole('alert')).toHaveTextContent('allocated quantity'); expect(vi.mocked(request).mock.calls.filter(c => c[1]?.method)).toHaveLength(0);
  });
  it('requires a second quantity verification before packing', async () => {
    current.state = 'PICKED'; current.version = 1; current.lines[0].pickedQuantity = 5; status = 'PROCESSING'; render(<FulfillmentPage />); await open();
    fireEvent.change(screen.getByLabelText('Verified CHAIR-01'), { target: { value: '4' } }); fireEvent.click(screen.getByRole('button', { name: 'Verify and pack' })); expect(await screen.findByRole('alert')).toHaveTextContent('full allocated quantity');
    fireEvent.change(screen.getByLabelText('Verified CHAIR-01'), { target: { value: '5' } }); fireEvent.click(screen.getByRole('button', { name: 'Verify and pack' })); await waitFor(() => expect(request).toHaveBeenCalledWith('fulfillment/tasks/task-1/pack', expect.objectContaining({ body: JSON.stringify({ version: 1, lines: [{ skuId: 'sku-1', quantity: 5 }] }) })));
  });
  it('prepares a shipment with carrier and tracking data', async () => {
    current.state = 'PACKED'; current.version = 2; current.packageRecord = { id: 'package-1', packedAt: '2026-10-14T12:00:00Z' }; status = 'PROCESSING'; render(<FulfillmentPage />); await open();
    fireEvent.change(screen.getByLabelText('Carrier'), { target: { value: 'Synthetic carrier' } }); fireEvent.change(screen.getByLabelText('Tracking reference'), { target: { value: 'TRACK-101' } }); fireEvent.click(screen.getByRole('button', { name: 'Create shipment' })); await waitFor(() => expect(request).toHaveBeenCalledWith('fulfillment/tasks/task-1/shipment', expect.objectContaining({ body: JSON.stringify({ version: 2, carrier: 'Synthetic carrier', trackingReference: 'TRACK-101' }) })));
  });
  it('requires explicit whole-order dispatch confirmation', async () => {
    current.state = 'READY_TO_SHIP'; status = 'READY_TO_SHIP'; render(<FulfillmentPage />); await open(); fireEvent.click(screen.getByRole('button', { name: 'Dispatch whole order' })); expect(screen.getByText(/can no longer be cancelled/)).toBeInTheDocument(); expect(vi.mocked(request).mock.calls.filter(c => c[1]?.method)).toHaveLength(0); fireEvent.click(screen.getByRole('button', { name: 'Confirm dispatch' })); await waitFor(() => expect(request).toHaveBeenCalledWith('fulfillment/orders/order-1/dispatch', { method: 'POST' }));
  });
  it('keeps sales users read-only while showing tracking information', async () => {
    current.state = 'READY_TO_SHIP'; status = 'READY_TO_SHIP'; current.shipment = { id: 'shipment-1', taskId: current.id, orderId: current.orderId, orderNumber: current.orderNumber, warehouseCode: current.warehouseCode, packageId: 'package-1', carrier: 'Synthetic carrier', trackingReference: 'TRACK-101', state: 'READY', createdAt: '2026-10-14T12:00:00Z', dispatchedAt: null };
    render(<FulfillmentPage readOnly />); await open(); expect(screen.getByText('TRACK-101')).toBeInTheDocument(); expect(screen.queryByRole('button', { name: 'Dispatch whole order' })).not.toBeInTheDocument(); expect(screen.queryByRole('button', { name: 'Confirm CHAIR-01' })).not.toBeInTheDocument();
  });
  it('shows conflicts without automatically resubmitting a mutation', async () => {
    render(<FulfillmentPage />); await open(); vi.mocked(request).mockRejectedValueOnce(new ApiError('Task changed', {}, 409)); fireEvent.change(screen.getByLabelText('Picked CHAIR-01'), { target: { value: '2' } }); fireEvent.click(screen.getByRole('button', { name: 'Confirm CHAIR-01' })); expect(await screen.findByRole('alert')).toHaveTextContent('Conflict: Task changed'); expect(vi.mocked(request).mock.calls.filter(c => c[1]?.method)).toHaveLength(1);
  });
  it('shows empty and unavailable API states', async () => {
    vi.mocked(request).mockResolvedValue({ content: [], totalElements: 0, totalPages: 0 }); const view = render(<FulfillmentPage />); expect(await screen.findByText('No fulfillment tasks found')).toBeInTheDocument(); view.unmount(); vi.mocked(request).mockRejectedValue(new Error('API unavailable')); render(<FulfillmentPage />); expect(await screen.findByRole('button', { name: 'Retry queue' })).toBeInTheDocument();
  });
});
