import { useEffect, useState } from 'react';
import { request } from '../../shared/api/management';

export interface Timeline {
  orderId: string;
  status: string;
  milestones: { code: string; label: string; progress: 'COMPLETED' | 'IN_PROGRESS' | 'PENDING' | 'NOT_REACHED'; occurredAt: string | null; detail: string }[];
}
const progressLabel = { COMPLETED: 'Confirmed', IN_PROGRESS: 'In progress', PENDING: 'Pending', NOT_REACHED: 'Not reached' };
const statusLabel: Record<string, string> = { RESERVED: 'Stock reserved', PROCESSING: 'Preparing order', READY_TO_SHIP: 'Ready for dispatch', SHIPPED: 'Dispatched', CANCELLED: 'Cancelled' };

export function OrderTimeline({ orderId, revision }: { orderId: string; revision: string }) {
  const [data, setData] = useState<Timeline | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [refresh, setRefresh] = useState(0);
  useEffect(() => {
    const abort = new AbortController(); setLoading(true); setData(null); setError('');
    request<Timeline>(`orders/${orderId}/timeline`, { signal: abort.signal })
      .then(result => { if (!Array.isArray(result.milestones) || result.orderId !== orderId) throw new Error('Unexpected timeline response.'); if (!abort.signal.aborted) setData(result); })
      .catch(err => { if (!abort.signal.aborted) setError((err as Error).message); })
      .finally(() => { if (!abort.signal.aborted) setLoading(false); });
    return () => abort.abort();
  }, [orderId, revision, refresh]);
  return <section className="order-timeline" aria-label="Order status timeline" aria-busy={loading}>
    <div className="timeline-heading"><div><p className="eyebrow">Operational progress</p><h3>Order status timeline</h3></div><button type="button" disabled={loading} onClick={() => setRefresh(r => r + 1)} aria-label="Refresh timeline">Refresh</button></div>
    {loading ? <p role="status" className="muted">Loading timeline…</p> : error ? <p role="alert" className="error">{error}</p> : data && <>
      <p className={`timeline-current ${data.status === 'CANCELLED' ? 'timeline-cancelled' : ''}`}><span>Current status</span><strong>{statusLabel[data.status] ?? data.status}</strong></p>
      {data.milestones.length ? <ol className="timeline-stages" aria-label="Confirmed and pending milestones">{data.milestones.map(stage => <li key={stage.code} className={`timeline-stage timeline-${stage.progress.toLowerCase()} ${stage.code === 'CANCELLED' ? 'timeline-cancelled' : ''}`}>
        <span className="timeline-marker" aria-hidden="true">{stage.code === 'CANCELLED' ? '×' : stage.progress === 'COMPLETED' ? '✓' : '○'}</span>
        <div><div className="timeline-stage-heading"><h4>{stage.label}</h4><span className="timeline-progress">{progressLabel[stage.progress]}</span></div><p>{stage.detail}</p>
          {stage.occurredAt ? <time dateTime={stage.occurredAt}>{new Intl.DateTimeFormat('en-GB', { dateStyle: 'medium', timeStyle: 'medium', timeZone: 'UTC' }).format(new Date(stage.occurredAt))} UTC</time> : stage.progress === 'COMPLETED' && <small>Timestamp not recorded</small>}
        </div>
      </li>)}</ol> : <p className="muted">No recorded milestones available.</p>}
      <p className="timeline-note">Persisted operational events · Times in UTC · No delivery confirmation</p>
    </>}
  </section>;
}
