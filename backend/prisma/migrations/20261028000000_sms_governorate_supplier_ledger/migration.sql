-- CreateEnum
CREATE TYPE "SupplierVoucherKind" AS ENUM ('PAYMENT', 'COMMISSION');

-- CreateEnum
CREATE TYPE "SmsStatus" AS ENUM ('PENDING', 'SENDING', 'SENT', 'FAILED');

-- AlterTable
ALTER TABLE "customers" ADD COLUMN     "area" TEXT,
ADD COLUMN     "governorate" TEXT;

-- AlterTable
ALTER TABLE "products" ADD COLUMN     "bag_size" TEXT,
ADD COLUMN     "heel_height_cm" DECIMAL(5,1);

-- AlterTable
ALTER TABLE "purchase_order_items" ADD COLUMN     "note" TEXT;

-- CreateTable
CREATE TABLE "supplier_account_entries" (
    "id" UUID NOT NULL,
    "supplier_id" UUID NOT NULL,
    "currency" TEXT NOT NULL,
    "amount" DECIMAL(18,2) NOT NULL,
    "source_type" TEXT NOT NULL,
    "source_id" UUID,
    "note" TEXT,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "supplier_account_entries_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "supplier_vouchers" (
    "id" UUID NOT NULL,
    "number" TEXT NOT NULL,
    "supplier_id" UUID NOT NULL,
    "kind" "SupplierVoucherKind" NOT NULL,
    "usd_amount" DECIMAL(14,2) NOT NULL,
    "foreign_currency" TEXT,
    "foreign_per_usd" DECIMAL(14,6),
    "foreign_amount" DECIMAL(18,2),
    "cash_box_id" UUID,
    "period_from" DATE,
    "period_to" DATE,
    "note" TEXT,
    "journal_entry_id" UUID,
    "cancelled_at" TIMESTAMP(3),
    "created_by_id" UUID NOT NULL,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "supplier_vouchers_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "outgoing_sms" (
    "id" UUID NOT NULL,
    "phone" TEXT NOT NULL,
    "body" TEXT NOT NULL,
    "purpose" TEXT NOT NULL,
    "status" "SmsStatus" NOT NULL DEFAULT 'PENDING',
    "attempts" INTEGER NOT NULL DEFAULT 0,
    "claimed_at" TIMESTAMP(3),
    "sent_at" TIMESTAMP(3),
    "error" TEXT,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "outgoing_sms_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "phone_otps" (
    "id" UUID NOT NULL,
    "phone" TEXT NOT NULL,
    "code_hash" TEXT NOT NULL,
    "expires_at" TIMESTAMP(3) NOT NULL,
    "attempts" INTEGER NOT NULL DEFAULT 0,
    "verified_at" TIMESTAMP(3),
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "phone_otps_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "supplier_account_entries_supplier_id_created_at_idx" ON "supplier_account_entries"("supplier_id", "created_at");

-- CreateIndex
CREATE INDEX "supplier_account_entries_source_id_idx" ON "supplier_account_entries"("source_id");

-- CreateIndex
CREATE UNIQUE INDEX "supplier_vouchers_number_key" ON "supplier_vouchers"("number");

-- CreateIndex
CREATE INDEX "supplier_vouchers_supplier_id_created_at_idx" ON "supplier_vouchers"("supplier_id", "created_at");

-- CreateIndex
CREATE INDEX "outgoing_sms_status_created_at_idx" ON "outgoing_sms"("status", "created_at");

-- CreateIndex
CREATE INDEX "phone_otps_phone_created_at_idx" ON "phone_otps"("phone", "created_at");

-- AddForeignKey
ALTER TABLE "supplier_account_entries" ADD CONSTRAINT "supplier_account_entries_supplier_id_fkey" FOREIGN KEY ("supplier_id") REFERENCES "suppliers"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "supplier_vouchers" ADD CONSTRAINT "supplier_vouchers_supplier_id_fkey" FOREIGN KEY ("supplier_id") REFERENCES "suppliers"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

