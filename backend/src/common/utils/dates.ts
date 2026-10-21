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

/** The instant the current business day began in [timeZone] (local midnight). */
export function startOfBusinessDay(timeZone: string, at: Date = new Date()): Date {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(at);
  const get = (t: string) => Number(parts.find((p) => p.type === t)!.value);
  const localAsUtc = Date.UTC(get('year'), get('month') - 1, get('day'), get('hour'), get('minute'), get('second'));
  const offset = localAsUtc - Math.floor(at.getTime() / 1000) * 1000;
  return new Date(Date.UTC(get('year'), get('month') - 1, get('day')) - offset);
}
