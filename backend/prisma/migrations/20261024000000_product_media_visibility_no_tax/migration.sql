-- CreateEnum
CREATE TYPE "ProductVisibility" AS ENUM ('ALL', 'POS_ONLY', 'MERCHANTS', 'HIDDEN');

-- AlterTable
ALTER TABLE "products" ADD COLUMN     "brand" TEXT,
ADD COLUMN     "instagram_url" TEXT,
ADD COLUMN     "video_url" TEXT,
ADD COLUMN     "visibility" "ProductVisibility" NOT NULL DEFAULT 'ALL';


-- Taxes are not used: prices are final, no VAT is charged.
UPDATE "products" SET "tax_rate_id" = NULL;
UPDATE "tax_rates" SET "is_active" = false, "is_default" = false;
