import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ManagementPage } from './ManagementPage';

const product = { id: 'p1', name: 'Synthetic chair', description: 'Test description', status: 'ACTIVE', version: 2, skus: [{ id: 's1', code: 'CHAIR-01' }] };
const warehouse = { id: 'w1', name: 'Synthetic Berlin', code: 'BER-01', addressLine: 'Example street 1', city: 'Berlin', postalCode: '10115', countryCode: 'DE', status: 'ACTIVE', version: 0 };
const page = (content: unknown[] = []) => ({ content, page: 0, size: 10, totalElements: content.length, totalPages: content.length ? 1 : 0 });
const response = (body: unknown, ok = true) => ({ ok, status: ok ? 200 : 409, json: async () => body });

describe('management screens', () => {
  it('renders loading, empty results and server errors with retry', async () => {
    const fetchMock = vi.fn().mockRejectedValueOnce(new TypeError('offline')).mockResolvedValue(response(page()));
    vi.stubGlobal('fetch', fetchMock);
    render(<ManagementPage kind="products" />);
    expect(screen.getByText('Loading products…')).toBeInTheDocument();
    expect(await screen.findByRole('alert')).toHaveTextContent('Cannot reach the API');
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('No products found')).toBeInTheDocument();
  });

  it('creates a product through the real request contract', async () => {
    const fetchMock = vi.fn().mockImplementation(async (_url, options) => response(options?.method === 'POST' ? product : page()));
    vi.stubGlobal('fetch', fetchMock);
    render(<ManagementPage kind="products" />);
    await screen.findByText('No products found');
    fireEvent.click(screen.getByRole('button', { name: '+ New product' }));
    fireEvent.change(screen.getByLabelText(/Initial SKU code/), { target: { value: 'CHAIR-01' } });
    fireEvent.change(screen.getByLabelText(/Product name/), { target: { value: 'Synthetic chair' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save product' }));
    await screen.findByText('Product saved.');
    const call = fetchMock.mock.calls.find(([, options]) => options?.method === 'POST');
    expect(call?.[0]).toBe('/api/v1/products');
    expect(JSON.parse(call?.[1].body)).toEqual({ name: 'Synthetic chair', description: '', skuCode: 'CHAIR-01' });
  });

  it('submits search and filters to the backend rather than filtering fixtures', async () => {
    const fetchMock = vi.fn().mockResolvedValue(response(page([product])));
    vi.stubGlobal('fetch', fetchMock);
    render(<ManagementPage kind="products" />);
    await screen.findByText('Synthetic chair');
    fireEvent.change(screen.getByLabelText('Search products'), { target: { value: 'CHAIR' } });
    fireEvent.click(screen.getByRole('button', { name: 'Search' }));
    fireEvent.change(screen.getByLabelText('Status'), { target: { value: 'INACTIVE' } });
    await waitFor(() => expect(fetchMock.mock.calls.at(-1)?.[0]).toContain('status=INACTIVE'));
    expect(fetchMock.mock.calls.at(-1)?.[0]).toContain('query=CHAIR');
  });

  it('loads latest details, preserves immutable SKU and sends the version when editing', async () => {
    const fetchMock = vi.fn().mockImplementation(async url => response(url.includes('?') ? page([product]) : product));
    vi.stubGlobal('fetch', fetchMock);
    render(<ManagementPage kind="products" />);
    fireEvent.click(await screen.findByRole('button', { name: 'View Synthetic chair' }));
    const name = await screen.findByLabelText(/Product name/);
    expect(screen.queryByLabelText(/Initial SKU code/)).not.toBeInTheDocument();
    fireEvent.change(name, { target: { value: 'Updated chair' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save product' }));
    await screen.findByText('Product saved.');
    const call = fetchMock.mock.calls.find(([, options]) => options?.method === 'PUT');
    expect(call?.[0]).toBe('/api/v1/products/p1');
    expect(JSON.parse(call?.[1].body)).toEqual({ name: 'Updated chair', description: 'Test description', version: 2 });
  });

  it('shows conflict details and preserves entered values for correction', async () => {
    const fetchMock = vi.fn().mockImplementation(async (_url, options) => response(options?.method === 'POST' ? { detail: 'Identifier already exists.' } : page(), options?.method !== 'POST'));
    vi.stubGlobal('fetch', fetchMock);
    render(<ManagementPage kind="products" />);
    fireEvent.click(screen.getByRole('button', { name: '+ New product' }));
    fireEvent.change(screen.getByLabelText(/Initial SKU code/), { target: { value: 'CHAIR-01' } });
    fireEvent.change(screen.getByLabelText(/Product name/), { target: { value: 'Synthetic chair' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save product' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Identifier already exists.');
    expect(screen.getByLabelText(/Product name/)).toHaveValue('Synthetic chair');
  });

  it('creates warehouse metadata and confirms deactivation explicitly', async () => {
    const fetchMock = vi.fn().mockImplementation(async url => response(url.includes('?') ? page([warehouse]) : warehouse));
    vi.stubGlobal('fetch', fetchMock);
    render(<ManagementPage kind="warehouses" />);
    fireEvent.click(screen.getByRole('button', { name: '+ New warehouse' }));
    for (const [label, value] of [['Warehouse code', 'BER-01'], ['Warehouse name', 'Synthetic Berlin'], ['Street address', 'Example street 1'], ['City', 'Berlin'], ['Postal code', '10115'], ['Country code', 'DE']]) {
      fireEvent.change(screen.getByLabelText(new RegExp(label)), { target: { value } });
    }
    fireEvent.click(screen.getByRole('button', { name: 'Save warehouse' }));
    await screen.findByText('Warehouse saved.');
    const create = fetchMock.mock.calls.find(([, options]) => options?.method === 'POST');
    expect(JSON.parse(create?.[1].body)).toMatchObject({ code: 'BER-01', city: 'Berlin', countryCode: 'DE' });
    fireEvent.click(await screen.findByRole('button', { name: 'View Synthetic Berlin' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Deactivate warehouse' }));
    expect(fetchMock.mock.calls.filter(([url]) => url.includes('/deactivate'))).toHaveLength(0);
    fireEvent.click(screen.getByRole('button', { name: 'Confirm deactivation' }));
    await screen.findByText(/Warehouse deactivated/);
    expect(fetchMock.mock.calls.find(([url]) => url.endsWith('/deactivate'))?.[1].body).toBe('{"version":0}');
  });

  it('renders inactive records read-only', async () => {
    const inactive = { ...warehouse, status: 'INACTIVE' };
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async url => response(url.includes('?') ? page([inactive]) : inactive)));
    render(<ManagementPage kind="warehouses" />);
    fireEvent.click(await screen.findByRole('button', { name: 'View Synthetic Berlin' }));
    expect(await screen.findByText('This record is inactive and read-only.')).toBeInTheDocument();
    expect(screen.getByLabelText(/Warehouse name/)).toBeDisabled();
    expect(screen.queryByRole('button', { name: 'Save warehouse' })).not.toBeInTheDocument();
  });
});

describe('customer management', () => {
  const customer = { ...warehouse, id: 'c1', name: 'Synthetic customer', referenceNumber: 'SYNTH-01', code: undefined };
  it('creates a business customer with a permanent reference and shipping address', async () => {
    const mock = vi.fn().mockImplementation(async (_url, options) => response(options?.method === 'POST' ? customer : page()));
    vi.stubGlobal('fetch', mock); render(<ManagementPage kind="customers" />);
    fireEvent.click(screen.getByRole('button', { name: '+ New customer' }));
    for (const [label, value] of [['Customer reference', 'SYNTH-01'], ['Business name', 'Synthetic customer'], ['Street address', 'Example street 1'], ['City', 'Berlin'], ['Postal code', '10115'], ['Country code', 'DE']]) fireEvent.change(screen.getByLabelText(new RegExp(label)), { target: { value } });
    fireEvent.click(screen.getByRole('button', { name: 'Save customer' })); await screen.findByText('Customer saved.');
    const call = mock.mock.calls.find(([, o]) => o?.method === 'POST'); expect(call?.[0]).toBe('/api/v1/customers');
    expect(JSON.parse(call?.[1].body)).toMatchObject({ referenceNumber: 'SYNTH-01', name: 'Synthetic customer', addressLine: 'Example street 1' });
  });
  it('protects customer reference on edit and displays an open-order deactivation conflict', async () => {
    const mock = vi.fn().mockImplementation(async (url, options) => options?.method === 'POST' ? response({ detail: 'Cancel open orders before deactivating this customer.' }, false) : response(url.includes('?') ? page([customer]) : customer));
    vi.stubGlobal('fetch', mock); render(<ManagementPage kind="customers" />);
    fireEvent.click(await screen.findByRole('button', { name: 'View Synthetic customer' })); await screen.findByLabelText(/Business name/);
    expect(screen.queryByLabelText(/Customer reference/)).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Deactivate customer' })); fireEvent.click(screen.getByRole('button', { name: 'Confirm deactivation' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Cancel open orders'); expect(screen.getByLabelText(/Business name/)).toHaveValue('Synthetic customer');
  });
});
