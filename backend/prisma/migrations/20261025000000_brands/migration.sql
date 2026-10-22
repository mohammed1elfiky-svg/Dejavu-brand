-- Brands become their own table (managed like categories); products point at one.

CREATE TABLE "brands" (
    "id" UUID NOT NULL,
    "name" TEXT NOT NULL,
    "slug" TEXT NOT NULL,
    "logo_url" TEXT,
    "sort_order" INTEGER NOT NULL DEFAULT 0,
    "is_active" BOOLEAN NOT NULL DEFAULT true,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "brands_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX "brands_name_key" ON "brands"("name");
CREATE UNIQUE INDEX "brands_slug_key" ON "brands"("slug");

ALTER TABLE "products" ADD COLUMN "brand_id" UUID;

-- Existing free-text brands become brand rows.
INSERT INTO "brands" ("id", "name", "slug", "updated_at")
SELECT gen_random_uuid(), b.name, 'brand-' || substr(md5(b.name), 1, 10), CURRENT_TIMESTAMP
FROM (SELECT DISTINCT trim("brand") AS name FROM "products" WHERE "brand" IS NOT NULL AND trim("brand") <> '') b;

UPDATE "products" p SET "brand_id" = b."id" FROM "brands" b WHERE trim(p."brand") = b."name";

ALTER TABLE "products" DROP COLUMN "brand";

CREATE INDEX "products_brand_id_idx" ON "products"("brand_id");

ALTER TABLE "products" ADD CONSTRAINT "products_brand_id_fkey" FOREIGN KEY ("brand_id") REFERENCES "brands"("id") ON DELETE SET NULL ON UPDATE CASCADE;
