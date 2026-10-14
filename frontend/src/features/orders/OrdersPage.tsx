import { useEffect, useRef, useState, type FormEvent } from 'react';
import { ApiError, request, type ManagedRecord } from '../../shared/api/management';

interface Page<T> { content: T[]; totalElements: number; totalPages: number }
interface Summary { id: string; orderNumber: string; customerName: string; status: string; totalUnits: number; createdAt: string }
interface Order extends Omit<Summary, 'customerName'> {
  customer: { name: string; referenceNumber: string; addressLine: string; city: string; postalCode: string; countryCode: string };
  lines: { skuId: string; skuCode: string; productName: string; quantity: number }[];
  allocations: { skuId: string; warehouseId: string; warehouseCode: string; quantity: number }[];
  reservationId: string; cancellationReason: string | null;
}
interface Availability { skuId: string; totalAvailable: number; balances: Page<{ warehouseId: string; warehouseCode: string; onHand: number; reserved: number; available: number }> }
interface Attempt { key: string; body: string }
const storageKey = 'operationsflow.pending-order.v1';
function savedAttempt(): Attempt | null {
  try {
    const value = JSON.parse(sessionStorage.getItem(storageKey) ?? 'null');
    return value && typeof value.key === 'string' && typeof value.body === 'string' ? value : null;
  } catch { return null; }
}

export function OrdersPage({ readOnly = false }: { readOnly?: boolean }) {
  const [data, setData] = useState<Page<Summary> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [query, setQuery] = useState('');
  const [search, setSearch] = useState('');
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(0);
  const [revision, setRevision] = useState(0);
  const [creating, setCreating] = useState(false);
  const [detail, setDetail] = useState<Order | null>(null);
  const [customers, setCustomers] = useState<ManagedRecord[]>([]);
  const [products, setProducts] = useState<ManagedRecord[]>([]);
  const [customerSearch, setCustomerSearch] = useState('');
  const [productSearch, setProductSearch] = useState('');
  const [references, setReferences] = useState({ customer: '', product: '' });
  const [referenceLoading, setReferenceLoading] = useState(true);
  const [referenceError, setReferenceError] = useState('');
  const [truncated, setTruncated] = useState(false);
  const [customerId, setCustomerId] = useState('');
  const [skuId, setSkuId] = useState('');
  const [availability, setAvailability] = useState<Availability | null>(null);
  const [availabilityError, setAvailabilityError] = useState('');
  const [availabilityPage, setAvailabilityPage] = useState(0);
  const [availabilityRevision, setAvailabilityRevision] = useState(0);
  const [quantity, setQuantity] = useState('1');
  const [lines, setLines] = useState<{ skuId: string; skuCode: string; quantity: number }[]>([]);
  const [busy, setBusy] = useState(false);
  const [mutationError, setMutationError] = useState('');
  const [notice, setNotice] = useState('');
  const [pending, setPending] = useState<Attempt | null>(savedAttempt);
  const [confirmCancel, setConfirmCancel] = useState(false);
  const [reason, setReason] = useState('');
  const inflight = useRef(false);

  useEffect(() => {
    const abort = new AbortController(); setLoading(true); setData(null); setError('');
    const params = new URLSearchParams({ query: search, page: String(page), size: '10' });
    if (status) params.set('status', status);
    request<Page<Summary>>(`orders?${params}`, { signal: abort.signal })
      .then(result => { if (!Array.isArray(result.content)) throw new Error('Unexpected order response.'); if (!abort.signal.aborted) setData(result); })
      .catch(err => { if (!abort.signal.aborted) setError(err.message); })
      .finally(() => { if (!abort.signal.aborted) setLoading(false); });
    return () => abort.abort();
  }, [search, status, page, revision]);
  useEffect(() => {
    if (!creating) return;
    const abort = new AbortController(); setReferenceLoading(true); setReferenceError('');
    Promise.all([
      request<Page<ManagedRecord>>(`customers?${new URLSearchParams({ status: 'ACTIVE', size: '100', query: references.customer })}`, { signal: abort.signal }),
      request<Page<ManagedRecord>>(`products?${new URLSearchParams({ status: 'ACTIVE', size: '100', query: references.product })}`, { signal: abort.signal }),
    ]).then(([c, p]) => { if (!abort.signal.aborted) { setCustomers(c.content); setProducts(p.content); setTruncated(c.totalElements > 100 || p.totalElements > 100); } })
      .catch(err => { if (!abort.signal.aborted) setReferenceError(err.message); })
      .finally(() => { if (!abort.signal.aborted) setReferenceLoading(false); });
    return () => abort.abort();
  }, [creating, references, revision]);
  useEffect(() => {
    if (!creating || !skuId) return;
    const abort = new AbortController(); setAvailability(null); setAvailabilityError('');
    request<Availability>(`inventory/availability?${new URLSearchParams({ skuId, page: String(availabilityPage), size: '10' })}`, { signal: abort.signal })
      .then(result => { if (!abort.signal.aborted) setAvailability(result); })
      .catch(err => { if (!abort.signal.aborted) setAvailabilityError(err.message); });
    return () => abort.abort();
  }, [creating, skuId, availabilityPage, availabilityRevision]);
  const stock = availability?.skuId === skuId ? availability : null;
  const skus = products.flatMap(p => (p.skus ?? []).map(s => ({ ...s, name: p.name })));
  function addLine(event: FormEvent) {
    event.preventDefault(); const sku = skus.find(s => s.id === skuId); const amount = Number(quantity);
    if (!sku || !Number.isSafeInteger(amount) || amount < 1 || amount > 1_000_000_000) return;
    if (lines.some(l => l.skuId === skuId)) { setMutationError('This SKU is already in the order. Remove its line to change the quantity.'); return; }
    setLines([...lines, { skuId, skuCode: sku.code, quantity: amount }]); setSkuId(''); setAvailabilityPage(0); setQuantity('1'); setMutationError('');
  }
  async function send(attempt: Attempt) {
    if (inflight.current) return;
    inflight.current = true; setBusy(true); setMutationError(''); setNotice('');
    try {
      // Persist before sending, so navigation or reload can retry the same operation.
      sessionStorage.setItem(storageKey, JSON.stringify(attempt)); setPending(attempt);
      const result = await request<Order>('orders', { method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': attempt.key }, body: attempt.body });
      sessionStorage.removeItem(storageKey); setPending(null); setCreating(false); setLines([]); setCustomerId(''); setDetail(null); setRevision(r => r + 1);
      setNotice(`${result.orderNumber} created. Stock reserved. Open the order to see its current status.`);
    } catch (err) {
      setMutationError((err as Error).message);
      if (err instanceof ApiError && err.status && err.status >= 400 && err.status < 500) { sessionStorage.removeItem(storageKey); setPending(null); }
    } finally { inflight.current = false; setBusy(false); }
  }
  function create() {
    if (pending || !customerId || !lines.length || inflight.current) return;
    void send({ key: crypto.randomUUID(), body: JSON.stringify({ customerId, lines: lines.map(l => ({ skuId: l.skuId, quantity: l.quantity })) }) });
  }
  async function open(id: string) {
    if (inflight.current) return;
    inflight.current = true; setBusy(true); setMutationError(''); setDetail(null); setCreating(false); setConfirmCancel(false); setReason('');
    try { setDetail(await request<Order>(`orders/${id}`)); }
    catch (err) { setMutationError((err as Error).message); }
    finally { inflight.current = false; setBusy(false); }
  }
  async function cancel(event: FormEvent) {
    event.preventDefault(); if (!detail || inflight.current) return;
    inflight.current = true; setBusy(true); setMutationError('');
    try {
      setDetail(await request<Order>(`orders/${detail.id}/cancel`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ reason }) }));
      setConfirmCancel(false); setNotice('Order cancelled. Reserved stock released; on-hand stock is unchanged.'); setRevision(r => r + 1);
    } catch (err) { setMutationError((err as Error).message); }
    finally { inflight.current = false; setBusy(false); }
  }
  return <section aria-labelledby="orders-title">
    <div className="page-header"><div><p className="eyebrow">Workspace / Orders</p><h1 id="orders-title">Orders</h1><p className="muted">Customer orders with atomic stock allocation and traceable cancellation.</p></div>{!readOnly && <button className="primary" disabled={busy || Boolean(pending)} onClick={() => { setCreating(true); setDetail(null); setMutationError(''); setConfirmCancel(false); }}>+ New order</button>}</div>
    <p className="pricing-note">Unpriced orders · This milestone tracks quantities and stock commitments. Pricing, currency, tax and monetary totals are not yet supported.</p>
    {notice && <p className="notice" aria-live="polite">{notice}</p>}
    {mutationError && <p className="error" role="alert">{mutationError}</p>}
    {pending && !readOnly && <div className="pending-request"><p>An order request is awaiting confirmation. Retry it with the same key before creating another order. This request survives navigation and reload in this tab.</p><code>{pending.key}</code><button disabled={busy} onClick={() => void send(pending)}>Retry same order</button></div>}
    <div className="management-grid">
      <div className="panel records-panel"><div className="panel-heading"><h2>Order register</h2><span className="count">{data ? `${data.totalElements} orders` : '—'}</span></div>
        <div className="toolbar"><form className="search" onSubmit={e => { e.preventDefault(); setSearch(query); setPage(0); }}><label htmlFor="order-search">Search orders</label><div className="input-row"><input id="order-search" value={query} maxLength={100} placeholder="Order number or customer" onChange={e => setQuery(e.target.value)} /><button>Search</button></div></form><label>Order status<select value={status} onChange={e => { setStatus(e.target.value); setPage(0); }}><option value="">All statuses</option><option value="RESERVED">Reserved</option><option value="PROCESSING">Processing</option><option value="READY_TO_SHIP">Ready to ship</option><option value="SHIPPED">Shipped</option><option value="CANCELLED">Cancelled</option></select></label><button disabled={busy} onClick={() => setRevision(r => r + 1)}>Refresh orders</button></div>
        {error && <div className="error" role="alert">{error}<button onClick={() => setRevision(r => r + 1)}>Retry</button></div>}
        {loading ? <div className="empty" aria-live="polite">Loading orders…</div> : data && <>{data.content.length === 0 ? <div className="empty"><h3>No orders found</h3><p>Create an order after adding an active customer and receiving stock.</p></div> : <div className="table-scroll"><table><caption className="sr-only">Order results</caption><thead><tr><th>Order / created</th><th>Customer</th><th>Units</th><th>Status</th><th>Actions</th></tr></thead><tbody>{data.content.map(o => <tr key={o.id}><td><strong>{o.orderNumber}</strong><small>{new Date(o.createdAt).toLocaleString()}</small></td><td>{o.customerName}</td><td>{o.totalUnits.toLocaleString()}</td><td><span className={`badge ${o.status === 'RESERVED' ? 'active' : 'inactive'}`}>{o.status}</span></td><td><button disabled={busy} onClick={() => void open(o.id)} aria-label={`View ${o.orderNumber}`}>View</button></td></tr>)}</tbody></table></div>}
          <div className="pagination"><span>Page {data.totalPages ? page + 1 : 0} of {data.totalPages}</span><div><button disabled={page === 0} onClick={() => setPage(p => p - 1)}>Previous</button><button disabled={page + 1 >= data.totalPages} onClick={() => setPage(p => p + 1)}>Next</button></div></div></>}
      </div>
      {creating && <aside className="panel editor order-editor" aria-label="Order editor"><div className="panel-heading"><h2>New order</h2><button disabled={busy} onClick={() => setCreating(false)} aria-label="Close order editor">×</button></div>
        <form onSubmit={e => { e.preventDefault(); setReferences({ customer: customerSearch, product: productSearch }); setCustomerId(''); setSkuId(''); }}><fieldset disabled={busy || Boolean(pending)}><label>Find customers<input value={customerSearch} maxLength={100} onChange={e => setCustomerSearch(e.target.value)} /></label><label>Find products<input value={productSearch} maxLength={100} onChange={e => setProductSearch(e.target.value)} /></label><button>Find references</button></fieldset></form>
        {referenceLoading && <p className="muted">Loading active customers and SKUs…</p>}{referenceError && <p role="alert" className="error">{referenceError}<button onClick={() => setRevision(r => r + 1)}>Reload references</button></p>}{truncated && <p className="muted">Showing the first 100 matches. Refine your search to find other records.</p>}
        {!referenceLoading && !referenceError && (!customers.length || !skus.length) && <p className="muted">No matching active customers or SKUs. Create them in their management screens or refine your search.</p>}
        <form onSubmit={addLine}><fieldset disabled={busy || Boolean(pending) || referenceLoading || Boolean(referenceError)}><label>Customer<select required value={customerId} onChange={e => setCustomerId(e.target.value)}><option value="">Select customer</option>{customers.map(c => <option key={c.id} value={c.id}>{c.referenceNumber} — {c.name}</option>)}</select></label><label>SKU<select required value={skuId} onChange={e => { setSkuId(e.target.value); setAvailabilityPage(0); }}><option value="">Select SKU</option>{skus.map(s => <option key={s.id} value={s.id}>{s.code} — {s.name}</option>)}</select></label><label>Quantity<input type="number" required min="1" max="1000000000" step="1" value={quantity} onChange={e => setQuantity(e.target.value)} /></label><button disabled={lines.length >= 50}>Add line</button></fieldset></form>
        {skuId && <div className="availability-preview" aria-label="Stock availability"><h3>Stock availability</h3>
          {availabilityError ? <p role="alert" className="error">{availabilityError}<button onClick={() => setAvailabilityRevision(r => r + 1)}>Retry availability</button></p> : stock ? <>
            <p><strong>{stock.totalAvailable.toLocaleString()} available units</strong> across active warehouses</p>
            {stock.balances.content.length ? <ul>{stock.balances.content.map(b => <li key={b.warehouseId}><strong>{b.warehouseCode}</strong><span>{b.available.toLocaleString()} available · {b.reserved.toLocaleString()} reserved · {b.onHand.toLocaleString()} on-hand</span></li>)}</ul> : <p>No recorded stock at active warehouses.</p>}
            {stock.balances.totalPages > 1 && <div className="input-row"><button disabled={availabilityPage === 0} onClick={() => setAvailabilityPage(p => p - 1)}>Previous warehouses</button><button disabled={availabilityPage + 1 >= stock.balances.totalPages} onClick={() => setAvailabilityPage(p => p + 1)}>Next warehouses</button></div>}
            <button onClick={() => setAvailabilityRevision(r => r + 1)}>Refresh availability</button>
          </> : <p aria-live="polite">Loading availability…</p>}
          <p className="muted">Preview only. Stock can change before submission; adding a line does not reserve it.</p>
        </div>}
        <div className="order-lines">{lines.length ? <ul>{lines.map(l => <li key={l.skuId}><code>{l.skuCode}</code><span>{l.quantity.toLocaleString()} units</span><button disabled={busy || Boolean(pending)} aria-label={`Remove ${l.skuCode}`} onClick={() => setLines(lines.filter(line => line.skuId !== l.skuId))}>Remove</button></li>)}</ul> : <p className="muted">Add at least one SKU line.</p>}<p><strong>{lines.reduce((total, l) => total + l.quantity, 0).toLocaleString()} units</strong> · {lines.length} lines</p><p className="muted">Stock is checked on submission. Allocation prefers a single warehouse, then splits by warehouse code. An insufficient order reserves nothing.</p><button className="primary" disabled={busy || Boolean(pending) || !customerId || !lines.length || referenceLoading || Boolean(referenceError)} onClick={create}>{busy ? 'Reserving…' : 'Create & reserve order'}</button></div>
      </aside>}
      {detail && <aside className="panel editor order-editor" aria-label="Order details"><div className="panel-heading"><h2>{detail.orderNumber}</h2><button disabled={busy} onClick={() => setDetail(null)} aria-label="Close order details">×</button></div><div className="order-details"><span className={`badge ${detail.status === 'RESERVED' ? 'active' : 'inactive'}`}>{detail.status}</span><h3>Shipping snapshot</h3><p><strong>{detail.customer.name}</strong><br />{detail.customer.referenceNumber}<br />{detail.customer.addressLine}<br />{detail.customer.postalCode} {detail.customer.city}, {detail.customer.countryCode}</p><h3>Order lines</h3><ul>{detail.lines.map(l => <li key={l.skuId}><code>{l.skuCode}</code> · {l.productName} · {l.quantity.toLocaleString()} units</li>)}</ul><h3>Warehouse allocation</h3><ul>{detail.allocations.map(a => <li key={`${a.warehouseId}-${a.skuId}`}><strong>{a.warehouseCode}</strong> · {detail.lines.find(l => l.skuId === a.skuId)?.skuCode ?? a.skuId} · {a.quantity.toLocaleString()} units</li>)}</ul><p><strong>{detail.totalUnits.toLocaleString()} total units</strong></p><small className="identifier">Reservation: {detail.reservationId}</small>{detail.cancellationReason && <p>Cancellation reason: {detail.cancellationReason}</p>}{!readOnly && ['RESERVED', 'PROCESSING', 'READY_TO_SHIP'].includes(detail.status) && !confirmCancel && <button className="danger" disabled={busy} onClick={() => setConfirmCancel(true)}>Cancel order</button>}</div>
        {confirmCancel && <form className="terminal-form" onSubmit={cancel}><h3>Cancel this order?</h3><p className="muted">All reserved units will be released once. This cannot be undone here.</p><label>Cancellation reason<textarea required maxLength={500} disabled={busy} value={reason} onChange={e => setReason(e.target.value)} /></label><div className="input-row"><button className="danger" disabled={busy}>Confirm cancellation</button><button type="button" disabled={busy} onClick={() => setConfirmCancel(false)}>Keep order</button></div></form>}
      </aside>}
    </div>
  </section>;
}
