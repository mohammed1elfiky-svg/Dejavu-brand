-- AlterTable
ALTER TABLE "chat_messages" ADD COLUMN     "report_reason" TEXT,
ADD COLUMN     "reported_at" TIMESTAMP(3);

