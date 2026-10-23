-- CreateEnum
CREATE TYPE "ShiftStatus" AS ENUM ('OPEN', 'CLOSED');

-- AlterTable
ALTER TABLE "currencies" ADD COLUMN     "image_url" TEXT;

-- AlterTable
ALTER TABLE "orders" ADD COLUMN     "client_ref" TEXT;

-- AlterTable
ALTER TABLE "users" ADD COLUMN     "pos_settings" JSONB;

-- CreateTable
CREATE TABLE "cashier_shifts" (
    "id" UUID NOT NULL,
    "number" TEXT NOT NULL,
    "user_id" UUID NOT NULL,
    "status" "ShiftStatus" NOT NULL DEFAULT 'OPEN',
    "opened_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "closed_at" TIMESTAMP(3),
    "closed_by_id" UUID,
    "note" TEXT,

    CONSTRAINT "cashier_shifts_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "cashier_shift_lines" (
    "id" UUID NOT NULL,
    "shift_id" UUID NOT NULL,
    "cash_box_id" UUID NOT NULL,
    "currency" TEXT NOT NULL,
    "opening" DECIMAL(18,2) NOT NULL DEFAULT 0,
    "expected" DECIMAL(18,2),
    "counted" DECIMAL(18,2),
    "difference" DECIMAL(18,2),

    CONSTRAINT "cashier_shift_lines_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "cashier_shifts_number_key" ON "cashier_shifts"("number");

-- CreateIndex
CREATE INDEX "cashier_shifts_user_id_status_idx" ON "cashier_shifts"("user_id", "status");

-- CreateIndex
CREATE INDEX "cashier_shifts_opened_at_idx" ON "cashier_shifts"("opened_at");

-- CreateIndex
CREATE UNIQUE INDEX "cashier_shift_lines_shift_id_currency_key" ON "cashier_shift_lines"("shift_id", "currency");

-- CreateIndex
CREATE UNIQUE INDEX "orders_client_ref_key" ON "orders"("client_ref");

-- AddForeignKey
ALTER TABLE "cashier_shifts" ADD CONSTRAINT "cashier_shifts_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "cashier_shift_lines" ADD CONSTRAINT "cashier_shift_lines_shift_id_fkey" FOREIGN KEY ("shift_id") REFERENCES "cashier_shifts"("id") ON DELETE CASCADE ON UPDATE CASCADE;

