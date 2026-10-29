/** A username: two English letters first, then letters, digits, "." or "_" (like Instagram), 3–30 long. */
export const USERNAME_REGEX = /^[a-z]{2}[a-z0-9._]{1,28}$/;

/** "@Sara.Ali " → "sara.ali" (usernames are stored lowercase, without the @). */
export const normalizeUsername = (raw: string) => raw.trim().replace(/^@/, '').toLowerCase();

export const isUsername = (raw: string) => USERNAME_REGEX.test(normalizeUsername(raw));

/** A 4-digit code the customer sends from their Instagram account to the store's. */
export const instagramCode = () => String(Math.floor(1000 + Math.random() * 9000));
