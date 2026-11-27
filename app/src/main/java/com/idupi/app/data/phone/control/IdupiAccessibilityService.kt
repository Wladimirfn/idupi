package com.idupi.app.data.phone.control

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.idupi.app.data.IduPiClientProvider
import com.idupi.app.data.calendar.CalendarReporting
import com.idupi.app.data.health.HealthReporting
import com.idupi.app.data.phone.PhoneStatusReporting
import com.idupi.app.data.phone.approval.PhoneApprovalReporting
import com.idupi.app.data.phone.approval.PhoneApprovals
import com.idupi.app.data.phone.bubble.AssistDictationHost
import com.idupi.app.data.phone.bubble.BubbleGate
import com.idupi.app.data.phone.bubble.PhoneBubbleOverlay
import com.idupi.app.data.settings.SettingsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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

    /**
     * True while a launch from the system assistant keeps the bubble showing
     * even though the owner's bubble setting is off (assistant-key T1). It is
     * session state only: the persisted "Burbuja de IDUPI" setting is never
     * written here, and the gate falls back to it when the session ends.
     */
    private val assistSessionActive = MutableStateFlow(false)

    /**
     * Keyguard state for the bubble gate (assistant-key T5). Accessibility
     * overlays draw ABOVE the lock screen, so the bubble must stay hidden while
     * the keyguard is showing. The system broadcasts screen on/off and user
     * present; each event re-reads [KeyguardManager.isKeyguardLocked] instead
     * of inferring the state from the broadcast alone.
     */
    private val keyguardLocked = MutableStateFlow(false)

    private val keyguardManager by lazy {
        getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    }

    private var keyguardReceiverRegistered = false

    private val keyguardReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshKeyguardLocked()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        PhoneStatusReporting.start(this)
        CalendarReporting.start(this)
        HealthReporting.start(this)

        // assistant-key T5: read the current lock state before the first gate
        // emission and keep it fresh for the whole binding.
        refreshKeyguardLocked()
        registerKeyguardReceiver()

        // assistant-key T1: the exported ASSIST/VOICE_ASSIST activity reaches
        // the overlay through this process-wide host. The service being bound
        // IS the accessibility grant, so the request can open the panel even
        // when the owner's bubble setting is off (temporary session).
        AssistDictationHost.attach {
            // assistant-key T5: never attach an overlay above the keyguard.
            // The activity already asked for the unlock, so this is a
            // fail-closed guard for any other launch path, not the normal one.
            refreshKeyguardLocked()
            if (keyguardLocked.value) {
                false
            } else {
                assistSessionActive.value = true
                val opened = bubbleOverlay.openForAssistantDictation {
                    assistSessionActive.value = false
                }
                // A window that could not be drawn leaves no temporary session
                // behind; the activity falls back to the app-with-hint path.
                if (!opened) assistSessionActive.value = false
                opened
            }
        }

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
        // being bound IS the accessibility grant (phone-bubble T4). A launch
        // from the system assistant overrides those settings for its session
        // (assistant-key T1), but never the keyguard (assistant-key T5).
        // WindowManager.addView/removeView must run on the main thread, so this
        // collector overrides the service's IO dispatcher.
        bubbleJob?.cancel()
        bubbleJob = scope.launch(Dispatchers.Main.immediate) {
            combine(
                SettingsProvider.repository.phoneBubbleEnabled,
                SettingsProvider.repository.phoneControlEnabled,
                assistSessionActive,
                keyguardLocked,
            ) { bubble, control, assist, locked ->
                BubbleGate.shouldShow(
                    bubbleEnabled = bubble,
                    phoneControlEnabled = control,
                    accessibilityConnected = true,
                    temporarySessionActive = assist,
                    keyguardLocked = locked,
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
        // assistant-key T1: no launch may reach a dead service.
        AssistDictationHost.detach()
        unregisterKeyguardReceiver()
        assistSessionActive.value = false
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
        AssistDictationHost.detach()
        unregisterKeyguardReceiver()
        assistSessionActive.value = false
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

    /**
     * Re-reads the keyguard and feeds the bubble gate (assistant-key T5).
     *
     * Runs on the main thread from the service lifecycle and from the screen/
     * unlock receiver, so the overlay is already hidden when the lock screen
     * appears and comes back only on a real unlock. A lock also ends the
     * temporary assistant session: unlocking must never reopen a dictation
     * panel the owner did not ask for again. The log line carries the
     * transition only, never any content.
     */
    private fun refreshKeyguardLocked() {
        val locked = keyguardManager.isKeyguardLocked
        if (locked == keyguardLocked.value) return
        keyguardLocked.value = locked
        if (locked) assistSessionActive.value = false
        Log.i(TAG, if (locked) "keyguard locked" else "keyguard unlocked")
        // A side-key press made on the lock screen is honoured by the unlock
        // that follows it, once and only inside its window.
        if (!locked && AssistDictationHost.consumeDeferred(System.currentTimeMillis())) {
            Log.i(TAG, "opening the dictation deferred to this unlock")
            AssistDictationHost.requestDictation()
        }
    }

    /**
     * Dynamic receiver: the lock state is not in a sticky broadcast, so the
     * three transitions that can change it are observed while the service is
     * bound and unregistered on unbind/destroy.
     */
    private fun registerKeyguardReceiver() {
        if (keyguardReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(
            this,
            keyguardReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        keyguardReceiverRegistered = true
    }

    private fun unregisterKeyguardReceiver() {
        if (!keyguardReceiverRegistered) return
        keyguardReceiverRegistered = false
        runCatching { unregisterReceiver(keyguardReceiver) }
    }

    companion object {
        private const val TAG = "IdupiAccessibilityService"
    }
}
