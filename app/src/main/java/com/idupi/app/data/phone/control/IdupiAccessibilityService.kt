package com.idupi.app.data.phone.control

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.idupi.app.data.IduPiClientProvider
import com.idupi.app.data.calendar.CalendarReporting
import com.idupi.app.data.health.HealthReporting
import com.idupi.app.data.phone.PhoneStatusReporting
import com.idupi.app.data.phone.approval.PhoneApprovalReporting
import com.idupi.app.data.phone.approval.PhoneApprovals
import com.idupi.app.data.phone.bubble.BubbleGate
import com.idupi.app.data.phone.bubble.PhoneBubbleOverlay
import com.idupi.app.data.settings.SettingsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Executes commands queued by the PC (phone-agent T3).
 *
 * The service is the owner's explicit grant: the system only binds it after
 * "IDUPI" is enabled in the accessibility settings, and the loop additionally
 * requires the persisted "Control desde la PC" switch (default off) plus a
 * saved connection profile. Turning the switch off cancels the in-flight
 * long-poll immediately instead of waiting for it to time out.
 *
 * It also hosts [PhoneStatusReporting.start] so a phone with accessibility
 * granted keeps reporting status even when the notification listener is not
 * connected; the reporter is process-wide and idempotent.
 */
class IdupiAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var controlJob: Job? = null
    private var settingsJob: Job? = null
    private var bubbleJob: Job? = null

    private val executor by lazy { PhoneCommandExecutor(this, PhoneApprovals.ledger(this)) }

    /** The floating bubble (phone-bubble T4); created on first use. */
    private val bubbleOverlay by lazy { PhoneBubbleOverlay(this) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        PhoneStatusReporting.start(this)
        CalendarReporting.start(this)
        HealthReporting.start(this)

        settingsJob?.cancel()
        settingsJob = scope.launch {
            SettingsProvider.repository.phoneControlEnabled.collect { enabled ->
                if (enabled) {
                    startControlLoop()
                    // Risky commands wait for the owner; poll while control is on.
                    PhoneApprovalReporting.start(this@IdupiAccessibilityService)
                } else {
                    stopControlLoop()
                    PhoneApprovalReporting.stop()
                }
            }
        }

        // The bubble needs the owner's setting AND phone control; the service
        // being bound IS the accessibility grant (phone-bubble T4).
        bubbleJob?.cancel()
        bubbleJob = scope.launch {
            combine(
                SettingsProvider.repository.phoneBubbleEnabled,
                SettingsProvider.repository.phoneControlEnabled,
            ) { bubble, control ->
                BubbleGate.shouldShow(
                    bubbleEnabled = bubble,
                    phoneControlEnabled = control,
                    accessibilityConnected = true,
                )
            }.collect { show -> bubbleOverlay.setVisible(show) }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        bubbleOverlay.onConfigurationChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Commands read the tree on demand; live events are not needed.
    }

    override fun onInterrupt() {
        stopControlLoop()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopControlLoop()
        PhoneApprovalReporting.stop()
        bubbleOverlay.setVisible(false)
        bubbleJob?.cancel()
        bubbleJob = null
        settingsJob?.cancel()
        settingsJob = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopControlLoop()
        PhoneApprovalReporting.stop()
        bubbleOverlay.destroy()
        bubbleJob?.cancel()
        bubbleJob = null
        settingsJob?.cancel()
        settingsJob = null
        scope.cancel()
        super.onDestroy()
    }

    private fun startControlLoop() {
        if (controlJob?.isActive == true) return

        val poller = PhoneControlPoller(
            clientSource = IduPiClientProvider,
            isEnabled = { SettingsProvider.repository.phoneControlEnabled.first() },
            canPoll = { IduPiClientProvider.configureFromStorage(this) },
            execute = executor::execute,
        )

        controlJob = scope.launch {
            while (isActive) {
                when (val step = poller.pollOnce()) {
                    PollStep.Stop -> break
                    PollStep.Continue -> Unit
                    is PollStep.Retry -> delay(step.delayMillis)
                }
            }
        }
    }

    private fun stopControlLoop() {
        controlJob?.cancel()
        controlJob = null
    }
}
