-- Push outbox: notifications not yet handed to FCM.
ALTER TABLE "notifications" ADD COLUMN "pushed_at" TIMESTAMP(3);

-- Nothing written before this migration should be pushed now.
UPDATE "notifications" SET "pushed_at" = "sent_at";

-- Only the (small) set of unsent rows is scanned by the dispatcher.
CREATE INDEX "notifications_unpushed_idx" ON "notifications" ("sent_at") WHERE "pushed_at" IS NULL;
