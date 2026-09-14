export function formatCurrency(amount: number): string {
  return amount.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

/** Ratios/rates come back as decimal fractions (0.28 -> "28.00%"). */
export function formatPercent(ratio: number | null, fractionDigits = 2): string {
  if (ratio === null) return '—'
  return `${(ratio * 100).toFixed(fractionDigits)}%`
}

/** Simulated timestamps come back as LocalDateTime strings with no offset, e.g. "2027-03-14T09:00:00". */
export function formatDateTime(isoLocal: string): string {
  const [date, time] = isoLocal.split('T')
  return `${date} ${(time ?? '').slice(0, 8)}`
}
