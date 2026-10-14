import { useEffect, useRef, useState, type FormEvent } from 'react';
import { ApiError, listRecords, request, writeRecord, type ManagedRecord, type PageResult } from '../../shared/api/management';

type Kind = 'products' | 'warehouses' | 'customers';
type Field = { key: string; label: string; max: number; required?: boolean; pattern?: string; help?: string };
const productFields: Field[] = [
  { key: 'name', label: 'Product name', max: 160, required: true },
  { key: 'description', label: 'Description', max: 2000 },
];
const warehouseFields: Field[] = [
  { key: 'name', label: 'Warehouse name', max: 160, required: true },
  { key: 'addressLine', label: 'Street address', max: 200, required: true },
  { key: 'city', label: 'City', max: 100, required: true },
  { key: 'postalCode', label: 'Postal code', max: 20, required: true },
  { key: 'countryCode', label: 'Country code', max: 2, required: true, pattern: '[A-Z]{2}', help: 'Two uppercase letters, for example DE.' },
];

export function ManagementPage({ kind, readOnly = false }: { kind: Kind; readOnly?: boolean }) {
  const isProduct = kind === 'products';
  const isCustomer = kind === 'customers';
  const singular = isProduct ? 'product' : isCustomer ? 'customer' : 'warehouse';
  const title = isProduct ? 'Products' : isCustomer ? 'Customers' : 'Warehouses';
  const [query, setQuery] = useState('');
  const [search, setSearch] = useState('');
  const [status, setStatus] = useState('');
  const [sort, setSort] = useState('name');
  const [direction, setDirection] = useState('asc');
  const [page, setPage] = useState(0);
  const [revision, setRevision] = useState(0);
  const [result, setResult] = useState<PageResult | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [editing, setEditing] = useState<ManagedRecord | 'new' | null>(null);
  const [draft, setDraft] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState('');
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [confirm, setConfirm] = useState(false);
  const [skuCode, setSkuCode] = useState('');
  const firstInput = useRef<HTMLInputElement>(null);
  const fields = isProduct ? productFields : warehouseFields.map(f => f.key === 'name' && isCustomer ? { ...f, label: 'Business name' } : f);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true); setError(''); setResult(null);
    const params = new URLSearchParams({ query: search, page: String(page), size: '10', sort, direction });
    if (status) params.set('status', status);
    listRecords(`${kind}?${params}`, controller.signal)
      .then(data => { if (!controller.signal.aborted) setResult(data); })
      .catch(err => { if (!controller.signal.aborted) setError(err.message); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [kind, search, status, sort, direction, page, revision]);
  useEffect(() => { if (editing) firstInput.current?.focus(); }, [editing]);

  function startNew() {
    setEditing('new'); setDraft(Object.fromEntries(fields.map(f => [f.key, ''])));
    setFormError(''); setFieldErrors({}); setConfirm(false); setSkuCode(''); setNotice('');
  }
  async function open(record: ManagedRecord) {
    setBusy(true); setError(''); setNotice('');
    try {
      const latest = await request<ManagedRecord>(`${kind}/${record.id}`);
      setEditing(latest);
      setDraft(Object.fromEntries(fields.map(f => [f.key, String(latest[f.key as keyof ManagedRecord] ?? '')])));
      setFormError(''); setFieldErrors({}); setConfirm(false); setSkuCode('');
    } catch (err) { setError((err as Error).message); }
    finally { setBusy(false); }
  }
  function showFormError(err: unknown) {
    setFormError((err as Error).message);
    setFieldErrors(err instanceof ApiError ? err.fields : {});
  }
  async function save(event: FormEvent) {
    event.preventDefault(); if (!editing) return;
    setBusy(true); setFormError(''); setFieldErrors({});
    try {
      const body = editing === 'new' ? draft : { ...draft, version: editing.version };
      await writeRecord(editing === 'new' ? kind : `${kind}/${editing.id}`, editing === 'new' ? 'POST' : 'PUT', body);
      setNotice(`${title.slice(0, -1)} saved.`); setEditing(null); setRevision(r => r + 1);
    } catch (err) { showFormError(err); }
    finally { setBusy(false); }
  }
  async function deactivate() {
    if (!editing || editing === 'new') return;
    setBusy(true); setFormError('');
    try {
      await writeRecord(`${kind}/${editing.id}/deactivate`, 'POST', { version: editing.version });
      setNotice(`${title.slice(0, -1)} deactivated. Its identifier remains reserved.`);
      setEditing(null); setRevision(r => r + 1);
    } catch (err) { showFormError(err); }
    finally { setBusy(false); }
  }
  async function addSku(event: FormEvent) {
    event.preventDefault(); if (!editing || editing === 'new') return;
    setBusy(true); setFormError(''); setFieldErrors({});
    try {
      const updated = await writeRecord(`products/${editing.id}/skus`, 'POST', { code: skuCode, version: editing.version });
      setEditing(updated); setSkuCode(''); setRevision(r => r + 1); setNotice('SKU added. Its code cannot be changed.');
    } catch (err) { showFormError(err); }
    finally { setBusy(false); }
  }
  const inactive = readOnly || editing && editing !== 'new' && editing.status === 'INACTIVE';
  const identifier: Field = isProduct
    ? { key: 'skuCode', label: 'Initial SKU code', max: 64, required: true, pattern: '[A-Z0-9][A-Z0-9_-]{0,63}', help: 'Unique and permanent. Uppercase letters, numbers, hyphens and underscores.' }
    : { key: isCustomer ? 'referenceNumber' : 'code', label: isCustomer ? 'Customer reference' : 'Warehouse code', max: 32, required: true, pattern: '[A-Z0-9][A-Z0-9_-]{0,31}', help: 'Unique and permanent. Uppercase letters, numbers, hyphens and underscores.' };

  return <section aria-labelledby="page-title">
    <div className="page-header"><div><p className="eyebrow">Workspace / {title}</p><h1 id="page-title">{title}</h1>
      <p className="muted">{isProduct ? 'Maintain your catalog and permanent SKU identifiers.' : isCustomer ? 'Manage business customers and shipping addresses. Use synthetic business data only.' : 'Manage the locations behind your fulfillment operations.'}</p></div>
      {!readOnly && <button className="primary" disabled={busy} onClick={startNew}>+ New {singular}</button>}</div>
    {notice && <p className="notice" aria-live="polite">{notice}</p>}
    <div className="management-grid">
      <div className="panel records-panel">
        <div className="panel-heading"><h2>{isProduct ? 'Product catalog' : isCustomer ? 'Customer directory' : 'Warehouse directory'}</h2><span className="count">{result ? `${result.totalElements} records` : '—'}</span></div>
        <div className="toolbar">
          <form className="search" onSubmit={e => { e.preventDefault(); setSearch(query); setPage(0); }}>
            <label htmlFor="search">Search {kind}</label><div className="input-row"><input id="search" value={query} maxLength={100} placeholder={isProduct ? 'Name or SKU code' : 'Name, code or city'} onChange={e => setQuery(e.target.value)} /><button type="submit">Search</button></div>
          </form>
          <label>Status<select value={status} onChange={e => { setStatus(e.target.value); setPage(0); }}><option value="">All statuses</option><option value="ACTIVE">Active</option><option value="INACTIVE">Inactive</option></select></label>
          <label>Sort by<select value={sort} onChange={e => { setSort(e.target.value); setPage(0); }}><option value="name">Name</option>{!isProduct && <option value={isCustomer ? "referenceNumber" : "code"}>{isCustomer ? "Reference" : "Code"}</option>}{!isProduct && <option value="city">City</option>}<option value="createdAt">Created date</option><option value="status">Status</option></select></label>
          <label>Direction<select value={direction} onChange={e => { setDirection(e.target.value); setPage(0); }}><option value="asc">Ascending</option><option value="desc">Descending</option></select></label>
        </div>
        {error && <div role="alert" className="error"><p>{error}</p><button onClick={() => setRevision(r => r + 1)}>Retry</button></div>}
        {loading ? <div className="empty" aria-live="polite">Loading {kind}…</div> : result && <>
          {result.content.length === 0 ? <div className="empty"><h3>No {kind} found</h3><p>Try another search or create your first {singular}.</p></div> :
          <div className="table-scroll"><table><caption className="sr-only">{title} results</caption><thead><tr><th>Name</th><th>{isProduct ? 'SKU identifiers' : 'Location'}</th><th>Status</th><th><span className="sr-only">Actions</span></th></tr></thead>
            <tbody>{result.content.map(record => <tr key={record.id}><td><strong>{record.name}</strong>{!isProduct && <small>{isCustomer ? record.referenceNumber : record.code}</small>}</td><td>{isProduct ? <div className="codes">{record.skus?.map(sku => <code key={sku.id}>{sku.code}</code>)}</div> : <><span>{record.city}, {record.countryCode}</span><small>{record.addressLine}</small></>}</td><td><span className={`badge ${record.status.toLowerCase()}`}>{record.status === 'ACTIVE' ? 'Active' : 'Inactive'}</span></td><td><button disabled={busy} onClick={() => open(record)} aria-label={`View ${record.name}`}>View</button></td></tr>)}</tbody>
          </table></div>}
          <div className="pagination"><span>Page {result.totalPages === 0 ? 0 : page + 1} of {result.totalPages}</span><div><button disabled={page === 0} onClick={() => setPage(p => p - 1)}>Previous</button><button disabled={page + 1 >= result.totalPages} onClick={() => setPage(p => p + 1)}>Next</button></div></div>
        </>}
      </div>
      {editing && <aside className="panel editor" aria-label={`${singular} editor`}>
        <div className="panel-heading"><h2>{editing === 'new' ? `New ${singular}` : `${inactive ? 'View' : 'Edit'} ${singular}`}</h2><button aria-label="Close editor" disabled={busy} onClick={() => setEditing(null)}>×</button></div>
        {formError && <p className="error" role="alert">{formError}</p>}
        {inactive && <p className="muted">{readOnly ? 'Your role has read-only access to these records.' : 'This record is inactive and read-only.'}</p>}
        {editing !== 'new' && <div className="identity"><span className="eyebrow">Permanent identifiers</span><div className="codes">{isProduct ? editing.skus?.map(s => <code key={s.id}>{s.code}</code>) : <code>{isCustomer ? editing.referenceNumber : editing.code}</code>}</div></div>}
        <form onSubmit={save}>
          <fieldset disabled={busy || Boolean(inactive)}>
            {[...(editing === 'new' ? [identifier] : []), ...fields].map((field, index) => <label key={field.key} htmlFor={`field-${field.key}`}>{field.label}{field.required ? ' *' : ''}
              {field.key === 'description' ? <textarea id={`field-${field.key}`} maxLength={field.max} value={draft[field.key] ?? ''} onChange={e => setDraft({ ...draft, [field.key]: e.target.value })} aria-invalid={Boolean(fieldErrors[field.key])} aria-describedby={fieldErrors[field.key] ? `error-${field.key}` : undefined} /> :
              <input ref={index === 0 ? firstInput : undefined} id={`field-${field.key}`} required={field.required} maxLength={field.max} pattern={field.pattern} value={draft[field.key] ?? ''} onChange={e => setDraft({ ...draft, [field.key]: e.target.value })} aria-invalid={Boolean(fieldErrors[field.key])} aria-describedby={fieldErrors[field.key] ? `error-${field.key}` : undefined} />}
              {field.help && <small>{field.help}</small>}{fieldErrors[field.key] && <small className="field-error" id={`error-${field.key}`}>{fieldErrors[field.key]}</small>}
            </label>)}
            {!inactive && <button className="primary" type="submit">{busy ? 'Saving…' : `Save ${singular}`}</button>}
          </fieldset>
        </form>
        {isProduct && editing !== 'new' && !inactive && <form className="sku-form" onSubmit={addSku}>
          <label htmlFor="additional-sku">Add SKU code<input id="additional-sku" required maxLength={64} pattern="[A-Z0-9][A-Z0-9_-]{0,63}" value={skuCode} onChange={e => setSkuCode(e.target.value)} disabled={busy} /></label>
          {fieldErrors.code && <small className="field-error">{fieldErrors.code}</small>}<button disabled={busy}>Add SKU</button>
        </form>}
        {editing !== 'new' && !inactive && <div className="deactivation">
          {confirm ? <><p>Deactivate this {singular}? It will become read-only. Its identifiers cannot be reused. This cannot be undone here.</p><div className="input-row"><button className="danger" disabled={busy} onClick={deactivate}>Confirm deactivation</button><button disabled={busy} onClick={() => setConfirm(false)}>Keep active</button></div></> : <button className="danger-link" disabled={busy} onClick={() => setConfirm(true)}>Deactivate {singular}</button>}
        </div>}
      </aside>}
    </div>
  </section>;
}
