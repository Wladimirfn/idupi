package com.idupi.app.ui.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One-shot navigation requests that originate outside the Compose tree
 * (a notification tap delivered to MainActivity). AppNavigation observes
 * [pending], navigates, and clears it.
 */
object AppNavigationIntents {

    private val _pending = MutableStateFlow<Screen?>(null)
    val pending: StateFlow<Screen?> = _pending.asStateFlow()

    @Synchronized
    fun request(screen: Screen) {
        _pending.value = screen
    }

    @Synchronized
    fun clear() {
        _pending.value = null
    }
}
