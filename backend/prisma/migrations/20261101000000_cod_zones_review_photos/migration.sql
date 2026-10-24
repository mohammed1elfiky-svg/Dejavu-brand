-- AlterTable
ALTER TABLE "product_reviews" ADD COLUMN     "images" TEXT[] DEFAULT ARRAY[]::TEXT[];

-- AlterTable
ALTER TABLE "shipping_zones" ADD COLUMN     "cod_enabled" BOOLEAN NOT NULL DEFAULT true;


-- The store ships in Yemen: zones left over from the Egyptian demo data go.
DELETE FROM "shipping_zones" WHERE "governorate" NOT IN (
  'أمانة العاصمة','صنعاء','عمران','صعدة','حجة','الحديدة','ذمار','إب','ريمة','المحويت','البيضاء','الجوف',
  'تعز','عدن','لحج','أبين','الضالع','شبوة','حضرموت','المهرة','سقطرى','مأرب'
);
