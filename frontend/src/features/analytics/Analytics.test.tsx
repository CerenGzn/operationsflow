import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { request } from '../../shared/api/management';
import { DashboardPage } from './DashboardPage';
import { ReportsPage } from './ReportsPage';
import { Chart } from './Chart';
import { exportCsv } from './exportCsv';
import { integer, validateRange, type DashboardData, type ReportData } from './types';
vi.mock('../../shared/api/management', async original => ({ ...await original<typeof import('../../shared/api/management')>(), request: vi.fn() }));
vi.mock('./exportCsv', () => ({ exportCsv: vi.fn() }));
const range = { from: '2026-03-29', to: '2026-03-29', zone: 'Europe/Berlin' };
function dashboard(): DashboardData {
  return { asOf: '2026-03-29T12:00:00Z', range, lowStockThreshold: 10,
    kpis: Object.fromEntries(['total_orders', 'open_orders', 'cancelled_orders', 'shipped_orders', 'awaiting_fulfillment', 'inventory_units', 'reserved_units', 'available_units', 'low_stock', 'active_warehouses', 'active_products', 'shipments_awaiting_dispatch'].map(k => [k, '0'])),
    orderVolume: [{ label: '2026-03-29', value: '2', secondary: '0' }], orderStatus: [], inventoryByWarehouse: [], movementTrends: [], shipmentThroughput: [], fulfillmentStatus: [], topSkus: [], recentActivity: [] };
}
function report(): ReportData { return { kind: 'orders', asOf: '2026-03-29T12:00:00Z', range, dateBasis: 'Created in selected range; current status', columns: [{ key: 'order_number', label: 'Order', numeric: false }], content: [{ order_number: 'OF-101' }], page: 0, size: 20, totalElements: 21, totalPages: 2 }; }
beforeEach(() => { vi.mocked(request).mockReset(); vi.mocked(exportCsv).mockReset().mockResolvedValue(undefined); });
function reportsSetup(data = report()) { vi.mocked(request).mockImplementation(async path => path.startsWith('warehouses?') ? { content: [], totalElements: 0 } : data); }
describe('operational dashboard', () => {
  it('shows loading then real KPI values and accessible chart tables without revenue', async () => {
    const data = dashboard(); data.kpis.total_orders = '9007199254740993'; vi.mocked(request).mockResolvedValue(data);
    render(<DashboardPage />); expect(screen.getByRole('status')).toHaveTextContent('Loading');
    expect(await screen.findByText(integer('9007199254740993'))).toBeInTheDocument();
    expect(screen.getByRole('img', { name: 'Order volume' })).toBeInTheDocument();
    expect(screen.getByText('View data table — Order volume')).toBeInTheDocument();
    expect(screen.getByText(/Orders are unpriced/)).toBeInTheDocument(); expect(screen.getByText('No operational activity in this period.')).toBeInTheDocument();
  });
  it('applies dates and threshold only on submission and refreshes applied filters', async () => {
    vi.mocked(request).mockResolvedValue(dashboard()); render(<DashboardPage />); await screen.findByText('Current operations');
    fireEvent.change(screen.getByLabelText('From date'), { target: { value: '2026-03-29' } }); fireEvent.change(screen.getByLabelText('To date'), { target: { value: '2026-03-30' } }); fireEvent.change(screen.getByLabelText('Low-stock threshold'), { target: { value: '5' } });
    expect(request).toHaveBeenCalledTimes(1); fireEvent.click(screen.getByRole('button', { name: 'Apply filters' }));
    await waitFor(() => expect(request).toHaveBeenLastCalledWith(expect.stringContaining('from=2026-03-29&to=2026-03-30&zone=Europe%2FBerlin&threshold=5'), expect.anything()));
    fireEvent.click(screen.getByRole('button', { name: 'Refresh dashboard' })); await waitFor(() => expect(request).toHaveBeenCalledTimes(3));
  });
  it('does not show restricted movement analytics when the server omits them', async () => {
    const data = dashboard(); data.movementTrends = null; vi.mocked(request).mockResolvedValue(data); render(<DashboardPage />); await screen.findByText('Current operations'); expect(screen.queryByText('Stock movement trends')).not.toBeInTheDocument();
  });
  it('shows an actionable error and retries without invented KPI values', async () => {
    vi.mocked(request).mockRejectedValueOnce(new Error('API unavailable')).mockResolvedValue(dashboard()); render(<DashboardPage />); expect(await screen.findByRole('alert')).toHaveTextContent('API unavailable'); expect(screen.queryByText('Total orders')).not.toBeInTheDocument(); fireEvent.click(screen.getByRole('button', { name: 'Retry dashboard' })); expect(await screen.findByText('Total orders')).toBeInTheDocument();
  });
  it('does not equate zero net movement with no activity', () => {
    render(<Chart title="Net movement" description="Signed deltas" data={[{ label: '2026-03-29', value: '0', secondary: '0' }]} primary="On hand" secondary="Reserved" trend />);
    expect(screen.getByText('All plotted values are zero for this selection.')).toBeInTheDocument(); expect(screen.queryByText('No activity for this selection.')).not.toBeInTheDocument();
  });
  it('renders signed net trends and retains precise values in the chart table', () => {
    render(<Chart title="Net changes" description="Signed units" data={[{ label: '2026-03-29', value: '-3', secondary: '2' }]} primary="On hand" secondary="Reserved" trend />);
    expect(screen.getByRole('img', { name: 'Net changes' })).toBeInTheDocument(); expect(screen.getByText('-3', { selector: 'td' })).toBeInTheDocument();
    expect(validateRange({ ...range, from: '2025-01-01' })).toBe(false); expect(validateRange(range)).toBe(true);
  });
});
describe('operational reports', () => {
  it('restricts dataset choices by role and disables dates for snapshot reports', async () => {
    reportsSetup(); render(<ReportsPage role="SALES_OPERATOR" />); await screen.findByText('OF-101');
    const select = screen.getByLabelText('Report dataset'); expect(within(select).queryByRole('option', { name: 'Audit' })).not.toBeInTheDocument(); expect(within(select).queryByRole('option', { name: 'Movements' })).not.toBeInTheDocument();
    fireEvent.change(select, { target: { value: 'inventory' } }); expect(screen.getByLabelText('From date')).toBeDisabled(); expect(screen.getByText(/Date filters do not apply/)).toBeInTheDocument();
  });
  it('paginates and exports all applied filters instead of the visible page or pending edits', async () => {
    reportsSetup(); render(<ReportsPage role="ADMIN" />); await screen.findByText('OF-101');
    fireEvent.change(screen.getByLabelText('Search report'), { target: { value: 'OF-1' } }); fireEvent.click(screen.getByRole('button', { name: 'Apply report filters' }));
    await waitFor(() => expect(request).toHaveBeenLastCalledWith(expect.stringContaining('query=OF-1'), expect.anything()));
    await screen.findByText('OF-101'); fireEvent.click(screen.getByRole('button', { name: 'Next' })); await waitFor(() => expect(request).toHaveBeenLastCalledWith(expect.stringContaining('page=1'), expect.anything())); await screen.findByText('OF-101');
    fireEvent.change(screen.getByLabelText('Search report'), { target: { value: 'Unapplied' } }); fireEvent.click(screen.getByRole('button', { name: 'Export filtered CSV' }));
    await screen.findByText(/CSV downloaded/); const [kind, filters] = vi.mocked(exportCsv).mock.calls[0]; expect(kind).toBe('orders'); expect(filters.get('query')).toBe('OF-1'); expect(filters.has('page')).toBe(false); expect(filters.has('size')).toBe(false);
  });
  it('shows export limits as an error without claiming a download', async () => {
    reportsSetup(); vi.mocked(exportCsv).mockRejectedValue(new Error('More than 5,000 rows match. Narrow the filters.')); render(<ReportsPage role="WAREHOUSE_OPERATOR" />); await screen.findByText('OF-101'); fireEvent.click(screen.getByRole('button', { name: 'Export filtered CSV' })); expect(await screen.findByRole('alert')).toHaveTextContent('5,000'); expect(screen.queryByText(/CSV downloaded/)).not.toBeInTheDocument();
  });
  it('renders empty results and report retry states', async () => {
    reportsSetup({ ...report(), content: [], totalElements: 0, totalPages: 0 }); const view = render(<ReportsPage role="ADMIN" />); expect(await screen.findByText('No records match the applied filters.')).toBeInTheDocument(); expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled(); view.unmount();
    vi.mocked(request).mockImplementation(async path => { if (path.startsWith('warehouses?')) return { content: [], totalElements: 0 }; throw new Error('Report unavailable'); }); render(<ReportsPage role="ADMIN" />); expect(await screen.findByRole('button', { name: 'Retry report' })).toBeInTheDocument();
  });
});
