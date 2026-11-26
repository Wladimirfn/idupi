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
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
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
import com.idupi.app.data.phone.approval.PendingApprovalsStore
import com.idupi.app.data.phone.approval.PhoneApprovals
import com.idupi.app.data.settings.SettingsProvider
import com.idupi.app.data.settings.SettingsRepository
import com.idupi.app.domain.model.ThemeSettings
import com.idupi.app.ui.theme.ThemePalettes
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

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
 * remains only as the fallback when the phone cannot dictate in-process. The
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

    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var stateJob: Job? = null
    private var voiceJob: Job? = null
    private var permissionJob: Job? = null
    private var themeJob: Job? = null
    private var dockJob: Job? = null

    private var mode = BubbleMode.COLLAPSED
    private var visible = false

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

    /** In-bubble dictation state (T11); the reducer is the only writer. */
    private var voiceState: BubbleVoiceState = BubbleVoiceState.Idle

    /** Live in-process recognizer; created on first use, destroyed on collapse. */
    private var speech: SpeechRecognizer? = null

    private var idleAnimator: ValueAnimator? = null
    private var dockAnimator: ValueAnimator? = null

    /** Adds the window when the gate turns on, removes it when it turns off. */
    fun setVisible(show: Boolean) {
        if (show == visible) return
        visible = show
        if (show) attach() else detach()
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
        visible = false
        detach()
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
    }

    private fun detach() {
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
                state to pending.size
            }.collect { (state, pendingCount) ->
                chatState = state
                approvalUi = BubbleApprovalState.of(state.phase, pendingCount)
                render()
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
     */
    private fun onMicTapped() {
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

    private fun startSpeech() {
        try {
            ensureSpeechRecognizer().startListening(recognitionIntent())
        } catch (error: Exception) {
            Log.w(TAG, "could not start in-bubble dictation: ${error.javaClass.simpleName}")
            dispatchVoice(BubbleVoiceEvent.OtherError)
        }
    }

    /**
     * One recognizer for the life of the listening attempts; T11 prefers the
     * on-device model on API 31+ because it answers without a round trip.
     */
    private fun ensureSpeechRecognizer(): SpeechRecognizer {
        speech?.let { return it }
        val created = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(service)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(service)
        } else {
            SpeechRecognizer.createSpeechRecognizer(service)
        }
        created.setRecognitionListener(recognitionListener)
        speech = created
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
        try {
            speech?.stopListening()
        } catch (error: Exception) {
            Log.w(TAG, "could not stop dictation: ${error.javaClass.simpleName}")
        }
    }

    /**
     * Cancels and destroys the recognizer (T11: on collapse, detach and send).
     * The next mic tap creates a fresh one.
     */
    private fun abandonVoice(render: Boolean) {
        val recognizer = speech
        speech = null
        if (recognizer != null) {
            try {
                recognizer.cancel()
                recognizer.destroy()
            } catch (error: Exception) {
                Log.w(TAG, "could not destroy dictation: ${error.javaClass.simpleName}")
            }
        }
        voiceState = BubbleVoiceState.Idle
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

    /** A system-sheet transcript lands in the field for editing, never sent. */
    private fun acceptTranscript(text: String) {
        voiceState = BubbleVoiceState.Idle
        putTranscript(text)
        renderVoice()
    }

    /**
     * The only writer of the field for dictation: partial hypotheses stream in
     * and the final transcript stays for editing. Never sends, and never asks
     * for field focus: starting to listen must not force the IME up (T16).
     */
    private fun putTranscript(text: String) {
        val field = input ?: return
        field.setText(text)
        field.setSelection(text.length)
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

    /** Thin bridge: every recognizer callback becomes one reducer event. */
    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) {
            dispatchVoice(BubbleVoiceEvent.Level(rmsdB))
        }
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onError(error: Int) {
            dispatchVoice(recognitionErrorEvent(error))
        }
        override fun onResults(results: Bundle?) {
            val text = firstResult(results)
            dispatchVoice(
                if (text != null) BubbleVoiceEvent.Final(text) else BubbleVoiceEvent.NoSpeech,
            )
        }
        override fun onPartialResults(partialResults: Bundle?) {
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
        // T11: a collapsed bubble must not keep the microphone open.
        abandonVoice(render = false)
        hideKeyboard()
        input?.clearFocus()
        controller.close()
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
    }

    private fun sendDraft() {
        val text = input?.text?.toString().orEmpty()
        if (text.isBlank()) return
        // A live recognizer must not drop a late transcript into the field the
        // request is leaving from.
        abandonVoice(render = true)
        input?.setText("")
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
