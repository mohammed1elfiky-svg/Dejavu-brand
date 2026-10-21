package com.dejavu.admin.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dejavu.admin.AdminApp
import com.dejavu.admin.AdminContainer

@Composable
fun adminContainer(): AdminContainer = (LocalContext.current.applicationContext as AdminApp).container

@Composable
inline fun <reified VM : ViewModel> adminViewModel(key: String? = null, noinline create: (AdminContainer) -> VM): VM {
    val container = adminContainer()
    return viewModel(key = key) { create(container) }
}
