package com.idupi.app.data.phone.bubble

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.idupi.app.MainActivity
import com.idupi.app.data.IduPiClientProvider
import com.idupi.app.data.inbox.InboxNotifier
import com.idupi.app.data.phone.approval.PendingApprovalsStore
import com.idupi.app.data.phone.approval.PhoneApprovals
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The floating IDUPI bubble (phone-bubble T4), drawn by the accessibility
 * service with `TYPE_ACCESSIBILITY_OVERLAY` (no extra permission).
 *
 * Classic Views, not Compose: the overlay lives in a Service, outside any
 * Activity, so Compose would need a hand-built lifecycle owner, saved-state
 * registry and recomposer around a window that is added and removed at
 * runtime. The panel is a handful of widgets; plain Views keep the IME focus
 * behaviour and the window lifecycle obvious. The palette mirrors
 * `DarkAppColors` because Compose colour accessors are `@Composable`-only.
 *
 * Collapsed it is a draggable pill that snaps to the nearest vertical edge
 * (the same `snapBubbleToEdge` rule as the fullscreen bubble). Tapping expands
 * a focusable panel so the keyboard can reach the text field; minimizing
 * collapses it and cancels the long poll ([BubbleChatController.close]).
 *
 * Voice (T5) and the approval banner (T7) plug into this shell: they own their
 * own wiring, the shell only renders what the state exposes.
 */
class PhoneBubbleOverlay(
    private val service: AccessibilityService,
    private val controller: BubbleChatController = BubbleChatController(IduPiClientProvider),
    private val approvals: PendingApprovalsStore = PhoneApprovals.pendingStore,
) {

    private val windowManager =
        service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = service.resources.displayMetrics.density
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var stateJob: Job? = null
    private var voiceJob: Job? = null

    private var mode = BubbleMode.COLLAPSED
    private var visible = false

    /** Current window top-left, in pixels. */
    private var position = 0 to 0

    /** Last pill position, restored when the panel collapses. */
    private var collapsedPosition = 0 to 0

    private var chatState = BubbleChatState()
    private var approvalUi = BubbleApprovalUi(
        waitingForApproval = false,
        badgeVisible = false,
        pendingCount = 0,
    )

    private var pill: TextView? = null
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

    /** Adds the window when the gate turns on, removes it when it turns off. */
    fun setVisible(show: Boolean) {
        if (show == visible) return
        visible = show
        if (show) attach() else detach()
    }

    /** Re-clamps the window after a rotation or a display-size change. */
    fun onConfigurationChanged() {
        if (!visible) return
        val screen = screenSize()
        val spec = currentSpec(screen)
        position = BubbleOverlayPosition.clamped(
            position.first,
            position.second,
            spec.width,
            spec.height,
            screen.first,
            screen.second,
        )
        if (mode == BubbleMode.COLLAPSED) {
            position = BubbleOverlayPosition.snapped(
                position.first,
                position.second,
                spec.width,
                spec.height,
                screen.first,
                screen.second,
            )
            collapsedPosition = position
        }
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
        controller.close()
        val view = root ?: return
        root = null
        params = null
        pill = null
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
    }

    private fun rebuildContent() {
        when (mode) {
            BubbleMode.COLLAPSED -> buildPill()
            BubbleMode.EXPANDED -> buildPanel()
        }
        render()
    }

    // --- collapsed pill ---------------------------------------------------

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
        val pillView = TextView(service).apply {
            text = "IDUPI"
            setTextColor(COLOR_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = rounded(COLOR_PRIMARY, radiusDp = 28)
            contentDescription = "Abrir la burbuja de IDUPI"
            isClickable = false
            layoutParams = FrameLayout.LayoutParams(size, size)
            installDrag(this)
        }
        val badgeView = TextView(service).apply {
            text = "!"
            setTextColor(COLOR_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = rounded(COLOR_ERROR, radiusDp = 10)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(dp(20), dp(20)).apply {
                gravity = Gravity.TOP or Gravity.END
            }
        }
        container.addView(pillView)
        container.addView(badgeView)
        pill = pillView
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
    }

    // --- expanded panel ---------------------------------------------------

    private fun buildPanel() {
        val container = root ?: return
        container.removeAllViews()

        val card = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(COLOR_CARD, radiusDp = 18, strokeColor = COLOR_BORDER)
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
        badge = null
    }

    private fun buildHeader(): View {
        val row = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(service).apply {
            text = "IDUPI"
            setTextColor(COLOR_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val minimize = Button(service).apply {
            text = "Minimizar"
            setTextColor(COLOR_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            background = rounded(COLOR_BACKGROUND, radiusDp = 10, strokeColor = COLOR_BORDER)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { collapse() }
        }
        row.addView(title)
        row.addView(minimize)
        return row
    }

    private fun buildApprovalBanner(): View {
        val row = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(COLOR_WARNING_BG, radiusDp = 10, strokeColor = COLOR_WARNING)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        }
        val label = TextView(service).apply {
            text = "Esperando tu aprobación"
            setTextColor(COLOR_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val open = Button(service).apply {
            text = "Ver en Alertas"
            setTextColor(COLOR_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            background = rounded(COLOR_PRIMARY, radiusDp = 10)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { openAlerts() }
        }
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
            setTextColor(COLOR_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(4), 0, dp(2))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }.also { statusLine = it }

    private fun buildRetryButton(): View =
        Button(service).apply {
            text = "Reintentar"
            setTextColor(COLOR_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            background = rounded(COLOR_PRIMARY, radiusDp = 10)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(12), dp(6), dp(12), dp(6))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) }
            setOnClickListener { controller.retry() }
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
            setHintTextColor(COLOR_SECONDARY)
            setTextColor(COLOR_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            maxLines = 3
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(COLOR_BACKGROUND, radiusDp = 10, strokeColor = COLOR_BORDER)
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
        val send = Button(service).apply {
            text = "Enviar"
            setTextColor(COLOR_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            background = rounded(COLOR_PRIMARY, radiusDp = 10)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { sendDraft() }
        }
        val dictate = Button(service).apply {
            text = "Dictar"
            setTextColor(COLOR_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = rounded(COLOR_BACKGROUND, radiusDp = 10, strokeColor = COLOR_BORDER)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { launchDictation() }
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
        }
        val hint = TextView(service).apply {
            text = VoiceInputContract.FAILURE_MESSAGE
            setTextColor(COLOR_ERROR)
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

    private fun installDrag(view: View) {
        val touchSlop = ViewConfiguration.get(service).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
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
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        expand()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun expand() {
        val screen = screenSize()
        collapsedPosition = position
        val spec = BubbleWindowSpecs.forMode(
            BubbleMode.EXPANDED,
            BASE_FLAGS,
            screen.first,
            screen.second,
            density,
        )
        position = BubbleOverlayPosition.centeredX(spec.width, screen.first) to
            BubbleOverlayPosition.clamped(
                0,
                position.second,
                spec.width,
                spec.height,
                screen.first,
                screen.second,
            ).second
        mode = BubbleMode.EXPANDED
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

    // --- rendering --------------------------------------------------------

    private fun render() {
        when (mode) {
            BubbleMode.COLLAPSED -> renderPill()
            BubbleMode.EXPANDED -> renderPanel()
        }
    }

    private fun renderPill() {
        badge?.visibility = if (approvalUi.badgeVisible) View.VISIBLE else View.GONE
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
                        BubbleMessageRole.OWNER -> COLOR_TEXT
                        BubbleMessageRole.STEP -> COLOR_SECONDARY
                        BubbleMessageRole.ANSWER -> COLOR_TEXT
                        BubbleMessageRole.ERROR -> COLOR_ERROR
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

    private fun currentSpec(screen: Pair<Int, Int>): BubbleWindowSpec =
        BubbleWindowSpecs.forMode(mode, BASE_FLAGS, screen.first, screen.second, density)

    private fun applyWindow(spec: BubbleWindowSpec) {
        val layoutParams = params ?: return
        layoutParams.width = spec.width
        layoutParams.height = spec.height
        layoutParams.flags = spec.flags
        layoutParams.x = position.first
        layoutParams.y = position.second
        val view = root ?: return
        try {
            windowManager.updateViewLayout(view, layoutParams)
        } catch (error: Exception) {
            Log.w(TAG, "could not update the bubble window: ${error.javaClass.simpleName}")
        }
    }

    private fun dp(value: Int): Int =
        (value * density).roundToInt().coerceAtLeast(1)

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }

    companion object {
        private const val TAG = "PhoneBubbleOverlay"

        /** Kept in both modes; the focus flag is the only one we toggle. */
        private const val BASE_FLAGS =
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

        // Mirror of DarkAppColors: Compose colour accessors are @Composable-only.
        private val COLOR_BACKGROUND = 0xFF0B0F19.toInt()
        private val COLOR_CARD = 0xFF161E2E.toInt()
        private val COLOR_BORDER = 0xFF242F41.toInt()
        private val COLOR_TEXT = 0xFFF8FAFC.toInt()
        private val COLOR_SECONDARY = 0xFF94A3B8.toInt()
        private val COLOR_PRIMARY = 0xFF6366F1.toInt()
        private val COLOR_ERROR = 0xFFEF4444.toInt()
        private val COLOR_WARNING = 0xFFF59E0B.toInt()
        private val COLOR_WARNING_BG = 0xFF3A2E14.toInt()
    }
}
