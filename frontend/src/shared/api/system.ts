export interface SystemInfo { application: string; apiVersion: string }

export async function getSystemInfo(signal: AbortSignal): Promise<SystemInfo> {
  const response = await fetch('/api/v1/system', { signal });
  if (!response.ok) throw new Error('Backend unavailable');
  const data: unknown = await response.json();
  if (typeof data !== 'object' || data === null || !('application' in data) ||
      !('apiVersion' in data) || typeof data.application !== 'string' || typeof data.apiVersion !== 'string') {
    throw new Error('Unexpected backend response');
  }
  return { application: data.application, apiVersion: data.apiVersion };
}
