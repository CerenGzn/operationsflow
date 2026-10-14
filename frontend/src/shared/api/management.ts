import { csrfHeaders, resetCsrf } from './csrf';
export type Status = 'ACTIVE' | 'INACTIVE';
export interface ManagedRecord {
  id: string; name: string; status: Status; version: number;
  description?: string; skus?: { id: string; code: string }[];
  code?: string; referenceNumber?: string; addressLine?: string; city?: string; postalCode?: string; countryCode?: string;
}
export interface PageResult { content: ManagedRecord[]; page: number; size: number; totalElements: number; totalPages: number }
export class ApiError extends Error {
  constructor(message: string, public fields: Record<string, string> = {}, public status?: number) { super(message); }
}
export async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  let response: Response;
  try {
    const unsafe = !['GET', 'HEAD', 'OPTIONS'].includes((options.method ?? 'GET').toUpperCase());
    const headers = new Headers(options.headers);
    if (unsafe) for (const [key, value] of Object.entries(await csrfHeaders())) headers.set(key, value);
    response = await fetch(`/api/v1/${path}`, { ...options, headers, credentials: 'same-origin' });
  }
  catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error;
    throw new ApiError('Cannot reach the API. Check your connection and retry.');
  }
  if (response.status === 204) return undefined as T;
  const body = await response.json().catch(() => null);
  if (!response.ok) {
    if (response.status === 401 && !['auth/login', 'auth/session'].includes(path)) window.dispatchEvent(new Event('operationsflow:session-expired'));
    if (body?.code === 'CSRF_INVALID') { resetCsrf(); window.dispatchEvent(new Event('operationsflow:check-session')); }
    if (body?.code === 'PASSWORD_CHANGE_REQUIRED') window.dispatchEvent(new Event('operationsflow:check-session'));
    const fields: Record<string, string> = {};
    if (Array.isArray(body?.errors)) for (const error of body.errors) fields[error.field] = error.message;
    throw new ApiError(body?.detail ?? `Request failed (${response.status}). Please retry.`, fields, response.status);
  }
  if (!body) throw new ApiError('The API returned an unexpected response.');
  return body as T;
}
export async function listRecords(path: string, signal: AbortSignal): Promise<PageResult> {
  const result = await request<PageResult>(path, { signal });
  if (!Array.isArray(result.content) || typeof result.totalElements !== 'number') throw new ApiError('The API returned an unexpected list response.');
  return result;
}
export function writeRecord(path: string, method: 'POST' | 'PUT', body: unknown) {
  return request<ManagedRecord>(path, { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
}
