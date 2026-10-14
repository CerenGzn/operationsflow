import { useEffect, useState } from 'react';
import { getSystemInfo } from '../shared/api/system';

export function App() {
  const [status, setStatus] = useState('Connecting to backend…');
  useEffect(() => {
    const controller = new AbortController();
    getSystemInfo(controller.signal)
      .then(info => setStatus(`Connected to ${info.application} API ${info.apiVersion}`))
      .catch(() => { if (!controller.signal.aborted) setStatus('Backend unavailable. Check that the backend is running.'); });
    return () => controller.abort();
  }, []);
  return <main><p className="eyebrow">OperationsFlow</p><h1>Order, inventory and fulfillment operations</h1>
    <p>The platform foundation is ready for development.</p><p role="status">{status}</p></main>;
}
