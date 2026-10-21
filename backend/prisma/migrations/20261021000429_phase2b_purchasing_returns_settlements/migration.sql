-- AlterEnum
ALTER TYPE "JournalSourceType" ADD VALUE 'COURIER_SETTLEMENT' AFTER 'SUPPLIER_PAYMENT';

-- AlterTable
ALTER TABLE "expenses" ADD COLUMN     "approved_at" TIMESTAMP(3),
ADD COLUMN     "paid_at" TIMESTAMP(3);

-- AlterTable
ALTER TABLE "orders" ADD COLUMN     "returned_total" DECIMAL(14,2) NOT NULL DEFAULT 0;

-- AlterTable
ALTER TABLE "sales_return_items" ADD COLUMN     "tax_amount" DECIMAL(14,2) NOT NULL DEFAULT 0;

-- AlterTable
ALTER TABLE "sales_returns" ADD COLUMN     "received_at" TIMESTAMP(3),
ADD COLUMN     "reject_reason" TEXT;

-- AlterTable
ALTER TABLE "shipments" ADD COLUMN     "settlement_id" UUID;

-- CreateTable
CREATE TABLE "courier_settlements" (
    "id" UUID NOT NULL,
    "number" TEXT NOT NULL,
    "carrier" TEXT NOT NULL,
    "collected_amount" DECIMAL(14,2) NOT NULL,
    "fees_amount" DECIMAL(14,2) NOT NULL DEFAULT 0,
    "net_amount" DECIMAL(14,2) NOT NULL,
    "account_id" UUID NOT NULL,
    "reference" TEXT,
    "settled_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "created_by_id" UUID NOT NULL,

    CONSTRAINT "courier_settlements_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "courier_settlements_number_key" ON "courier_settlements"("number");

-- CreateIndex
CREATE INDEX "courier_settlements_carrier_idx" ON "courier_settlements"("carrier");

-- CreateIndex
CREATE INDEX "shipments_carrier_settlement_id_idx" ON "shipments"("carrier", "settlement_id");

-- AddForeignKey
ALTER TABLE "shipments" ADD CONSTRAINT "shipments_settlement_id_fkey" FOREIGN KEY ("settlement_id") REFERENCES "courier_settlements"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "courier_settlements" ADD CONSTRAINT "courier_settlements_account_id_fkey" FOREIGN KEY ("account_id") REFERENCES "accounts"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "courier_settlements" ADD CONSTRAINT "courier_settlements_created_by_id_fkey" FOREIGN KEY ("created_by_id") REFERENCES "users"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- ---------- Integrity rules for the new money fields -------------------------
ALTER TABLE orders ADD CONSTRAINT orders_returned_total_chk
  CHECK (returned_total >= 0 AND returned_total <= grand_total);
ALTER TABLE courier_settlements ADD CONSTRAINT courier_settlements_amounts_chk
  CHECK (collected_amount > 0 AND fees_amount >= 0 AND net_amount = collected_amount - fees_amount);
ALTER TABLE sales_return_items ADD CONSTRAINT sales_return_items_amounts_chk
  CHECK (quantity > 0 AND refund_amount >= 0 AND tax_amount >= 0 AND tax_amount <= refund_amount);
ALTER TABLE expenses ADD CONSTRAINT expenses_amounts_chk CHECK (amount > 0 AND tax_amount >= 0);
ALTER TABLE supplier_payments ADD CONSTRAINT supplier_payments_amount_chk CHECK (amount > 0);
ALTER TABLE purchase_orders ADD CONSTRAINT purchase_orders_amounts_chk
  CHECK (subtotal >= 0 AND tax_total >= 0 AND shipping_cost >= 0 AND grand_total >= 0 AND amount_paid >= 0);
