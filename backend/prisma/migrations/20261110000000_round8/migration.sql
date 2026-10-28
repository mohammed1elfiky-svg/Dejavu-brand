-- CreateEnum
CREATE TYPE "TransferSource" AS ENUM ('TOP_UP', 'POS', 'RECEIPT');

-- AlterTable
ALTER TABLE "layaways" ADD COLUMN     "held_at" TIMESTAMP(3),
ADD COLUMN     "held_by_id" UUID;

-- AlterTable
ALTER TABLE "orders" ADD COLUMN     "approved_at" TIMESTAMP(3),
ADD COLUMN     "approved_by_id" UUID,
ADD COLUMN     "needs_approval" BOOLEAN NOT NULL DEFAULT false;

-- AlterTable
ALTER TABLE "pre_orders" ADD COLUMN     "confirmed_at" TIMESTAMP(3),
ADD COLUMN     "created_by_id" UUID,
ADD COLUMN     "item_color" TEXT,
ADD COLUMN     "item_name" TEXT,
ADD COLUMN     "item_size" TEXT,
ADD COLUMN     "unit_price" DECIMAL(14,2),
ALTER COLUMN "variant_id" DROP NOT NULL;

-- AlterTable
ALTER TABLE "products" ADD COLUMN     "view_count" INTEGER NOT NULL DEFAULT 0;

-- AlterTable
ALTER TABLE "stored_files" ADD COLUMN     "sha256" TEXT;

-- AlterTable
ALTER TABLE "wallet_top_ups" ADD COLUMN     "auto_approved" BOOLEAN NOT NULL DEFAULT false,
ADD COLUMN     "verified_at" TIMESTAMP(3),
ADD COLUMN     "verified_by_id" UUID;

-- CreateTable
CREATE TABLE "bank_transfers" (
    "id" UUID NOT NULL,
    "reference" TEXT,
    "source" "TransferSource" NOT NULL,
    "customer_id" UUID,
    "currency" TEXT,
    "amount" DECIMAL(18,2),
    "bank_name" TEXT,
    "extracted" BOOLEAN NOT NULL DEFAULT false,
    "file_id" UUID,
    "top_up_id" UUID,
    "order_id" UUID,
    "created_by_id" UUID,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "bank_transfers_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "bank_transfers_reference_key" ON "bank_transfers"("reference");

-- CreateIndex
CREATE INDEX "bank_transfers_created_at_idx" ON "bank_transfers"("created_at");

-- CreateIndex
CREATE INDEX "bank_transfers_customer_id_idx" ON "bank_transfers"("customer_id");

-- CreateIndex
CREATE INDEX "stored_files_sha256_idx" ON "stored_files"("sha256");


-- Backfill: visits so far, and fingerprints of the files already stored.
UPDATE "products" p SET "view_count" = v.n FROM (SELECT "product_id", COUNT(*)::int AS n FROM "product_views" GROUP BY "product_id") v WHERE v."product_id" = p."id";
UPDATE "stored_files" SET "sha256" = encode(sha256("data"), 'hex') WHERE "sha256" IS NULL AND "is_public" = false;
-- AddForeignKey
ALTER TABLE "layaways" ADD CONSTRAINT "layaways_held_by_id_fkey" FOREIGN KEY ("held_by_id") REFERENCES "users"("id") ON DELETE SET NULL ON UPDATE CASCADE;

