import { useEffect, useRef, useState, type FormEvent } from 'react';
import { ApiError, request, type ManagedRecord } from '../../shared/api/management';

type Tab = 'balances' | 'movements' | 'reservations';
type Operation = 'receipts' | 'adjustments' | 'reservations';
interface Page<T> { content: T[]; page: number; size: number; totalElements: number; totalPages: number }
interface Balance { warehouseId: string; warehouseCode: string; skuId: string; skuCode: string; productName: string; onHand: number; reserved: number; available: number }
interface Movement { id: string; warehouseCode: string; skuCode: string; type: string; onHandDelta: number; reservedDelta: number; onHandAfter: number; reservedAfter: number; reason: string; actor: string; operationId: string; createdAt: string }
interface Reservation { id: string; ownerReference: string; orderId?: string | null; state: 'ACTIVE' | 'RELEASED' | 'CONSUMED'; lines: { warehouseId: string; skuId: string; quantity: number }[]; createdAt: string }
interface Pending { path: string; key: string; body: string }
const labels: Record<Operation, string> = { receipts: 'Receive stock', adjustments: 'Adjust stock', reservations: 'Reserve stock' };
const number = (value: number) => value.toLocaleString();

export function InventoryPage({ readOnly = false }: { readOnly?: boolean }) {
  const [tab, setTab] = useState<Tab>('balances');
  const [warehouseId, setWarehouseId] = useState('');
  const [skuId, setSkuId] = useState('');
  const [state, setState] = useState('');
  const [page, setPage] = useState(0);
  const [revision, setRevision] = useState(0);
  const [data, setData] = useState<(Page<Balance | Movement | Reservation> & { source: Tab }) | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [products, setProducts] = useState<ManagedRecord[]>([]);
  const [warehouses, setWarehouses] = useState<ManagedRecord[]>([]);
  const [productSearch, setProductSearch] = useState('');
  const [warehouseSearch, setWarehouseSearch] = useState('');
  const [referenceQuery, setReferenceQuery] = useState({ products: '', warehouses: '' });
  const [referenceTotal, setReferenceTotal] = useState(0);
  const [referenceError, setReferenceError] = useState('');
  const [referencesLoading, setReferencesLoading] = useState(true);
  const [operation, setOperation] = useState<Operation>('receipts');
  const [quantity, setQuantity] = useState('');
  const [reason, setReason] = useState('');
  const [owner, setOwner] = useState('');
  const [busy, setBusy] = useState(false);
  const [mutationError, setMutationError] = useState('');
  const [pending, setPending] = useState<Pending | null>(null);
  const [terminal, setTerminal] = useState<{ reservation: Reservation; action: 'release' | 'consume' } | null>(null);
  const [terminalReason, setTerminalReason] = useState('');
  // Ref closes the click-to-render gap so double submits cannot create two keys.
  const inflight = useRef(false);

  useEffect(() => {
    const controller = new AbortController(); setReferencesLoading(true); setReferenceError('');
    const productParams = new URLSearchParams({ size: '100', query: referenceQuery.products });
    const warehouseParams = new URLSearchParams({ size: '100', query: referenceQuery.warehouses });
    Promise.all([request<Page<ManagedRecord>>(`products?${productParams}`, { signal: controller.signal }), request<Page<ManagedRecord>>(`warehouses?${warehouseParams}`, { signal: controller.signal })])
      .then(([p, w]) => {
        if (!Array.isArray(p.content) || !Array.isArray(w.content)) throw new Error('Unexpected catalog response.');
        if (!controller.signal.aborted) { setProducts(p.content); setWarehouses(w.content); setReferenceTotal(Math.max(p.totalElements, w.totalElements)); }
      }).catch(err => { if (!controller.signal.aborted) setReferenceError(err.message); })
      .finally(() => { if (!controller.signal.aborted) setReferencesLoading(false); });
    return () => controller.abort();
  }, [referenceQuery, revision]);

  useEffect(() => {
    const controller = new AbortController(); setLoading(true); setError(''); setData(null);
    const params = new URLSearchParams({ page: String(page), size: '20' });
    if (tab === 'reservations') { if (state) params.set('state', state); }
    else { if (warehouseId) params.set('warehouseId', warehouseId); if (skuId) params.set('skuId', skuId); }
    request<Page<Balance | Movement | Reservation>>(`inventory/${tab}?${params}`, { signal: controller.signal })
      .then(result => { if (!Array.isArray(result.content)) throw new Error('Unexpected inventory response.'); if (!controller.signal.aborted) setData({ ...result, source: tab }); })
      .catch(err => { if (!controller.signal.aborted) setError(err.message); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [tab, warehouseId, skuId, state, page, revision]);

  const skus = products.flatMap(p => (p.skus ?? []).map(s => ({ ...s, product: p.name, status: p.status })));
  const canWrite = warehouses.some(w => w.id === warehouseId && w.status === 'ACTIVE') && skus.some(s => s.id === skuId && s.status === 'ACTIVE');
  async function send(attempt: Pending) {
    if (inflight.current) return;
    inflight.current = true; setBusy(true); setPending(attempt); setMutationError(''); setNotice('');
    try {
      await request(`inventory/${attempt.path}`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': attempt.key }, body: attempt.body });
      setPending(null); setQuantity(''); setReason(''); setOwner(''); setNotice('Stock operation recorded. Balances and history refreshed.'); setRevision(r => r + 1);
    } catch (err) {
      setMutationError((err as Error).message);
      // A definite client rejection did not apply this request. Ambiguous failures keep the exact key and payload.
      if (err instanceof ApiError && err.status && err.status >= 400 && err.status < 500) setPending(null);
    } finally { inflight.current = false; setBusy(false); }
  }
  function submit(event: FormEvent) {
    event.preventDefault(); if (pending || inflight.current) return;
    const amount = Number(quantity);
    if (!Number.isSafeInteger(amount) || amount === 0 || Math.abs(amount) > 1_000_000_000 || (operation !== 'adjustments' && amount < 0)) {
      setMutationError('Enter a nonzero whole quantity within the allowed range.'); return;
    }
    const body = operation === 'reservations' ? { ownerReference: owner, lines: [{ warehouseId, skuId, quantity: amount }], reason }
      : { warehouseId, skuId, [operation === 'receipts' ? 'quantity' : 'delta']: amount, reason };
    void send({ path: operation, key: crypto.randomUUID(), body: JSON.stringify(body) });
  }
  async function finish(event: FormEvent) {
    event.preventDefault(); if (!terminal || inflight.current) return;
    inflight.current = true; setBusy(true); setMutationError(''); setNotice('');
    try {
      await request(`inventory/reservations/${terminal.reservation.id}/${terminal.action}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ reason: terminalReason }) });
      setNotice(terminal.action === 'release' ? 'Reservation released. On-hand stock is unchanged.' : 'Reservation consumed. On-hand and reserved stock were deducted.');
      setTerminal(null); setTerminalReason(''); setRevision(r => r + 1);
    } catch (err) { setMutationError((err as Error).message); }
    finally { inflight.current = false; setBusy(false); }
  }
  function changeTab(next: Tab) { if (next === tab) return; setData(null); setLoading(true); setError(''); setTab(next); setPage(0); }
  const skuLabel = (id: string) => skus.find(s => s.id === id)?.code ?? id;
  const warehouseLabel = (id: string) => warehouses.find(w => w.id === id)?.code ?? id;

  const currentData = data?.source === tab ? data : null;

  return <section aria-labelledby="inventory-title">
    <div className="page-header"><div><p className="eyebrow">Workspace / Inventory</p><h1 id="inventory-title">Inventory</h1><p className="muted">Accurate stock, explicit reservations, and a traceable movement history.</p></div><button disabled={busy} onClick={() => setRevision(r => r + 1)}>Refresh inventory</button></div>
    {notice && <p className="notice" aria-live="polite">{notice}</p>}
    <div className="inventory-layout">
      <div className="panel records-panel">
        <div className="inventory-tabs" role="group" aria-label="Inventory views">{((readOnly ? ['balances'] : ['balances', 'movements', 'reservations']) as Tab[]).map(t => <button key={t} aria-pressed={tab === t} className={tab === t ? 'primary' : ''} onClick={() => changeTab(t)}>{t.charAt(0).toUpperCase() + t.slice(1)}</button>)}</div>
        <div className="toolbar">
          {tab === 'reservations' ? <label>Reservation state<select value={state} onChange={e => { setState(e.target.value); setPage(0); }}><option value="">All states</option><option>ACTIVE</option><option>RELEASED</option><option>CONSUMED</option></select></label> : <span className="muted">Balances and movements follow the warehouse/SKU selectors. Available = on-hand − reserved.</span>}
          {currentData && <span className="count">{currentData.totalElements} records</span>}
        </div>
        {error && <div role="alert" className="error">{error}<button onClick={() => setRevision(r => r + 1)}>Retry</button></div>}
        {loading ? <div className="empty" aria-live="polite">Loading {tab}…</div> : currentData && <>
          {currentData.content.length === 0 ? <div className="empty"><h3>No {tab} found</h3><p>{tab === 'balances' ? 'Select a SKU and warehouse to receive the first stock. Missing balances represent zero stock.' : 'Recorded operations will appear here.'}</p></div> :
          <div className="table-scroll"><table><caption className="sr-only">Inventory {tab}</caption>
            {tab === 'balances' && <><thead><tr><th>SKU / product</th><th>Warehouse</th><th>On-hand</th><th>Reserved</th><th>Available</th></tr></thead><tbody>{(currentData.content as Balance[]).map(b => <tr key={`${b.warehouseId}-${b.skuId}`}><td><code>{b.skuCode}</code><small>{b.productName}</small></td><td>{b.warehouseCode}</td><td>{number(b.onHand)}</td><td>{number(b.reserved)}</td><td><strong className={b.available === 0 ? 'stock-zero' : 'stock-available'}>{number(b.available)}</strong></td></tr>)}</tbody></>}
            {tab === 'movements' && <><thead><tr><th>Movement</th><th>Stock location</th><th>Δ on-hand / reserved</th><th>Reason / trace</th></tr></thead><tbody>{(currentData.content as Movement[]).map(m => <tr key={m.id}><td><strong>{m.type}</strong><small>{new Date(m.createdAt).toLocaleString()}</small></td><td><code>{m.skuCode}</code><small>{m.warehouseCode}</small></td><td>{m.onHandDelta > 0 ? '+' : ''}{number(m.onHandDelta)} / {m.reservedDelta > 0 ? '+' : ''}{number(m.reservedDelta)}<small>After: {number(m.onHandAfter)} / {number(m.reservedAfter)}</small></td><td>{m.reason}<details><summary>Audit details</summary><small>Actor: {m.actor}</small><small className="identifier">Operation: {m.operationId}</small><small className="identifier">Movement: {m.id}</small></details></td></tr>)}</tbody></>}
            {tab === 'reservations' && <><thead><tr><th>Owner / reference</th><th>Reserved lines</th><th>State</th><th>Actions</th></tr></thead><tbody>{(currentData.content as Reservation[]).map(r => <tr key={r.id}><td><strong>{r.ownerReference}</strong><small className="identifier">{r.id}</small></td><td>{r.lines.map(l => <small key={`${l.warehouseId}-${l.skuId}`}>{skuLabel(l.skuId)} · {warehouseLabel(l.warehouseId)} · {number(l.quantity)} units</small>)}</td><td><span className={`badge ${r.state === 'ACTIVE' ? 'active' : 'inactive'}`}>{r.state}</span></td><td>{r.orderId && <small>Managed through Orders</small>}{r.state === 'ACTIVE' && !r.orderId && <div className="reservation-actions"><button disabled={busy || Boolean(pending)} onClick={() => { setTerminal({ reservation: r, action: 'release' }); setTerminalReason(''); setMutationError(''); }}>Release</button><button disabled={busy || Boolean(pending)} onClick={() => { setTerminal({ reservation: r, action: 'consume' }); setTerminalReason(''); setMutationError(''); }}>Consume</button></div>}</td></tr>)}</tbody></>}
          </table></div>}
          <div className="pagination"><span>Page {currentData.totalPages === 0 ? 0 : page + 1} of {currentData.totalPages}</span><div><button disabled={page === 0} onClick={() => setPage(p => p - 1)}>Previous</button><button disabled={page + 1 >= currentData.totalPages} onClick={() => setPage(p => p + 1)}>Next</button></div></div>
        </>}
      </div>
      <aside className="panel editor inventory-editor" aria-label="Stock operation">
        <div className="panel-heading"><h2>{readOnly ? 'Stock filters' : 'Stock operations'}</h2></div>
        <form onSubmit={e => { e.preventDefault(); setReferenceQuery({ products: productSearch, warehouses: warehouseSearch }); setWarehouseId(''); setSkuId(''); setPage(0); }}>
          <label>Find products<input disabled={busy || Boolean(pending)} value={productSearch} maxLength={100} placeholder="Product name or SKU code" onChange={e => setProductSearch(e.target.value)} /></label><label>Find warehouses<input disabled={busy || Boolean(pending)} value={warehouseSearch} maxLength={100} placeholder="Name, code or city" onChange={e => setWarehouseSearch(e.target.value)} /></label><button disabled={busy || Boolean(pending)} type="submit">Find references</button>
          {referenceTotal > 100 && <small>Showing the first 100 matches. Refine the search to find another record.</small>}
        </form>
        {referencesLoading && <p className="muted">Loading catalog references…</p>}
        {referenceError && <p className="error" role="alert">{referenceError} <button onClick={() => setRevision(r => r + 1)}>Reload references</button></p>}
        <form onSubmit={submit}>
          <fieldset disabled={busy || Boolean(pending) || Boolean(terminal)}>
            <label>Warehouse<select value={warehouseId} onChange={e => { setWarehouseId(e.target.value); setPage(0); }}><option value="">All / select warehouse</option>{warehouses.map(w => <option key={w.id} value={w.id}>{w.code} — {w.name}{w.status === 'INACTIVE' ? ' (inactive)' : ''}</option>)}</select></label>
            <label>SKU<select value={skuId} onChange={e => { setSkuId(e.target.value); setPage(0); }}><option value="">All / select SKU</option>{skus.map(s => <option key={s.id} value={s.id}>{s.code} — {s.product}{s.status === 'INACTIVE' ? ' (inactive)' : ''}</option>)}</select></label>
            {!readOnly && <><label>Operation<select value={operation} onChange={e => { setOperation(e.target.value as Operation); setQuantity(''); }}><option value="receipts">Receive stock</option><option value="adjustments">Adjust stock</option><option value="reservations">Reserve stock</option></select></label>
            <label>{operation === 'adjustments' ? 'Signed quantity change' : 'Quantity'}<input type="number" required min={operation === 'adjustments' ? -1_000_000_000 : 1} max={1_000_000_000} step="1" value={quantity} onChange={e => setQuantity(e.target.value)} /></label>
            {operation === 'reservations' && <div className="owner-field"><label htmlFor="reservation-owner">Owner reference</label><input id="reservation-owner" aria-describedby="reservation-owner-help" required maxLength={100} pattern="[A-Za-z0-9][A-Za-z0-9._:-]{0,99}" value={owner} onChange={e => setOwner(e.target.value)} /><small id="reservation-owner-help">Unique opaque reference, for a manual reservation. The order: prefix is reserved. Never enter customer details.</small></div>}
            <label>Reason<textarea required maxLength={500} value={reason} onChange={e => setReason(e.target.value)} /></label>
            {!canWrite && <p className="muted">Choose an active warehouse and SKU to change stock.</p>}
            <button className="primary" disabled={!canWrite || referencesLoading || Boolean(referenceError)}>{busy ? 'Recording…' : labels[operation]}</button></>}
          </fieldset>
        </form>
        {mutationError && <p role="alert" className="error">{mutationError}</p>}
        {pending && !busy && <div className="pending-request"><p>The result is not confirmed. Retry the same request to avoid duplicate stock changes.</p><code className="identifier">{pending.key}</code><button onClick={() => void send(pending)}>Retry same request</button></div>}
        {terminal && <form className="terminal-form" onSubmit={finish}>
          <h3>{terminal.action === 'release' ? 'Release reservation?' : 'Consume reserved stock?'}</h3><p className="muted">{terminal.reservation.ownerReference}. {terminal.action === 'release' ? 'This frees reserved units without changing on-hand stock.' : 'This permanently deducts both on-hand and reserved units. It is an inventory primitive, not an order shipment.'}</p>
          <label>Transition reason<textarea required maxLength={500} value={terminalReason} disabled={busy} onChange={e => setTerminalReason(e.target.value)} /></label><div className="input-row"><button className="primary" disabled={busy}>Confirm {terminal.action}</button><button type="button" disabled={busy} onClick={() => setTerminal(null)}>Cancel</button></div>
        </form>}
      </aside>
    </div>
  </section>;
}
