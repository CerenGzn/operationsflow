import { useEffect, useState, type FormEvent } from 'react';
import { request } from '../../shared/api/management';
import { Chart } from './Chart';
import { DateControls } from './DateControls';
import { initialRange, integer, validateRange, type DashboardData } from './types';
const metrics = [
  ['total_orders', 'Total orders', 'All retained orders, including cancelled and shipped'],
  ['open_orders', 'Open orders', 'Reserved, processing or ready to ship'],
  ['cancelled_orders', 'Cancelled orders', 'Current cancelled state'],
  ['shipped_orders', 'Shipped orders', 'Current shipped state'],
  ['awaiting_fulfillment', 'Awaiting fulfillment', 'Reserved orders before warehouse work begins'],
  ['inventory_units', 'Inventory units', 'Current on-hand units across recorded balances'],
  ['reserved_units', 'Reserved units', 'Units held by active reservations'],
  ['available_units', 'Available units', 'On hand minus reserved'],
  ['low_stock', 'Low-stock combinations', 'Established active SKU/warehouse pairs at or below the threshold'],
  ['active_warehouses', 'Active warehouses', 'Current active warehouse records'],
  ['active_products', 'Active products', 'Current active products, not individual SKUs'],
  ['shipments_awaiting_dispatch', 'Awaiting dispatch', 'Prepared shipments in READY state'],
];
export function DashboardPage() {
  const [draft, setDraft] = useState(initialRange); const [range, setRange] = useState(draft); const [threshold, setThreshold] = useState('10'); const [appliedThreshold, setAppliedThreshold] = useState('10');
  const [revision, setRevision] = useState(0); const [data, setData] = useState<DashboardData | null>(null); const [error, setError] = useState(''); const [validation, setValidation] = useState('');
  useEffect(() => {
    const abort = new AbortController(); setData(null); setError('');
    request<DashboardData>(`analytics/dashboard?${new URLSearchParams({ ...range, threshold: appliedThreshold })}`, { signal: abort.signal }).then(result => {
      if (!result.kpis || !metrics.every(([key]) => typeof result.kpis[key] === 'string') || !Array.isArray(result.orderVolume)) throw new Error('The API returned an unexpected dashboard response.');
      if (!abort.signal.aborted) setData(result);
    }).catch(e => { if (!abort.signal.aborted) setError(e.message); }); return () => abort.abort();
  }, [range, appliedThreshold, revision]);
  function apply(event: FormEvent) { event.preventDefault(); const amount = Number(threshold); if (!validateRange(draft) || threshold.trim() === '' || !Number.isInteger(amount) || amount < 0 || amount > 1000000) { setValidation('Choose 1–366 days and a whole low-stock threshold from 0 to 1,000,000.'); return; } setValidation(''); setRange({ ...draft }); setAppliedThreshold(String(amount)); }
  return <section aria-labelledby="dashboard-title">
    <div className="page-header"><div><p className="eyebrow">Operations intelligence</p><h1 id="dashboard-title">Operational overview</h1><p className="muted">Orders, stock and fulfillment — grounded in your live business records.</p></div><button onClick={() => setRevision(r => r + 1)}>Refresh dashboard</button></div>
    <form className="panel analytics-filters" onSubmit={apply}><DateControls value={draft} onChange={setDraft} /><label>Low-stock threshold<input type="number" required min={0} max={1000000} step={1} value={threshold} onChange={e => setThreshold(e.target.value)} /></label><button className="primary">Apply filters</button></form>
    {validation && <p role="alert" className="error">{validation}</p>}
    {error ? <div className="error" role="alert">{error}<button onClick={() => setRevision(r => r + 1)}>Retry dashboard</button></div> : !data ? <div className="panel empty" role="status">Loading operational analytics…</div> : <>
      <div className="analytics-section-heading"><div><h2>Current operations</h2><p className="muted">Current / all-time totals. The activity date range does not filter these cards.</p></div><small>Snapshot {new Date(data.asOf).toLocaleString(undefined, { timeZone: data.range.zone })} · {data.range.zone}</small></div>
      <div className="kpi-grid">{metrics.map(([key, name, definition]) => <article className={`panel kpi-card ${key === 'low_stock' ? 'kpi-alert' : ''}`} key={key}><h3>{name}</h3><strong>{integer(data.kpis[key])}</strong><p>{key === 'low_stock' ? `${definition} (${data.lowStockThreshold} units)` : definition}</p></article>)}</div>
      <p className="analytics-note">Low-stock alerts cover established active SKU/warehouse balances only. Orders are unpriced; no revenue is inferred.</p>
      <div className="analytics-section-heading"><div><h2>Activity and trends</h2><p className="muted">{data.range.from} through {data.range.to}, inclusive · {data.range.zone}</p></div></div>
      <div className="analytics-charts">
        <Chart title="Order volume" description="Orders created each day, including subsequently cancelled orders." data={data.orderVolume} primary="Orders" trend />
        <Chart title="Order status distribution" description="Current status of orders created in the selected period." data={data.orderStatus} primary="Orders" />
        <Chart title="Inventory by warehouse" description="Current stock, independent of the date range. Top 12 warehouses plus Other." data={data.inventoryByWarehouse} primary="On hand" secondary="Reserved" />
        {data.movementTrends !== null && <Chart title="Stock movement trends" description="Daily signed net deltas. Zero net change does not mean no operations." data={data.movementTrends} primary="On-hand change" secondary="Reserved change" trend />}
        <Chart title="Shipment throughput" description="Shipments dispatched each day, based on dispatch time." data={data.shipmentThroughput} primary="Dispatched shipments" trend />
        <Chart title="Fulfillment status" description="Current state of warehouse tasks created in the selected period." data={data.fulfillmentStatus} primary="Tasks" />
        <Chart title="Frequently ordered SKUs" description="Top 10 by non-cancelled order frequency. Ordered units appear in the data table." data={data.topSkus} primary="Orders" secondary="Ordered units" secondaryPlot={false} />
      </div>
      <section className="panel recent-activity"><div className="panel-heading"><h2>Recent operational activity</h2><span className="count">Latest 10 within period</span></div>{!data.recentActivity.length ? <p className="empty">No operational activity in this period.</p> : <ol>{data.recentActivity.map((event, i) => <li key={`${event.occurredAt}:${event.type}:${i}`}><span className="activity-dot" /><div><strong>{event.type.replaceAll('_', ' ')}</strong><p>{event.reference}</p></div><time dateTime={event.occurredAt}>{new Date(event.occurredAt).toLocaleString(undefined, { timeZone: data.range.zone })}</time></li>)}</ol>}</section>
    </>}
  </section>;
}
