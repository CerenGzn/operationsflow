import { useEffect, useState } from 'react';
import { getSystemInfo } from '../shared/api/system';
import { OrdersPage } from '../features/orders/OrdersPage';
import { InventoryPage } from '../features/inventory/InventoryPage';
import { ManagementPage } from '../features/management/ManagementPage';

export function App() {
  const [status, setStatus] = useState('Connecting to backend…');
  const [section, setSection] = useState<'products' | 'warehouses' | 'inventory' | 'customers' | 'orders'>('products');
  useEffect(() => {
    const controller = new AbortController();
    getSystemInfo(controller.signal)
      .then(info => setStatus(`Connected to ${info.application} API ${info.apiVersion}`))
      .catch(() => { if (!controller.signal.aborted) setStatus('Backend unavailable. Check that the backend is running.'); });
    return () => controller.abort();
  }, []);
  return <div className="app-shell">
    <aside className="sidebar"><a className="brand" href="#"><span className="brand-icon">OF</span>OperationsFlow</a><p className="nav-label">OPERATIONS</p>
      <nav aria-label="Main navigation"><button className={section === 'products' ? 'selected' : ''} aria-current={section === 'products' ? 'page' : undefined} onClick={() => setSection('products')}>Products<span>Catalog & SKUs</span></button><button className={section === 'warehouses' ? 'selected' : ''} aria-current={section === 'warehouses' ? 'page' : undefined} onClick={() => setSection('warehouses')}>Warehouses<span>Locations & details</span></button><button className={section === 'inventory' ? 'selected' : ''} aria-current={section === 'inventory' ? 'page' : undefined} onClick={() => setSection('inventory')}>Inventory<span>Stock & reservations</span></button><button className={section === 'customers' ? 'selected' : ''} aria-current={section === 'customers' ? 'page' : undefined} onClick={() => setSection('customers')}>Customers<span>Business directory</span></button><button className={section === 'orders' ? 'selected' : ''} aria-current={section === 'orders' ? 'page' : undefined} onClick={() => setSection('orders')}>Orders<span>Allocation & cancellation</span></button></nav>
      <div className="sidebar-note">Operations workspace<small>Inventory & order management</small></div>
    </aside>
    <div className="workspace"><header className="topbar"><span>Operations workspace</span><span className="environment">Local development</span></header>
      <main>{section === 'orders' ? <OrdersPage /> : section === 'inventory' ? <InventoryPage /> : <ManagementPage key={section} kind={section} />}</main>
      <footer role="status">{status}</footer>
    </div>
  </div>;
}
