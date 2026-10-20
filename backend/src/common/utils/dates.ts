/**
 * Returns "today" in the store's timezone as a UTC-midnight Date, which is how
 * Prisma represents PostgreSQL DATE columns.
 */
export function businessDate(timeZone: string, at: Date = new Date()): Date {
  const ymd = new Intl.DateTimeFormat('en-CA', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(at);
  return new Date(`${ymd}T00:00:00.000Z`);
}

/** Parses "YYYY-MM-DD" into a UTC-midnight Date. */
export function parseDateOnly(value: string): Date {
  return new Date(`${value.slice(0, 10)}T00:00:00.000Z`);
}
