-- CreateEnum
CREATE TYPE "PurchaseKind" AS ENUM ('LOCAL', 'IMPORT');

-- AlterTable
ALTER TABLE "purchase_order_items" ADD COLUMN     "cbm" DECIMAL(12,4),
ADD COLUMN     "created_product" BOOLEAN NOT NULL DEFAULT false,
ADD COLUMN     "landed_unit_cost" DECIMAL(14,4),
ADD COLUMN     "unit_price_foreign" DECIMAL(14,4);

-- AlterTable
ALTER TABLE "purchase_orders" ADD COLUMN     "cbm_rate_usd" DECIMAL(14,2),
ADD COLUMN     "commission_percent" DECIMAL(5,2) NOT NULL DEFAULT 0,
ADD COLUMN     "domestic_shipping" DECIMAL(14,2) NOT NULL DEFAULT 0,
ADD COLUMN     "foreign_currency" TEXT,
ADD COLUMN     "foreign_per_usd" DECIMAL(14,6),
ADD COLUMN     "freight_cost" DECIMAL(14,2) NOT NULL DEFAULT 0,
ADD COLUMN     "kind" "PurchaseKind" NOT NULL DEFAULT 'LOCAL',
ADD COLUMN     "received_at" TIMESTAMP(3),
ADD COLUMN     "sar_per_usd" DECIMAL(14,6),
ADD COLUMN     "total_cbm" DECIMAL(12,4);

-- AlterTable
ALTER TABLE "suppliers" ADD COLUMN     "commission_percent" DECIMAL(5,2) NOT NULL DEFAULT 0;


-- Import costs are never negative.
ALTER TABLE "suppliers" ADD CONSTRAINT "suppliers_commission_range" CHECK ("commission_percent" >= 0 AND "commission_percent" < 100);
ALTER TABLE "purchase_orders" ADD CONSTRAINT "purchase_orders_import_costs_nonneg" CHECK (
  "commission_percent" >= 0 AND "domestic_shipping" >= 0 AND "freight_cost" >= 0
  AND COALESCE("total_cbm", 0) >= 0 AND COALESCE("cbm_rate_usd", 0) >= 0
  AND COALESCE("foreign_per_usd", 1) > 0 AND COALESCE("sar_per_usd", 1) > 0
);
ALTER TABLE "purchase_order_items" ADD CONSTRAINT "purchase_order_items_import_nonneg" CHECK (
  COALESCE("unit_price_foreign", 0) >= 0 AND COALESCE("cbm", 0) >= 0
);
