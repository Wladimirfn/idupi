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
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
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
 * Voice (T5) and the approval banner (T7) plug into this shell: they own their
 * own wiring, the shell only renders what the state exposes.
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
        val view = FrameLayout(service)
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
        themeJob?.cancel()
        themeJob = null
        dockJob?.cancel()
        dockJob = null
        cancelIdleAnimation()
        dockAnimator?.cancel()
        dockAnimator = null
        controller.close()
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
        }
        val send = filledButton("Enviar") { sendDraft() }
        val dictate = glassButton("Dictar") { launchDictation() }
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
        }
        val hint = TextView(service).apply {
            text = VoiceInputContract.FAILURE_MESSAGE
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
        column.addView(buttons)
        column.addView(hint)
        input = field
        sendButton = send
        voiceButton = dictate
        voiceHint = hint
        return column
    }

    // --- dictation (T5) ---------------------------------------------------

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

    /** The transcript lands in the field for editing; it is never auto-sent. */
    private fun acceptTranscript(text: String) {
        val field = input ?: return
        field.setText(text)
        field.setSelection(text.length)
        field.requestFocus()
        voiceHint?.visibility = View.GONE
    }

    private fun showVoiceFailure() {
        voiceHint?.text = VoiceInputContract.FAILURE_MESSAGE
        voiceHint?.visibility = View.VISIBLE
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
        cancelIdleAnimation()
        applyWindow(spec)
        buildPanel()
        render()
        controller.resume()
    }

    private fun collapse() {
        hideKeyboard()
        controller.close()
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
        layoutParams.width = spec.width
        layoutParams.height = spec.height
        layoutParams.flags = spec.flags
        applyBlur(layoutParams)
        layoutParams.x = windowX(spec)
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

        /** White "!" on the red approval dot. */
        private val COLOR_ON_ERROR = 0xFFFFFFFF.toInt()
    }
}
