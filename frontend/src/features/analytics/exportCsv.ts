import { ApiError } from '../../shared/api/management';
export async function exportCsv(kind: string, filters: URLSearchParams) {
  const response = await fetch(`/api/v1/reports/${kind}/export?${filters}`, { credentials: 'same-origin', cache: 'no-store' });
  if (!response.ok) {
    const body = await response.json().catch(() => null);
    if (response.status === 401) window.dispatchEvent(new Event('operationsflow:session-expired'));
    if (body?.code === 'PASSWORD_CHANGE_REQUIRED') window.dispatchEvent(new Event('operationsflow:check-session'));
    throw new ApiError(body?.detail ?? `Export failed (${response.status}).`, {}, response.status);
  }
  if (!response.headers.get('Content-Type')?.startsWith('text/csv')) throw new Error('Unexpected export response. No file was downloaded.');
  const url = URL.createObjectURL(await response.blob());
  try { const link = document.createElement('a'); link.href = url; link.download = `operationsflow-${kind}.csv`; document.body.appendChild(link); link.click(); link.remove(); }
  finally { setTimeout(() => URL.revokeObjectURL(url), 1000); }
}
