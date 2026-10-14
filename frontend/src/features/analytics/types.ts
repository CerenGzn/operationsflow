export interface Range { from: string; to: string; zone: string }
export interface Point { label: string; value: string; secondary: string }
export interface DashboardData { asOf: string; range: Range; lowStockThreshold: number; kpis: Record<string, string>; orderVolume: Point[]; orderStatus: Point[]; inventoryByWarehouse: Point[]; movementTrends: Point[] | null; shipmentThroughput: Point[]; fulfillmentStatus: Point[]; topSkus: Point[]; recentActivity: { type: string; reference: string; occurredAt: string }[] }
export interface ReportData { kind: string; dateBasis: string; asOf: string; range: Range; columns: { key: string; label: string; numeric: boolean }[]; content: Record<string, string>[]; page: number; size: number; totalElements: number; totalPages: number }
export function integer(value: string) { try { return new Intl.NumberFormat().format(BigInt(value)); } catch { return value; } }
export function initialRange(zone = 'Europe/Berlin'): Range {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date());
  const part = (name: string) => parts.find(p => p.type === name)!.value;
  const to = `${part('year')}-${part('month')}-${part('day')}`; const start = new Date(`${to}T12:00:00Z`); start.setUTCDate(start.getUTCDate() - 29);
  return { from: start.toISOString().slice(0, 10), to, zone };
}
export function validateRange(range: Range) { const days = (Date.parse(range.to) - Date.parse(range.from)) / 86400000; return Number.isFinite(days) && days >= 0 && days <= 365 && range.from >= '1900-01-01' && range.to <= '2100-12-31'; }
