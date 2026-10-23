-- AlterTable
ALTER TABLE "phone_otps" ADD COLUMN     "purpose" TEXT NOT NULL DEFAULT 'REGISTER';

-- AlterTable
ALTER TABLE "users" ADD COLUMN     "sale_currency" TEXT;


-- Existing cashiers sell in Yemeni rials by default; everyone else in SAR.
UPDATE "users" SET "sale_currency" = 'YER'
WHERE "id" IN (SELECT ur."user_id" FROM "user_roles" ur JOIN "roles" r ON r."id" = ur."role_id" WHERE r."name" = 'cashier');
