
-- CreateEnum
CREATE TYPE "OrderChannel" AS ENUM ('ONLINE', 'POS');

-- CreateEnum
CREATE TYPE "CashBoxKind" AS ENUM ('CASHIER', 'VAULT', 'BANK');

-- CreateEnum
CREATE TYPE "TopUpStatus" AS ENUM ('PENDING', 'APPROVED', 'REJECTED');

-- AlterEnum
-- This migration adds more than one value to an enum.
-- With PostgreSQL versions 11 and earlier, this is not possible
-- in a single migration. This can be worked around by creating
-- multiple migrations, each migration adding only one value to
-- the enum.


ALTER TYPE "JournalSourceType" ADD VALUE IF NOT EXISTS 'CASH_TRANSFER';
ALTER TYPE "JournalSourceType" ADD VALUE IF NOT EXISTS 'WALLET_TOPUP';

-- AlterEnum
ALTER TYPE "NotificationType" ADD VALUE IF NOT EXISTS 'WALLET';

-- AlterEnum
ALTER TYPE "PaymentMethod" ADD VALUE IF NOT EXISTS 'CUSTOMER_ACCOUNT';

ALTER TABLE "exchange_rates" DROP CONSTRAINT exchange_rates_rate_chk;
ALTER TABLE "orders" DROP CONSTRAINT orders_exchange_rate_chk;
ALTER TABLE "payments" DROP CONSTRAINT payments_exchange_rate_chk;

-- AlterTable: currencies become rows (admins can add more), so the columns turn into text keys.
ALTER TABLE "exchange_rates" ALTER COLUMN "currency" TYPE TEXT USING "currency"::text;

-- AlterTable
ALTER TABLE "orders" ADD COLUMN     "cashier_id" UUID,
ADD COLUMN     "channel" "OrderChannel" NOT NULL DEFAULT 'ONLINE';
ALTER TABLE "orders" ALTER COLUMN "currency" DROP DEFAULT;
ALTER TABLE "orders" ALTER COLUMN "currency" TYPE TEXT USING "currency"::text;
ALTER TABLE "orders" ALTER COLUMN "currency" SET DEFAULT 'SAR';

-- AlterTable
ALTER TABLE "payments" ADD COLUMN     "is_change" BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE "payments" ALTER COLUMN "currency" DROP DEFAULT;
ALTER TABLE "payments" ALTER COLUMN "currency" TYPE TEXT USING "currency"::text;
ALTER TABLE "payments" ALTER COLUMN "currency" SET DEFAULT 'SAR';

-- AlterTable
ALTER TABLE "product_images" ADD COLUMN     "color" TEXT;

-- AlterTable
ALTER TABLE "product_variants" ADD COLUMN     "variant_no" INTEGER;

-- AlterTable
ALTER TABLE "products" ADD COLUMN     "cost_price" DECIMAL(14,4) NOT NULL DEFAULT 0,
ADD COLUMN     "pos_sort_order" INTEGER NOT NULL DEFAULT 0,
ADD COLUMN     "sku_number" INTEGER;

-- DropEnum
DROP TYPE "Currency";

-- CreateTable
CREATE TABLE "currencies" (
    "code" TEXT NOT NULL,
    "name_ar" TEXT NOT NULL,
    "name_en" TEXT NOT NULL,
    "symbol" TEXT NOT NULL,
    "decimals" INTEGER NOT NULL DEFAULT 2,
    "is_base" BOOLEAN NOT NULL DEFAULT false,
    "is_active" BOOLEAN NOT NULL DEFAULT true,
    "show_in_customer_app" BOOLEAN NOT NULL DEFAULT true,
    "sort_order" INTEGER NOT NULL DEFAULT 0,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "currencies_pkey" PRIMARY KEY ("code")
);

INSERT INTO "currencies" ("code", "name_ar", "name_en", "symbol", "decimals", "is_base", "sort_order", "updated_at") VALUES
  ('SAR', 'ريال سعودي', 'Saudi Riyal', 'ر.س', 2, true, 0, now()),
  ('YER', 'ريال يمني', 'Yemeni Rial', 'ر.ي', 0, false, 1, now());

-- CreateTable
CREATE TABLE "cash_boxes" (
    "id" UUID NOT NULL,
    "name" TEXT NOT NULL,
    "kind" "CashBoxKind" NOT NULL,
    "currency" TEXT NOT NULL,
    "user_id" UUID,
    "account_id" UUID NOT NULL,
    "is_active" BOOLEAN NOT NULL DEFAULT true,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "cash_boxes_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "cash_box_entries" (
    "id" UUID NOT NULL,
    "cash_box_id" UUID NOT NULL,
    "amount" DECIMAL(18,2) NOT NULL,
    "base_amount" DECIMAL(14,2) NOT NULL,
    "source_type" TEXT NOT NULL,
    "source_id" UUID,
    "journal_entry_id" UUID,
    "note" TEXT,
    "created_by_id" UUID,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "cash_box_entries_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "cash_transfers" (
    "id" UUID NOT NULL,
    "number" TEXT NOT NULL,
    "from_box_id" UUID NOT NULL,
    "to_box_id" UUID NOT NULL,
    "from_amount" DECIMAL(18,2) NOT NULL,
    "to_amount" DECIMAL(18,2) NOT NULL,
    "base_amount" DECIMAL(14,2) NOT NULL,
    "fx_difference" DECIMAL(14,2) NOT NULL DEFAULT 0,
    "note" TEXT,
    "created_by_id" UUID NOT NULL,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "cash_transfers_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "customer_account_entries" (
    "id" UUID NOT NULL,
    "customer_id" UUID NOT NULL,
    "currency" TEXT NOT NULL,
    "amount" DECIMAL(18,2) NOT NULL,
    "base_amount" DECIMAL(14,2) NOT NULL,
    "source_type" TEXT NOT NULL,
    "source_id" UUID,
    "journal_entry_id" UUID,
    "note" TEXT,
    "created_by_id" UUID,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "customer_account_entries_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "store_bank_accounts" (
    "id" UUID NOT NULL,
    "bank_name" TEXT NOT NULL,
    "account_name" TEXT NOT NULL,
    "account_number" TEXT NOT NULL,
    "iban" TEXT,
    "currency" TEXT NOT NULL,
    "instructions" TEXT,
    "is_active" BOOLEAN NOT NULL DEFAULT true,
    "sort_order" INTEGER NOT NULL DEFAULT 0,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "store_bank_accounts_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "wallet_top_ups" (
    "id" UUID NOT NULL,
    "number" TEXT NOT NULL,
    "customer_id" UUID NOT NULL,
    "currency" TEXT NOT NULL,
    "amount" DECIMAL(18,2) NOT NULL,
    "bank_account_id" UUID,
    "reference" TEXT,
    "note" TEXT,
    "receipt_file_id" UUID NOT NULL,
    "status" "TopUpStatus" NOT NULL DEFAULT 'PENDING',
    "cash_box_id" UUID,
    "base_amount" DECIMAL(14,2),
    "exchange_rate" DECIMAL(18,6),
    "reviewed_by_id" UUID,
    "reviewed_at" TIMESTAMP(3),
    "reject_reason" TEXT,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "wallet_top_ups_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "promotions" (
    "id" UUID NOT NULL,
    "name" TEXT NOT NULL,
    "percent" DECIMAL(5,2) NOT NULL,
    "starts_at" TIMESTAMP(3) NOT NULL,
    "ends_at" TIMESTAMP(3),
    "is_active" BOOLEAN NOT NULL DEFAULT true,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "promotions_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "promotion_products" (
    "promotion_id" UUID NOT NULL,
    "product_id" UUID NOT NULL,

    CONSTRAINT "promotion_products_pkey" PRIMARY KEY ("promotion_id","product_id")
);

-- CreateTable
CREATE TABLE "stored_files" (
    "id" UUID NOT NULL,
    "mime_type" TEXT NOT NULL,
    "size" INTEGER NOT NULL,
    "data" BYTEA NOT NULL,
    "is_public" BOOLEAN NOT NULL DEFAULT false,
    "user_id" UUID,
    "customer_id" UUID,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "stored_files_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "cash_boxes_account_id_key" ON "cash_boxes"("account_id");

-- CreateIndex
CREATE UNIQUE INDEX "cash_boxes_user_id_currency_key" ON "cash_boxes"("user_id", "currency");

-- CreateIndex
CREATE INDEX "cash_box_entries_cash_box_id_created_at_idx" ON "cash_box_entries"("cash_box_id", "created_at");

-- CreateIndex
CREATE UNIQUE INDEX "cash_transfers_number_key" ON "cash_transfers"("number");

-- CreateIndex
CREATE INDEX "cash_transfers_created_at_idx" ON "cash_transfers"("created_at");

-- CreateIndex
CREATE INDEX "customer_account_entries_customer_id_currency_created_at_idx" ON "customer_account_entries"("customer_id", "currency", "created_at");

-- CreateIndex
CREATE UNIQUE INDEX "wallet_top_ups_number_key" ON "wallet_top_ups"("number");

-- CreateIndex
CREATE INDEX "wallet_top_ups_status_created_at_idx" ON "wallet_top_ups"("status", "created_at");

-- CreateIndex
CREATE INDEX "wallet_top_ups_customer_id_created_at_idx" ON "wallet_top_ups"("customer_id", "created_at");

-- CreateIndex
CREATE INDEX "promotion_products_product_id_idx" ON "promotion_products"("product_id");

-- CreateIndex
CREATE INDEX "orders_channel_placed_at_idx" ON "orders"("channel", "placed_at");

-- CreateIndex
CREATE UNIQUE INDEX "products_sku_number_key" ON "products"("sku_number");

-- AddForeignKey
ALTER TABLE "orders" ADD CONSTRAINT "orders_cashier_id_fkey" FOREIGN KEY ("cashier_id") REFERENCES "users"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "orders" ADD CONSTRAINT "orders_currency_fkey" FOREIGN KEY ("currency") REFERENCES "currencies"("code") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "payments" ADD CONSTRAINT "payments_currency_fkey" FOREIGN KEY ("currency") REFERENCES "currencies"("code") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "exchange_rates" ADD CONSTRAINT "exchange_rates_currency_fkey" FOREIGN KEY ("currency") REFERENCES "currencies"("code") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "cash_boxes" ADD CONSTRAINT "cash_boxes_currency_fkey" FOREIGN KEY ("currency") REFERENCES "currencies"("code") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "cash_boxes" ADD CONSTRAINT "cash_boxes_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "cash_boxes" ADD CONSTRAINT "cash_boxes_account_id_fkey" FOREIGN KEY ("account_id") REFERENCES "accounts"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "cash_box_entries" ADD CONSTRAINT "cash_box_entries_cash_box_id_fkey" FOREIGN KEY ("cash_box_id") REFERENCES "cash_boxes"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "cash_transfers" ADD CONSTRAINT "cash_transfers_from_box_id_fkey" FOREIGN KEY ("from_box_id") REFERENCES "cash_boxes"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "cash_transfers" ADD CONSTRAINT "cash_transfers_to_box_id_fkey" FOREIGN KEY ("to_box_id") REFERENCES "cash_boxes"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "customer_account_entries" ADD CONSTRAINT "customer_account_entries_customer_id_fkey" FOREIGN KEY ("customer_id") REFERENCES "customers"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "customer_account_entries" ADD CONSTRAINT "customer_account_entries_currency_fkey" FOREIGN KEY ("currency") REFERENCES "currencies"("code") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "store_bank_accounts" ADD CONSTRAINT "store_bank_accounts_currency_fkey" FOREIGN KEY ("currency") REFERENCES "currencies"("code") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "wallet_top_ups" ADD CONSTRAINT "wallet_top_ups_customer_id_fkey" FOREIGN KEY ("customer_id") REFERENCES "customers"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "wallet_top_ups" ADD CONSTRAINT "wallet_top_ups_currency_fkey" FOREIGN KEY ("currency") REFERENCES "currencies"("code") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "wallet_top_ups" ADD CONSTRAINT "wallet_top_ups_bank_account_id_fkey" FOREIGN KEY ("bank_account_id") REFERENCES "store_bank_accounts"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "wallet_top_ups" ADD CONSTRAINT "wallet_top_ups_cash_box_id_fkey" FOREIGN KEY ("cash_box_id") REFERENCES "cash_boxes"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "wallet_top_ups" ADD CONSTRAINT "wallet_top_ups_reviewed_by_id_fkey" FOREIGN KEY ("reviewed_by_id") REFERENCES "users"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "wallet_top_ups" ADD CONSTRAINT "wallet_top_ups_receipt_file_id_fkey" FOREIGN KEY ("receipt_file_id") REFERENCES "stored_files"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "promotion_products" ADD CONSTRAINT "promotion_products_promotion_id_fkey" FOREIGN KEY ("promotion_id") REFERENCES "promotions"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "promotion_products" ADD CONSTRAINT "promotion_products_product_id_fkey" FOREIGN KEY ("product_id") REFERENCES "products"("id") ON DELETE CASCADE ON UPDATE CASCADE;


-- ---------- Integrity -------------------------------------------------------
ALTER TABLE "exchange_rates" ADD CONSTRAINT exchange_rates_rate_chk CHECK (rate > 0 AND currency <> 'SAR');
ALTER TABLE "orders" ADD CONSTRAINT orders_exchange_rate_chk
  CHECK (exchange_rate > 0 AND (currency <> 'SAR' OR exchange_rate = 1));
ALTER TABLE "payments" ADD CONSTRAINT payments_exchange_rate_chk
  CHECK (exchange_rate > 0 AND (currency <> 'SAR' OR exchange_rate = 1));
-- Exactly one base currency, and it never has a rate of its own.
CREATE UNIQUE INDEX currencies_single_base ON "currencies" ("is_base") WHERE "is_base";
ALTER TABLE "currencies" ADD CONSTRAINT currencies_code_chk CHECK (code ~ '^[A-Z][A-Z0-9_]{1,9}$' AND decimals BETWEEN 0 AND 4);
ALTER TABLE "products" ADD CONSTRAINT products_sku_number_chk CHECK (sku_number IS NULL OR sku_number BETWEEN 100 AND 100000);
ALTER TABLE "products" ADD CONSTRAINT products_cost_price_chk CHECK (cost_price >= 0);
ALTER TABLE "product_variants" ADD CONSTRAINT product_variants_variant_no_chk CHECK (variant_no IS NULL OR variant_no BETWEEN 1 AND 1000);
ALTER TABLE "promotions" ADD CONSTRAINT promotions_percent_chk CHECK (percent > 0 AND percent < 100 AND (ends_at IS NULL OR ends_at > starts_at));
ALTER TABLE "cash_transfers" ADD CONSTRAINT cash_transfers_chk CHECK (from_amount > 0 AND to_amount > 0 AND from_box_id <> to_box_id);
ALTER TABLE "wallet_top_ups" ADD CONSTRAINT wallet_top_ups_amount_chk CHECK (amount > 0);
ALTER TABLE "cash_box_entries" ADD CONSTRAINT cash_box_entries_amount_chk CHECK (amount <> 0 OR base_amount <> 0);
ALTER TABLE "customer_account_entries" ADD CONSTRAINT customer_account_entries_amount_chk CHECK (amount <> 0 OR base_amount <> 0);
-- One drawer per cashier per currency; vaults and banks have no owner.
ALTER TABLE "cash_boxes" ADD CONSTRAINT cash_boxes_owner_chk CHECK ((kind = 'CASHIER') = (user_id IS NOT NULL));

-- Ledgers are append-only, like stock movements.
CREATE OR REPLACE FUNCTION money_entry_append_only() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION '% rows are append-only', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_cash_box_entries_append_only BEFORE UPDATE OR DELETE ON "cash_box_entries"
  FOR EACH ROW EXECUTE FUNCTION money_entry_append_only();
CREATE TRIGGER trg_customer_account_entries_append_only BEFORE UPDATE OR DELETE ON "customer_account_entries"
  FOR EACH ROW EXECUTE FUNCTION money_entry_append_only();
CREATE INDEX cash_box_entries_journal_idx ON "cash_box_entries" ("journal_entry_id");
CREATE INDEX customer_account_entries_journal_idx ON "customer_account_entries" ("journal_entry_id");
