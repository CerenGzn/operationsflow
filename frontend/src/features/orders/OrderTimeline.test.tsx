import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { OrderTimeline, type Timeline } from './OrderTimeline';
const at = '2026-01-02T13:04:05Z';
const stage = (code: string, label: string, progress: Timeline['milestones'][number]['progress'], occurredAt: string | null = null) => ({ code, label, progress, occurredAt, detail: 'Recorded operational facts.' });
function api(milestones: Timeline['milestones'], status = 'RESERVED') {
  const fetch = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ orderId: 'o1', status, milestones }) });
  vi.stubGlobal('fetch', fetch);return fetch;
}
describe('order timeline', () => {
  it('shows confirmed creation and pending milestones without inventing timestamps', async () => {
    api([stage('CONFIRMED','Order Confirmed','COMPLETED',at),stage('PREPARING','Preparing Order','PENDING'),stage('PACKED','Packed','PENDING'),stage('SHIPPED','Shipped','PENDING')]);
    const { container }=render(<OrderTimeline orderId="o1" revision="RESERVED" />);
    expect(screen.getByRole('status')).toHaveTextContent('Loading timeline');
    expect(await screen.findByText('Stock reserved')).toBeInTheDocument();
    expect(screen.getAllByText('Pending')).toHaveLength(3);
    expect(container.querySelectorAll('time')).toHaveLength(1);
    expect(container.querySelector('time')).toHaveAttribute('datetime',at);
    expect(screen.getByText('2 Jan 2026, 13:04:05 UTC')).toBeInTheDocument();
    expect(screen.queryByText('Shipment Prepared')).not.toBeInTheDocument();
  });
  it.each([
    ['PROCESSING','PREPARING','Preparing Order'],['PROCESSING','PACKED','Packed'],
    ['READY_TO_SHIP','SHIPMENT_PREPARED','Shipment Prepared'],['SHIPPED','SHIPPED','Shipped'],
  ])('renders persisted %s / %s confirmation', async (status,code,label) => {
    api([stage(code,label,'COMPLETED',at)],status);
    render(<OrderTimeline orderId="o1" revision={status} />);
    const heading=await screen.findByRole('heading',{name:label});
    expect(within(heading.closest('li')!).getByText('Confirmed')).toBeInTheDocument();
    expect(screen.queryByText('Delivered')).not.toBeInTheDocument();
  });
  it('keeps cancellation distinct from uncompleted shipping and unknown historical time', async () => {
    api([stage('SHIPPED','Shipped','NOT_REACHED'),stage('CANCELLED','Cancelled','COMPLETED')],'CANCELLED');
    const { container }=render(<OrderTimeline orderId="o1" revision="CANCELLED" />);
    const heading=await screen.findByRole('heading',{name:'Shipped'});
    expect(within(heading.closest('li')!).getByText('Not reached')).toBeInTheDocument();
    expect(screen.getByText('Timestamp not recorded')).toBeInTheDocument();
    expect(container.querySelector('time')).toBeNull();
  });
  it('handles forbidden responses, refresh and empty history with GET requests only', async () => {
    const fetch=api([]);fetch.mockResolvedValueOnce({ok:false,status:403,json:async()=>({detail:'Your role does not permit this action.'})});
    render(<OrderTimeline orderId="o1" revision="RESERVED" />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Your role does not permit');
    fireEvent.click(screen.getByRole('button',{name:'Refresh timeline'}));
    expect(await screen.findByText('No recorded milestones available.')).toBeInTheDocument();
    expect(fetch.mock.calls).toHaveLength(2);
    expect(fetch.mock.calls.every(([url, options])=>url==='/api/v1/orders/o1/timeline' && (!options.method || options.method==='GET'))).toBe(true);
  });
  it('refreshes on cancellation and represents partially packed warehouses as in progress', async () => {
    const fetch=api([stage('PACKED','Packed','IN_PROGRESS')],'PROCESSING');
    const view=render(<OrderTimeline orderId="o1" revision="PROCESSING" />);
    expect(await screen.findByText('In progress')).toBeInTheDocument();
    fetch.mockResolvedValue({ok:true,json:async()=>({orderId:'o1',status:'CANCELLED',milestones:[stage('CANCELLED','Cancelled','COMPLETED',at)]})});
    view.rerender(<OrderTimeline orderId="o1" revision="CANCELLED" />);
    expect(await screen.findByRole('heading',{name:'Cancelled'})).toBeInTheDocument();
    expect(screen.queryByText('In progress')).not.toBeInTheDocument();
  });
});
