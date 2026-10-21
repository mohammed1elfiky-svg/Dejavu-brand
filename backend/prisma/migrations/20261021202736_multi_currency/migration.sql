-- CreateEnum
CREATE TYPE "Currency" AS ENUM ('SAR', 'YER');

-- AlterTable
ALTER TABLE "orders" ADD COLUMN     "currency" "Currency" NOT NULL DEFAULT 'SAR',
ADD COLUMN     "exchange_rate" DECIMAL(18,6) NOT NULL DEFAULT 1;

-- AlterTable
ALTER TABLE "payments" ADD COLUMN     "currency" "Currency" NOT NULL DEFAULT 'SAR',
ADD COLUMN     "currency_amount" DECIMAL(16,2),
ADD COLUMN     "exchange_rate" DECIMAL(18,6) NOT NULL DEFAULT 1;

-- CreateTable
CREATE TABLE "exchange_rates" (
    "id" UUID NOT NULL,
    "currency" "Currency" NOT NULL,
    "rate" DECIMAL(18,6) NOT NULL,
    "note" TEXT,
    "effective_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "created_by_id" UUID,

    CONSTRAINT "exchange_rates_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "exchange_rates_currency_effective_at_idx" ON "exchange_rates"("currency", "effective_at");

-- AddForeignKey
ALTER TABLE "exchange_rates" ADD CONSTRAINT "exchange_rates_created_by_id_fkey" FOREIGN KEY ("created_by_id") REFERENCES "users"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- ---------- Integrity -------------------------------------------------------
ALTER TABLE "exchange_rates" ADD CONSTRAINT exchange_rates_rate_chk CHECK (rate > 0 AND currency <> 'SAR');
ALTER TABLE "orders" ADD CONSTRAINT orders_exchange_rate_chk
  CHECK (exchange_rate > 0 AND (currency <> 'SAR' OR exchange_rate = 1));
ALTER TABLE "payments" ADD CONSTRAINT payments_exchange_rate_chk
  CHECK (exchange_rate > 0 AND (currency <> 'SAR' OR exchange_rate = 1));

-- Everything recorded so far was in the base currency.
UPDATE "payments" SET "currency_amount" = "amount" WHERE "currency_amount" IS NULL;
