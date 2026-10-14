import { useEffect, useRef, useState, type FormEvent } from 'react';
import { ApiError, request, type ManagedRecord } from '../../shared/api/management';

type State = 'PENDING' | 'PICKING' | 'PICKED' | 'PACKED' | 'READY_TO_SHIP' | 'DISPATCHED' | 'CANCELLED';
interface Page<T> { content: T[]; totalElements: number; totalPages: number }
interface Line { skuId: string; skuCode: string; productName: string; quantity: number; pickedQuantity: number }
export interface Shipment { id: string; taskId: string; orderId: string; orderNumber: string; warehouseCode: string; packageId: string; carrier: string; trackingReference: string; state: string; createdAt: string; dispatchedAt: string | null }
export interface Task { id: string; orderId: string; orderNumber: string; warehouseId: string; warehouseCode: string; state: State; version: number; lines: Line[]; packageRecord: { id: string; packedAt: string } | null; shipment: Shipment | null }
interface Order { orderId: string; orderNumber: string; status: string; tasks: Task[] }
const taskStates: State[] = ['PENDING', 'PICKING', 'PICKED', 'PACKED', 'READY_TO_SHIP', 'DISPATCHED', 'CANCELLED'];
const label = (value: string) => value.replaceAll('_', ' ');

function TaskCard({ task, readOnly, busy, mutate }: { task: Task; readOnly: boolean; busy: boolean; mutate: (path: string, method: string, body?: unknown) => Promise<void> }) {
  const [quantities, setQuantities] = useState<Record<string, string>>(() => Object.fromEntries(task.lines.map(l => [l.skuId, String(l.pickedQuantity)])));
  const [verified, setVerified] = useState<Record<string, string>>({});
  const [carrier, setCarrier] = useState(''); const [tracking, setTracking] = useState(''); const [error, setError] = useState('');
  const picking = ['PENDING', 'PICKING', 'PICKED'].includes(task.state);
  async function pick(line: Line) {
    const value = Number(quantities[line.skuId]);
    if (quantities[line.skuId]?.trim() === '' || !Number.isSafeInteger(value) || value < 0 || value > line.quantity) { setError('Enter a whole picked quantity from zero to the allocated quantity.'); return; }
    setError(''); await mutate(`tasks/${task.id}/picks/${line.skuId}`, 'PUT', { quantity: value, version: task.version });
  }
  async function pack(event: FormEvent) {
    event.preventDefault(); setError('');
    if (task.lines.some(l => !verified[l.skuId]?.trim() || Number(verified[l.skuId]) !== l.quantity)) { setError('Verify the full allocated quantity for every item.'); return; }
    await mutate(`tasks/${task.id}/pack`, 'POST', { version: task.version, lines: task.lines.map(l => ({ skuId: l.skuId, quantity: Number(verified[l.skuId]) })) });
  }
  async function prepare(event: FormEvent) { event.preventDefault(); await mutate(`tasks/${task.id}/shipment`, 'POST', { version: task.version, carrier, trackingReference: tracking }); }
  return <article className="panel fulfillment-task" aria-label={`Warehouse ${task.warehouseCode}`}>
    <div className="panel-heading"><h3>{task.warehouseCode}</h3><span className="badge">{label(task.state)}</span></div>
    {error && <p className="error" role="alert">{error}</p>}
    <div className="table-scroll"><table><caption className="sr-only">Allocated items for {task.warehouseCode}</caption><thead><tr><th>Item</th><th>Allocated</th><th>Picked</th>{!readOnly && picking && <th>Confirm count</th>}</tr></thead><tbody>{task.lines.map(line => <tr key={line.skuId}><td><code>{line.skuCode}</code><small>{line.productName}</small></td><td>{line.quantity}</td><td>{line.pickedQuantity}</td>{!readOnly && picking && <td><div className="input-row"><input aria-label={`Picked ${line.skuCode}`} disabled={busy} type="number" min={0} max={line.quantity} step={1} value={quantities[line.skuId]} onChange={e => setQuantities(q => ({ ...q, [line.skuId]: e.target.value }))} /><button disabled={busy} onClick={() => void pick(line)} aria-label={`Confirm ${line.skuCode}`}>Confirm</button></div></td>}</tr>)}</tbody></table></div>
    {!readOnly && task.state === 'PICKED' && <form onSubmit={pack}><fieldset disabled={busy}><h4>Verify package contents</h4><p className="muted">Count every item again. One package contains this entire warehouse allocation.</p>{task.lines.map(line => <label key={line.skuId}>Verified {line.skuCode}<input required type="number" min={1} max={line.quantity} step={1} value={verified[line.skuId] ?? ''} onChange={e => setVerified(q => ({ ...q, [line.skuId]: e.target.value }))} /></label>)}<button className="primary">Verify and pack</button></fieldset></form>}
    {task.packageRecord && <div className="fulfillment-package"><strong>Verified package</strong><small className="identifier">{task.packageRecord.id}</small><small>Packed {new Date(task.packageRecord.packedAt).toLocaleString()}</small></div>}
    {!readOnly && task.state === 'PACKED' && <form onSubmit={prepare}><fieldset disabled={busy}><h4>Prepare shipment</h4><label>Carrier<input required maxLength={100} value={carrier} onChange={e => setCarrier(e.target.value)} /></label><label>Tracking reference<input required maxLength={160} value={tracking} onChange={e => setTracking(e.target.value)} /></label><p className="muted">Confirm these details before saving. Shipment details cannot be edited after preparation.</p><button className="primary">Create shipment</button></fieldset></form>}
    {task.shipment && <div className="fulfillment-package"><strong>{task.shipment.carrier} · {label(task.shipment.state)}</strong><p>Tracking: <code>{task.shipment.trackingReference}</code></p>{task.shipment.dispatchedAt && <small>Dispatched {new Date(task.shipment.dispatchedAt).toLocaleString()}</small>}</div>}
  </article>;
}

export function FulfillmentPage({ readOnly = false }: { readOnly?: boolean }) {
  const [tab, setTab] = useState<'tasks' | 'shipments'>('tasks'); const [state, setState] = useState(''); const [warehouse, setWarehouse] = useState('');
  const [query, setQuery] = useState(''); const [search, setSearch] = useState(''); const [page, setPage] = useState(0); const [revision, setRevision] = useState(0);
  const [data, setData] = useState<Page<Task | Shipment> | null>(null); const [warehouses, setWarehouses] = useState<ManagedRecord[]>([]); const [error, setError] = useState(''); const [referenceError, setReferenceError] = useState('');
  const [detail, setDetail] = useState<Order | null>(null); const [detailError, setDetailError] = useState(''); const [busy, setBusy] = useState(false); const [notice, setNotice] = useState(''); const [confirmDispatch, setConfirmDispatch] = useState(false);
  const inflight = useRef(false);
  useEffect(() => {
    const abort = new AbortController(); setReferenceError('');
    request<Page<ManagedRecord>>('warehouses?size=100', { signal: abort.signal }).then(result => { if (!abort.signal.aborted) setWarehouses(result.content); }).catch(e => { if (!abort.signal.aborted) setReferenceError(e.message); });
    return () => abort.abort();
  }, [revision]);
  useEffect(() => {
    const abort = new AbortController(); setData(null); setError('');
    const params = new URLSearchParams({ page: String(page), size: '10' }); if (warehouse) params.set('warehouseId', warehouse); if (state) params.set('state', state); if (tab === 'tasks') params.set('query', search);
    request<Page<Task | Shipment>>(`fulfillment/${tab}?${params}`, { signal: abort.signal }).then(result => { if (!Array.isArray(result.content)) throw new Error('Unexpected fulfillment response.'); if (!abort.signal.aborted) setData(result); }).catch(e => { if (!abort.signal.aborted) setError(e.message); });
    return () => abort.abort();
  }, [tab, state, warehouse, page, search, revision]);
  async function open(id: string) {
    if (inflight.current) return; inflight.current = true; setBusy(true); setDetailError(''); setConfirmDispatch(false);
    try { setDetail(await request<Order>(`fulfillment/orders/${id}`)); } catch (e) { setDetailError((e as Error).message); } finally { inflight.current = false; setBusy(false); }
  }
  async function mutate(path: string, method: string, body?: unknown) {
    if (!detail || inflight.current || readOnly) return; inflight.current = true; setBusy(true); setDetailError(''); setNotice('');
    try {
      await request(`fulfillment/${path}`, { method, ...(body === undefined ? {} : { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }) });
      setDetail(await request<Order>(`fulfillment/orders/${detail.orderId}`)); setRevision(r => r + 1); setConfirmDispatch(false); setNotice('Fulfillment updated. Stock is deducted only when the whole order is dispatched.');
    } catch (e) { setDetailError((e instanceof ApiError && e.status === 409 ? 'Conflict: ' : '') + (e as Error).message + ' Refresh order details before retrying.'); }
    finally { inflight.current = false; setBusy(false); }
  }
  return <section aria-labelledby="fulfillment-title">
    <div className="page-header"><div><p className="eyebrow">Warehouse operations / Fulfillment</p><h1 id="fulfillment-title">Fulfillment</h1><p className="muted">Prepare each warehouse independently. Dispatch complete orders with verified stock.</p></div><button disabled={busy} onClick={() => setRevision(r => r + 1)}>Refresh queue</button></div>
    {notice && <p className="notice" role="status">{notice}</p>}
    <div className="panel"><div className="inventory-tabs" role="group" aria-label="Fulfillment views">{(['tasks', 'shipments'] as const).map(t => <button key={t} aria-pressed={tab === t} onClick={() => { setTab(t); setState(''); setPage(0); }}>{t === 'tasks' ? 'Picking queue' : 'Shipment history'}</button>)}</div>
      <div className="toolbar"><label>Warehouse filter<select value={warehouse} onChange={e => { setWarehouse(e.target.value); setPage(0); }}><option value="">All warehouses</option>{warehouses.map(w => <option key={w.id} value={w.id}>{w.code} — {w.name}</option>)}</select></label><label>Fulfillment status<select value={state} onChange={e => { setState(e.target.value); setPage(0); }}><option value="">All states</option>{(tab === 'tasks' ? taskStates : ['READY', 'DISPATCHED', 'CANCELLED']).map(s => <option key={s} value={s}>{label(s)}</option>)}</select></label>{tab === 'tasks' && <form className="search" onSubmit={e => { e.preventDefault(); setSearch(query); setPage(0); }}><label>Order number<input maxLength={100} value={query} onChange={e => setQuery(e.target.value)} /></label><button>Search queue</button></form>}</div>
      {referenceError && <p className="error" role="alert">Warehouse filters unavailable: {referenceError}</p>}
      {error ? <p className="error" role="alert">{error}<button onClick={() => setRevision(r => r + 1)}>Retry queue</button></p> : !data ? <p className="empty">Loading fulfillment…</p> : <>{!data.content.length ? <div className="empty"><h3>{tab === 'tasks' ? 'No fulfillment tasks found' : 'No shipments found'}</h3><p>Records appear here as orders move through warehouse preparation.</p></div> : <div className="table-scroll"><table><thead><tr><th>Order</th><th>Warehouse</th><th>Status</th><th>{tab === 'tasks' ? 'Picking progress' : 'Carrier / tracking'}</th><th>Details</th></tr></thead><tbody>{data.content.map(row => <tr key={row.id}><td><strong>{row.orderNumber}</strong></td><td>{row.warehouseCode}</td><td><span className="badge">{label(row.state)}</span></td><td>{'lines' in row ? `${row.lines.reduce((s, l) => s + l.pickedQuantity, 0)} / ${row.lines.reduce((s, l) => s + l.quantity, 0)} units` : <>{row.carrier}<small>{row.trackingReference}</small>{row.dispatchedAt && <small>{new Date(row.dispatchedAt).toLocaleString()}</small>}</>}</td><td><button disabled={busy} aria-label={`Open ${row.orderNumber} ${row.warehouseCode}`} onClick={() => void open(row.orderId)}>Open order</button></td></tr>)}</tbody></table></div>}<div className="pagination"><span>{data.totalElements} records · Page {data.totalPages ? page + 1 : 0} of {data.totalPages}</span><div><button disabled={!page} onClick={() => setPage(p => p - 1)}>Previous</button><button disabled={page + 1 >= data.totalPages} onClick={() => setPage(p => p + 1)}>Next</button></div></div></>}
    </div>
    {detailError && <p className="error" role="alert">{detailError}{detail && <button disabled={busy} onClick={() => void open(detail.orderId)}>Refresh order details</button>}</p>}
    {detail && <section className="fulfillment-detail" aria-label="Fulfillment order details"><div className="page-header"><div><h2>{detail.orderNumber}</h2><span className="badge">{label(detail.status)}</span></div><div className="input-row"><button disabled={busy} onClick={() => void open(detail.orderId)}>Refresh order details</button><button disabled={busy} onClick={() => { setDetail(null); setConfirmDispatch(false); setDetailError(''); }}>Close details</button></div></div>
      <div className="notice">Each warehouse prepares its own allocation. All packages must be ready before this whole order can be dispatched. Picking and packing do not deduct stock.</div>
      <div className="fulfillment-grid">{detail.tasks.map(task => <TaskCard key={`${task.id}:${task.version}`} task={task} readOnly={readOnly} busy={busy} mutate={mutate} />)}</div>
      {!readOnly && detail.status === 'READY_TO_SHIP' && <div className="panel dispatch-panel">{!confirmDispatch ? <button className="primary" disabled={busy} onClick={() => setConfirmDispatch(true)}>Dispatch whole order</button> : <><h3>Confirm whole-order dispatch</h3><p>This dispatches every warehouse shipment and deducts all reserved stock. The order can no longer be cancelled.</p><button className="primary" disabled={busy} onClick={() => void mutate(`orders/${detail.orderId}/dispatch`, 'POST')}>Confirm dispatch</button><button disabled={busy} onClick={() => setConfirmDispatch(false)}>Keep preparing</button></>}</div>}
    </section>}
  </section>;
}
