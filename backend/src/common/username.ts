/**
 * A username is the customer's Instagram handle, so it follows Instagram's rule: 1–30 letters,
 * digits, "." or "_" (it may start with a digit), no two dots in a row and no dot at the end.
 */
export const USERNAME_REGEX = /^(?!.*\.\.)(?!.*\.$)[a-z0-9._]{1,30}$/;

export const USERNAME_MESSAGE = 'اليوزر حروف إنجليزية وأرقام ونقطة أو _ (حتى 30 حرفاً)، بلا نقطتين متتاليتين ولا نقطة في آخره';

/** "@Sara.Ali " → "sara.ali" (usernames are stored lowercase, without the @). */
export const normalizeUsername = (raw: string) => raw.trim().replace(/^@/, '').toLowerCase();

export const isUsername = (raw: string) => USERNAME_REGEX.test(normalizeUsername(raw));

/** Digits (and a leading +) only, long enough to be a mobile number: otherwise it is a username. */
export const looksLikePhone = (raw: string) => {
  const t = raw.trim().replace(/[\s-]/g, '');
  return !t.startsWith('@') && /^\+?\d{7,15}$/.test(t);
};

/** A 4-digit code the customer sends from their Instagram account to the store's. */
export const instagramCode = () => String(Math.floor(1000 + Math.random() * 9000));
