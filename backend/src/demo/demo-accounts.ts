/** Demo credentials. Shown in Swagger and docs/TRY_IT.md — never use in production. */
export const DEMO_PASSWORD = 'Demo!2026';

export const DEMO_STAFF = [
  { role: 'admin', name: 'System Admin', email: 'admin@dejavu.local', password: 'ChangeMe!2026' },
  { role: 'accountant', name: 'محاسب تجريبي', email: 'accountant@dejavu.local', password: DEMO_PASSWORD },
  { role: 'storekeeper', name: 'أمين مخزن تجريبي', email: 'store@dejavu.local', password: DEMO_PASSWORD },
  { role: 'sales', name: 'موظف مبيعات تجريبي', email: 'sales@dejavu.local', password: DEMO_PASSWORD },
  { role: 'cashier', name: 'كاشير تجريبي', email: 'cashier@dejavu.local', password: DEMO_PASSWORD },
] as const;

export const DEMO_CUSTOMERS = [
  { name: 'منى أحمد', phone: '01000000001', email: 'mona@example.com', governorate: 'القاهرة', city: 'مدينة نصر', street: 'شارع عباس العقاد 15' },
  { name: 'سارة محمود', phone: '01000000002', email: 'sara@example.com', governorate: 'الإسكندرية', city: 'سموحة', street: 'شارع فوزي معاذ 7' },
  { name: 'ياسمين علي', phone: '01000000003', email: undefined, governorate: 'الجيزة', city: 'الدقي', street: 'شارع التحرير 22' },
] as const;
