export function formatCurrency(amount: number): string {
  return amount.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

/** Simulated timestamps come back as LocalDateTime strings with no offset, e.g. "2027-03-14T09:00:00". */
export function formatDateTime(isoLocal: string): string {
  const [date, time] = isoLocal.split('T')
  return `${date} ${(time ?? '').slice(0, 8)}`
}
