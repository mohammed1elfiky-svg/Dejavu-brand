-- AlterTable
ALTER TABLE "orders" ADD COLUMN     "received_reminder_at" TIMESTAMP(3);

-- AlterTable
ALTER TABLE "shipments" ADD COLUMN     "photo_urls" TEXT[] DEFAULT ARRAY[]::TEXT[],
ADD COLUMN     "receipt_image_url" TEXT;

