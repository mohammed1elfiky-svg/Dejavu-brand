-- AlterTable
ALTER TABLE "customers" ADD COLUMN     "findable_by_phone" BOOLEAN NOT NULL DEFAULT true,
ADD COLUMN     "pending_username" TEXT;


-- A username is the customer's Instagram handle and shows only once the store has checked it:
-- the ones typed so far (not yet verified) wait as requests, with a code to send from Instagram.
UPDATE "customers"
SET "pending_username" = "username",
    "username" = NULL,
    "ig_status" = 'PENDING',
    "ig_code" = COALESCE("ig_code", LPAD((FLOOR(RANDOM() * 9000) + 1000)::int::text, 4, '0'))
WHERE "username" IS NOT NULL AND "ig_status" <> 'VERIFIED';
