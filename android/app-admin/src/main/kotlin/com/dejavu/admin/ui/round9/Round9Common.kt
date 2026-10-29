package com.dejavu.admin.ui.round9

import androidx.lifecycle.viewModelScope
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.toLoad
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One screen of data loaded by [loader], plus actions that report their result and reload. */
open class LoadViewModel<T>(private val loader: suspend () -> Result<T>) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<T>>(Load.Loading)
    val state = _state.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch { _state.value = loader().toLoad() }
    }

    /** Runs [block]; on success shows [done] (if any), calls [then] and reloads. */
    fun <R> act(done: String?, then: (R) -> Unit = {}, block: suspend () -> Result<R>) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            block().orSay()?.let {
                done?.let(::say)
                then(it)
                _state.value = loader().toLoad()
            }
            _busy.value = false
        }
    }
}

/** "777123456, 777000111" → a clean list. */
internal fun phonesList(text: String): List<String> = text.split(',', '،', '\n').map { it.trim() }.filter { it.isNotEmpty() }
