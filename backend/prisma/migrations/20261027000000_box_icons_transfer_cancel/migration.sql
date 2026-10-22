-- AlterTable
ALTER TABLE "cash_boxes" ADD COLUMN     "icon" TEXT,
ADD COLUMN     "image_url" TEXT;

-- AlterTable
ALTER TABLE "cash_transfers" ADD COLUMN     "cancel_reason" TEXT,
ADD COLUMN     "cancelled_at" TIMESTAMP(3),
ADD COLUMN     "cancelled_by_id" UUID,
ADD COLUMN     "replaced_by_id" UUID;

