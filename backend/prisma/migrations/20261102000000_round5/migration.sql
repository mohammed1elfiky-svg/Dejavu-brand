-- CreateEnum
CREATE TYPE "ChatRole" AS ENUM ('USER', 'BOT');

-- CreateEnum
CREATE TYPE "GiftCardStatus" AS ENUM ('ACTIVE', 'REDEEMED', 'CANCELLED');

-- CreateEnum
CREATE TYPE "LayawayStatus" AS ENUM ('OPEN', 'COMPLETED', 'CANCELLED');

-- AlterEnum
ALTER TYPE "JournalSourceType" ADD VALUE 'GIFT_CARD';

-- AlterTable
ALTER TABLE "orders" ADD COLUMN     "review_prompted_at" TIMESTAMP(3);

-- AlterTable
ALTER TABLE "wishlist_items" ADD COLUMN     "last_price" DECIMAL(14,2),
ADD COLUMN     "low_stock_notified_at" TIMESTAMP(3);

-- CreateTable
CREATE TABLE "chat_messages" (
    "id" UUID NOT NULL,
    "customer_id" UUID NOT NULL,
    "role" "ChatRole" NOT NULL,
    "text" TEXT NOT NULL,
    "image_file_id" UUID,
    "product_ids" TEXT[] DEFAULT ARRAY[]::TEXT[],
    "handover" BOOLEAN NOT NULL DEFAULT false,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "currencyCode" TEXT,

    CONSTRAINT "chat_messages_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "app_sessions" (
    "id" UUID NOT NULL,
    "device_id" TEXT NOT NULL,
    "customer_id" UUID,
    "started_at" TIMESTAMP(3) NOT NULL,
    "seconds" INTEGER NOT NULL,
    "updated_at" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "app_sessions_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "product_views" (
    "id" UUID NOT NULL,
    "product_id" UUID NOT NULL,
    "device_id" TEXT NOT NULL,
    "customer_id" UUID,
    "seconds" INTEGER NOT NULL,
    "viewed_at" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "product_views_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "search_logs" (
    "id" UUID NOT NULL,
    "query" TEXT NOT NULL,
    "results" INTEGER NOT NULL,
    "device_id" TEXT NOT NULL,
    "customer_id" UUID,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "search_logs_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "campaigns" (
    "id" UUID NOT NULL,
    "title" TEXT NOT NULL,
    "body" TEXT NOT NULL,
    "image_url" TEXT,
    "target" JSONB NOT NULL,
    "segment" JSONB NOT NULL,
    "channels" TEXT[],
    "recipients" INTEGER NOT NULL,
    "sms_count" INTEGER NOT NULL DEFAULT 0,
    "created_by_id" UUID,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "campaigns_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "gift_cards" (
    "id" UUID NOT NULL,
    "code" TEXT NOT NULL,
    "currency" TEXT NOT NULL,
    "amount" DECIMAL(18,2) NOT NULL,
    "base_amount" DECIMAL(14,2) NOT NULL,
    "status" "GiftCardStatus" NOT NULL DEFAULT 'ACTIVE',
    "buyer_name" TEXT,
    "note" TEXT,
    "expires_at" TIMESTAMP(3),
    "issued_by_id" UUID,
    "cash_box_id" UUID,
    "issue_entry_id" UUID,
    "redeemed_at" TIMESTAMP(3),
    "redeemed_customer_id" UUID,
    "redeem_entry_id" UUID,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "gift_cards_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "layaways" (
    "id" UUID NOT NULL,
    "number" TEXT NOT NULL,
    "customer_id" UUID NOT NULL,
    "warehouse_id" UUID NOT NULL,
    "status" "LayawayStatus" NOT NULL DEFAULT 'OPEN',
    "items" JSONB NOT NULL,
    "total" DECIMAL(14,2) NOT NULL,
    "expires_at" TIMESTAMP(3),
    "note" TEXT,
    "sale_order_id" UUID,
    "created_by_id" UUID,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMP(3) NOT NULL,
    "closed_at" TIMESTAMP(3),
    "currencyCode" TEXT,

    CONSTRAINT "layaways_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "layaway_payments" (
    "id" UUID NOT NULL,
    "layaway_id" UUID NOT NULL,
    "currency" TEXT NOT NULL,
    "amount" DECIMAL(18,2) NOT NULL,
    "base_amount" DECIMAL(14,2) NOT NULL,
    "journal_entry_id" UUID,
    "created_by_id" UUID,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "layaway_payments_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "stock_damages" (
    "id" UUID NOT NULL,
    "number" TEXT NOT NULL,
    "warehouse_id" UUID NOT NULL,
    "items" JSONB NOT NULL,
    "total_value" DECIMAL(14,2) NOT NULL,
    "note" TEXT,
    "photo_urls" TEXT[] DEFAULT ARRAY[]::TEXT[],
    "journal_entry_id" UUID,
    "created_by_id" UUID,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "stock_damages_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "owner_alerts" (
    "id" UUID NOT NULL,
    "kind" TEXT NOT NULL,
    "title" TEXT NOT NULL,
    "body" TEXT NOT NULL,
    "data" JSONB,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "owner_alerts_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "chat_messages_customer_id_created_at_idx" ON "chat_messages"("customer_id", "created_at");

-- CreateIndex
CREATE INDEX "chat_messages_created_at_idx" ON "chat_messages"("created_at");

-- CreateIndex
CREATE INDEX "app_sessions_started_at_idx" ON "app_sessions"("started_at");

-- CreateIndex
CREATE INDEX "app_sessions_customer_id_idx" ON "app_sessions"("customer_id");

-- CreateIndex
CREATE INDEX "app_sessions_device_id_idx" ON "app_sessions"("device_id");

-- CreateIndex
CREATE INDEX "product_views_product_id_viewed_at_idx" ON "product_views"("product_id", "viewed_at");

-- CreateIndex
CREATE INDEX "product_views_viewed_at_idx" ON "product_views"("viewed_at");

-- CreateIndex
CREATE INDEX "search_logs_created_at_idx" ON "search_logs"("created_at");

-- CreateIndex
CREATE INDEX "search_logs_query_idx" ON "search_logs"("query");

-- CreateIndex
CREATE INDEX "campaigns_created_at_idx" ON "campaigns"("created_at");

-- CreateIndex
CREATE UNIQUE INDEX "gift_cards_code_key" ON "gift_cards"("code");

-- CreateIndex
CREATE INDEX "gift_cards_status_idx" ON "gift_cards"("status");

-- CreateIndex
CREATE UNIQUE INDEX "layaways_number_key" ON "layaways"("number");

-- CreateIndex
CREATE INDEX "layaways_status_created_at_idx" ON "layaways"("status", "created_at");

-- CreateIndex
CREATE UNIQUE INDEX "stock_damages_number_key" ON "stock_damages"("number");

-- CreateIndex
CREATE INDEX "stock_damages_created_at_idx" ON "stock_damages"("created_at");

-- CreateIndex
CREATE INDEX "owner_alerts_created_at_idx" ON "owner_alerts"("created_at");

-- AddForeignKey
ALTER TABLE "chat_messages" ADD CONSTRAINT "chat_messages_customer_id_fkey" FOREIGN KEY ("customer_id") REFERENCES "customers"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "chat_messages" ADD CONSTRAINT "chat_messages_currencyCode_fkey" FOREIGN KEY ("currencyCode") REFERENCES "currencies"("code") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "product_views" ADD CONSTRAINT "product_views_product_id_fkey" FOREIGN KEY ("product_id") REFERENCES "products"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "layaways" ADD CONSTRAINT "layaways_customer_id_fkey" FOREIGN KEY ("customer_id") REFERENCES "customers"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "layaways" ADD CONSTRAINT "layaways_currencyCode_fkey" FOREIGN KEY ("currencyCode") REFERENCES "currencies"("code") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "layaway_payments" ADD CONSTRAINT "layaway_payments_layaway_id_fkey" FOREIGN KEY ("layaway_id") REFERENCES "layaways"("id") ON DELETE CASCADE ON UPDATE CASCADE;

