package com.idupi.app.data.phone.bubble

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import com.idupi.app.MainActivity
import com.idupi.app.R
import com.idupi.app.data.IduPiClientProvider
import com.idupi.app.data.inbox.InboxNotifier
import com.idupi.app.data.phone.approval.ApprovalNotifier
import com.idupi.app.data.phone.approval.ApprovalVoiceFallback
import com.idupi.app.data.phone.approval.ApprovalVoiceHost
import com.idupi.app.data.phone.approval.ApprovalVoiceKind
import com.idupi.app.data.phone.approval.ApprovalVoiceRoute
import com.idupi.app.data.phone.approval.ApprovalVoiceRouting
import com.idupi.app.data.phone.approval.OwnerTurnRegistry
import com.idupi.app.data.phone.approval.PendingApprovalsStore
import com.idupi.app.data.phone.approval.PhoneApprovals
import com.idupi.app.data.settings.SettingsProvider
import com.idupi.app.data.settings.SettingsRepository
import com.idupi.app.domain.model.PhoneApproval
import com.idupi.app.domain.model.ThemeSettings
import com.idupi.app.ui.theme.ThemePalettes
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The floating IDUPI bubble (phone-bubble T4, glass pass T9, docking T10),
 * drawn by the accessibility service with `TYPE_ACCESSIBILITY_OVERLAY` (no
 * extra permission).
 *
 * Classic Views, not Compose: the overlay lives in a Service, outside any
 * Activity, so Compose would need a hand-built lifecycle owner, saved-state
 * registry and recomposer around a window that is added and removed at
 * runtime. The panel is a handful of widgets; plain Views keep the IME focus
 * behaviour and the window lifecycle obvious.
 *
 * The look follows the owner's theme (T9): `ThemePalettes.resolve` turns the
 * persisted `ThemeSettings` into `AppColors`, `toArgb()` flattens each Compose
 * `Color`, and [BubbleGlassStyle] derives the translucent glass surfaces. No
 * colour constants live here except the semantic error/warning pair.
 *
 * Collapsed it is a draggable glass circle with the translucent hedgehog mark
 * that breathes slowly; releasing snaps it to the nearest vertical edge (the
 * same `snapBubbleToEdge` rule as the fullscreen bubble) and, after a short
 * idle, slides it half off that edge (T10). Tapping expands a focusable glass
 * panel so the keyboard can reach the text field; minimizing collapses it and
 * cancels the long poll ([BubbleChatController.close]).
 *
 * Voice (T5) plugs into this shell, and since T11 the panel owns the
 * microphone: `SpeechRecognizer` runs inside the overlay, partial hypotheses
 * stream into the field, level bars follow `onRmsChanged` and the system sheet
 * remains only as the fallback when the phone cannot dictate in-process. Since
 * T22 a failing on-device recognizer is replaced by the standard one for the
 * rest of the overlay's life, and a standard recognizer that cannot serve the
 * request hands the mic to that sheet instead of leaving a dead-end error.
 * Since T21 a dictated final transcript is read back on screen and out loud and
 * may be sent after the owner's spoken confirmation ("como Android Auto con
 * Gemini"); T23 removed that request read-back: a dictated request now goes
 * straight to the agent, and the spoken loop serves the approval of an outbound
 * message instead. While a bubble turn started on this phone is active, a
 * messaging approval raised by the phone's own risk gate is asked out loud in
 * the panel and approved, rejected, read back or extended by voice. The
 * approval banner (T7) is rendered by the same shell; it never decides.
 */
class PhoneBubbleOverlay(
    private val service: AccessibilityService,
    private val controller: BubbleChatController = BubbleChatController(IduPiClientProvider),
    private val approvals: PendingApprovalsStore = PhoneApprovals.pendingStore,
    private val settings: SettingsRepository = SettingsProvider.repository,
) {

    private val windowManager =
        service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = service.resources.displayMetrics.density
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Posts the usual approval notification when the voice loop cannot. */
    private val approvalNotifier by lazy { ApprovalNotifier(service) }

    /** Reads and edits the app behind for the voice approval (T23). */
    private val screenReader by lazy { ApprovalScreenReader(service) }

    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var stateJob: Job? = null
    private var voiceJob: Job? = null
    private var permissionJob: Job? = null
    private var themeJob: Job? = null
    private var dockJob: Job? = null

    private var mode = BubbleMode.COLLAPSED

    /** Read from the approval poll thread when it offers an approval. */
    @Volatile
    private var visible = false

    /**
     * Ends a session-only show requested by the system assistant
     * (assistant-key T1); null when the panel was not opened by that path.
     */
    private var assistantSessionEnded: (() -> Unit)? = null

    /** Current window top-left while fully visible, in pixels. */
    private var position = 0 to 0

    /** Last circle position, restored when the panel collapses. */
    private var collapsedPosition = 0 to 0

    /**
     * Last expanded-panel spot, remembered while the service lives (T15). Null
     * until the first expansion, which centers the panel horizontally.
     */
    private var panelPosition: Pair<Int, Int>? = null

    /** Keyboard and window-focus state of the expanded panel (T16). */
    private var focusState = BubbleFocusState()

    /** Whether the window is currently tucked half off its edge (T10). */
    private var docked = false

    /** Milliseconds (uptime) of the last touch on the circle. */
    private var lastTouchAt = 0L

    /** A finger is moving the window right now. */
    private var dragging = false

    /** The current touch started on a docked circle, so it must not expand. */
    private var touchBeganDocked = false

    private var chatState = BubbleChatState()
    private var approvalUi = BubbleApprovalUi(
        waitingForApproval = false,
        badgeVisible = false,
        pendingCount = 0,
    )

    /** Glass colours for the owner's current theme; rebuilt on theme changes. */
    private var style: BubbleGlassStyle = glassStyleOf(ThemeSettings())

    private var pill: View? = null
    private var mark: ImageView? = null
    private var highlight: View? = null
    private var badge: TextView? = null
    private var panel: LinearLayout? = null
    private var messagesContainer: LinearLayout? = null
    private var messagesScroll: ScrollView? = null
    private var statusLine: TextView? = null
    private var approvalBanner: View? = null
    private var retryButton: Button? = null
    private var input: EditText? = null
    private var sendButton: Button? = null
    private var voiceButton: Button? = null
    private var voiceHint: TextView? = null
    private var voiceListeningRow: View? = null
    private var voiceLevels: VoiceLevelView? = null

    /** Read-back line of the spoken loop: the approval question (T21/T23). */
    private var confirmPrompt: TextView? = null

    /**
     * Aprobar / Rechazar under the read-back line (phone-dashboard T4). Shown
     * only while a bubble approval owns the loop.
     */
    private var confirmButtons: View? = null

    /** Text currently on the read-back line; null hides it. */
    private var confirmPromptText: String? = null

    /** In-bubble dictation state (T11); the reducer is the only writer. */
    private var voiceState: BubbleVoiceState = BubbleVoiceState.Idle

    /** Voice confirmation loop state (T21, approval loop since T23). */
    private var confirmState: BubbleConfirmState = BubbleConfirmState.Idle

    /**
     * The one approval the voice loop owns (T23). Only this id can be approved
     * or rejected by voice; cleared as soon as the loop leaves it.
     */
    private var voiceApproval: PhoneApproval? = null

    /** Which sentence the loop speaks: message (T23), cache or uninstall (T4). */
    private var voiceApprovalKind: ApprovalVoiceKind = ApprovalVoiceKind.MESSAGE

    /**
     * The routed approval was seen in a pending poll. From then on, a poll
     * without it means another surface decided it or it expired.
     */
    private var voiceApprovalSeenPending = false

    /** Spoken read-back engine; created on first use, shut down in [destroy]. */
    private var tts: TextToSpeech? = null

    /** The engine finished initializing and has a usable Spanish voice. */
    private var ttsReady = false

    /** Initialization or voice selection failed; the loop skips the speech. */
    private var ttsFailed = false

    /** Prompt waiting for the engine to initialize. */
    private var pendingSpeech: String? = null

    /** Live in-process recognizer; created on first use, destroyed on collapse. */
    private var speech: SpeechRecognizer? = null

    /** Whether [speech] is the on-device model; drives the T22 recovery table. */
    private var speechOnDevice = false

    /**
     * The on-device model failed once (T22). It is never created again for the
     * life of this overlay: on-device models usually lack the owner's Spanish
     * locale, and the standard recognizer is the one the phone accepts.
     */
    private var onDeviceRecognizerDisabled = false

    /** Recognition request of the live attempt; the T22 retry resends it. */
    private var speechIntent: Intent? = null

    /** The owner tapped Detener; a late error must not restart the microphone. */
    private var stopRequested = false

    /** Where the last request came from; the follow-up only applies to voice (T17). */
    private var requestOrigin = BubbleRequestOrigin.TYPED

    /** The field holds a transcript the owner has not edited since (T17). */
    private var dictatedDraft = false

    /** True while the overlay writes the field itself; the watcher ignores it. */
    private var applyingTranscript = false

    private var idleAnimator: ValueAnimator? = null
    private var dockAnimator: ValueAnimator? = null

    /**
     * True when this panel was opened for a Dashboard request while the
     * visibility gate had it hidden (phone-dashboard T2). Minimizing then
     * removes the window again instead of leaving a bubble the owner's setting
     * keeps off.
     */
    private var ownerRequestSessionOnly = false

    init {
        // The Dashboard's "Optimizar teléfono" sends through the bubble, so the
        // turn is registered as owner-initiated. The overlay only exists while
        // the accessibility service is bound, which is the grant this host
        // checks; when it is off, the host refuses and the screen explains.
        BubbleRequestHost.attach(::openForOwnerRequest)
    }

    /** Adds the window when the gate turns on, removes it when it turns off. */
    fun setVisible(show: Boolean) {
        if (show == visible) return
        visible = show
        if (show) attach() else detach()
    }

    /**
     * Opens the panel in dictation for a launch of the system assistant
     * (assistant-key T1).
     *
     * The accessibility service has already marked a temporary session, so the
     * window may be attached here even when the owner's bubble icon is off.
     * Expanding undocks first (T10) and nothing shows the keyboard: T16 keeps
     * the IME owner's choice. Dictation then runs through the same
     * [onMicTapped] path as the Dictar button, including the RECORD_AUDIO flow
     * and the T22 recognizer fallback.
     *
     * @param onSessionEnded invoked once when the owner collapses this panel;
     *   the service clears the temporary override and the visibility gate hides
     *   the bubble again unless the owner's setting keeps it.
     * @return true when the panel is up and listening.
     */
    fun openForAssistantDictation(onSessionEnded: () -> Unit): Boolean {
        assistantSessionEnded = onSessionEnded
        if (!visible) setVisible(true)
        if (!visible) {
            // No window means no session to end later; the service clears the
            // temporary gate and the activity falls back to the app hint.
            assistantSessionEnded = null
            return false
        }
        if (mode == BubbleMode.COLLAPSED) expand()
        onMicTapped()
        return true
    }

    /**
     * Opens the panel and sends a request started outside the bubble
     * (phone-dashboard T2: the Dashboard's "Optimizar teléfono" button).
     *
     * The request uses [BubbleChatController.send] — the same path as a typed
     * draft — so `OwnerTurnRegistry` registers the turn as owner-initiated,
     * which is what approvals rely on. A request started while the gate has the
     * bubble hidden brings up a session-only panel: collapsing removes it again
     * so the owner's bubble setting stays respected.
     *
     * @return true when the panel is up and the request was accepted.
     */
    fun openForOwnerRequest(text: String): Boolean {
        val request = text.trim()
        if (request.isEmpty()) return false
        if (!visible) {
            setVisible(true)
            if (!visible) return false
            ownerRequestSessionOnly = true
        }
        if (mode == BubbleMode.COLLAPSED) expand()
        // Same preparation as a typed send: a spoken loop from a previous
        // request must not keep the microphone open, and a late transcript may
        // not land in the new request.
        cancelConfirmLoop()
        stopTts()
        abandonVoice(render = true)
        input?.setText("")
        dictatedDraft = false
        requestOrigin = BubbleRequestOrigin.TYPED
        IduPiClientProvider.configureFromStorage(service)
        controller.send(request)
        return true
    }

    /** Re-clamps the window after a rotation or a display-size change. */
    fun onConfigurationChanged() {
        if (!visible) return
        // A dock is measured against the old screen; drop it before re-clamping
        // so the next layout is always fully valid on the new display.
        undock(animate = false)
        val screen = screenSize()
        val spec = currentSpec(screen)
        if (mode == BubbleMode.COLLAPSED) {
            position = BubbleOverlayPosition.clamped(
                position.first,
                position.second,
                spec.width,
                spec.height,
                screen.first,
                screen.second,
            )
            position = BubbleOverlayPosition.snapped(
                position.first,
                position.second,
                spec.width,
                spec.height,
                screen.first,
                screen.second,
            )
            collapsedPosition = position
        } else {
            // The panel keeps the owner's chosen spot, re-clamped so a smaller
            // display after a rotation does not leave it half off-screen.
            val panel = BubbleOverlayPosition.panel(
                rememberedX = panelPosition?.first,
                rememberedY = panelPosition?.second,
                width = spec.width,
                height = spec.height,
                screenWidth = screen.first,
                screenHeight = screen.second,
                fallbackY = position.second,
            )
            position = panel
            panelPosition = panel
        }
        cancelIdleAnimation()
        rebuildContent()
        applyWindow(spec)
    }

    fun destroy() {
        BubbleRequestHost.detach()
        visible = false
        detach()
        shutdownTts()
        scope.cancel()
    }

    // --- window lifecycle -------------------------------------------------

    private fun attach() {
        if (root != null) return
        val screen = screenSize()
        val spec = BubbleWindowSpecs.forMode(
            BubbleMode.COLLAPSED,
            BASE_FLAGS,
            screen.first,
            screen.second,
            density,
        )
        position = BubbleOverlayPosition.snapped(
            position.first,
            position.second,
            spec.width,
            spec.height,
            screen.first,
            screen.second,
        )
        collapsedPosition = position
        val view = BubbleRootLayout(service).apply {
            onBack = { handleFocusEvent(BubbleFocusEvent.BACK_PRESSED) }
            onOutsideTouch = { handleFocusEvent(BubbleFocusEvent.OUTSIDE_TOUCH) }
        }
        val layoutParams = WindowManager.LayoutParams(
            spec.width,
            spec.height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            spec.flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = position.first
            y = position.second
            // The panel is focusable when expanded; resizing keeps the input
            // row above the keyboard instead of under it.
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
        }
        root = view
        params = layoutParams
        mode = BubbleMode.COLLAPSED
        try {
            windowManager.addView(view, layoutParams)
        } catch (error: Exception) {
            Log.w(TAG, "could not add the bubble window: ${error.javaClass.simpleName}")
            root = null
            params = null
            visible = false
            return
        }
        rebuildContent()
        startCollecting()
        // T23: the approval poll can now hand a messaging approval to the
        // bubble instead of posting a notification.
        ApprovalVoiceHost.attach(::offerVoiceApproval)
    }

    private fun detach() {
        // No new approval may be routed here while the window is going away;
        // an approval already in the loop falls back to the notification below.
        ApprovalVoiceHost.detach()
        // A session-only show dies with its window; the service clears the
        // temporary gate separately (assistant-key T1).
        assistantSessionEnded = null
        ownerRequestSessionOnly = false
        stateJob?.cancel()
        stateJob = null
        voiceJob?.cancel()
        voiceJob = null
        permissionJob?.cancel()
        permissionJob = null
        themeJob?.cancel()
        themeJob = null
        dockJob?.cancel()
        dockJob = null
        cancelIdleAnimation()
        dockAnimator?.cancel()
        dockAnimator = null
        // T21/T23: the spoken loop and its speech die with the window; the
        // request text stays where the owner left it, and a routed approval
        // falls back to its notification.
        cancelConfirmLoop()
        stopTts()
        abandonVoice(render = false)
        controller.close()
        focusState = BubbleFocusState()
        val view = root ?: return
        root = null
        params = null
        docked = false
        dragging = false
        pill = null
        mark = null
        highlight = null
        badge = null
        panel = null
        messagesContainer = null
        messagesScroll = null
        statusLine = null
        approvalBanner = null
        retryButton = null
        input = null
        sendButton = null
        voiceButton = null
        voiceHint = null
        voiceListeningRow = null
        voiceLevels = null
        confirmPrompt = null
        confirmButtons = null
        try {
            windowManager.removeView(view)
        } catch (error: Exception) {
            Log.w(TAG, "could not remove the bubble window: ${error.javaClass.simpleName}")
        }
    }

    private fun startCollecting() {
        stateJob?.cancel()
        stateJob = scope.launch {
            combine(controller.state, approvals.pending) { state, pending ->
                state to pending
            }.collect { (state, pending) ->
                val previousPhase = chatState.phase
                chatState = state
                approvalUi = BubbleApprovalState.of(state.phase, pending.size)
                render()
                maybeReopenForVoice(previousPhase, state)
                // T23: an approval that leaves the pending poll was decided by
                // another surface or expired; the voice loop stops without a
                // second notification.
                onPendingApprovals(pending)
            }
        }
        voiceJob?.cancel()
        voiceJob = scope.launch {
            VoiceInputChannel.events.collect { event ->
                when (event) {
                    is VoiceInputEvent.Transcript -> acceptTranscript(event.text)
                    VoiceInputEvent.Failed -> showVoiceFailure()
                }
            }
        }
        permissionJob?.cancel()
        permissionJob = scope.launch {
            MicPermissionChannel.results.collect { granted ->
                dispatchVoice(
                    if (granted) BubbleVoiceEvent.PermissionGranted
                    else BubbleVoiceEvent.PermissionDenied,
                )
            }
        }
        themeJob?.cancel()
        themeJob = scope.launch {
            settings.themeSettings.collect { theme ->
                val next = glassStyleOf(theme)
                if (next == style) return@collect
                style = next
                if (!visible) return@collect
                // A palette change must repaint the live window, not only the
                // next one: the owner can change the theme with the bubble up.
                cancelIdleAnimation()
                rebuildContent()
            }
        }
    }

    private fun rebuildContent() {
        when (mode) {
            BubbleMode.COLLAPSED -> buildPill()
            BubbleMode.EXPANDED -> buildPanel()
        }
        render()
    }

    // --- collapsed circle ------------------------------------------------

    private fun buildPill() {
        val container = root ?: return
        container.removeAllViews()
        val size = BubbleWindowSpecs.forMode(
            BubbleMode.COLLAPSED,
            BASE_FLAGS,
            screenSize().first,
            screenSize().second,
            density,
        ).width

        // The circle is its own view so the idle animation scales the glass and
        // the mark together while the approval badge stays crisp outside it.
        val circle = FrameLayout(service).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(style.circleFill)
                setStroke(dp(1), style.circleRim)
            }
            contentDescription = "Abrir la burbuja de IDUPI"
            isClickable = false
            layoutParams = FrameLayout.LayoutParams(size, size)
            installDrag(this)
        }
        // A soft top sheen: a vertical white gradient clipped to the circle, so
        // the fill never reads as one flat solid colour.
        val sheen = View(service).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(style.circleHighlight, 0x00000000),
            ).apply { shape = GradientDrawable.OVAL }
            isClickable = false
            layoutParams = FrameLayout.LayoutParams(size, size)
        }
        val markView = ImageView(service).apply {
            setImageResource(R.drawable.idupi_mark)
            imageTintList = ColorStateList.valueOf(style.markTint)
            scaleType = ImageView.ScaleType.FIT_CENTER
            isClickable = false
            val inset = dp(13)
            layoutParams = FrameLayout.LayoutParams(
                (size - inset * 2).coerceAtLeast(1),
                (size - inset * 2).coerceAtLeast(1),
                Gravity.CENTER,
            )
        }
        circle.addView(sheen)
        circle.addView(markView)

        val badgeView = TextView(service).apply {
            text = "!"
            setTextColor(COLOR_ON_ERROR)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(style.badge)
            }
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(dp(20), dp(20)).apply {
                gravity = badgeGravity()
            }
        }
        container.addView(circle)
        container.addView(badgeView)
        pill = circle
        mark = markView
        highlight = sheen
        badge = badgeView
        panel = null
        messagesContainer = null
        messagesScroll = null
        statusLine = null
        approvalBanner = null
        retryButton = null
        input = null
        sendButton = null
        voiceButton = null
        voiceHint = null
        voiceListeningRow = null
        voiceLevels = null
        confirmPrompt = null
        confirmButtons = null
        startIdleAnimation(circle, sheen)
    }

    /**
     * Slow breathing scale plus a sheen that follows it (T9). Cheap: one
     * `ValueAnimator` on the view, cancelled on detach and on every rebuild.
     */
    private fun startIdleAnimation(circle: View, sheen: View) {
        cancelIdleAnimation()
        idleAnimator = ValueAnimator.ofFloat(IDLE_SCALE_MIN, 1f).apply {
            duration = IDLE_BREATH_MILLIS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val scale = animator.animatedValue as Float
                circle.scaleX = scale
                circle.scaleY = scale
                sheen.alpha = (scale - IDLE_SCALE_MIN) / (1f - IDLE_SCALE_MIN) * 0.45f + 0.55f
            }
            start()
        }
    }

    private fun cancelIdleAnimation() {
        idleAnimator?.cancel()
        idleAnimator = null
    }

    /** The badge sits on the visible side, so a docked circle still shows it. */
    private fun badgeGravity(): Int {
        if (!docked) return Gravity.TOP or Gravity.END
        val size = pillSize()
        val left = BubbleOverlayPosition.snapsLeft(position.first, size, screenSize().first)
        return Gravity.TOP or (if (left) Gravity.END else Gravity.START)
    }

    // --- expanded panel ---------------------------------------------------

    private fun buildPanel() {
        val container = root ?: return
        container.removeAllViews()

        val card = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = glassSurface(
                fill = style.panelFill,
                radiusDp = PANEL_CORNER_DP,
                strokeColor = style.panelRim,
            )
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        card.addView(buildHeader())
        card.addView(buildApprovalBanner())

        val scroll = ScrollView(service).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        val messages = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        scroll.addView(messages)
        card.addView(scroll)

        card.addView(buildStatusLine())
        card.addView(buildConfirmPrompt())
        card.addView(buildConfirmButtons())
        card.addView(buildRetryButton())
        card.addView(buildInputRow())

        container.addView(card)
        panel = card
        messagesContainer = messages
        messagesScroll = scroll
        pill = null
        mark = null
        highlight = null
        badge = null
    }

    private fun buildHeader(): View {
        val row = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val markView = ImageView(service).apply {
            setImageResource(R.drawable.idupi_mark)
            imageTintList = ColorStateList.valueOf(style.markTint)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                rightMargin = dp(8)
            }
        }
        val title = TextView(service).apply {
            text = "IDUPI"
            setTextColor(style.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(markView)
        row.addView(title)
        row.addView(glassButton("Minimizar") { collapse() })
        installPanelDrag(row)
        return row
    }

    private fun buildApprovalBanner(): View {
        val row = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            // Semantic, never themed: the owner must recognise "needs you".
            background = glassSurface(
                fill = BubbleGlassStyle.withAlpha(style.warning, WARNING_FILL_ALPHA),
                radiusDp = 12,
                strokeColor = style.warning,
            )
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        }
        val label = TextView(service).apply {
            text = "Esperando tu aprobación"
            setTextColor(style.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val open = filledButton("Ver en Alertas") { openAlerts() }
        row.addView(label)
        row.addView(open)
        return row.also { approvalBanner = it }
    }

    /**
     * Sends the owner to Alertas, where the approvals card owns the decision.
     * The bubble never approves or rejects: that is the whole point of the
     * queue living on the phone.
     */
    private fun openAlerts() {
        val intent = Intent(service, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra(InboxNotifier.EXTRA_OPEN_ALERTS, true)
        }
        try {
            service.startActivity(intent)
        } catch (error: Exception) {
            Log.w(TAG, "could not open Alertas: ${error.javaClass.simpleName}")
        }
    }

    private fun buildStatusLine(): View =
        TextView(service).apply {
            setTextColor(style.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(4), 0, dp(2))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }.also { statusLine = it }

    /**
     * The read-back line of the spoken loop (T21/T23): the same sentence
     * TextToSpeech speaks, so the owner both hears and reads what the bubble is
     * asking before she answers.
     */
    private fun buildConfirmPrompt(): View =
        TextView(service).apply {
            setTextColor(style.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(2))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }.also {
            confirmPrompt = it
            renderConfirmPrompt()
        }

    /**
     * Aprobar / Rechazar under the prompt (T4): owners who type instead of
     * speak decide through the same path as a voice answer. Hidden unless a
     * bubble approval owns the loop.
     */
    private fun buildConfirmButtons(): View {
        val row = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            visibility = View.GONE
            setPadding(0, dp(2), 0, dp(2))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        row.addView(filledButton("Aprobar") { onApprovalButton(approve = true) }
            .apply { layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { rightMargin = dp(8) } })
        row.addView(glassButton("Rechazar") { onApprovalButton(approve = false) })
        return row.also { confirmButtons = it }
    }

    private fun buildRetryButton(): View =
        filledButton("Reintentar") { controller.retry() }.apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) }
        }.also { retryButton = it }

    private fun buildInputRow(): View {
        val column = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val field = EditText(service).apply {
            hint = "¿Qué necesitas?"
            setHintTextColor(style.textSecondary)
            setTextColor(style.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            maxLines = 3
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = glassSurface(
                fill = style.inputFill,
                radiusDp = 12,
                strokeColor = style.glassOutline,
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendDraft()
                    true
                } else {
                    false
                }
            }
            // A tap is the owner asking for the keyboard again after an outside
            // touch released the window focus (T16). Returning false lets the
            // EditText process the same gesture normally.
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    handleFocusEvent(BubbleFocusEvent.FIELD_TAPPED)
                }
                false
            }
            // T17: dictation tracks its own drafts. The overlay's writes are
            // flagged, so only a real edit by the owner turns the draft typed.
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) = Unit

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) = Unit

                override fun afterTextChanged(s: Editable?) {
                    if (applyingTranscript) return
                    dictatedDraft = false
                    // T21/T23: typing while the spoken loop runs is the owner
                    // taking over; the loop stops and a pending approval falls
                    // back to its notification, never to a silent dead end.
                    if (confirmState != BubbleConfirmState.Idle) {
                        dispatchConfirm(BubbleConfirmEvent.UserTyped)
                    }
                }
            })
        }
        val send = filledButton("Enviar") { sendDraft() }
        // T11: the primary path is in-bubble dictation; the recognizer sheet is
        // reached only through the reducer's fallback.
        val dictate = glassButton("Dictar") { onMicTapped() }
        val hideKeyboard = glassButton(HIDE_KEYBOARD_GLYPH) {
            handleFocusEvent(BubbleFocusEvent.HIDE_KEYBOARD)
        }.apply {
            contentDescription = BubbleFocusReducer.HIDE_KEYBOARD_DESCRIPTION
        }
        val buttons = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
            addView(send)
            addView(
                dictate,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { leftMargin = dp(8) },
            )
            addView(
                hideKeyboard,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { leftMargin = dp(8) },
            )
        }
        val hint = TextView(service).apply {
            setTextColor(style.error)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(6), 0, 0)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        column.addView(field)
        column.addView(buildVoiceListeningRow())
        column.addView(buttons)
        column.addView(hint)
        input = field
        sendButton = send
        voiceButton = dictate
        voiceHint = hint
        return column
    }

    /**
     * The listening feedback that lives inside the panel (T11): the dancing
     * level bars and "Escuchando…". The partial transcript goes into the field
     * itself, not here, so the owner can already edit it while speaking.
     */
    private fun buildVoiceListeningRow(): View {
        val row = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) }
        }
        val bars = VoiceLevelView(service, barColor = style.primary).apply {
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(18))
        }
        val label = TextView(service).apply {
            text = BubbleVoiceReducer.MESSAGE_LISTENING
            setTextColor(style.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(8), 0, 0, 0)
        }
        row.addView(bars)
        row.addView(label)
        voiceLevels = bars
        voiceListeningRow = row
        return row
    }

    // --- dictation (T5, in-bubble since T11) ------------------------------

    /**
     * The mic toggle. The reducer decides everything: ask for RECORD_AUDIO,
     * start or finish the recognizer, retry after an error, or fall back to the
     * system sheet when the phone cannot dictate in-process.
     *
     * T21/T23: while the spoken loop runs the microphone belongs to it, so a
     * mic tap stops the loop (and a pending approval falls back to its
     * notification); a second tap starts a fresh dictation.
     */
    private fun onMicTapped() {
        if (confirmState != BubbleConfirmState.Idle) {
            cancelConfirmLoop()
            return
        }
        dispatchVoice(
            BubbleVoiceEvent.MicTapped(
                permissionGranted = MicPermission.isGranted(service),
                recognizerAvailable = inBubbleRecognitionAvailable(),
            ),
        )
    }

    private fun inBubbleRecognitionAvailable(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(service) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(service))

    private fun dispatchVoice(event: BubbleVoiceEvent) {
        val decision = BubbleVoiceReducer.reduce(voiceState, event)
        voiceState = decision.state
        decision.actions.forEach(::applyVoiceAction)
        renderVoice()
    }

    private fun applyVoiceAction(action: BubbleVoiceAction) {
        when (action) {
            BubbleVoiceAction.RequestMicrophonePermission -> startMicPermission()
            BubbleVoiceAction.StartRecognizer -> startSpeech()
            BubbleVoiceAction.StopRecognizer -> stopSpeech()
            BubbleVoiceAction.LaunchSystemDictation -> launchDictation()
            is BubbleVoiceAction.PutText -> putTranscript(action.text)
        }
    }

    /**
     * The overlay cannot request a runtime permission, so a transparent
     * activity asks once and publishes its answer on [MicPermissionChannel].
     */
    private fun startMicPermission() {
        val intent = Intent(service, MicPermissionActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_HISTORY or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
        }
        try {
            service.startActivity(intent)
        } catch (error: Exception) {
            Log.w(TAG, "could not ask for the microphone: ${error.javaClass.simpleName}")
            // No dialog means no permission: the system sheet still works.
            dispatchVoice(BubbleVoiceEvent.PermissionDenied)
        }
    }

    /**
     * Starts the in-bubble recognizer for one listening attempt.
     *
     * [intent] defaults to the T5/T11 request; the T22 retry passes the intent
     * of the failed attempt so the owner keeps the same language and extras.
     *
     * @return true when a recognizer is listening when this returns, including
     *   a successful T22 retry with the standard recognizer.
     */
    private fun startSpeech(intent: Intent = recognitionIntent()): Boolean {
        speechIntent = intent
        return try {
            stopRequested = false
            ensureSpeechRecognizer().startListening(intent)
            true
        } catch (error: Exception) {
            Log.w(
                TAG,
                "could not start in-bubble dictation (onDevice=$speechOnDevice): " +
                    error.javaClass.simpleName,
            )
            onStartSpeechFailure()
        }
    }

    /**
     * Applies the start-failure half of the T22 table: an on-device start that
     * throws retries the standard recognizer; a standard one that throws leaves
     * the system sheet as the only dictation left on this phone.
     *
     * @return true when the retry is listening.
     */
    private fun onStartSpeechFailure(): Boolean =
        when (BubbleRecognizerRecovery.forStartFailure(speechOnDevice)) {
            RecognizerRecovery.RETRY_STANDARD -> retryWithStandardRecognizer()
            RecognizerRecovery.SYSTEM_SHEET -> {
                fallBackToSystemSheet()
                false
            }
            RecognizerRecovery.REPORT -> {
                showVoiceFailure()
                false
            }
        }

    /**
     * T22: the on-device model cannot serve this phone. Destroy it, keep it
     * disabled for the rest of the overlay's life and listen again with the
     * standard recognizer without touching the state: the owner keeps the same
     * "Escuchando…" UI, sees no error and the spoken loop keeps waiting for its
     * answer. A failure of this standard attempt is evaluated as a standard
     * one, so the retry can never repeat.
     *
     * @return true when the standard recognizer started listening.
     */
    private fun retryWithStandardRecognizer(): Boolean {
        onDeviceRecognizerDisabled = true
        destroySpeechRecognizer()
        return startSpeech(speechIntent ?: recognitionIntent())
    }

    /**
     * T22: in-process dictation cannot answer here, so the existing system
     * sheet takes over (the path that has worked since 1.55). Inside the spoken
     * approval loop the sheet never opens: the loop stops like on any other
     * failure and the approval falls back to its notification.
     */
    private fun fallBackToSystemSheet() {
        destroySpeechRecognizer()
        if (confirmState != BubbleConfirmState.Idle) {
            stopLoopOnFailure()
            return
        }
        voiceState = BubbleVoiceState.Idle
        renderVoice()
        launchDictation()
    }

    /** Ends the spoken loop on a failure; a pending approval falls back. */
    private fun stopLoopOnFailure() {
        voiceState = BubbleVoiceState.Idle
        renderVoice()
        dispatchConfirm(BubbleConfirmEvent.NoSpeech)
    }

    /**
     * One recognizer for the life of the listening attempts; T11 prefers the
     * on-device model on API 31+ because it answers without a round trip.
     *
     * T22 drops that preference for the rest of the overlay's life after the
     * first on-device failure ([onDeviceRecognizerDisabled]): such a failure
     * usually means the on-device model has no model for the owner's Spanish
     * locale, so a retried attempt uses the standard recognizer the phone is
     * known to accept. Until then this is the T11 behaviour unchanged.
     */
    private fun ensureSpeechRecognizer(): SpeechRecognizer {
        speech?.let { return it }
        val onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !onDeviceRecognizerDisabled &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(service)
        val created = if (onDevice) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(service)
        } else {
            SpeechRecognizer.createSpeechRecognizer(service)
        }
        created.setRecognitionListener(recognitionListener)
        speech = created
        speechOnDevice = onDevice
        return created
    }

    /** es-CL on a Spanish phone, the device tag otherwise (T5 rule). */
    private fun recognitionIntent(): Intent {
        val language = VoiceInputContract.resolveLanguage(Locale.getDefault().toLanguageTag())
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
    }

    /**
     * Asks the recognizer to finish instead of dropping the attempt: the final
     * transcript still arrives in `onResults`, which is what the owner expects
     * from tapping the mic a second time.
     */
    private fun stopSpeech() {
        // A failure after Detener must not restart the microphone (T22).
        stopRequested = true
        try {
            speech?.stopListening()
        } catch (error: Exception) {
            Log.w(TAG, "could not stop dictation: ${error.javaClass.simpleName}")
        }
    }

    /** Cancels and destroys the live recognizer, if any (T11, retry in T22). */
    private fun destroySpeechRecognizer() {
        val recognizer = speech ?: return
        speech = null
        speechOnDevice = false
        try {
            recognizer.cancel()
            recognizer.destroy()
        } catch (error: Exception) {
            Log.w(TAG, "could not destroy dictation: ${error.javaClass.simpleName}")
        }
    }

    /**
     * Cancels and destroys the recognizer (T11: on collapse, detach and send).
     * The next mic tap creates a fresh one.
     */
    private fun abandonVoice(render: Boolean) {
        destroySpeechRecognizer()
        voiceState = BubbleVoiceState.Idle
        stopRequested = false
        if (render) renderVoice()
    }

    /**
     * Fallback only: the existing transparent activity opens the system
     * recognizer sheet when RECORD_AUDIO is denied or no recognizer exists.
     */
    private fun launchDictation() {
        val intent = Intent(service, VoiceInputActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_HISTORY or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
        }
        try {
            service.startActivity(intent)
        } catch (error: Exception) {
            Log.w(TAG, "could not open dictation: ${error.javaClass.simpleName}")
            showVoiceFailure()
        }
    }

    /**
     * A system-sheet transcript lands in the field for editing and keeps the
     * T5 rule: the sheet path never enters the voice confirmation loop.
     */
    private fun acceptTranscript(text: String) {
        voiceState = BubbleVoiceState.Idle
        putTranscript(text)
        renderVoice()
    }

    /**
     * The only writer of the field for dictation: partial hypotheses stream in
     * and the final transcript stays for editing. Since T23 the overlay sends
     * a final transcript straight to the agent through the send button's path
     * (no read-back); partials keep the T11 rule and never send. This never
     * asks for field focus: starting to listen must not force the IME up (T16).
     */
    private fun putTranscript(text: String) {
        val field = input ?: return
        applyingTranscript = true
        field.setText(text)
        field.setSelection(text.length)
        applyingTranscript = false
        // T17: this draft is a transcript until the owner edits it.
        dictatedDraft = true
        voiceHint?.visibility = View.GONE
    }

    private fun showVoiceFailure() {
        voiceState = BubbleVoiceState.Error(VoiceInputContract.FAILURE_MESSAGE)
        renderVoice()
    }

    /** Paints the mic toggle, the level bars and the short error copy. */
    private fun renderVoice() {
        val listening = voiceState as? BubbleVoiceState.Listening
        voiceListeningRow?.visibility = if (listening != null) View.VISIBLE else View.GONE
        listening?.let { voiceLevels?.setLevel(it.level) }
        voiceLevels?.setActive(listening != null)

        voiceButton?.text = if (listening != null) "Detener" else "Dictar"
        voiceButton?.contentDescription =
            if (listening != null) "Detener el dictado" else "Dictar tu pedido"
        val waitingForPermission = voiceState == BubbleVoiceState.RequestingPermission
        voiceButton?.isEnabled = !waitingForPermission
        voiceButton?.alpha = if (waitingForPermission) 0.5f else 1f

        when (val state = voiceState) {
            is BubbleVoiceState.Error -> {
                voiceHint?.text = state.message
                voiceHint?.visibility = View.VISIBLE
            }
            else -> voiceHint?.visibility = View.GONE
        }
    }

    /**
     * Thin bridge: every recognizer callback becomes one reducer event.
     *
     * `onError` first applies the T22 recovery table: an on-device failure
     * retries the standard recognizer without leaving the Listening UI (the
     * spoken loop keeps waiting), while a standard failure either keeps T11's
     * copy or opens the system sheet when in-process dictation is unusable.
     */
    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) {
            dispatchVoice(BubbleVoiceEvent.Level(rmsdB))
        }
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onError(error: Int) {
            val onDevice = speechOnDevice
            val recovery = BubbleRecognizerRecovery.forError(
                onDevice = onDevice,
                errorCode = error,
            )
            // No transcript here on purpose: ids and codes only (T22).
            Log.w(
                TAG,
                "recognizer error code=$error onDevice=$onDevice recovery=$recovery",
            )
            // A callback from a recognizer already destroyed (collapse, send,
            // detach, or the on-device instance the T22 retry dropped) owns
            // nothing and must never open the sheet by itself.
            if (speech == null) return
            if (recovery == RecognizerRecovery.RETRY_STANDARD && !stopRequested) {
                // T22: same Listening UI, standard recognizer; inside the
                // spoken loop it simply keeps waiting for its answer.
                retryWithStandardRecognizer()
                return
            }
            if (confirmState != BubbleConfirmState.Idle) {
                // Inside the spoken approval loop any final failure ends the
                // conversation and falls back to the notification. A
                // SYSTEM_SHEET verdict never opens the sheet from here.
                stopLoopOnFailure()
                return
            }
            if (recovery == RecognizerRecovery.SYSTEM_SHEET && !stopRequested) {
                fallBackToSystemSheet()
                return
            }
            dispatchVoice(recognitionErrorEvent(error))
        }
        override fun onResults(results: Bundle?) {
            val text = firstResult(results)
            if (confirmState != BubbleConfirmState.Idle) {
                // The approval loop is listening: this transcript is the
                // answer, not a new request, so it never reaches the field.
                voiceState = BubbleVoiceState.Idle
                renderVoice()
                val approval = voiceApproval
                when {
                    approval != null &&
                        ApprovalVoiceRouting.isExpired(approval, System.currentTimeMillis()) ->
                        dispatchConfirm(BubbleConfirmEvent.Expired)
                    text != null -> dispatchConfirm(BubbleConfirmEvent.Answer(text))
                    else -> dispatchConfirm(BubbleConfirmEvent.NoSpeech)
                }
                return
            }
            if (text != null) {
                // T11 places the transcript in the field; T23 sends it straight
                // to the agent through the send button's path (no read-back).
                dispatchVoice(BubbleVoiceEvent.Final(text))
                dispatchConfirm(BubbleConfirmEvent.FinalTranscript(text))
            } else {
                dispatchVoice(BubbleVoiceEvent.NoSpeech)
            }
        }
        override fun onPartialResults(partialResults: Bundle?) {
            // While the loop listens a partial is the answer in progress; it
            // must never overwrite the request the bubble is confirming.
            if (confirmState != BubbleConfirmState.Idle) return
            val text = firstResult(partialResults) ?: return
            dispatchVoice(BubbleVoiceEvent.Partial(text))
        }
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun recognitionErrorEvent(error: Int): BubbleVoiceEvent = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        -> BubbleVoiceEvent.NoSpeech
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        -> BubbleVoiceEvent.NetworkError
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> BubbleVoiceEvent.Busy
        else -> BubbleVoiceEvent.OtherError
    }

    private fun firstResult(results: Bundle?): String? =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    // --- voice confirmation loop (T21, approval loop since T23) -----------

    /**
     * Runs the pure loop reducer and applies its actions on the main thread.
     *
     * The loop only exists for a voice approval now (T23): the request path
     * sends a dictated final transcript straight to the agent. The recognizer
     * is shared with T11: while the loop is active it "owns" the microphone,
     * and leaving the loop drops the recognizer so a late transcript can never
     * approve, reject or overwrite anything.
     */
    private fun dispatchConfirm(event: BubbleConfirmEvent) {
        val wasActive = confirmState != BubbleConfirmState.Idle
        val decision = if (voiceApproval != null) {
            BubbleVoiceApprovalReducer.reduce(confirmState, event, voiceApprovalKind)
        } else {
            BubbleVoiceConfirmReducer.reduce(confirmState, event)
        }
        confirmState = decision.state
        if (wasActive && confirmState == BubbleConfirmState.Idle) {
            abandonVoice(render = true)
        }
        decision.actions.forEach(::applyConfirmAction)
        renderConfirmPrompt()
    }

    private fun applyConfirmAction(action: BubbleConfirmAction) {
        when (action) {
            is BubbleConfirmAction.ShowPrompt -> {
                confirmPromptText = action.text
                renderConfirmPrompt()
            }
            is BubbleConfirmAction.Speak -> speakPrompt(action.text)
            BubbleConfirmAction.StartRecognizer -> startConfirmListening()
            BubbleConfirmAction.Send -> sendWhenIdle()
            BubbleConfirmAction.HidePrompt -> {
                confirmPromptText = null
                renderConfirmPrompt()
            }
            BubbleConfirmAction.StopTts -> stopTts()
            BubbleConfirmAction.Approve -> decideVoiceApproval(approve = true)
            BubbleConfirmAction.Reject -> decideVoiceApproval(approve = false)
            BubbleConfirmAction.ReadScreen -> readVoiceApprovalScreen()
            is BubbleConfirmAction.AppendToCompose -> appendVoiceApprovalText(action.text)
            BubbleConfirmAction.PostFallbackNotification -> stopVoiceApprovalFallback()
        }
    }

    /** Stops the loop from outside an event (collapse, mic tap, teardown). */
    private fun cancelConfirmLoop() {
        if (confirmState == BubbleConfirmState.Idle) return
        dispatchConfirm(BubbleConfirmEvent.Cancelled)
    }

    /**
     * The dictated send path. While another turn is still running the
     * controller would ignore the request, so the text stays in the field
     * instead of being cleared.
     */
    private fun sendWhenIdle() {
        if (chatState.isBusy) {
            confirmPromptText = null
            renderConfirmPrompt()
            return
        }
        sendDraft()
    }

    private fun renderConfirmPrompt() {
        val line = confirmPrompt ?: return
        val text = confirmPromptText
        line.text = text.orEmpty()
        line.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        confirmButtons?.visibility =
            if (!text.isNullOrEmpty() && voiceApproval != null) View.VISIBLE else View.GONE
    }

    // --- voice approval of the outbound message (T23) ---------------------

    /**
     * Called by the approval poll (IO thread). The routing rule already said
     * this approval may be voiced; the window work is posted to the main thread
     * and the overlay re-validates before it claims the id. Returning true means
     * the bubble owns the approval and will either voice it or post the
     * fallback notification itself.
     */
    private fun offerVoiceApproval(approval: PhoneApproval): Boolean {
        if (!visible) return false
        scope.launch(Dispatchers.Main) { startVoiceApproval(approval) }
        return true
    }

    /**
     * Starts the spoken approval: expand (undocked, no keyboard), keep the app
     * behind as the accessibility target, speak the question and listen.
     *
     * Bails out to the normal notification when the bubble cannot take it now
     * (another loop or a dictation owns the mic, the routing rule changed) so a
     * claimed approval is never dropped.
     */
    private fun startVoiceApproval(approval: PhoneApproval) {
        val turnId = approval.ownerTurnId
        val ownerTurnActive = OwnerTurnRegistry.hasActiveTurn() &&
            (turnId.isNullOrBlank() || OwnerTurnRegistry.isActive(turnId))
        val route = ApprovalVoiceRouting.decide(
            approval = approval,
            ownerTurnActive = ownerTurnActive,
            bubbleAttached = visible && root != null,
            nowMillis = System.currentTimeMillis(),
        )
        if (route != ApprovalVoiceRoute.Voice ||
            voiceApproval != null ||
            confirmState != BubbleConfirmState.Idle ||
            voiceState is BubbleVoiceState.Listening ||
            voiceState == BubbleVoiceState.RequestingPermission
        ) {
            Log.i(TAG, "voice approval skipped: ${approval.id}")
            postApprovalNotification(approval, stillPending = true)
            return
        }
        val kind = ApprovalVoiceRouting.voiceKind(approval)
        if (kind == null) {
            Log.i(TAG, "voice approval skipped: ${approval.id}")
            postApprovalNotification(approval, stillPending = true)
            return
        }
        voiceApproval = approval
        voiceApprovalKind = kind
        // The same poll already replaced the pending list before offering new
        // rows, so this is the freshest local evidence that the approval is
        // still pending; a later poll without it means it was decided or
        // expired elsewhere.
        voiceApprovalSeenPending = approvals.pending.value.any { it.id == approval.id }
        Log.i(TAG, "voice approval started: ${approval.id}")
        if (mode == BubbleMode.COLLAPSED) expand()
        // The app behind must keep the input focus and stay the accessibility
        // active window; the panel is only a mirror of the spoken question.
        releaseFocusForVoiceApproval()
        // T23 names the messaging app from the command/window; T4/T6 name the
        // Settings app or the selected screen item from the title the owner is
        // looking at (one read for both the app and the generic subject).
        val screenTitle = if (kind == ApprovalVoiceKind.MESSAGE) null else resolveSettingsTitle()
        val appLabel = if (kind == ApprovalVoiceKind.MESSAGE) {
            resolveAppLabel(approval)
        } else {
            screenTitle
        }
        val subject = if (kind == ApprovalVoiceKind.DESTRUCTIVE) screenTitle else null
        val action = if (kind == ApprovalVoiceKind.DESTRUCTIVE) {
            ApprovalVoiceRouting.destructiveAction(approval)
        } else {
            null
        }
        dispatchConfirm(
            BubbleConfirmEvent.StartApproval(
                ApprovalVoiceCopy.promptFor(kind, appLabel, subject = subject, action = action),
            ),
        )
    }

    /** Settings screen title read for a cache/uninstall prompt, or null. */
    private fun resolveSettingsTitle(): String? = runCatching {
        screenReader.read().recipient?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun releaseFocusForVoiceApproval() {
        hideKeyboard()
        input?.clearFocus()
        if (mode != BubbleMode.EXPANDED || !focusState.windowFocusable) return
        focusState = focusState.copy(imeVisible = false, windowFocusable = false)
        setWindowFocusable(false)
    }

    /**
     * Friendly label of the app that will send, or null so the copy says
     * "esta app". The command's own resource id is the most reliable source
     * (`com.whatsapp:id/send` -> WhatsApp); the live active window comes next.
     * The approval's stored `foregroundApp` is last: on the owner's phone it
     * was a stale "Inicio de One UI" while WhatsApp was on screen.
     */
    private fun resolveAppLabel(approval: PhoneApproval): String? {
        val fromCommand = (approval.command.args["id"] as? JsonPrimitive)?.contentOrNull
            ?.let(ApprovalVoiceCopy::packageFromResourceId)
        val fromWindow = runCatching {
            service.windows
                .filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
                .firstOrNull { it.isActive }
                ?.root?.packageName?.toString()
        }.getOrNull()
            ?: runCatching { service.rootInActiveWindow?.packageName?.toString() }.getOrNull()
        listOf(fromCommand, fromWindow)
            .filter { !it.isNullOrBlank() && it != service.packageName }
            .forEach { packageName -> appLabelOf(packageName!!)?.let { return it } }
        return approval.foregroundApp?.label?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun appLabelOf(packageName: String): String? = runCatching {
        val manager = service.packageManager
        manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)).toString()
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * Decides the one approval the loop owns, through the same
     * [PhoneApprovals.decisionFlow] the notification's Aprobar / Rechazar use:
     * the ledger grant and the server decision are identical, so the executor
     * proceeds exactly as it does after a manual approval.
     */
    private fun decideVoiceApproval(approve: Boolean) {
        val approval = voiceApproval ?: return
        voiceApproval = null
        voiceApprovalKind = ApprovalVoiceKind.MESSAGE
        voiceApprovalSeenPending = false
        Log.i(TAG, "voice approval ${approval.id}: ${if (approve) "approved" else "rejected"}")
        scope.launch(Dispatchers.IO) {
            try {
                PhoneApprovals.decisionFlow(service).decide(approval, approve)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // The PC is unreachable: keep the surface the owner knows so
                // she can retry, exactly like the notification receiver does.
                Log.w(TAG, "voice approval ${approval.id} failed: ${error.javaClass.simpleName}")
                postApprovalNotification(approval, stillPending = true)
            }
        }
    }

    private fun readVoiceApprovalScreen() {
        // Screen text is data only: it is spoken, never classified or logged.
        val content = screenReader.read()
        postConfirm(
            BubbleConfirmEvent.ReadDone(content.recipient, content.text, content.detail),
        )
    }

    /**
     * Aprobar / Rechazar (T4): stops the microphone and the spoken prompt, then
     * uses the same reducer decision as the spoken answer ("sí" / "no").
     */
    private fun onApprovalButton(approve: Boolean) {
        if (voiceApproval == null) return
        stopTts()
        abandonVoice(render = true)
        dispatchConfirm(
            if (approve) BubbleConfirmEvent.ButtonApprove else BubbleConfirmEvent.ButtonReject,
        )
    }

    private fun appendVoiceApprovalText(text: String) {
        val appended = screenReader.append(text)
        postConfirm(
            if (appended) BubbleConfirmEvent.AppendDone else BubbleConfirmEvent.AppendFailed,
        )
    }

    /**
     * Runs the next reducer event after the current action list, so the
     * synchronous accessibility read/set-text never re-enters the dispatcher.
     */
    private fun postConfirm(event: BubbleConfirmEvent) {
        scope.launch(Dispatchers.Main) { dispatchConfirm(event) }
    }

    /** The loop ended without an answer: leave the usual notification behind. */
    private fun stopVoiceApprovalFallback() {
        val approval = voiceApproval ?: return
        val stillPending = !voiceApprovalSeenPending ||
            approvals.pending.value.any { it.id == approval.id }
        voiceApproval = null
        voiceApprovalKind = ApprovalVoiceKind.MESSAGE
        voiceApprovalSeenPending = false
        postApprovalNotification(approval, stillPending)
    }

    private fun postApprovalNotification(approval: PhoneApproval, stillPending: Boolean) {
        if (!ApprovalVoiceFallback.shouldNotify(approval, stillPending, System.currentTimeMillis())) {
            return
        }
        Log.i(TAG, "voice approval ${approval.id}: notification fallback")
        approvalNotifier.show(approval)
    }

    /**
     * A poll without the routed approval means another surface decided it or it
     * expired. Only a previously seen row can disappear; the first poll may
     * simply race the routing.
     */
    private fun onPendingApprovals(pending: List<PhoneApproval>) {
        val approval = voiceApproval ?: return
        if (pending.any { it.id == approval.id }) {
            voiceApprovalSeenPending = true
            return
        }
        if (!voiceApprovalSeenPending) return
        val expired = ApprovalVoiceRouting.isExpired(approval, System.currentTimeMillis())
        dispatchConfirm(
            if (expired) BubbleConfirmEvent.Expired else BubbleConfirmEvent.DecidedElsewhere,
        )
        // Neither event leaves a surface behind: the approval is already gone.
        if (confirmState == BubbleConfirmState.Idle) {
            voiceApproval = null
            voiceApprovalKind = ApprovalVoiceKind.MESSAGE
            voiceApprovalSeenPending = false
        }
    }

    /**
     * Starts the recognizer for one loop answer. The T11 listening UI is reused
     * (bars and "Escuchando…") so the owner sees the microphone is hers.
     */
    private fun startConfirmListening() {
        voiceState = BubbleVoiceState.Listening()
        renderVoice()
        if (!startSpeech()) dispatchConfirm(BubbleConfirmEvent.NoSpeech)
    }

    /**
     * Speaks a prompt of the spoken loop (T21/T23). Audio focus is left to
     * TextToSpeech's own defaults, so the bubble adds no permission and no
     * focus policy of its own. Initialization is asynchronous: the first prompt
     * is queued and spoken when the engine is ready, and any failure makes the
     * loop skip the voice and start listening immediately (the prompt line
     * stays visible).
     */
    private fun speakPrompt(text: String) {
        if (ttsFailed) {
            dispatchConfirm(BubbleConfirmEvent.TtsUnavailable)
            return
        }
        val engine = tts
        if (engine != null && ttsReady) {
            speakNow(engine, text)
            return
        }
        pendingSpeech = text
        if (engine == null) {
            tts = TextToSpeech(service) { status -> onTtsInitialized(status) }
                .also(::installTtsListener)
        }
    }

    private fun installTtsListener(engine: TextToSpeech) {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                // Binder thread: the reducer and every view call run on main.
                scope.launch { dispatchConfirm(BubbleConfirmEvent.TtsDone) }
            }

            override fun onError(utteranceId: String?) {
                scope.launch { dispatchConfirm(BubbleConfirmEvent.TtsUnavailable) }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                scope.launch { dispatchConfirm(BubbleConfirmEvent.TtsUnavailable) }
            }
        })
    }

    private fun onTtsInitialized(status: Int) {
        scope.launch {
            if (status != TextToSpeech.SUCCESS) {
                failTts()
                return@launch
            }
            val engine = tts ?: return@launch
            if (!selectSpanishVoice(engine)) {
                failTts()
                return@launch
            }
            // The engine is configured even when the loop was cancelled while
            // it started, so the next read-back speaks without waiting again.
            ttsReady = true
            val queued = pendingSpeech
            pendingSpeech = null
            if (queued != null && confirmState is BubbleConfirmState.Speaking) {
                speakNow(engine, queued)
            }
        }
    }

    /** `es-CL` first, then plain Spanish, as the task names. */
    private fun selectSpanishVoice(engine: TextToSpeech): Boolean {
        if (isLanguageUsable(engine.setLanguage(Locale.forLanguageTag("es-CL")))) return true
        return isLanguageUsable(engine.setLanguage(Locale.forLanguageTag("es")))
    }

    private fun isLanguageUsable(result: Int): Boolean =
        result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED

    private fun speakNow(engine: TextToSpeech, text: String) {
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, TTS_UTTERANCE_ID)
        if (result == TextToSpeech.ERROR) {
            dispatchConfirm(BubbleConfirmEvent.TtsUnavailable)
        }
    }

    private fun failTts() {
        ttsFailed = true
        ttsReady = false
        pendingSpeech = null
        dispatchConfirm(BubbleConfirmEvent.TtsUnavailable)
    }

    private fun stopTts() {
        pendingSpeech = null
        try {
            tts?.stop()
        } catch (error: Exception) {
            Log.w(TAG, "could not stop the spoken prompt: ${error.javaClass.simpleName}")
        }
    }

    private fun shutdownTts() {
        pendingSpeech = null
        ttsReady = false
        val engine = tts
        tts = null
        if (engine == null) return
        try {
            engine.stop()
            engine.shutdown()
        } catch (error: Exception) {
            Log.w(TAG, "could not shut TextToSpeech down: ${error.javaClass.simpleName}")
        }
    }

    // --- voice follow-up (T17) --------------------------------------------

    /**
     * Runs after every chat emission. A dictated request whose finished turn
     * answered with a question opens the bubble and starts listening; the phase
     * guard makes it fire once per turn, not on every later emission.
     */
    private fun maybeReopenForVoice(
        previousPhase: BubbleChatPhase,
        state: BubbleChatState,
    ) {
        if (!visible) return
        // A spoken approval owns the panel and the microphone; the follow-up
        // waits for the next turn rather than stepping on it.
        if (voiceApproval != null || confirmState != BubbleConfirmState.Idle) return
        // Staying DONE (an approval update, a re-render) must not fire again.
        if (previousPhase == BubbleChatPhase.DONE) return
        // The answer is rendered last for a finished turn, so requiring it to be
        // the last message ties the follow-up to this turn and never to a
        // previous answer the panel still shows.
        val last = state.messages.lastOrNull()
        if (last?.role != BubbleMessageRole.ANSWER) return
        if (!BubbleVoiceFollowUp.shouldListen(requestOrigin, state.phase, last.text)) return
        openForVoiceFollowUp()
    }

    /**
     * Shows the answer (the panel already renders it) and leaves the mic
     * listening. Expanding undocks first, and nothing here shows the keyboard:
     * T16 keeps the IME owner's choice. T23: the reply she dictates is sent
     * straight to the agent as the next request (no read-back).
     */
    private fun openForVoiceFollowUp() {
        if (mode == BubbleMode.COLLAPSED) expand()
        if (voiceState is BubbleVoiceState.Listening ||
            voiceState == BubbleVoiceState.RequestingPermission
        ) {
            return
        }
        dispatchVoice(
            BubbleVoiceEvent.MicTapped(
                permissionGranted = MicPermission.isGranted(service),
                recognizerAvailable = inBubbleRecognitionAvailable(),
            ),
        )
    }

    // --- interaction ------------------------------------------------------

    /**
     * Moves the expanded window by its header (T15), clamped to the screen.
     *
     * The header is a plain row; the Minimizar button is a clickable child and
     * consumes its own taps before this listener sees them, so dragging can
     * only start on the title, the mark or the empty header space.
     */
    private fun installPanelDrag(header: View) {
        val touchSlop = ViewConfiguration.get(service).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moving = false
        header.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = position.first
                    startY = position.second
                    moving = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!moving && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        moving = true
                    }
                    if (moving) {
                        val screen = screenSize()
                        val spec = currentSpec(screen)
                        position = BubbleOverlayPosition.clamped(
                            startX + dx.roundToInt(),
                            startY + dy.roundToInt(),
                            spec.width,
                            spec.height,
                            screen.first,
                            screen.second,
                        )
                        applyWindow(spec)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moving) panelPosition = position
                    moving = false
                    true
                }
                else -> false
            }
        }
    }

    private fun installDrag(view: View) {
        val touchSlop = ViewConfiguration.get(service).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Any touch resets the idle clock and cancels a pending tuck.
                    dockJob?.cancel()
                    dockJob = null
                    lastTouchAt = SystemClock.uptimeMillis()
                    touchBeganDocked = docked
                    if (docked) undock(animate = true)
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = position.first
                    startY = position.second
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        // A drag takes over the window from the undock spring.
                        dockAnimator?.cancel()
                        dockAnimator = null
                    }
                    if (dragging) {
                        val screen = screenSize()
                        val spec = currentSpec(screen)
                        position = BubbleOverlayPosition.clamped(
                            startX + dx.roundToInt(),
                            startY + dy.roundToInt(),
                            spec.width,
                            spec.height,
                            screen.first,
                            screen.second,
                        )
                        applyWindow(spec)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        val screen = screenSize()
                        val spec = currentSpec(screen)
                        position = BubbleOverlayPosition.snapped(
                            position.first,
                            position.second,
                            spec.width,
                            spec.height,
                            screen.first,
                            screen.second,
                        )
                        collapsedPosition = position
                        applyWindow(spec)
                        scheduleDock()
                    } else if (event.actionMasked == MotionEvent.ACTION_UP && !touchBeganDocked) {
                        // A tap on a docked circle only un-docks it; opening the
                        // panel from a tucked edge would be a surprise.
                        expand()
                    }
                    dragging = false
                    touchBeganDocked = false
                    true
                }
                else -> false
            }
        }
    }

    private fun expand() {
        cancelDock()
        val screen = screenSize()
        collapsedPosition = position
        val spec = BubbleWindowSpecs.forMode(
            BubbleMode.EXPANDED,
            BASE_FLAGS,
            screen.first,
            screen.second,
            density,
        )
        position = BubbleOverlayPosition.panel(
            rememberedX = panelPosition?.first,
            rememberedY = panelPosition?.second,
            width = spec.width,
            height = spec.height,
            screenWidth = screen.first,
            screenHeight = screen.second,
            fallbackY = position.second,
        )
        panelPosition = position
        mode = BubbleMode.EXPANDED
        focusState = BubbleFocusState(expanded = true)
        cancelIdleAnimation()
        applyWindow(spec)
        buildPanel()
        render()
        controller.resume()
    }

    private fun collapse() {
        // T21/T23: the spoken loop dies with the panel; a routed approval falls
        // back to its notification and the request text stays in the field.
        cancelConfirmLoop()
        stopTts()
        // T11: a collapsed bubble must not keep the microphone open.
        abandonVoice(render = false)
        hideKeyboard()
        input?.clearFocus()
        // T17: a dictated request still in flight keeps following while the
        // bubble is minimized, so a question answer can reopen it with the mic
        // ready. A typed request keeps the T6/T18 rule: minimizing stops the
        // poll and this turn stops approving steps on the phone.
        if (requestOrigin != BubbleRequestOrigin.DICTATED || !chatState.isBusy) {
            controller.close()
        }
        focusState = BubbleFocusState()
        val screen = screenSize()
        val spec = BubbleWindowSpecs.forMode(
            BubbleMode.COLLAPSED,
            BASE_FLAGS,
            screen.first,
            screen.second,
            density,
        )
        position = BubbleOverlayPosition.snapped(
            collapsedPosition.first,
            collapsedPosition.second,
            spec.width,
            spec.height,
            screen.first,
            screen.second,
        )
        mode = BubbleMode.COLLAPSED
        applyWindow(spec)
        buildPill()
        render()
        scheduleDock()
        // assistant-key T1: a panel opened by the system assistant lasts for
        // that session. Ending it lets the service gate hide a temporary panel
        // again; with the owner's bubble setting on, the pill simply stays.
        assistantSessionEnded?.let { ended ->
            assistantSessionEnded = null
            ended()
        }
        // phone-dashboard T2: a panel opened from the Dashboard while the gate
        // had it hidden lasts for this request only.
        if (ownerRequestSessionOnly) {
            ownerRequestSessionOnly = false
            setVisible(false)
        }
    }

    private fun sendDraft() {
        // T21/T23: the send button works at any moment; sending while the
        // spoken loop runs stops the loop first. This is also the path the
        // dictated transcript uses, so typed and dictated sends stay identical.
        cancelConfirmLoop()
        val text = input?.text?.toString().orEmpty()
        if (text.isBlank()) return
        val dictated = dictatedDraft
        // A live recognizer must not drop a late transcript into the field the
        // request is leaving from.
        abandonVoice(render = true)
        input?.setText("")
        dictatedDraft = false
        // T17: remember how the owner asked. Only a dictated request may reopen
        // the microphone when the answer is a question.
        requestOrigin = if (dictated) {
            BubbleRequestOrigin.DICTATED
        } else {
            BubbleRequestOrigin.TYPED
        }
        // The service can start in a process where MainActivity never ran, so
        // the shared client may still point at the emulator default.
        IduPiClientProvider.configureFromStorage(service)
        controller.send(text)
    }

    private fun hideKeyboard() {
        val manager = service.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        input?.let { manager?.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    // --- keyboard and navigation (T16) ------------------------------------

    /**
     * Runs the pure [BubbleFocusReducer] and applies its actions. Called from
     * the root view's key/touch dispatch and from the panel controls, all on the
     * main thread, so every [WindowManager] call below stays there.
     *
     * @return true when the bubble consumed the event (BACK must not reach the
     *   system then).
     */
    private fun handleFocusEvent(event: BubbleFocusEvent): Boolean {
        val decision = BubbleFocusReducer.reduce(focusState, event)
        if (!decision.handled) return false
        focusState = decision.state
        decision.actions.forEach(::applyFocusAction)
        return true
    }

    private fun applyFocusAction(action: BubbleFocusAction) {
        when (action) {
            BubbleFocusAction.HideKeyboard -> hideKeyboard()
            BubbleFocusAction.ClearFieldFocus -> input?.clearFocus()
            BubbleFocusAction.ReleaseWindowFocus -> setWindowFocusable(false)
            BubbleFocusAction.AcquireWindowFocus -> setWindowFocusable(true)
            BubbleFocusAction.RequestFieldFocus -> input?.requestFocus()
            BubbleFocusAction.ShowKeyboard -> showKeyboard()
            BubbleFocusAction.CollapsePanel -> collapse()
        }
    }

    /**
     * Toggles `FLAG_NOT_FOCUSABLE` on the live expanded window. Releasing focus
     * gives BACK and system navigation back to the app behind; taking it back
     * lets the IME reach the field again.
     */
    private fun setWindowFocusable(focusable: Boolean) {
        if (mode != BubbleMode.EXPANDED) return
        val layoutParams = params ?: return
        val view = root ?: return
        val effective = BubbleWindowSpecs.withFocus(currentSpec(screenSize()), focusable)
        layoutParams.flags = effective.flags
        applyBlur(layoutParams)
        try {
            windowManager.updateViewLayout(view, layoutParams)
        } catch (error: Exception) {
            Log.w(TAG, "could not toggle bubble focus: ${error.javaClass.simpleName}")
        }
    }

    /** Posted so it runs after the window has accepted the focus flag change. */
    private fun showKeyboard() {
        val field = input ?: return
        field.post {
            // An outside touch may have released focus before this ran.
            if (!focusState.windowFocusable) return@post
            val manager =
                service.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            manager?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    // --- edge docking (T10) -----------------------------------------------

    /** Arms the idle timer that tucks the resting circle off its edge. */
    private fun scheduleDock() {
        dockJob?.cancel()
        if (mode != BubbleMode.COLLAPSED) return
        val armedAt = SystemClock.uptimeMillis()
        lastTouchAt = armedAt
        dockJob = scope.launch {
            delay(BubbleDock.IDLE_BEFORE_DOCK_MILLIS)
            if (!visible || docked || dragging) return@launch
            if (!BubbleDock.shouldDock(
                    nowMillis = SystemClock.uptimeMillis(),
                    lastTouchMillis = lastTouchAt,
                    dragging = dragging,
                    expanded = mode == BubbleMode.EXPANDED,
                )
            ) {
                return@launch
            }
            dock()
        }
    }

    private fun cancelDock() {
        dockJob?.cancel()
        dockJob = null
        if (docked) undock(animate = false)
    }

    private fun dock() {
        if (docked || mode != BubbleMode.COLLAPSED) return
        docked = true
        animateDock(dockedTarget = true, spring = false)
        // The badge moves to the visible side of the tucked circle.
        updateBadgePlacement()
    }

    private fun undock(animate: Boolean) {
        if (!docked) return
        docked = false
        if (animate) {
            animateDock(dockedTarget = false, spring = true)
        } else {
            dockAnimator?.cancel()
            dockAnimator = null
            pill?.alpha = 1f
            applyWindow(currentSpec(screenSize()))
        }
        updateBadgePlacement()
    }

    /**
     * Slides the window's `x` between the docked and snapped positions with
     * `updateViewLayout` on the main thread. The spring back overshoots a touch
     * so the circle settles instead of stopping dead.
     */
    private fun animateDock(dockedTarget: Boolean, spring: Boolean) {
        val layoutParams = params ?: return
        val view = root ?: return
        val circle = pill ?: return
        val screen = screenSize()
        val spec = currentSpec(screen)
        val dockedX = BubbleOverlayPosition.docked(
            position.first,
            spec.width,
            screen.first,
            DOCK_PEEK_FRACTION,
        )
        val targetX = if (dockedTarget) dockedX else position.first
        val targetAlpha = if (dockedTarget) style.dockedAlpha else 1f
        val fromX = layoutParams.x
        val fromAlpha = circle.alpha
        dockAnimator?.cancel()
        dockAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = BubbleDock.ANIMATION_MILLIS
            interpolator = if (spring) OvershootInterpolator(1.4f) else DecelerateInterpolator()
            addUpdateListener { animator ->
                // `animatedValue` is already run through the interpolator.
                val fraction = animator.animatedValue as Float
                layoutParams.x = (fromX + (targetX - fromX) * fraction).roundToInt()
                circle.alpha = (fromAlpha + (targetAlpha - fromAlpha) * fraction).coerceIn(0f, 1f)
                try {
                    windowManager.updateViewLayout(view, layoutParams)
                } catch (error: Exception) {
                    Log.w(TAG, "could not animate the bubble window: ${error.javaClass.simpleName}")
                    animator.cancel()
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    // Keep the logical and the real x in sync when the spring
                    // lands a pixel off.
                    layoutParams.x = if (dockedTarget) dockedX else position.first
                    circle.alpha = targetAlpha
                    try {
                        windowManager.updateViewLayout(view, layoutParams)
                    } catch (error: Exception) {
                        Log.w(TAG, "could not settle the bubble window: ${error.javaClass.simpleName}")
                    }
                }
            })
            start()
        }
    }

    private fun updateBadgePlacement() {
        val badgeView = badge ?: return
        val lp = badgeView.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.gravity = badgeGravity()
        badgeView.layoutParams = lp
    }

    // --- rendering --------------------------------------------------------

    private fun render() {
        when (mode) {
            BubbleMode.COLLAPSED -> renderPill()
            BubbleMode.EXPANDED -> renderPanel()
        }
    }

    private fun renderPill() {
        badge?.visibility = if (approvalUi.badgeVisible) View.VISIBLE else View.GONE
        updateBadgePlacement()
        pill?.alpha = if (docked) style.dockedAlpha else 1f
    }

    private fun renderPanel() {
        val messages = messagesContainer ?: return
        messages.removeAllViews()
        chatState.messages.forEach { message ->
            val line = TextView(service).apply {
                text = message.text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(
                    when (message.role) {
                        BubbleMessageRole.OWNER -> style.textPrimary
                        BubbleMessageRole.STEP -> style.textSecondary
                        BubbleMessageRole.ANSWER -> style.textPrimary
                        BubbleMessageRole.ERROR -> style.error
                    }
                )
                setPadding(0, dp(4), 0, dp(4))
                if (message.role == BubbleMessageRole.OWNER) {
                    setTypeface(typeface, Typeface.BOLD)
                }
                if (message.role == BubbleMessageRole.ANSWER) {
                    // The answer is the thing the owner copies out of the panel.
                    setTextIsSelectable(true)
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            messages.addView(line)
        }
        messagesScroll?.post { messagesScroll?.fullScroll(View.FOCUS_DOWN) }

        statusLine?.text = chatState.statusLine().orEmpty()
        statusLine?.visibility = if (chatState.statusLine() == null) View.GONE else View.VISIBLE
        retryButton?.visibility = if (chatState.canRetry) View.VISIBLE else View.GONE
        sendButton?.isEnabled = !chatState.isBusy
        sendButton?.alpha = if (chatState.isBusy) 0.5f else 1f
        approvalBanner?.visibility =
            if (approvalUi.waitingForApproval) View.VISIBLE else View.GONE
        renderVoice()
        renderConfirmPrompt()
    }

    // --- helpers ----------------------------------------------------------

    private fun screenSize(): Pair<Int, Int> {
        val metrics = service.resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun pillSize(): Int =
        BubbleWindowSpecs.forMode(
            BubbleMode.COLLAPSED,
            BASE_FLAGS,
            screenSize().first,
            screenSize().second,
            density,
        ).width

    private fun currentSpec(screen: Pair<Int, Int>): BubbleWindowSpec =
        BubbleWindowSpecs.forMode(mode, BASE_FLAGS, screen.first, screen.second, density)

    private fun applyWindow(spec: BubbleWindowSpec) {
        val layoutParams = params ?: return
        val effective = if (mode == BubbleMode.EXPANDED) {
            // A released window keeps FLAG_NOT_FOCUSABLE across drags, rotations
            // and theme rebuilds until the owner taps the field again (T16).
            BubbleWindowSpecs.withFocus(spec, focusState.windowFocusable)
        } else {
            spec
        }
        layoutParams.width = effective.width
        layoutParams.height = effective.height
        layoutParams.flags = effective.flags
        applyBlur(layoutParams)
        layoutParams.x = windowX(effective)
        layoutParams.y = position.second
        val view = root ?: return
        try {
            windowManager.updateViewLayout(view, layoutParams)
        } catch (error: Exception) {
            Log.w(TAG, "could not update the bubble window: ${error.javaClass.simpleName}")
        }
    }

    /** The real window x: a docked circle sits past its edge, the rest snapped. */
    private fun windowX(spec: BubbleWindowSpec): Int =
        if (docked && mode == BubbleMode.COLLAPSED) {
            BubbleOverlayPosition.docked(
                position.first,
                spec.width,
                screenSize().first,
                DOCK_PEEK_FRACTION,
            )
        } else {
            position.first
        }

    /**
     * Real blur behind the expanded panel on API 31+, when the system allows
     * cross-window blur. The tiny collapsed circle never asks for blur: a
     * translucent fill is enough there and blur on a 56 dp window is not free.
     */
    private fun applyBlur(layoutParams: WindowManager.LayoutParams) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val blur = mode == BubbleMode.EXPANDED && windowManager.isCrossWindowBlurEnabled
        layoutParams.flags = if (blur) {
            layoutParams.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        } else {
            layoutParams.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
        }
        layoutParams.blurBehindRadius = if (blur) dp(BLUR_RADIUS_DP) else 0
    }

    private fun dp(value: Int): Int =
        (value * density).roundToInt().coerceAtLeast(1)

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }

    /**
     * A glass surface: translucent fill, rounded corners and a hairline rim.
     * The rim is what makes translucent panels readable over a busy app.
     */
    private fun glassSurface(fill: Int, radiusDp: Int, strokeColor: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), strokeColor)
        }

    /** Filled pill: the accent surface with its own readable ink. */
    private fun filledButton(label: String, onClick: () -> Unit): Button =
        Button(service).apply {
            text = label
            setTextColor(style.onPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            background = rounded(style.primary, radiusDp = 18)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { onClick() }
        }

    /** Glass pill: a faint fill and an ink outline, for secondary actions. */
    private fun glassButton(label: String, onClick: () -> Unit): Button =
        Button(service).apply {
            text = label
            setTextColor(style.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            background = glassSurface(
                fill = style.inputFill,
                radiusDp = 18,
                strokeColor = style.glassOutline,
            )
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { onClick() }
        }

    /** Resolves the owner's theme into the glass palette (T9). */
    private fun glassStyleOf(theme: ThemeSettings): BubbleGlassStyle {
        val colors = ThemePalettes.resolve(theme)
        return BubbleGlassStyle.from(
            bgArgb = colors.bg.toArgb(),
            cardArgb = colors.card.toArgb(),
            borderArgb = colors.border.toArgb(),
            textPrimaryArgb = colors.textPrimary.toArgb(),
            textSecondaryArgb = colors.textSecondary.toArgb(),
            primaryArgb = colors.primary.toArgb(),
            secondaryArgb = colors.secondary.toArgb(),
            isLight = colors.isLight,
        )
    }

    companion object {
        private const val TAG = "PhoneBubbleOverlay"

        /** Kept in both modes; the focus flag is the only one we toggle. */
        private const val BASE_FLAGS =
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

        /** Fraction of the circle that stays on screen while docked (T10). */
        const val DOCK_PEEK_FRACTION = 0.55f

        /** Corner radius of the expanded glass card. */
        private const val PANEL_CORNER_DP = 22

        /** Backdrop blur radius while expanded, on API 31+. */
        private const val BLUR_RADIUS_DP = 24

        /** Idle breathing: 97% .. 100% over a slow cycle. */
        private const val IDLE_SCALE_MIN = 0.97f
        private const val IDLE_BREATH_MILLIS = 3_000L

        /** Fill of the semantic approval banner; the stroke carries the colour. */
        private const val WARNING_FILL_ALPHA = 0.22f

        /** Compact keyboard glyph on the hide-keyboard glass button. */
        private const val HIDE_KEYBOARD_GLYPH = "⌨"

        /** Utterance id of the spoken prompt (T21/T23). */
        private const val TTS_UTTERANCE_ID = "idupi-bubble-confirm"

        /** White "!" on the red approval dot. */
        private val COLOR_ON_ERROR = 0xFFFFFFFF.toInt()
    }
}

/**
 * Root view of the overlay window. A focusable accessibility overlay receives
 * BACK itself instead of the app behind (T16), so the root intercepts it before
 * any focused child and lets the bubble decide. An outside touch is delivered
 * only because the expanded spec sets `FLAG_WATCH_OUTSIDE_TOUCH`.
 */
private class BubbleRootLayout(context: Context) : FrameLayout(context) {

    var onBack: (() -> Boolean)? = null
    var onOutsideTouch: (() -> Unit)? = null

    private var backConsumed = false

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                backConsumed = onBack?.invoke() == true
            }
            return backConsumed
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            onOutsideTouch?.invoke()
            return true
        }
        return super.dispatchTouchEvent(event)
    }
}
