/**
 * Yemeni governorates and the rial their market uses: the old notes (YER) in the north,
 * the new "qu'aiti" notes (YEQ) in the south and east. Taiz is split: الحوبان uses the old
 * notes, المدينة the new ones.
 */
export const OLD_RIAL = 'YER';
export const NEW_RIAL = 'YEQ';

export const GOVERNORATES: { name: string; currency: string; areas?: { name: string; currency: string }[] }[] = [
  { name: 'أمانة العاصمة', currency: OLD_RIAL },
  { name: 'صنعاء', currency: OLD_RIAL },
  { name: 'عمران', currency: OLD_RIAL },
  { name: 'صعدة', currency: OLD_RIAL },
  { name: 'حجة', currency: OLD_RIAL },
  { name: 'الحديدة', currency: OLD_RIAL },
  { name: 'ذمار', currency: OLD_RIAL },
  { name: 'إب', currency: OLD_RIAL },
  { name: 'ريمة', currency: OLD_RIAL },
  { name: 'المحويت', currency: OLD_RIAL },
  { name: 'البيضاء', currency: OLD_RIAL },
  { name: 'الجوف', currency: OLD_RIAL },
  {
    name: 'تعز',
    currency: NEW_RIAL,
    areas: [
      { name: 'الحوبان', currency: OLD_RIAL },
      { name: 'المدينة', currency: NEW_RIAL },
    ],
  },
  { name: 'عدن', currency: NEW_RIAL },
  { name: 'لحج', currency: NEW_RIAL },
  { name: 'أبين', currency: NEW_RIAL },
  { name: 'الضالع', currency: NEW_RIAL },
  { name: 'شبوة', currency: NEW_RIAL },
  { name: 'حضرموت', currency: NEW_RIAL },
  { name: 'المهرة', currency: NEW_RIAL },
  { name: 'سقطرى', currency: NEW_RIAL },
  { name: 'مأرب', currency: NEW_RIAL },
];

export const GOVERNORATE_NAMES = GOVERNORATES.map((g) => g.name);

/** The rial of a governorate (and area); null when unknown. */
export function currencyOfRegion(governorate?: string | null, area?: string | null): string | null {
  const g = GOVERNORATES.find((x) => x.name === governorate);
  if (!g) return null;
  return g.areas?.find((a) => a.name === area)?.currency ?? g.currency;
}
