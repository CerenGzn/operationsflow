import { test, expect, login, nav, write, secret } from './fixtures';
import { mkdir } from 'node:fs/promises';

// The entire lifecycle belongs to one scenario: later assertions depend explicitly on earlier UI actions.
// Each browser gets unique identifiers and a fresh context; no order dependencies between test cases.
test('real operational lifecycle and read-only reviewer security', async ({ page, browser }) => {
  const suffix = crypto.randomUUID().slice(0, 8).toUpperCase();
  const sku = `SKU-${suffix}`, wh = `WH-${suffix}`, customer = `CUSTOMER-${suffix}`;
  let productId = '', skuId = '', warehouseId = '', customerId = '';
  const baseline: Record<string, number> = {};
  const metric = (name: string) => page.locator('article.kpi-card').filter({ has: page.getByRole('heading', { name, exact: true }) }).locator('strong');
  let cancelled: { id: string; orderNumber: string }, shipped: { id: string; orderNumber: string };
  await test.step('administrator login and session persistence', async () => {
    await login(page);
    await expect(page.getByRole('heading', { name: 'Operational overview' })).toBeVisible();
    await page.reload();
    await expect(page.getByRole('heading', { name: 'Operational overview' })).toBeVisible();
    const cookie = (await page.context().cookies()).find(c => c.name === 'JSESSIONID');
    expect(Boolean(cookie?.httpOnly && cookie.sameSite === 'Strict')).toBe(true);
    expect((await page.request.get('/api/v1/auth/session')).status()).toBe(200);
    await expect(page.getByRole('heading', { name: 'Current operations' })).toBeVisible();
    for (const name of ['Total orders', 'Cancelled orders', 'Shipped orders', 'Inventory units', 'Available units'])
      baseline[name] = Number((await metric(name).innerText()).replaceAll(',', ''));
    expect((await page.request.post('/api/v1/orders', { data: {} })).status()).toBe(403); // Missing CSRF.
  });
  async function save(kind: string, fields: Record<string, string>) {
    await nav(page, kind === 'product' ? 'Products' : kind === 'warehouse' ? 'Warehouses' : 'Customers');
    await page.getByRole('button', { name: `+ New ${kind}`, exact: true }).click();
    for (const [label, value] of Object.entries(fields)) await page.getByLabel(label, { exact: false }).fill(value);
    await page.getByRole('button', { name: `Save ${kind}`, exact: true }).click();
    await expect(page.getByText(`${kind[0].toUpperCase() + kind.slice(1)} saved.`, { exact: true })).toBeVisible();
  }
  const address = { 'Street address': 'Synthetic Street 1', City: 'Berlin', 'Postal code': '10115', 'Country code': 'DE' };
  await test.step('create product, warehouse and customer through forms', async () => {
    await save('product', { 'Initial SKU code': sku, 'Product name': `Browser product ${suffix}`, Description: 'Synthetic browser fixture' });
    await save('warehouse', { 'Warehouse code': wh, 'Warehouse name': `Browser warehouse ${suffix}`, ...address });
    await save('customer', { 'Customer reference': customer, 'Business name': `Browser customer ${suffix}`, ...address });
    const p = await (await page.request.get(`/api/v1/products?query=${sku}`)).json();
    productId = p.content[0].id; skuId = p.content[0].skus[0].id;
    warehouseId = (await (await page.request.get(`/api/v1/warehouses?query=${wh}`)).json()).content[0].id;
    customerId = (await (await page.request.get(`/api/v1/customers?query=${customer}`)).json()).content[0].id;
  });
  async function balance(onHand: number, reserved: number, available: number) {
    await nav(page, 'Inventory');
    await page.getByLabel('Warehouse', { exact: true }).selectOption(warehouseId);
    await page.getByLabel('SKU', { exact: true }).selectOption(skuId);
    const row = page.getByRole('row').filter({ hasText: sku });
    await expect(row).toHaveCount(1);
    await expect(row.getByRole('cell').nth(2)).toHaveText(String(onHand));
    await expect(row.getByRole('cell').nth(3)).toHaveText(String(reserved));
    await expect(row.getByRole('cell').nth(4)).toHaveText(String(available));
  }
  await test.step('receipt produces 100 on hand, zero reserved and 100 available', async () => {
    await nav(page, 'Inventory');
    await page.getByLabel('Warehouse', { exact: true }).selectOption(warehouseId);
    await page.getByLabel('SKU', { exact: true }).selectOption(skuId);
    await page.getByLabel('Quantity', { exact: true }).fill('100');
    await page.getByLabel('Reason', { exact: true }).fill('Synthetic browser receipt');
    await page.getByRole('button', { name: 'Receive stock', exact: true }).click();
    await expect(page.getByText('Stock operation recorded. Balances and history refreshed.')).toBeVisible();
    await balance(100, 0, 100);
  });
  async function order(quantity: number) {
    await nav(page, 'Orders');
    await page.getByRole('button', { name: '+ New order', exact: true }).click();
    await page.getByLabel('Customer', { exact: true }).selectOption(customerId);
    await page.getByLabel('SKU', { exact: true }).selectOption(skuId);
    await expect(page.getByLabel('Stock availability')).toContainText('100 available units');
    await page.getByLabel('Quantity', { exact: true }).fill(String(quantity));
    await page.getByRole('button', { name: 'Add line', exact: true }).click();
    const response = page.waitForResponse(r => r.url().endsWith('/api/v1/orders') && r.request().method() === 'POST');
    await page.getByRole('button', { name: 'Create & reserve order', exact: true }).click();
    const result = await response; expect(result.status()).toBe(201);
    const body = await result.json();
    await expect(page.getByText(`${body.orderNumber} created. Stock reserved. Open the order to see its current status.`)).toBeVisible();
    return body;
  }
  await test.step('create and cancel an order; reserved stock is released', async () => {
    cancelled = await order(20); await balance(100, 20, 80);
    await nav(page, 'Orders');
    await page.getByRole('button', { name: `View ${cancelled.orderNumber}`, exact: true }).click();
    await page.getByRole('button', { name: 'Cancel order', exact: true }).click();
    await page.getByLabel('Cancellation reason', { exact: true }).fill('Synthetic cancellation');
    await page.getByRole('button', { name: 'Confirm cancellation', exact: true }).click();
    await expect(page.getByText('Order cancelled. Reserved stock released; on-hand stock is unchanged.')).toBeVisible();
    await balance(100, 0, 100);
  });
  await test.step('create order and pick, pack and prepare shipment', async () => {
    shipped = await order(30); await balance(100, 30, 70);
    await nav(page, 'Fulfillment');
    await page.getByRole('button', { name: `Open ${shipped.orderNumber} ${wh}`, exact: true }).click();
    await page.getByLabel(`Picked ${sku}`, { exact: true }).fill('30');
    await page.getByRole('button', { name: `Confirm ${sku}`, exact: true }).click();
    await page.getByLabel(`Verified ${sku}`, { exact: true }).fill('30');
    await page.getByRole('button', { name: 'Verify and pack', exact: true }).click();
    await page.getByLabel('Carrier', { exact: true }).fill('Synthetic Carrier');
    await page.getByLabel('Tracking reference', { exact: true }).fill(`TRACK-${suffix}`);
    await page.getByRole('button', { name: 'Create shipment', exact: true }).click();
    await expect(page.getByRole('button', { name: 'Dispatch whole order', exact: true })).toBeVisible();
  });
  await test.step('viewer cannot mutate a dispatchable order through UI or direct API', async () => {
    const email = `viewer-${suffix.toLowerCase()}@example.invalid`, temporary = secret(), password = secret();
    const created = await write(page, 'users', 'POST', { email, displayName: 'Synthetic reviewer', role: 'DEMO_VIEWER', password: temporary });
    expect(created.status()).toBe(201);
    const viewerContext = await browser.newContext({ baseURL: 'http://127.0.0.1:18081' });
    const viewer = await viewerContext.newPage();
    try {
      await login(viewer, email, temporary);
      await expect(viewer.getByRole('heading', { name: 'Change password' })).toBeVisible();
      await viewer.getByLabel('Current password', { exact: true }).fill(temporary);
      await viewer.getByLabel('New password', { exact: true }).fill(password);
      await viewer.getByLabel('Confirm new password', { exact: true }).fill(password);
      await viewer.getByRole('button', { name: 'Change password', exact: true }).click();
      await expect(viewer.getByRole('heading', { name: 'Sign in' })).toBeVisible();
      await login(viewer, email, password);
      await expect(viewer.getByText('Read-only demo · Shared synthetic data · No business changes or exports')).toBeVisible();
      for (const section of ['Dashboard', 'Products', 'Warehouses', 'Inventory', 'Orders', 'Fulfillment', 'Reports']) {
        await nav(viewer, section);
        await expect(viewer.locator('main h1')).toBeVisible();
        await expect(viewer.getByRole('alert')).toHaveCount(0);
      }
      await expect(viewer.getByRole('navigation').getByRole('button', { name: 'Users', exact: true })).toHaveCount(0);
      await expect(viewer.getByRole('navigation').getByRole('button', { name: 'Customers', exact: true })).toHaveCount(0);
      await nav(viewer, 'Orders');
      await expect(viewer.getByRole('table')).toContainText(shipped.orderNumber);
      for (const name of ['+ New order', 'Cancel order', 'Confirm cancellation', 'Edit order'])
        await expect(viewer.getByRole('button', { name, exact: true })).toHaveCount(0);
      await nav(viewer, 'Fulfillment');
      await expect(viewer.getByRole('table')).toContainText(shipped.orderNumber);
      await expect(viewer.getByRole('button', { name: /Dispatch|pack|Confirm/ })).toHaveCount(0);
      const before = await (await page.request.get(`/api/v1/orders/${shipped.id}`)).json();
      const task = (await (await page.request.get(`/api/v1/fulfillment/orders/${shipped.id}`)).json()).tasks[0];
      const mutations: [string, string, unknown][] = [
        ['orders', 'POST', { customerId, lines: [{ skuId, quantity: 1 }] }],
        [`orders/${shipped.id}`, 'PUT', { customerId, lines: [{ skuId, quantity: 1 }] }],
        [`orders/${shipped.id}/cancel`, 'POST', { reason: 'Denied synthetic cancellation' }],
        [`fulfillment/orders/${shipped.id}/dispatch`, 'POST', undefined],
        [`fulfillment/tasks/${task.id}/picks/${skuId}`, 'PUT', { quantity: 30, version: task.version }],
        [`fulfillment/tasks/${task.id}/pack`, 'POST', { version: task.version, lines: [{ skuId, quantity: 30 }] }],
        [`fulfillment/tasks/${task.id}/shipment`, 'POST', { version: task.version, carrier: 'Synthetic', trackingReference: 'DENIED' }],
        ['inventory/receipts', 'POST', { warehouseId, skuId, quantity: 1, reason: 'Denied synthetic receipt' }],
        [`products/${productId}/deactivate`, 'POST', { version: 0 }],
      ];
      for (const [path, method, data] of mutations) expect((await write(viewer, path, method, data)).status()).toBe(403);
      for (const path of ['products', 'warehouses', 'inventory/balances', 'analytics/dashboard',
        'reports/orders', 'reports/inventory', 'reports/low-stock', 'reports/fulfillment', 'reports/movements'])
        expect((await viewer.request.get('/api/v1/' + path)).status()).toBe(200);
      for (const path of ['users', 'customers', 'orders', 'reports/audit', 'reports/orders/export'])
        expect((await viewer.request.get('/api/v1/' + path)).status()).toBe(403);
      expect(await (await page.request.get(`/api/v1/orders/${shipped.id}`)).json()).toEqual(before);
      await balance(100, 30, 70);
    } catch (error) {
      await mkdir('safe-artifacts', { recursive: true });
      await viewer.screenshot({ path: `safe-artifacts/viewer-${suffix}.png`,
        mask: [viewer.locator('input, textarea, select, .sidebar-note, .auth-card')], animations: 'disabled' }).catch(() => {});
      throw error;
    } finally { await viewerContext.close(); }
  });
  await test.step('dispatch deducts stock once and reports the shipment', async () => {
    await nav(page, 'Fulfillment');
    await page.getByRole('button', { name: `Open ${shipped.orderNumber} ${wh}`, exact: true }).click();
    await page.getByRole('button', { name: 'Dispatch whole order', exact: true }).click();
    await page.getByRole('button', { name: 'Confirm dispatch', exact: true }).click();
    await expect(page.getByLabel('Fulfillment order details')).toContainText('SHIPPED');
    await balance(70, 0, 70);
    await nav(page, 'Reports');
    await page.getByLabel('Search report', { exact: true }).fill(shipped.orderNumber);
    await page.getByLabel('Record status', { exact: true }).selectOption('SHIPPED');
    await page.getByRole('button', { name: 'Apply report filters' }).click();
    await expect(page.getByRole('heading', { name: '1 matching records', exact: true })).toBeVisible();
    await expect(page.getByRole('table')).toContainText(shipped.orderNumber);
    const downloadEvent = page.waitForEvent('download');
    await page.getByRole('button', { name: 'Export filtered CSV' }).click();
    const download = await downloadEvent; expect(await download.failure()).toBeNull();
    const stream = await download.createReadStream(); let csv = '';
    for await (const chunk of stream!) csv += chunk.toString();
    expect(csv.includes(shipped.orderNumber) && csv.includes('SHIPPED')).toBe(true);
    await download.delete();
    await nav(page, 'Dashboard');
    await expect(page.getByRole('heading', { name: 'Current operations' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Order volume', exact: true })).toBeVisible();
    for (const [name, delta] of Object.entries({ 'Total orders': 2, 'Cancelled orders': 1, 'Shipped orders': 1, 'Inventory units': 70, 'Available units': 70 }))
      await expect(metric(name)).toHaveText(String(baseline[name] + delta));
    await expect(page.getByRole('alert')).toHaveCount(0);
  });
  await test.step('logout invalidates the authenticated session', async () => {
    await page.getByRole('button', { name: 'Sign out', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
    expect((await page.request.get('/api/v1/auth/session')).status()).toBe(401);
    await page.reload();
    await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
  });
});
