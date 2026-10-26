-- CreateEnum
CREATE TYPE "DealType" AS ENUM ('BUY_X_GET_Y', 'BUNDLE');

-- CreateEnum
CREATE TYPE "PreOrderStatus" AS ENUM ('WAITING', 'NOTIFIED', 'CONVERTED', 'CANCELLED');

-- CreateTable
CREATE TABLE "deals" (
    "id" UUID NOT NULL,
    "name" TEXT NOT NULL,
    "type" "DealType" NOT NULL,
    "buy_qty" INTEGER NOT NULL DEFAULT 1,
    "free_qty" INTEGER NOT NULL DEFAULT 1,
    "bundle_price" DECIMAL(14,2),
    "starts_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "ends_at" TIMESTAMP(3),
    "is_active" BOOLEAN NOT NULL DEFAULT true,
    "at_pos" BOOLEAN NOT NULL DEFAULT true,
    "online" BOOLEAN NOT NULL DEFAULT true,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "deals_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "deal_products" (
    "deal_id" UUID NOT NULL,
    "product_id" UUID NOT NULL,

    CONSTRAINT "deal_products_pkey" PRIMARY KEY ("deal_id","product_id")
);

-- CreateTable
CREATE TABLE "pre_orders" (
    "id" UUID NOT NULL,
    "number" TEXT NOT NULL,
    "customer_id" UUID NOT NULL,
    "variant_id" UUID NOT NULL,
    "quantity" INTEGER NOT NULL,
    "status" "PreOrderStatus" NOT NULL DEFAULT 'WAITING',
    "note" TEXT,
    "layaway_id" UUID,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "notified_at" TIMESTAMP(3),
    "closed_at" TIMESTAMP(3),
    "currencyCode" TEXT,

    CONSTRAINT "pre_orders_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "deal_products_product_id_idx" ON "deal_products"("product_id");

-- CreateIndex
CREATE UNIQUE INDEX "pre_orders_number_key" ON "pre_orders"("number");

-- CreateIndex
CREATE INDEX "pre_orders_status_variant_id_idx" ON "pre_orders"("status", "variant_id");

-- CreateIndex
CREATE INDEX "pre_orders_customer_id_idx" ON "pre_orders"("customer_id");

-- AddForeignKey
ALTER TABLE "deal_products" ADD CONSTRAINT "deal_products_deal_id_fkey" FOREIGN KEY ("deal_id") REFERENCES "deals"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "deal_products" ADD CONSTRAINT "deal_products_product_id_fkey" FOREIGN KEY ("product_id") REFERENCES "products"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "pre_orders" ADD CONSTRAINT "pre_orders_customer_id_fkey" FOREIGN KEY ("customer_id") REFERENCES "customers"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "pre_orders" ADD CONSTRAINT "pre_orders_variant_id_fkey" FOREIGN KEY ("variant_id") REFERENCES "product_variants"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "pre_orders" ADD CONSTRAINT "pre_orders_currencyCode_fkey" FOREIGN KEY ("currencyCode") REFERENCES "currencies"("code") ON DELETE SET NULL ON UPDATE CASCADE;

