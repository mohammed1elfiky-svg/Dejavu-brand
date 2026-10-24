-- CreateEnum
CREATE TYPE "SupplierKind" AS ENUM ('SUPPLIER', 'SHIPPING_COMPANY');

-- AlterTable
ALTER TABLE "carts" ADD COLUMN     "reminded_at" TIMESTAMP(3);

-- AlterTable
ALTER TABLE "orders" ADD COLUMN     "shipping_receipt_file_id" UUID;

-- AlterTable
ALTER TABLE "purchase_orders" ADD COLUMN     "invoice_image_url" TEXT,
ADD COLUMN     "shipping_company_id" UUID;

-- AlterTable
ALTER TABLE "store_bank_accounts" ADD COLUMN     "currencies" TEXT[] DEFAULT ARRAY[]::TEXT[],
ADD COLUMN     "image_url" TEXT;

-- AlterTable
ALTER TABLE "suppliers" ADD COLUMN     "kind" "SupplierKind" NOT NULL DEFAULT 'SUPPLIER';

-- CreateTable
CREATE TABLE "stock_alerts" (
    "id" UUID NOT NULL,
    "customer_id" UUID NOT NULL,
    "variant_id" UUID NOT NULL,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "notified_at" TIMESTAMP(3),

    CONSTRAINT "stock_alerts_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "stock_alerts_notified_at_idx" ON "stock_alerts"("notified_at");

-- CreateIndex
CREATE UNIQUE INDEX "stock_alerts_customer_id_variant_id_key" ON "stock_alerts"("customer_id", "variant_id");

-- AddForeignKey
ALTER TABLE "purchase_orders" ADD CONSTRAINT "purchase_orders_shipping_company_id_fkey" FOREIGN KEY ("shipping_company_id") REFERENCES "suppliers"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "stock_alerts" ADD CONSTRAINT "stock_alerts_customer_id_fkey" FOREIGN KEY ("customer_id") REFERENCES "customers"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "stock_alerts" ADD CONSTRAINT "stock_alerts_variant_id_fkey" FOREIGN KEY ("variant_id") REFERENCES "product_variants"("id") ON DELETE CASCADE ON UPDATE CASCADE;


-- Existing bank accounts take their one currency.
UPDATE "store_bank_accounts" SET "currencies" = ARRAY["currency"] WHERE cardinality("currencies") = 0;
