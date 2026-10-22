-- AlterTable
ALTER TABLE "customers" ADD COLUMN     "credit_limit" DECIMAL(14,2),
ADD COLUMN     "is_merchant" BOOLEAN NOT NULL DEFAULT false,
ADD COLUMN     "merchant_discount" DECIMAL(5,2) NOT NULL DEFAULT 0;

-- AlterTable
ALTER TABLE "products" ADD COLUMN     "wholesale_price" DECIMAL(14,2);

-- AlterTable
ALTER TABLE "users" ADD COLUMN     "username" TEXT;

-- CreateTable
CREATE TABLE "cash_box_access" (
    "user_id" UUID NOT NULL,
    "cash_box_id" UUID NOT NULL,

    CONSTRAINT "cash_box_access_pkey" PRIMARY KEY ("user_id","cash_box_id")
);

-- CreateIndex
CREATE INDEX "cash_box_access_cash_box_id_idx" ON "cash_box_access"("cash_box_id");

-- CreateIndex
CREATE UNIQUE INDEX "users_username_key" ON "users"("username");

-- AddForeignKey
ALTER TABLE "cash_box_access" ADD CONSTRAINT "cash_box_access_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "cash_box_access" ADD CONSTRAINT "cash_box_access_cash_box_id_fkey" FOREIGN KEY ("cash_box_id") REFERENCES "cash_boxes"("id") ON DELETE CASCADE ON UPDATE CASCADE;


ALTER TABLE "customers" ADD CONSTRAINT customers_merchant_chk CHECK (merchant_discount >= 0 AND merchant_discount < 100 AND (credit_limit IS NULL OR credit_limit >= 0));
ALTER TABLE "products" ADD CONSTRAINT products_wholesale_price_chk CHECK (wholesale_price IS NULL OR wholesale_price >= 0);
