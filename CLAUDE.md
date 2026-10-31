# Dejavu Brand

## graphify (خريطة المشروع)

هذا المشروع كبير (أكثر من 400 ملف: الخادم في `backend/` وتطبيقات أندرويد في `android/`)،
ولديه رسم معرفي (knowledge graph) في `graphify-out/`.

- قبل البحث في الكود أو تعديله، راجع `graphify-out/GRAPH_REPORT.md` أولًا لمعرفة
  المجتمعات والعقد المحورية (مثل `apiCall`, `AuthPrincipal`, `AdminApi`, `PrismaService`).
- إذا وُجد `graphify-out/graph.json` فاستعلم منه (`graphify query "<السؤال>"`)
  بدل قراءة عشرات الملفات.
- قبل تعديل دالة أو كلاس محوري، افحص ما يرتبط به (`graphify explain "<الاسم>"`).
- بعد أي تعديل على الكود، حدّث الرسم (مجاني بلا توكينز): `graphify update .`
- إذا كان الرسم أقدم من آخر commit (قارن `git rev-parse HEAD` مع "Built from commit"
  في التقرير)، فاعتبره قديمًا وحدّثه قبل الاعتماد عليه.
