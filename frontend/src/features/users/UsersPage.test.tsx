import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { UsersPage } from './UsersPage';
import { request } from '../../shared/api/management';
vi.mock('../../shared/api/management', () => ({ request: vi.fn() }));
const user = { id: 'test', email: 'operator@example.test', displayName: 'Test Operator', role: 'SALES_OPERATOR', active: true, mustChangePassword: false, version: 2 };
beforeEach(() => { vi.mocked(request).mockReset().mockResolvedValue({ content: [user], totalElements: 1, totalPages: 1 }); });
describe('administrator user management', () => {
  it('loads real users and sends versioned role changes', async () => { render(<UsersPage />); fireEvent.click(await screen.findByRole('button', { name: 'Edit Test Operator' })); fireEvent.change(screen.getByLabelText('Role'), { target: { value: 'WAREHOUSE_OPERATOR' } }); fireEvent.click(screen.getByRole('button', { name: 'Save user' })); await waitFor(() => expect(request).toHaveBeenCalledWith('users/test', expect.objectContaining({ method: 'PUT', body: JSON.stringify({ email: user.email, displayName: user.displayName, role: 'WAREHOUSE_OPERATOR', active: true, version: 2 }) }))); });
  it('shows backend final administrator protection errors', async () => { render(<UsersPage />); fireEvent.click(await screen.findByRole('button', { name: 'Edit Test Operator' })); vi.mocked(request).mockRejectedValueOnce(new Error('The final active administrator cannot be disabled')); fireEvent.click(screen.getByRole('button', { name: 'Save user' })); expect(await screen.findByRole('alert')).toHaveTextContent('final active administrator'); });
  it('shows empty and failed API states', async () => { vi.mocked(request).mockResolvedValueOnce({ content: [], totalElements: 0, totalPages: 0 }); const view = render(<UsersPage />); expect(await screen.findByText('No users found.')).toBeInTheDocument(); view.unmount(); vi.mocked(request).mockRejectedValueOnce(new Error('Forbidden')); render(<UsersPage />); expect(await screen.findByRole('alert')).toHaveTextContent('Forbidden'); });
});

it('lets an administrator explicitly create an individually assigned demo viewer', async () => {
  render(<UsersPage />); fireEvent.click(screen.getByRole('button', { name: '+ New user' }));
  fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'reviewer@example.invalid' } });
  fireEvent.change(screen.getByLabelText('Display name'), { target: { value: 'Synthetic reviewer' } });
  fireEvent.change(screen.getByLabelText('Role'), { target: { value: 'DEMO_VIEWER' } });
  fireEvent.change(screen.getByLabelText('Temporary password'), { target: { value: 'Synthetic-test-password-11!' } });
  fireEvent.click(screen.getByRole('button', { name: 'Save user' }));
  await waitFor(() => expect(request).toHaveBeenCalledWith('users', expect.objectContaining({ method: 'POST', body: JSON.stringify({ email: 'reviewer@example.invalid', displayName: 'Synthetic reviewer', role: 'DEMO_VIEWER', password: 'Synthetic-test-password-11!' }) })));
});
