package com.dejavu.customer.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dejavu.customer.AppContainer
import com.dejavu.customer.DejavuApp

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as DejavuApp).container

/** A screen-scoped ViewModel built from the app's dependencies. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, noinline create: (AppContainer) -> VM): VM {
    val container = appContainer()
    return viewModel(key = key) { create(container) }
}
