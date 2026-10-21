@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.admin.ui.products

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.core.admin.AdminCategory
import com.dejavu.core.admin.AdminProduct
import com.dejavu.core.admin.AdminVariant
import com.dejavu.core.admin.CreateProductRequest
import com.dejavu.core.admin.ImageRequest
import com.dejavu.core.admin.InventoryRepository
import com.dejavu.core.admin.NewVariantRequest
import com.dejavu.core.admin.ProductStatus
import com.dejavu.core.admin.UpdateProductRequest
import com.dejavu.core.admin.UpdateVariantRequest
import com.dejavu.core.admin.VariantPlanner
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.SectionTitle
import com.dejavu.core.ui.toLoad
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val SLUG = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")

/** A non-negative amount with up to 2 decimals, as the API accepts. */
internal fun isAmount(v: String) = v.trim().toBigDecimalOrNull()?.let { it.signum() >= 0 && it.scale() <= 2 } == true

/** One size × color line of a new product. Text fields stay strings while typing. */
data class VariantRow(
    val size: String?,
    val color: String?,
    val colorHex: String?,
    val sku: String,
    val quantity: String = "",
    val unitCost: String = "",
    val price: String = "",
) {
    val title: String get() = listOfNotNull(size, color).joinToString(" · ").ifBlank { "مقاس واحد" }
}

data class ProductForm(
    val categoryId: String? = null,
    val nameAr: String = "",
    val nameEn: String = "",
    val slug: String = "",
    val slugEdited: Boolean = false,
    val descriptionAr: String = "",
    val price: String = "",
    val compareAt: String = "",
    val status: ProductStatus = ProductStatus.DRAFT,
    val featured: Boolean = false,
    val imageUrls: String = "",
    val sizes: String = "",
    val colors: String = "",
    val variants: List<VariantRow> = emptyList(),
)

class ProductFormViewModel(private val productId: String?, private val repo: InventoryRepository) : BaseViewModel() {
    private val _categories = MutableStateFlow<Load<List<Pair<AdminCategory, Int>>>>(Load.Loading)
    val categories = _categories.asStateFlow()
    private val _form = MutableStateFlow(ProductForm())
    val form = _form.asStateFlow()
    private val _ready = MutableStateFlow(productId == null)

    /** False while an existing product is still loading. */
    val ready = _ready.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _saved = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val saved = _saved.asSharedFlow()
    val isNew = productId == null

    fun load() {
        viewModelScope.launch { _categories.value = repo.categories().toLoad() }
        if (productId != null && !_ready.value) {
            viewModelScope.launch {
                repo.product(productId).orSay()?.let { p ->
                    _form.value = p.toForm()
                    _ready.value = true
                }
            }
        }
    }

    fun addCategory(nameAr: String, nameEn: String) {
        viewModelScope.launch {
            repo.createCategory(nameAr, nameEn).orSay()?.let { c ->
                _categories.value = repo.categories().toLoad()
                edit { it.copy(categoryId = c.id) }
                say("تمت إضافة قسم ${c.nameAr}")
            }
        }
    }

    fun edit(change: (ProductForm) -> ProductForm) {
        _form.value = change(_form.value)
    }

    fun setNameEn(v: String) = edit { f -> f.copy(nameEn = v, slug = if (f.slugEdited || !isNew) f.slug else VariantPlanner.slugOf(v)) }

    /** Rebuilds the size × color lines, keeping what was typed for lines that stay. */
    fun generate() {
        val f = _form.value
        val sizes = VariantPlanner.split(f.sizes)
        val colors = VariantPlanner.split(f.colors)
        val slug = f.slug.ifBlank { VariantPlanner.slugOf(f.nameEn) }
        val rows = VariantPlanner.plan(slug, sizes, colors).map { p ->
            f.variants.firstOrNull { it.size == p.size && it.color == p.color }?.copy(sku = p.sku)
                ?: VariantRow(p.size, p.color, p.colorHex, p.sku)
        }
        _form.value = f.copy(variants = rows)
    }

    fun fillAll(quantity: String, unitCost: String) = edit { f ->
        f.copy(variants = f.variants.map { it.copy(quantity = quantity.ifBlank { it.quantity }, unitCost = unitCost.ifBlank { it.unitCost }) })
    }

    fun editRow(index: Int, change: (VariantRow) -> VariantRow) = edit { f ->
        f.copy(variants = f.variants.mapIndexed { i, r -> if (i == index) change(r) else r })
    }

    fun removeRow(index: Int) = edit { f -> f.copy(variants = f.variants.filterIndexed { i, _ -> i != index }) }

    fun save() {
        val f = _form.value
        problem(f)?.let { return say(it) }
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val urls = f.imageUrls.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val id = if (productId == null) {
                repo.createProduct(
                    CreateProductRequest(
                        categoryId = f.categoryId!!,
                        nameAr = f.nameAr.trim(),
                        nameEn = f.nameEn.trim(),
                        slug = f.slug.trim(),
                        basePrice = f.price.trim(),
                        compareAtPrice = f.compareAt.trim().ifBlank { null },
                        descriptionAr = f.descriptionAr.trim().ifBlank { null },
                        status = f.status,
                        isFeatured = f.featured,
                        images = urls.map(::ImageRequest).ifEmpty { null },
                        variants = f.variants.map { it.toRequest() },
                    ),
                ).orSay()?.id
            } else {
                repo.updateProduct(
                    productId,
                    UpdateProductRequest(
                        categoryId = f.categoryId,
                        nameAr = f.nameAr.trim(),
                        nameEn = f.nameEn.trim(),
                        slug = f.slug.trim(),
                        descriptionAr = f.descriptionAr.trim().ifBlank { null },
                        basePrice = f.price.trim(),
                        compareAtPrice = f.compareAt.trim().ifBlank { null },
                        status = f.status,
                        isFeatured = f.featured,
                    ),
                ).orSay()?.let { p ->
                    if (urls != p.images.map { it.url }) repo.setImages(p.id, urls).orSay()?.id else p.id
                }
            }
            _busy.value = false
            id?.let { _saved.tryEmit(it) }
        }
    }

    private fun problem(f: ProductForm): String? = when {
        f.categoryId == null -> "اختر القسم"
        f.nameAr.trim().length < 2 -> "اكتب اسم المنتج بالعربية"
        f.nameEn.trim().length < 2 -> "اكتب اسم المنتج بالإنجليزية"
        !SLUG.matches(f.slug.trim()) -> "الرابط: حروف إنجليزية صغيرة وأرقام وشرطات فقط (مثل linen-shirt)"
        !isAmount(f.price) || f.price.trim().toBigDecimal().signum() == 0 -> "اكتب سعراً صحيحاً"
        f.compareAt.isNotBlank() && !isAmount(f.compareAt) -> "السعر قبل الخصم غير صحيح"
        f.imageUrls.lines().any { it.isNotBlank() && !it.trim().startsWith("http") } -> "روابط الصور يجب أن تبدأ بـ http"
        !isNew -> null
        f.variants.isEmpty() -> "أضف المقاسات والألوان ثم اضغط \"توليد المتغيرات\""
        f.variants.any { !VariantPlanner.isValidSku(it.sku) } -> "رمز SKU: حروف إنجليزية كبيرة وأرقام وشرطات"
        f.variants.map { it.sku }.toSet().size != f.variants.size -> "رموز SKU مكررة"
        f.variants.any { it.quantity.isNotBlank() && it.quantity.toIntOrNull()?.let { q -> q >= 0 } != true } -> "الكمية يجب أن تكون رقماً صحيحاً"
        f.variants.any { (it.quantity.toIntOrNull() ?: 0) > 0 && !isAmount(it.unitCost) } -> "اكتب تكلفة القطعة لكل متغير له كمية"
        f.variants.any { it.price.isNotBlank() && !isAmount(it.price) } -> "سعر المتغير غير صحيح"
        else -> null
    }
}

private fun VariantRow.toRequest() = NewVariantRequest(
    sku = sku,
    size = size,
    color = color,
    colorHex = colorHex,
    price = price.trim().ifBlank { null },
    initialQuantity = quantity.toIntOrNull()?.takeIf { it > 0 },
    unitCost = unitCost.trim().ifBlank { null }?.takeIf { (quantity.toIntOrNull() ?: 0) > 0 },
)

private fun AdminProduct.toForm() = ProductForm(
    categoryId = category.id,
    nameAr = nameAr,
    nameEn = nameEn,
    slug = slug,
    slugEdited = true,
    descriptionAr = descriptionAr.orEmpty(),
    price = basePrice,
    compareAt = compareAtPrice.orEmpty(),
    status = status,
    featured = isFeatured,
    imageUrls = images.joinToString("\n") { it.url },
)

/** Create ([productId] null) or edit a product. */
@Composable
fun ProductFormScreen(productId: String?, onBack: () -> Unit, saved: (String) -> Unit) {
    val vm = adminViewModel(key = "product-form:${productId ?: "new"}") { ProductFormViewModel(productId, it.inventory) }
    val categories by vm.categories.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm, snackbar)
    LaunchedEffect(vm) { vm.load() }
    LaunchedEffect(vm) { vm.saved.collect { saved(it) } }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar(if (vm.isNew) "منتج جديد" else "تعديل المنتج", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Button(onClick = vm::save, enabled = ready && !busy, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("حفظ المنتج")
                }
            }
        },
    ) { padding ->
        LoadContent(if (ready) categories else Load.Loading, onRetry = vm::load, modifier = Modifier.padding(padding)) { cats ->
            LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
                item { SectionTitle("البيانات الأساسية") }
                item {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CategoryPicker(cats, form.categoryId, onAdd = vm::addCategory) { id -> vm.edit { it.copy(categoryId = id) } }
                        Field("اسم المنتج (عربي)", form.nameAr) { v -> vm.edit { it.copy(nameAr = v) } }
                        Field("اسم المنتج (إنجليزي)", form.nameEn, vm::setNameEn)
                        Field("الرابط في المتجر (slug)", form.slug, hint = "يُكتب تلقائياً من الاسم الإنجليزي") { v ->
                            vm.edit { it.copy(slug = v.lowercase().replace(' ', '-'), slugEdited = true) }
                        }
                        OutlinedTextField(
                            value = form.descriptionAr,
                            onValueChange = { v -> vm.edit { it.copy(descriptionAr = v) } },
                            label = { Text("الوصف (اختياري)") },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                item { SectionTitle("السعر (ر.س، شامل الضريبة)") }
                item {
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field("السعر", form.price, Modifier.weight(1f), decimal = true) { v -> vm.edit { it.copy(price = v) } }
                        Field("قبل الخصم (اختياري)", form.compareAt, Modifier.weight(1f), decimal = true) { v -> vm.edit { it.copy(compareAt = v) } }
                    }
                }
                item { SectionTitle("النشر") }
                item {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(form.status == ProductStatus.DRAFT, { vm.edit { it.copy(status = ProductStatus.DRAFT) } }, label = { Text("مسودة (مخفي)") })
                            FilterChip(form.status == ProductStatus.ACTIVE, { vm.edit { it.copy(status = ProductStatus.ACTIVE) } }, label = { Text("منشور في المتجر") })
                            if (!vm.isNew) {
                                FilterChip(form.status == ProductStatus.ARCHIVED, { vm.edit { it.copy(status = ProductStatus.ARCHIVED) } }, label = { Text("مؤرشف") })
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("مميز في الصفحة الرئيسية", Modifier.weight(1f))
                            Switch(checked = form.featured, onCheckedChange = { v -> vm.edit { it.copy(featured = v) } })
                        }
                    }
                }
                item { SectionTitle("الصور") }
                item {
                    OutlinedTextField(
                        value = form.imageUrls,
                        onValueChange = { v -> vm.edit { it.copy(imageUrls = v) } },
                        label = { Text("روابط الصور (رابط في كل سطر)") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                }
                if (vm.isNew) {
                    item { SectionTitle("المقاسات والألوان") }
                    item { VariantOptions(form, vm) }
                    itemsIndexed(form.variants, key = { _, r -> "${r.size}|${r.color}" }) { i, row ->
                        VariantRowCard(row, onChange = { change -> vm.editRow(i, change) }, onRemove = { vm.removeRow(i) })
                    }
                } else {
                    item {
                        Text(
                            "المقاسات والألوان وكمياتها تُعدَّل من صفحة المنتج.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VariantOptions(form: ProductForm, vm: ProductFormViewModel) {
    var bulkQty by rememberSaveable { mutableStateOf("") }
    var bulkCost by rememberSaveable { mutableStateOf("") }
    val chosenColors = VariantPlanner.split(form.colors)
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Field("المقاسات (افصل بفاصلة)", form.sizes, hint = "مثال: S, M, L أو 38, 40 · اتركه فارغاً لمقاس واحد") { v -> vm.edit { it.copy(sizes = v) } }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VariantPlanner.SIZE_PRESETS.forEach { (label, sizes) ->
                FilterChip(false, { vm.edit { it.copy(sizes = sizes.joinToString(", ")) } }, label = { Text(label) })
            }
        }
        Field("الألوان (افصل بفاصلة)", form.colors, hint = "اختر من الألوان أدناه أو اكتب لوناً آخر") { v -> vm.edit { it.copy(colors = v) } }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VariantPlanner.COLORS.forEach { (name, codeHex) ->
                val selected = name in chosenColors
                FilterChip(
                    selected = selected,
                    onClick = {
                        val next = if (selected) chosenColors - name else chosenColors + name
                        vm.edit { it.copy(colors = next.joinToString("، ")) }
                    },
                    label = { Text(name) },
                    leadingIcon = { Swatch(codeHex.second) },
                )
            }
        }
        OutlinedButton(onClick = vm::generate, modifier = Modifier.fillMaxWidth()) {
            Text(if (form.variants.isEmpty()) "توليد المتغيرات" else "تحديث المتغيرات (${form.variants.size})")
        }
        if (form.variants.size > 1) {
            Text("تعبئة سريعة لكل المتغيرات", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Field("الكمية", bulkQty, Modifier.weight(1f), number = true) { bulkQty = it }
                Field("تكلفة القطعة", bulkCost, Modifier.weight(1f), decimal = true) { bulkCost = it }
                TextButton(onClick = { vm.fillAll(bulkQty, bulkCost) }) { Text("تطبيق") }
            }
        }
    }
}

@Composable
private fun VariantRowCard(row: VariantRow, onChange: ((VariantRow) -> VariantRow) -> Unit, onRemove: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.colorHex?.let { Swatch(it) }
                Text(row.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, contentDescription = "حذف") }
            }
            Field("SKU", row.sku) { v -> onChange { it.copy(sku = v.uppercase()) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("الكمية الحالية", row.quantity, Modifier.weight(1f), number = true) { v -> onChange { it.copy(quantity = v) } }
                Field("تكلفة القطعة", row.unitCost, Modifier.weight(1f), decimal = true) { v -> onChange { it.copy(unitCost = v) } }
                Field("سعر خاص", row.price, Modifier.weight(1f), decimal = true) { v -> onChange { it.copy(price = v) } }
            }
        }
    }
}

// ----------------------------------------------- variants of an existing product ---

/** Adds a size/color to an existing product, with its current stock. */
@Composable
fun AddVariantDialog(product: AdminProduct, onDismiss: () -> Unit, onSave: (NewVariantRequest) -> Unit) {
    var size by rememberSaveable { mutableStateOf("") }
    var color by rememberSaveable { mutableStateOf("") }
    var sku by rememberSaveable { mutableStateOf("") }
    var skuEdited by rememberSaveable { mutableStateOf(false) }
    var quantity by rememberSaveable { mutableStateOf("") }
    var cost by rememberSaveable { mutableStateOf("") }
    var price by rememberSaveable { mutableStateOf("") }
    val suggested = VariantPlanner.plan(product.slug, listOfNotNull(size.trim().ifBlank { null }), listOfNotNull(color.trim().ifBlank { null })).first().sku
    val shownSku = if (skuEdited) sku else suggested
    val qty = quantity.toIntOrNull() ?: 0
    val taken = product.variants.any { it.sku == shownSku }
    val valid = VariantPlanner.isValidSku(shownSku) && !taken && (quantity.isBlank() || quantity.toIntOrNull() != null) &&
        (qty == 0 || isAmount(cost)) && (price.isBlank() || isAmount(price))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إضافة مقاس / لون") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("المقاس", size) { size = it }
                Field("اللون", color) { color = it }
                Field("SKU", shownSku, hint = if (taken) "هذا الرمز مستخدم" else null) { sku = it.uppercase(); skuEdited = true }
                Field("الكمية الحالية", quantity, number = true) { quantity = it }
                if (qty > 0) Field("تكلفة القطعة (ر.س)", cost, decimal = true) { cost = it }
                Field("سعر خاص (اختياري)", price, decimal = true) { price = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onDismiss()
                    onSave(
                        NewVariantRequest(
                            sku = shownSku,
                            size = size.trim().ifBlank { null },
                            color = color.trim().ifBlank { null },
                            colorHex = VariantPlanner.colorHex(color),
                            price = price.trim().ifBlank { null },
                            initialQuantity = qty.takeIf { it > 0 },
                            unitCost = cost.trim().takeIf { qty > 0 },
                        ),
                    )
                },
            ) { Text("إضافة") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("تراجع") } },
    )
}

/** Edits one variant. Stock is changed through stock adjustments, not here. */
@Composable
fun EditVariantDialog(variant: AdminVariant, onDismiss: () -> Unit, onSave: (UpdateVariantRequest) -> Unit) {
    var size by rememberSaveable { mutableStateOf(variant.size.orEmpty()) }
    var color by rememberSaveable { mutableStateOf(variant.color.orEmpty()) }
    var price by rememberSaveable { mutableStateOf(variant.price.orEmpty()) }
    var threshold by rememberSaveable { mutableStateOf(variant.lowStockThreshold.toString()) }
    var active by rememberSaveable { mutableStateOf(variant.isActive) }
    val valid = (price.isBlank() || isAmount(price)) && threshold.toIntOrNull()?.let { it >= 0 } == true
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(variant.sku) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("المقاس", size) { size = it }
                Field("اللون", color) { color = it }
                Field("سعر خاص (فارغ = سعر المنتج)", price, decimal = true) { price = it }
                Field("تنبيه عند وصول المخزون إلى", threshold, number = true) { threshold = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("متاح للبيع", Modifier.weight(1f))
                    Switch(checked = active, onCheckedChange = { active = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onDismiss()
                    onSave(
                        UpdateVariantRequest(
                            size = size.trim().ifBlank { null },
                            color = color.trim().ifBlank { null },
                            colorHex = VariantPlanner.colorHex(color),
                            // An empty field keeps the product price; the API has no "unset", so 0 is never sent.
                            price = price.trim().ifBlank { null },
                            lowStockThreshold = threshold.toInt(),
                            isActive = active,
                        ),
                    )
                },
            ) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("تراجع") } },
    )
}

// ----------------------------------------------------------------- widgets ---

@Composable
private fun Field(
    label: String,
    value: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    hint: String? = null,
    decimal: Boolean = false,
    number: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(if (decimal) v.filter { it.isDigit() || it == '.' } else if (number) v.filter(Char::isDigit) else v) },
        label = { Text(label) },
        supportingText = hint?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = when {
                decimal -> KeyboardType.Decimal
                number -> KeyboardType.Number
                else -> KeyboardType.Text
            },
        ),
        modifier = modifier,
    )
}

@Composable
private fun Swatch(hex: String) {
    val color = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.Gray)
    Box(Modifier.size(16.dp).background(color, CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
}

@Composable
private fun CategoryPicker(
    categories: List<Pair<AdminCategory, Int>>,
    selectedId: String?,
    onAdd: (nameAr: String, nameEn: String) -> Unit,
    onPick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val selected = categories.firstOrNull { it.first.id == selectedId }?.first
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Text(selected?.let { "القسم: ${it.nameAr}" } ?: "اختر القسم", Modifier.weight(1f))
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("القسم") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    if (categories.isEmpty()) Text("لا توجد أقسام بعد")
                    categories.forEach { (c, depth) ->
                        Text(
                            "${"   ".repeat(depth)}${if (depth > 0) "↳ " else ""}${c.nameAr}",
                            style = if (c.id == selectedId) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth().clickable { open = false; onPick(c.id) }.padding(vertical = 10.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("إغلاق") } },
            dismissButton = { TextButton(onClick = { open = false; adding = true }) { Text("+ قسم جديد") } },
        )
    }
    if (adding) {
        var nameAr by rememberSaveable { mutableStateOf("") }
        var nameEn by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("قسم جديد") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("الاسم بالعربية", nameAr) { nameAr = it }
                    Field("الاسم بالإنجليزية", nameEn, hint = "يُستخدم في رابط القسم") { nameEn = it }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = nameAr.trim().length >= 2 && VariantPlanner.slugOf(nameEn).length >= 2,
                    onClick = { adding = false; onAdd(nameAr, nameEn) },
                ) { Text("إضافة") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("تراجع") } },
        )
    }
}
