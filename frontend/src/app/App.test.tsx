import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { App } from './App';

describe('backend connectivity', () => {
  it('shows the API identity after a successful request', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ application: 'OperationsFlow', apiVersion: 'v1' }) });
    vi.stubGlobal('fetch', fetchMock);
    render(<App />);
    expect(screen.getByRole('status')).toHaveTextContent('Connecting');
    expect(await screen.findByText('Connected to OperationsFlow API v1')).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/system', { signal: expect.any(AbortSignal) });
  });

  it.each([
    { ok: false },
    { ok: true, json: async () => ({ unexpected: true }) },
  ])('shows a useful message for unsuccessful or invalid responses', async response => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response));
    render(<App />);
    expect(await screen.findByText(/Backend unavailable/)).toBeInTheDocument();
  });

  it('handles a network failure', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Network error')));
    render(<App />);
    expect(await screen.findByText(/Backend unavailable/)).toBeInTheDocument();
  });
});
