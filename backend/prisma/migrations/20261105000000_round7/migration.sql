-- AlterEnum
ALTER TYPE "CustomerStatus" ADD VALUE 'PENDING';

-- AlterEnum
ALTER TYPE "JournalSourceType" ADD VALUE 'WALLET_GIFT';

-- AlterTable
ALTER TABLE "products" ADD COLUMN     "store_position" INTEGER;

