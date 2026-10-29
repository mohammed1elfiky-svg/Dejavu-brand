package com.dejavu.admin.ui.round9

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dejavu.admin.ui.adminContainer
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.ProductPrompt
import com.dejavu.core.data.userMessage
import com.dejavu.core.ui.theme.OutlinedTextField
import kotlinx.coroutines.launch

/** "تعليمات الذكاء الاصطناعي": the saved words used when a product is filled from its photo. */
@Composable
fun AiPromptButton() {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text("تعديل تعليمات الذكاء الاصطناعي") }
    if (open) AiPromptDialog(onDismiss = { open = false })
}

@Composable
private fun AiPromptDialog(onDismiss: () -> Unit) {
    val repo = adminContainer().round9
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf<ProductPrompt?>(null) }
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        repo.productPrompt().onSuccess {
            loaded = it
            text = it.prompt
        }.onFailure { error = it.userMessage() }
    }
    fun save(value: String) {
        busy = true
        scope.launch {
            repo.setProductPrompt(value).onSuccess { onDismiss() }.onFailure { error = it.userMessage() }
            busy = false
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تعليمات الذكاء الاصطناعي") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "ما يكتبه الذكاء الاصطناعي عند تعبئة المنتج من صورته: الأسلوب، ما يذكره في الوصف، طول الاسم… يبقى شكل البيانات ثابتاً.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Lux.InkSoft,
                )
                if (loaded == null && error == null) CircularProgressIndicator()
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(2000) },
                    label = { Text("التعليمات") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (loaded?.isDefault == false) TextButton(onClick = { save("") }, enabled = !busy) { Text("استرجاع التعليمات الأصلية") }
            }
        },
        confirmButton = { TextButton(onClick = { save(text) }, enabled = !busy && loaded != null && text.isNotBlank()) { Text("حفظ") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } },
    )
}
