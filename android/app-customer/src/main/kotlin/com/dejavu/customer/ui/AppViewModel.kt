package com.dejavu.customer.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dejavu.core.data.userMessage
import com.dejavu.customer.AppContainer
import com.dejavu.customer.DejavuApp
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Loading / failed / loaded state of one piece of screen data. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Failed(val message: String) : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
}

fun <T> Result<T>.toLoad(): Load<T> = fold({ Load.Ready(it) }, { Load.Failed(it.userMessage()) })

/** ViewModel with one-off user messages (shown as snackbars). */
abstract class BaseViewModel : ViewModel() {
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    protected fun say(message: String) {
        _messages.tryEmit(message)
    }

    /** The value on success; on failure shows the error and returns null. */
    protected fun <T> Result<T>.orSay(): T? = getOrElse {
        say(it.userMessage())
        null
    }
}

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as DejavuApp).container

/** A screen-scoped ViewModel built from the app's dependencies. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, noinline create: (AppContainer) -> VM): VM {
    val container = appContainer()
    return viewModel(key = key) { create(container) }
}
