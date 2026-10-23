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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import com.dejavu.core.ui.theme.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.dejavu.core.ui.theme.OutlinedButton
import com.dejavu.core.ui.theme.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.brands.BrandLogo
import com.dejavu.admin.ui.categories.CategoryManager
import com.dejavu.admin.ui.barcode.rememberBarcodeScanner
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.AdminBrand
import com.dejavu.core.admin.AdminCategory
import com.dejavu.core.admin.AdminProduct
import com.dejavu.core.admin.AdminVariant
import com.dejavu.core.admin.CreateProductRequest
import com.dejavu.core.admin.ImageRequest
import com.dejavu.core.admin.ImageUrl
import com.dejavu.core.admin.InventoryRepository
import com.dejavu.core.admin.NewVariantRequest
import com.dejavu.core.admin.ProductStatus
import com.dejavu.core.admin.ProductVisibility
import com.dejavu.core.admin.UpdateProductRequest
import com.dejavu.core.admin.UpdateVariantRequest
import com.dejavu.core.admin.VariantPlanner
import com.dejavu.core.model.Money
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

/** A non-negative amount with up to 2 decimals, as the API accepts. */
internal fun isAmount(v: String) = v.trim().toBigDecimalOrNull()?.let { it.signum() >= 0 && it.scale() <= 2 } == true

/** Shoe sizes offered as toggle chips; other sizes (S, M, 43…) are typed. */
private val SHOE_SIZES = (35..42).map { it.toString() }

/** Numbers in order, then the rest as entered (stable sort). */
private fun orderSizes(sizes: List<String>): List<String> =
    sizes.sortedWith(compareBy<String>({ it.toIntOrNull() == null }, { it.toIntOrNull() ?: 0 }))

/** A factory barcode as typed or scanned: printable characters only, at most 64. */
internal fun cleanBarcode(v: String) = v.filter { !it.isWhitespace() }.take(64)

/**
 * One size × color line of a new product. Text fields stay strings while typing.
 * The SKU is not typed: the server numbers variants "<product SKU>-<n>".
 */
data class VariantRow(
    val size: String?,
    val color: String?,
    val colorHex: String?,
    val quantity: String = "",
    /** Optional override; empty = the product cost. */
    val unitCost: String = "",
    val price: String = "",
    val barcode: String = "",
) {
    val title: String get() = listOfNotNull(size, color).joinToString(" · ").ifBlank { "مقاس واحد" }
}

data class ProductForm(
    val categoryId: String? = null,
    val nameAr: String = "",
    val brandId: String? = null,
    /** The brand when loaded, so removing it is sent as "". */
    val savedBrandId: String? = null,
    /** Push a "new product" notification to customers when saved (published products only). */
    val notify: Boolean = false,
    val instagramUrl: String = "",
    val savedInstagram: String = "",
    /** Uploaded video URL; blank = none. */
    val videoUrl: String = "",
    val savedVideo: String = "",
    val visibility: ProductVisibility = ProductVisibility.ALL,
    val descriptionAr: String = "",
    /** Bag size in cm, free text (e.g. 30 × 20 × 10); blank = none. */
    val bagSize: String = "",
    val savedBagSize: String = "",
    /** Heel height in cm (e.g. 7.5); blank = none. */
    val heelHeight: String = "",
    val savedHeelHeight: String = "",
    val price: String = "",
    val compareAt: String = "",
    /** Cost per piece (SAR), shared by all variants. */
    val costPrice: String = "",
    /** Price for merchant customers (SAR); blank = they pay the normal price. */
    val wholesalePrice: String = "",
    /** The wholesale price when loaded, so clearing it is sent as "". */
    val savedWholesale: String = "",
    val status: ProductStatus = ProductStatus.DRAFT,
    val featured: Boolean = false,
    val images: List<ImageUrl> = emptyList(),
    /** What the product had when loaded, so images are only re-sent when changed. */
    val savedImages: List<ImageUrl> = emptyList(),
    val sizes: String = "",
    val colors: String = "",
    val variants: List<VariantRow> = emptyList(),
    /** Existing product only. */
    val skuNumber: Int? = null,
    val variantSkus: List<String> = emptyList(),
    val variantColors: List<String> = emptyList(),
)

class ProductFormViewModel(private val productId: String?, private val repo: InventoryRepository) : BaseViewModel() {
    private val _categories = MutableStateFlow<Load<List<Pair<AdminCategory, Int>>>>(Load.Loading)
    val categories = _categories.asStateFlow()
    private val _brands = MutableStateFlow<List<AdminBrand>>(emptyList())
    val brands = _brands.asStateFlow()
    private val _notice = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** Outcome of the customer notification, shown after leaving the screen. */
    val notice = _notice.asSharedFlow()
    private val _form = MutableStateFlow(ProductForm())
    val form = _form.asStateFlow()
    private val _ready = MutableStateFlow(productId == null)

    /** False while an existing product is still loading. */
    val ready = _ready.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _uploading = MutableStateFlow(0)

    /** Photos still being uploaded. */
    val uploading = _uploading.asStateFlow()
    private val _videoUploading = MutableStateFlow(false)

    /** True while the product video is being uploaded. */
    val videoUploading = _videoUploading.asStateFlow()
    private val _saved = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val saved = _saved.asSharedFlow()
    val isNew = productId == null

    fun load() {
        viewModelScope.launch { _categories.value = repo.categories().toLoad() }
        viewModelScope.launch { repo.brands().onSuccess { _brands.value = it } }
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

    /** Fetches the categories again (after renaming / hiding them). */
    fun reloadCategories() {
        viewModelScope.launch { repo.categories().onSuccess { _categories.value = Load.Ready(it) } }
    }

    fun addBrand(name: String) {
        viewModelScope.launch {
            repo.createBrand(name).orSay()?.let { b ->
                _brands.value = (_brands.value + b).sortedBy { it.name }
                edit { it.copy(brandId = b.id) }
                say("تمت إضافة الماركة ${b.name}")
            }
        }
    }

    fun edit(change: (ProductForm) -> ProductForm) {
        _form.value = change(_form.value)
    }

    /** Shows [message] as a snackbar (used by the screen for picker errors). */
    fun showMessage(message: String) = say(message)

    /** Uploads a picked video and keeps its URL. */
    fun uploadVideo(bytes: ByteArray, mimeType: String) {
        if (_videoUploading.value) return
        _videoUploading.value = true
        viewModelScope.launch {
            repo.uploadVideo(bytes, mimeType).orSay()?.let { file ->
                edit { it.copy(videoUrl = file.url) }
                say("تم رفع الفيديو")
            }
            _videoUploading.value = false
        }
    }

    fun removeVideo() = edit { it.copy(videoUrl = "") }

    /** Colors a photo can be tagged with. */
    fun colorsOf(f: ProductForm): List<String> = if (isNew) VariantPlanner.split(f.colors) else f.variantColors

    /** Rebuilds the size × color lines, keeping what was typed for lines that stay. */
    fun generate() {
        val f = _form.value
        val sizes = VariantPlanner.split(f.sizes)
        val colors = VariantPlanner.split(f.colors)
        // Only the size × color combinations are used; the server assigns the SKUs.
        val rows = VariantPlanner.plan("item", sizes, colors).map { p ->
            f.variants.firstOrNull { it.size == p.size && it.color == p.color } ?: VariantRow(p.size, p.color, p.colorHex)
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

    fun setImages(images: List<ImageUrl>) = edit { it.copy(images = images.take(MAX_IMAGES)) }

    /** Uploads a picked photo and adds it to the gallery. */
    fun upload(bytes: ByteArray) {
        if (_form.value.images.size + _uploading.value >= MAX_IMAGES) return say("الحد الأقصى $MAX_IMAGES صور للمنتج")
        _uploading.value += 1
        viewModelScope.launch {
            repo.uploadImage(bytes).orSay()?.let { file ->
                edit { f -> f.copy(images = (f.images + ImageUrl(file.url)).take(MAX_IMAGES)) }
            }
            _uploading.value -= 1
        }
    }

    fun save() {
        val f = _form.value
        if (_uploading.value > 0) return say("انتظر حتى يكتمل رفع الصور")
        if (_videoUploading.value) return say("انتظر حتى يكتمل رفع الفيديو")
        problem(f)?.let { return say(it) }
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val colors = colorsOf(f)
            val images = f.images.map { if (it.color != null && it.color !in colors) it.copy(color = null) else it }
            val requests = images.map { ImageRequest(it.url, it.color) }
            val id = if (productId == null) {
                repo.createProduct(
                    CreateProductRequest(
                        categoryId = f.categoryId!!,
                        nameAr = f.nameAr.trim(),
                        // The server defaults the English name to the Arabic one and generates the slug.
                        nameEn = null,
                        slug = null,
                        basePrice = f.price.trim(),
                        costPrice = f.costPrice.trim().ifBlank { null },
                        wholesalePrice = f.wholesalePrice.trim().ifBlank { null },
                        compareAtPrice = f.compareAt.trim().ifBlank { null },
                        descriptionAr = f.descriptionAr.trim().ifBlank { null },
                        status = f.status,
                        isFeatured = f.featured,
                        images = requests.ifEmpty { null },
                        variants = f.variants.map { it.toRequest() },
                        brandId = f.brandId,
                        notifyCustomers = f.notify.takeIf { it },
                        videoUrl = f.videoUrl.trim().ifBlank { null },
                        instagramUrl = f.instagramUrl.trim().ifBlank { null },
                        visibility = f.visibility,
                        bagSize = f.bagSize.trim().ifBlank { null },
                        heelHeightCm = f.heelHeight.trim().ifBlank { null },
                    ),
                ).orSay()?.let { p ->
                    announce(f, p.notifiedCustomers)
                    p.id
                }
            } else {
                repo.updateProduct(
                    productId,
                    UpdateProductRequest(
                        categoryId = f.categoryId,
                        nameAr = f.nameAr.trim(),
                        descriptionAr = f.descriptionAr.trim().ifBlank { null },
                        basePrice = f.price.trim(),
                        costPrice = f.costPrice.trim().ifBlank { null },
                        // "" clears it; unchanged blank is left out.
                        wholesalePrice = f.wholesalePrice.trim().ifBlank { if (f.savedWholesale.isNotBlank()) "" else null },
                        compareAtPrice = f.compareAt.trim().ifBlank { null },
                        status = f.status,
                        isFeatured = f.featured,
                        // "" clears a value that was set; an unchanged blank is left out.
                        brandId = f.brandId ?: if (f.savedBrandId != null) "" else null,
                        notifyCustomers = f.notify.takeIf { it },
                        videoUrl = f.videoUrl.trim().ifBlank { if (f.savedVideo.isNotBlank()) "" else null },
                        instagramUrl = f.instagramUrl.trim().ifBlank { if (f.savedInstagram.isNotBlank()) "" else null },
                        visibility = f.visibility,
                        // "" clears a value that was set; an unchanged blank is left out.
                        bagSize = f.bagSize.trim().ifBlank { if (f.savedBagSize.isNotBlank()) "" else null },
                        heelHeightCm = f.heelHeight.trim().ifBlank { if (f.savedHeelHeight.isNotBlank()) "" else null },
                    ),
                ).orSay()?.let { p ->
                    announce(f, p.notifiedCustomers)
                    if (images != f.savedImages) repo.setImages(p.id, requests).orSay()?.id else p.id
                }
            }
            _busy.value = false
            id?.let { _saved.tryEmit(it) }
        }
    }

    private fun announce(f: ProductForm, notified: Int?) {
        if (!f.notify || notified == null) return
        _notice.tryEmit(
            if (notified > 0) "تم إرسال إشعار المنتج إلى $notified عميل"
            else "لم يُرسل الإشعار: المنتج غير منشور للعملاء أو لا يوجد عملاء بعد",
        )
    }

    private fun problem(f: ProductForm): String? = when {
        f.categoryId == null -> "اختر القسم"
        f.nameAr.trim().length < 2 -> "اكتب اسم المنتج بالعربية"
        f.instagramUrl.isNotBlank() && !isWebLink(f.instagramUrl) -> "رابط إنستغرام يجب أن يبدأ بـ http:// أو https://"
        !isAmount(f.price) || f.price.trim().toBigDecimal().signum() == 0 -> "اكتب سعراً صحيحاً"
        f.compareAt.isNotBlank() && !isAmount(f.compareAt) -> "السعر قبل الخصم غير صحيح"
        f.costPrice.isNotBlank() && !isAmount(f.costPrice) -> "التكلفة غير صحيحة"
        f.wholesalePrice.isNotBlank() && !isAmount(f.wholesalePrice) -> "سعر التاجر غير صحيح"
        f.heelHeight.isNotBlank() && !isHeelHeight(f.heelHeight) -> "ارتفاع الكعب غير صحيح (مثل 7.5 سم)"
        f.images.size > MAX_IMAGES -> "الحد الأقصى $MAX_IMAGES صور"
        !isNew -> null
        f.variants.isEmpty() -> "أضف المقاسات والألوان ثم اضغط \"توليد المتغيرات\""
        f.variants.any { it.quantity.isNotBlank() && it.quantity.toIntOrNull()?.let { q -> q >= 0 } != true } -> "الكمية يجب أن تكون رقماً صحيحاً"
        f.variants.any { it.unitCost.isNotBlank() && !isAmount(it.unitCost) } -> "تكلفة القطعة غير صحيحة"
        f.variants.any { (it.quantity.toIntOrNull() ?: 0) > 0 && it.unitCost.isBlank() } && Money.isZero(f.costPrice) ->
            "اكتب تكلفة المنتج حتى تُسجَّل الكميات الحالية بقيمتها"
        f.variants.any { it.price.isNotBlank() && !isAmount(it.price) } -> "سعر المتغير غير صحيح"
        f.variants.map { it.barcode.trim() }.filter { it.isNotEmpty() }.let { it.toSet().size != it.size } -> "باركود المصنع مكرر بين المتغيرات"
        else -> null
    }
}

/** Heel height in cm: up to two digits and one decimal, e.g. 7 or 7.5. */
private fun isHeelHeight(v: String) = Regex("""\d{1,2}(\.\d)?""").matches(v.trim())

private fun cleanHeel(v: String?): String = v?.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: v.orEmpty()

private fun VariantRow.toRequest(): NewVariantRequest {
    val qty = quantity.toIntOrNull()?.takeIf { it > 0 }
    return NewVariantRequest(
        sku = null,
        barcode = barcode.trim().ifBlank { null },
        size = size,
        color = color,
        colorHex = colorHex,
        price = price.trim().ifBlank { null },
        initialQuantity = qty,
        unitCost = unitCost.trim().ifBlank { null }?.takeIf { qty != null },
    )
}

private fun AdminProduct.toForm() = ProductForm(
    categoryId = category.id,
    nameAr = nameAr,
    brandId = brand?.id,
    savedBrandId = brand?.id,
    instagramUrl = instagramUrl.orEmpty(),
    savedInstagram = instagramUrl.orEmpty(),
    videoUrl = videoUrl.orEmpty(),
    savedVideo = videoUrl.orEmpty(),
    visibility = visibility,
    descriptionAr = descriptionAr.orEmpty(),
    bagSize = bagSize.orEmpty(),
    savedBagSize = bagSize.orEmpty(),
    heelHeight = cleanHeel(heelHeightCm),
    savedHeelHeight = cleanHeel(heelHeightCm),
    price = basePrice,
    compareAt = compareAtPrice.orEmpty(),
    costPrice = costPrice.takeIf { !Money.isZero(it) }.orEmpty(),
    wholesalePrice = wholesalePrice.orEmpty(),
    savedWholesale = wholesalePrice.orEmpty(),
    status = status,
    featured = isFeatured,
    images = images,
    savedImages = images,
    skuNumber = skuNumber,
    variantSkus = variants.map { it.sku },
    variantColors = variants.mapNotNull { it.color?.trim()?.ifBlank { null } }.distinct(),
)

/** Create ([productId] null) or edit a product. */
@Composable
fun ProductFormScreen(productId: String?, onBack: () -> Unit, saved: (String) -> Unit) {
    val vm = adminViewModel(key = "product-form:${productId ?: "new"}") { ProductFormViewModel(productId, it.inventory) }
    val categories by vm.categories.collectAsStateWithLifecycle()
    val brands by vm.brands.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val form by vm.form.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val uploading by vm.uploading.collectAsStateWithLifecycle()
    val videoUploading by vm.videoUploading.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var scanRow by rememberSaveable { mutableStateOf(-1) }
    var managingCategories by rememberSaveable { mutableStateOf(false) }
    val scan = rememberBarcodeScanner { code -> if (scanRow >= 0) vm.editRow(scanRow) { it.copy(barcode = cleanBarcode(code)) } }
    CollectMessages(vm, snackbar)
    LaunchedEffect(vm) { vm.load() }
    LaunchedEffect(vm) { vm.saved.collect { saved(it) } }
    // A toast, because the screen closes right after saving.
    LaunchedEffect(vm) { vm.notice.collect { Toast.makeText(context.applicationContext, it, Toast.LENGTH_LONG).show() } }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar(if (vm.isNew) "منتج جديد" else "تعديل المنتج", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Button(onClick = vm::save, enabled = ready && !busy && uploading == 0 && !videoUploading, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("حفظ المنتج")
                }
            }
        },
    ) { padding ->
        LoadContent(if (ready) categories else Load.Loading, onRetry = vm::load, modifier = Modifier.padding(padding)) { cats ->
            LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
                item { SkuCard(form, vm.isNew) }
                item { SectionTitle("البيانات الأساسية") }
                item {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                CategoryPicker(cats, form.categoryId, onAdd = vm::addCategory) { id -> vm.edit { it.copy(categoryId = id) } }
                            }
                            TextButton(onClick = { managingCategories = true }) { Text("تعديل الأقسام") }
                        }
                        Field("اسم المنتج", form.nameAr) { v -> vm.edit { it.copy(nameAr = v) } }
                        BrandPicker(brands, form.brandId, onAdd = vm::addBrand) { id -> vm.edit { it.copy(brandId = id) } }
                        OutlinedTextField(
                            value = form.descriptionAr,
                            onValueChange = { v -> vm.edit { it.copy(descriptionAr = v) } },
                            label = { Text("الوصف (اختياري)") },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = form.instagramUrl,
                            onValueChange = { v -> vm.edit { it.copy(instagramUrl = v.trim()) } },
                            label = { Text("رابط منشور إنستغرام (اختياري)") },
                            supportingText = {
                                Text(if (form.instagramUrl.isNotBlank() && !isWebLink(form.instagramUrl)) "يجب أن يبدأ الرابط بـ https://" else "مثل https://www.instagram.com/p/…")
                            },
                            isError = form.instagramUrl.isNotBlank() && !isWebLink(form.instagramUrl),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Field("مقاس الشنطة (سم) (اختياري)", form.bagSize, hint = "مثل 30 × 20 × 10") { v -> vm.edit { it.copy(bagSize = v.take(60)) } }
                        Field(
                            "ارتفاع الكعب (سم) (اختياري)",
                            form.heelHeight,
                            decimal = true,
                            hint = if (form.heelHeight.isNotBlank() && !isHeelHeight(form.heelHeight)) "رقم حتى خانتين وكسر عشري واحد، مثل 7.5" else "مثل 7.5",
                        ) { v -> vm.edit { it.copy(heelHeight = v.take(4)) } }
                    }
                }
                item { SectionTitle("السعر والتكلفة (ر.س)") }
                item {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Field("سعر البيع", form.price, Modifier.weight(1f), decimal = true) { v -> vm.edit { it.copy(price = v) } }
                            Field("قبل الخصم (اختياري)", form.compareAt, Modifier.weight(1f), decimal = true) { v -> vm.edit { it.copy(compareAt = v) } }
                        }
                        Field(
                            "التكلفة (ر.س)",
                            form.costPrice,
                            decimal = true,
                            hint = "تكلفة القطعة الواحدة، تُستخدم لكل المقاسات والألوان",
                        ) { v -> vm.edit { it.copy(costPrice = v) } }
                        Field(
                            "سعر التاجر (ر.س)",
                            form.wholesalePrice,
                            decimal = true,
                            hint = "سعر الجملة لعملاء حساب التاجر، فارغ = يدفعون سعر البيع",
                        ) { v -> vm.edit { it.copy(wholesalePrice = v) } }
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
                        VisibilityPicker(form.visibility) { v -> vm.edit { it.copy(visibility = v) } }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("إرسال إشعار للعملاء بهذا المنتج")
                                Text(
                                    "يصل لكل العملاء عند الحفظ مع اسم المنتج وصورته، إذا كان منشوراً في المتجر",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = form.notify, onCheckedChange = { v -> vm.edit { it.copy(notify = v) } })
                        }
                    }
                }
                if (vm.isNew) {
                    item { SectionTitle("المقاسات والألوان") }
                    item { VariantOptions(form, vm) }
                    itemsIndexed(form.variants, key = { _, r -> "${r.size}|${r.color}" }) { i, row ->
                        VariantRowCard(
                            row,
                            onChange = { change -> vm.editRow(i, change) },
                            onRemove = { vm.removeRow(i) },
                            onScan = { scanRow = i; scan() },
                        )
                    }
                } else {
                    item {
                        Text(
                            "المقاسات والألوان وكمياتها وباركود المصنع تُعدَّل من صفحة المنتج.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                item { SectionTitle("الصور") }
                item {
                    ProductGallery(
                        images = form.images,
                        colors = vm.colorsOf(form),
                        uploading = uploading > 0,
                        onChange = vm::setImages,
                        onPicked = vm::upload,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                item { SectionTitle("فيديو الصنف") }
                item {
                    ProductVideoCard(
                        videoUrl = form.videoUrl.ifBlank { null },
                        uploading = videoUploading,
                        onPicked = vm::uploadVideo,
                        onError = vm::showMessage,
                        onRemove = vm::removeVideo,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
    if (managingCategories) {
        val close = {
            managingCategories = false
            vm.reloadCategories()
        }
        AlertDialog(
            onDismissRequest = close,
            title = { Text("تعديل الأقسام") },
            text = { CategoryManager(Modifier.heightIn(max = 480.dp)) },
            confirmButton = { TextButton(onClick = close) { Text("تم") } },
        )
    }
}

/** Read-only product SKU: numbered by the server (100…), variants "<SKU>-<n>". */
@Composable
private fun SkuCard(form: ProductForm, isNew: Boolean) {
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("رقم الصنف (SKU)", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text(
                if (isNew || form.skuNumber == null) "يُولَّد تلقائياً" else form.skuNumber.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = Lux.GoldDeep,
            )
        }
        Text(
            if (isNew) {
                "يُرقَّم المنتج تلقائياً عند الحفظ (مثل 100) وكل مقاس/لون برقم فرعي (100-1، 100-2…)."
            } else {
                form.variantSkus.joinToString("، ").ifBlank { "لا توجد متغيرات بعد" }
            },
            style = MaterialTheme.typography.bodySmall,
            color = Lux.InkSoft,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VariantOptions(form: ProductForm, vm: ProductFormViewModel) {
    var bulkQty by rememberSaveable { mutableStateOf("") }
    var bulkCost by rememberSaveable { mutableStateOf("") }
    val chosenColors = VariantPlanner.split(form.colors)
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val chosenSizes = VariantPlanner.split(form.sizes)
        Field(
            "المقاسات (افصل بفاصلة)",
            form.sizes,
            hint = "اختر من المقاسات أدناه أو اكتب مقاساً آخر (مثل S أو M أو 43) · فارغ = مقاس واحد",
        ) { v -> vm.edit { it.copy(sizes = v) } }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SHOE_SIZES.forEach { size ->
                val selected = size in chosenSizes
                FilterChip(
                    selected = selected,
                    onClick = {
                        val next = if (selected) chosenSizes - size else orderSizes(chosenSizes + size)
                        vm.edit { it.copy(sizes = next.joinToString(", ")) }
                    },
                    label = { Text(size) },
                )
            }
            if (chosenSizes.isNotEmpty()) {
                FilterChip(false, { vm.edit { it.copy(sizes = "") } }, label = { Text("مقاس واحد") })
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
                Field("تكلفة خاصة (اختياري)", bulkCost, Modifier.weight(1f), decimal = true) { bulkCost = it }
                TextButton(onClick = { vm.fillAll(bulkQty, bulkCost) }) { Text("تطبيق") }
            }
        }
    }
}

@Composable
private fun VariantRowCard(row: VariantRow, onChange: ((VariantRow) -> VariantRow) -> Unit, onRemove: () -> Unit, onScan: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), contentPadding = 12.dp, elevation = 6.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.colorHex?.let { Swatch(it) }
                Text(row.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text("SKU تلقائي", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
                IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, contentDescription = "حذف") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("الكمية الحالية", row.quantity, Modifier.weight(1f), number = true) { v -> onChange { it.copy(quantity = v) } }
                Field("تكلفة خاصة", row.unitCost, Modifier.weight(1f), decimal = true) { v -> onChange { it.copy(unitCost = v) } }
                Field("سعر خاص", row.price, Modifier.weight(1f), decimal = true) { v -> onChange { it.copy(price = v) } }
            }
            BarcodeField(row.barcode, onScan = onScan) { v -> onChange { it.copy(barcode = v) } }
        }
    }
}

// ----------------------------------------------- variants of an existing product ---

/** Adds a size/color to an existing product, with its current stock. The SKU is numbered by the server. */
@Composable
fun AddVariantDialog(product: AdminProduct, onDismiss: () -> Unit, onSave: (NewVariantRequest) -> Unit) {
    var size by rememberSaveable { mutableStateOf("") }
    var color by rememberSaveable { mutableStateOf("") }
    var barcode by rememberSaveable { mutableStateOf("") }
    var quantity by rememberSaveable { mutableStateOf("") }
    var cost by rememberSaveable { mutableStateOf("") }
    var price by rememberSaveable { mutableStateOf("") }
    val scan = rememberBarcodeScanner { barcode = cleanBarcode(it) }
    val qty = quantity.toIntOrNull() ?: 0
    val nextNo = (product.variants.mapNotNull { it.variantNo }.maxOrNull() ?: product.variants.size) + 1
    val nextSku = product.skuNumber?.let { "$it-$nextNo" }
    val barcodeTaken = barcode.isNotBlank() && product.variants.any { it.barcode == barcode.trim() || it.sku == barcode.trim() }
    val valid = (quantity.isBlank() || quantity.toIntOrNull() != null) && !barcodeTaken &&
        (cost.isBlank() || isAmount(cost)) && (price.isBlank() || isAmount(price)) &&
        (size.isNotBlank() || color.isNotBlank())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إضافة مقاس / لون") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "SKU: ${nextSku ?: "يُولَّد تلقائياً"}",
                    style = MaterialTheme.typography.labelLarge,
                    color = Lux.GoldDeep,
                )
                Field("المقاس", size) { size = it }
                Field("اللون", color) { color = it }
                BarcodeField(barcode, hint = if (barcodeTaken) "هذا الباركود مستخدم في هذا المنتج" else null, onScan = scan) { barcode = it }
                Field("الكمية الحالية", quantity, number = true) { quantity = it }
                if (qty > 0) {
                    Field(
                        "تكلفة القطعة (اختياري)",
                        cost,
                        decimal = true,
                        hint = "فارغ = تكلفة المنتج ${Money.formatBase(product.costPrice)}",
                    ) { cost = it }
                }
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
                            sku = null,
                            barcode = barcode.trim().ifBlank { null },
                            size = size.trim().ifBlank { null },
                            color = color.trim().ifBlank { null },
                            colorHex = VariantPlanner.colorHex(color),
                            price = price.trim().ifBlank { null },
                            initialQuantity = qty.takeIf { it > 0 },
                            unitCost = cost.trim().ifBlank { null }?.takeIf { qty > 0 },
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
    var barcode by rememberSaveable { mutableStateOf(variant.barcode.orEmpty()) }
    var threshold by rememberSaveable { mutableStateOf(variant.lowStockThreshold.toString()) }
    var active by rememberSaveable { mutableStateOf(variant.isActive) }
    val scan = rememberBarcodeScanner { barcode = cleanBarcode(it) }
    val valid = (price.isBlank() || isAmount(price)) && threshold.toIntOrNull()?.let { it >= 0 } == true
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(variant.sku) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("المقاس", size) { size = it }
                Field("اللون", color) { color = it }
                BarcodeField(barcode, hint = if (variant.barcode != null && barcode.isBlank()) "سيُحذف باركود المصنع" else null, onScan = scan) { barcode = it }
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
                    val newBarcode = barcode.trim()
                    onSave(
                        UpdateVariantRequest(
                            // Only sent when changed; "" clears it.
                            barcode = newBarcode.takeIf { it != variant.barcode.orEmpty() },
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

/** "ظهور المنتج": where the product is sold (till, customer app, merchants only, hidden). */
@Composable
private fun VisibilityPicker(selected: ProductVisibility, onPick: (ProductVisibility) -> Unit) {
    Column(Modifier.fillMaxWidth().selectableGroup().padding(top = 8.dp)) {
        Text("ظهور المنتج", style = MaterialTheme.typography.titleSmall)
        ProductVisibility.entries.forEach { v ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = v == selected, onClick = { onPick(v) }, role = Role.RadioButton)
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = v == selected, onClick = null)
                Text(v.label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** "باركود المصنع" with a camera scan button. */
@Composable
private fun BarcodeField(value: String, hint: String? = null, onScan: () -> Unit, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(cleanBarcode(it)) },
        label = { Text("باركود المصنع (اختياري)") },
        supportingText = hint?.let { { Text(it) } },
        isError = hint != null && hint.contains("مستخدم"),
        singleLine = true,
        trailingIcon = {
            IconButton(onClick = onScan) { Icon(Icons.Filled.Search, contentDescription = "مسح الباركود بالكاميرا", tint = Lux.GoldDeep) }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

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
                            "${"   ".repeat(depth)}${if (depth > 0) "↳ " else ""}${c.nameAr}${if (c.isActive) "" else " (مخفي)"}",
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

/** Brand choice like the category one: pick a defined brand, none, or add a new one. */
@Composable
private fun BrandPicker(brands: List<AdminBrand>, selectedId: String?, onAdd: (name: String) -> Unit, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val selected = brands.firstOrNull { it.id == selectedId }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Text(selected?.let { "الماركة: ${it.name}" } ?: "الماركة (اختياري)", Modifier.weight(1f))
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("الماركة") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        "بدون ماركة",
                        style = if (selectedId == null) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clickable { open = false; onPick(null) }.padding(vertical = 10.dp),
                    )
                    brands.filter { it.isActive || it.id == selectedId }.forEach { b ->
                        Row(
                            Modifier.fillMaxWidth().clickable { open = false; onPick(b.id) }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BrandLogo(b.name, b.logoUrl, 32.dp)
                            Text(
                                b.name,
                                style = if (b.id == selectedId) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 10.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("إغلاق") } },
            dismissButton = { TextButton(onClick = { open = false; adding = true }) { Text("+ ماركة جديدة") } },
        )
    }
    if (adding) {
        var name by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("ماركة جديدة") },
            text = { Field("اسم الماركة", name) { name = it.take(80) } },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { adding = false; onAdd(name) }) { Text("إضافة") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("تراجع") } },
        )
    }
}
