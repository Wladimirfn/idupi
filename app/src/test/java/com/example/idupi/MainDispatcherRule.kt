package com.example.idupi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Swaps [Dispatchers.Main] for a [StandardTestDispatcher] for the duration of a test.
 *
 * `viewModelScope` dispatches on `Dispatchers.Main`, which has no Android main-thread
 * implementation on the JVM unit test classpath — without this rule every ViewModel
 * test would fail with "Module with the Main dispatcher had failed to initialize".
 */
class MainDispatcherRule(
    private val testDispatcher: TestDispatcher = StandardTestDispatcher()
) : TestWatcher() {

    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
