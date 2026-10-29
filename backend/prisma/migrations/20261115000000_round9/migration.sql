-- CreateEnum
CREATE TYPE "DeliveryMethod" AS ENUM ('HOME', 'OFFICE', 'PICKUP');

-- CreateEnum
CREATE TYPE "InstagramStatus" AS ENUM ('NONE', 'PENDING', 'VERIFIED');

-- CreateEnum
CREATE TYPE "GiftSource" AS ENUM ('POS', 'GROUP');

-- CreateEnum
CREATE TYPE "GiftStatus" AS ENUM ('WAITING', 'PICKUP', 'DELIVERY', 'DONE', 'CANCELLED');

-- CreateEnum
CREATE TYPE "GroupGiftStatus" AS ENUM ('OPEN', 'FUNDED', 'CANCELLED');

-- CreateEnum
CREATE TYPE "PostKind" AS ENUM ('LOOK', 'WISHLIST');

-- AlterEnum
-- This migration adds more than one value to an enum.
-- With PostgreSQL versions 11 and earlier, this is not possible
-- in a single migration. This can be worked around by creating
-- multiple migrations, each migration adding only one value to
-- the enum.


ALTER TYPE "JournalSourceType" ADD VALUE 'LOYALTY_REWARD';
ALTER TYPE "JournalSourceType" ADD VALUE 'GROUP_GIFT';

-- AlterTable
ALTER TABLE "cart_items" ADD COLUMN     "via_post_id" UUID;

-- AlterTable
ALTER TABLE "coupons" ADD COLUMN     "customer_id" UUID;

-- AlterTable
ALTER TABLE "customers" ADD COLUMN     "avatar_url" TEXT,
ADD COLUMN     "bio" TEXT,
ADD COLUMN     "ig_code" TEXT,
ADD COLUMN     "ig_status" "InstagramStatus" NOT NULL DEFAULT 'NONE',
ADD COLUMN     "ig_verified_at" TIMESTAMP(3),
ADD COLUMN     "loyalty_enabled" BOOLEAN,
ADD COLUMN     "username" TEXT;

-- AlterTable
ALTER TABLE "deals" ADD COLUMN     "max_customers" INTEGER,
ADD COLUMN     "max_pieces" INTEGER,
ADD COLUMN     "used_customers" INTEGER NOT NULL DEFAULT 0,
ADD COLUMN     "used_pieces" INTEGER NOT NULL DEFAULT 0;

-- AlterTable
ALTER TABLE "order_items" ADD COLUMN     "via_post_id" UUID;

-- AlterTable
ALTER TABLE "orders" ADD COLUMN     "carrier_id" UUID,
ADD COLUMN     "carrier_office_id" UUID,
ADD COLUMN     "delivery_method" "DeliveryMethod" NOT NULL DEFAULT 'HOME';

-- CreateTable
CREATE TABLE "carriers" (
    "id" UUID NOT NULL,
    "name" TEXT NOT NULL,
    "phones" TEXT[] DEFAULT ARRAY[]::TEXT[],
    "notes" TEXT,
    "is_active" BOOLEAN NOT NULL DEFAULT true,
    "sort_order" INTEGER NOT NULL DEFAULT 0,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "carriers_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "carrier_offices" (
    "id" UUID NOT NULL,
    "carrier_id" UUID NOT NULL,
    "name" TEXT NOT NULL,
    "governorate" TEXT NOT NULL,
    "city" TEXT,
    "address" TEXT NOT NULL,
    "phones" TEXT[] DEFAULT ARRAY[]::TEXT[],
    "is_active" BOOLEAN NOT NULL DEFAULT true,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "carrier_offices_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "deal_usages" (
    "id" UUID NOT NULL,
    "deal_id" UUID NOT NULL,
    "customer_id" UUID,
    "order_id" UUID,
    "pieces" INTEGER NOT NULL,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "deal_usages_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "loyalty_entries" (
    "id" UUID NOT NULL,
    "customer_id" UUID NOT NULL,
    "points" INTEGER NOT NULL,
    "kind" TEXT NOT NULL,
    "order_id" UUID,
    "note" TEXT,
    "created_by_id" UUID,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "loyalty_entries_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "blocked_phones" (
    "id" UUID NOT NULL,
    "phone" TEXT NOT NULL,
    "reason" TEXT,
    "created_by_id" UUID,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "blocked_phones_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "gifts" (
    "id" UUID NOT NULL,
    "number" TEXT NOT NULL,
    "recipient_id" UUID NOT NULL,
    "giver_customer_id" UUID,
    "giver_name" TEXT NOT NULL,
    "occasion" TEXT,
    "message" TEXT,
    "source" "GiftSource" NOT NULL,
    "status" "GiftStatus" NOT NULL DEFAULT 'WAITING',
    "items" JSONB NOT NULL,
    "sale_order_id" UUID,
    "delivery_method" "DeliveryMethod",
    "delivery" JSONB,
    "carrier_id" UUID,
    "carrier_office_id" UUID,
    "tracking_number" TEXT,
    "chosen_at" TIMESTAMP(3),
    "done_at" TIMESTAMP(3),
    "created_by_id" UUID,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "gifts_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "group_gifts" (
    "id" UUID NOT NULL,
    "number" TEXT NOT NULL,
    "organizer_id" UUID NOT NULL,
    "recipient_id" UUID NOT NULL,
    "variant_id" UUID NOT NULL,
    "title" TEXT NOT NULL,
    "occasion" TEXT,
    "message" TEXT,
    "target" DECIMAL(14,2) NOT NULL,
    "raised" DECIMAL(14,2) NOT NULL DEFAULT 0,
    "status" "GroupGiftStatus" NOT NULL DEFAULT 'OPEN',
    "ends_at" TIMESTAMP(3) NOT NULL,
    "gift_id" UUID,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "group_gifts_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "group_gift_contributions" (
    "id" UUID NOT NULL,
    "group_gift_id" UUID NOT NULL,
    "customer_id" UUID NOT NULL,
    "amount" DECIMAL(14,2) NOT NULL,
    "message" TEXT,
    "refunded" BOOLEAN NOT NULL DEFAULT false,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "group_gift_contributions_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "follows" (
    "follower_id" UUID NOT NULL,
    "following_id" UUID NOT NULL,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "follows_pkey" PRIMARY KEY ("follower_id","following_id")
);

-- CreateTable
CREATE TABLE "posts" (
    "id" UUID NOT NULL,
    "author_id" UUID NOT NULL,
    "kind" "PostKind" NOT NULL DEFAULT 'LOOK',
    "caption" TEXT,
    "images" TEXT[] DEFAULT ARRAY[]::TEXT[],
    "like_count" INTEGER NOT NULL DEFAULT 0,
    "comment_count" INTEGER NOT NULL DEFAULT 0,
    "is_hidden" BOOLEAN NOT NULL DEFAULT false,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "posts_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "post_tags" (
    "id" UUID NOT NULL,
    "post_id" UUID NOT NULL,
    "product_id" UUID NOT NULL,
    "variant_id" UUID,
    "image_index" INTEGER NOT NULL DEFAULT 0,
    "x" DOUBLE PRECISION,
    "y" DOUBLE PRECISION,

    CONSTRAINT "post_tags_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "post_likes" (
    "post_id" UUID NOT NULL,
    "customer_id" UUID NOT NULL,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "post_likes_pkey" PRIMARY KEY ("post_id","customer_id")
);

-- CreateTable
CREATE TABLE "post_comments" (
    "id" UUID NOT NULL,
    "post_id" UUID NOT NULL,
    "author_id" UUID NOT NULL,
    "body" TEXT NOT NULL,
    "is_hidden" BOOLEAN NOT NULL DEFAULT false,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "post_comments_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "post_mentions" (
    "post_id" UUID NOT NULL,
    "customer_id" UUID NOT NULL,

    CONSTRAINT "post_mentions_pkey" PRIMARY KEY ("post_id","customer_id")
);

-- CreateTable
CREATE TABLE "ambassador_rewards" (
    "id" UUID NOT NULL,
    "post_id" UUID NOT NULL,
    "poster_id" UUID NOT NULL,
    "buyer_id" UUID NOT NULL,
    "order_id" UUID NOT NULL,
    "sales_sar" DECIMAL(14,2) NOT NULL,
    "reward_sar" DECIMAL(14,2) NOT NULL,
    "kind" TEXT NOT NULL,
    "status" TEXT NOT NULL DEFAULT 'PENDING',
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "paid_at" TIMESTAMP(3),

    CONSTRAINT "ambassador_rewards_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "carrier_offices_carrier_id_idx" ON "carrier_offices"("carrier_id");

-- CreateIndex
CREATE INDEX "deal_usages_deal_id_customer_id_idx" ON "deal_usages"("deal_id", "customer_id");

-- CreateIndex
CREATE INDEX "deal_usages_order_id_idx" ON "deal_usages"("order_id");

-- CreateIndex
CREATE INDEX "loyalty_entries_customer_id_created_at_idx" ON "loyalty_entries"("customer_id", "created_at");

-- CreateIndex
CREATE INDEX "loyalty_entries_order_id_idx" ON "loyalty_entries"("order_id");

-- CreateIndex
CREATE UNIQUE INDEX "blocked_phones_phone_key" ON "blocked_phones"("phone");

-- CreateIndex
CREATE UNIQUE INDEX "gifts_number_key" ON "gifts"("number");

-- CreateIndex
CREATE INDEX "gifts_recipient_id_status_idx" ON "gifts"("recipient_id", "status");

-- CreateIndex
CREATE INDEX "gifts_status_created_at_idx" ON "gifts"("status", "created_at");

-- CreateIndex
CREATE UNIQUE INDEX "group_gifts_number_key" ON "group_gifts"("number");

-- CreateIndex
CREATE UNIQUE INDEX "group_gifts_gift_id_key" ON "group_gifts"("gift_id");

-- CreateIndex
CREATE INDEX "group_gifts_status_ends_at_idx" ON "group_gifts"("status", "ends_at");

-- CreateIndex
CREATE INDEX "group_gifts_recipient_id_idx" ON "group_gifts"("recipient_id");

-- CreateIndex
CREATE INDEX "group_gift_contributions_group_gift_id_idx" ON "group_gift_contributions"("group_gift_id");

-- CreateIndex
CREATE INDEX "follows_following_id_idx" ON "follows"("following_id");

-- CreateIndex
CREATE INDEX "posts_author_id_created_at_idx" ON "posts"("author_id", "created_at");

-- CreateIndex
CREATE INDEX "posts_created_at_idx" ON "posts"("created_at");

-- CreateIndex
CREATE INDEX "post_tags_post_id_idx" ON "post_tags"("post_id");

-- CreateIndex
CREATE INDEX "post_tags_product_id_idx" ON "post_tags"("product_id");

-- CreateIndex
CREATE INDEX "post_comments_post_id_created_at_idx" ON "post_comments"("post_id", "created_at");

-- CreateIndex
CREATE INDEX "ambassador_rewards_poster_id_status_idx" ON "ambassador_rewards"("poster_id", "status");

-- CreateIndex
CREATE UNIQUE INDEX "ambassador_rewards_order_id_post_id_key" ON "ambassador_rewards"("order_id", "post_id");

-- CreateIndex
CREATE UNIQUE INDEX "customers_username_key" ON "customers"("username");

-- AddForeignKey
ALTER TABLE "orders" ADD CONSTRAINT "orders_carrier_id_fkey" FOREIGN KEY ("carrier_id") REFERENCES "carriers"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "orders" ADD CONSTRAINT "orders_carrier_office_id_fkey" FOREIGN KEY ("carrier_office_id") REFERENCES "carrier_offices"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "carrier_offices" ADD CONSTRAINT "carrier_offices_carrier_id_fkey" FOREIGN KEY ("carrier_id") REFERENCES "carriers"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "deal_usages" ADD CONSTRAINT "deal_usages_deal_id_fkey" FOREIGN KEY ("deal_id") REFERENCES "deals"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "loyalty_entries" ADD CONSTRAINT "loyalty_entries_customer_id_fkey" FOREIGN KEY ("customer_id") REFERENCES "customers"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "gifts" ADD CONSTRAINT "gifts_recipient_id_fkey" FOREIGN KEY ("recipient_id") REFERENCES "customers"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "group_gifts" ADD CONSTRAINT "group_gifts_gift_id_fkey" FOREIGN KEY ("gift_id") REFERENCES "gifts"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "group_gift_contributions" ADD CONSTRAINT "group_gift_contributions_group_gift_id_fkey" FOREIGN KEY ("group_gift_id") REFERENCES "group_gifts"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "follows" ADD CONSTRAINT "follows_follower_id_fkey" FOREIGN KEY ("follower_id") REFERENCES "customers"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "follows" ADD CONSTRAINT "follows_following_id_fkey" FOREIGN KEY ("following_id") REFERENCES "customers"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "posts" ADD CONSTRAINT "posts_author_id_fkey" FOREIGN KEY ("author_id") REFERENCES "customers"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "post_tags" ADD CONSTRAINT "post_tags_post_id_fkey" FOREIGN KEY ("post_id") REFERENCES "posts"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "post_tags" ADD CONSTRAINT "post_tags_product_id_fkey" FOREIGN KEY ("product_id") REFERENCES "products"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "post_likes" ADD CONSTRAINT "post_likes_post_id_fkey" FOREIGN KEY ("post_id") REFERENCES "posts"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "post_comments" ADD CONSTRAINT "post_comments_post_id_fkey" FOREIGN KEY ("post_id") REFERENCES "posts"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "post_mentions" ADD CONSTRAINT "post_mentions_post_id_fkey" FOREIGN KEY ("post_id") REFERENCES "posts"("id") ON DELETE CASCADE ON UPDATE CASCADE;

