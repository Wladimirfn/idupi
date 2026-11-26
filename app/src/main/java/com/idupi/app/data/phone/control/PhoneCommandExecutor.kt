package com.idupi.app.data.phone.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.idupi.app.data.phone.PhoneStatusReporting
import com.idupi.app.data.phone.approval.ApprovalGate
import com.idupi.app.data.phone.approval.ApprovalGateResult
import com.idupi.app.data.phone.approval.ApprovalLedger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.resume

/**
 * Android glue for phone-agent T3: turns live accessibility windows into
 * [NodeSnapshot] trees and executes validated [PhoneCommand]s.
 *
 * Every helper returns a [CommandOutcome]; [execute] additionally catches
 * unexpected platform exceptions so a broken node never kills the service. The
 * service itself only talks to this class through [execute].
 */
class PhoneCommandExecutor(
    private val service: AccessibilityService,
    private val ledger: ApprovalLedger,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val reportStatusNow: suspend () -> Boolean = { PhoneStatusReporting.reportNow() },
) {

    suspend fun execute(command: PhoneCommand): CommandOutcome = try {
        when (command) {
            is PhoneCommand.Screen -> screen()
            is PhoneCommand.Tap -> tap(command.target, longPress = false, approval = command.approval)
            is PhoneCommand.LongPress ->
                tap(command.target, longPress = true, approval = command.approval)
            is PhoneCommand.TypeText -> type(command.text, command.submit, command.approval)
            is PhoneCommand.Scroll -> scroll(command.direction)
            is PhoneCommand.Global -> global(command.action)
            is PhoneCommand.OpenApp -> open(command.app)
            is PhoneCommand.Wait -> {
                delay(command.millis.toLong())
                CommandOutcome.success()
            }
            is PhoneCommand.ReportStatus -> {
                if (reportStatusNow()) CommandOutcome.success()
                else CommandOutcome.failure("no se pudo reportar el estado")
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.w(TAG, "command execution failed: ${error.javaClass.simpleName}")
        CommandOutcome.failure(INTERNAL_ERROR)
    }

    // --- screen ---

    private fun screen(): CommandOutcome {
        val snapshots = liveRoots().map(::snapshot)
        val dump = ScreenDumpFormatter.format(snapshots)
        return CommandOutcome.success(buildJsonObject { put("text", dump) })
    }

    private fun liveRoots(): List<AccessibilityNodeInfo> {
        val active = service.rootInActiveWindow
        if (active != null) return listOf(active)
        // Some apps expose only a non-active interactive window while a dialog
        // or IME owns the active one; fall back to the windows we can see.
        return try {
            service.windows
                .filter { it.isActive }
                .mapNotNull { it.root }
        } catch (error: Exception) {
            Log.w(TAG, "could not read accessibility windows: ${error.javaClass.simpleName}")
            emptyList()
        }
    }

    private fun snapshot(node: AccessibilityNodeInfo): NodeSnapshot {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val children = (0 until node.childCount).mapNotNull { index ->
            node.getChild(index)?.let(::snapshot)
        }
        return NodeSnapshot(
            className = node.className?.toString().orEmpty(),
            text = node.text?.toString().orEmpty(),
            desc = node.contentDescription?.toString().orEmpty(),
            viewId = node.viewIdResourceName.orEmpty(),
            clickable = node.isClickable,
            editable = node.isEditable,
            password = node.isPassword,
            visible = node.isVisibleToUser,
            bounds = NodeBounds(rect.left, rect.top, rect.width(), rect.height()),
            children = children,
        )
    }

    // --- tap / long_press ---

    private suspend fun tap(
        target: NodeTarget,
        longPress: Boolean,
        approval: CommandApproval?,
    ): CommandOutcome {
        if (target is NodeTarget.Point) {
            val roots = liveRoots()
            val texts = if (roots.isEmpty()) {
                emptyList()
            } else {
                NodeMatcher.textsAtPoint(roots.map(::snapshot), target.x, target.y)
            }
            refusal(
                ApprovalGate.checkNode(
                    approval = approval,
                    nodeTexts = texts,
                    ledger = ledger,
                    nowMillis = nowMillis(),
                    commandTarget = commandTarget(target),
                )
            )?.let { return it }
            return dispatchTap(target.x.toFloat(), target.y.toFloat(), longPress)
        }

        val roots = liveRoots()
        if (roots.isEmpty()) return CommandOutcome.failure("no hay ventana activa")
        val snapshots = roots.map(::snapshot)

        return when (val match = NodeMatcher.find(snapshots, target)) {
            is NodeMatch.NotFound -> CommandOutcome.failure(match.reason)
            is NodeMatch.Found -> {
                val texts = buildList {
                    if (target is NodeTarget.Text) add(target.text)
                    addAll(NodeMatcher.textCandidates(match.node))
                }
                refusal(
                    ApprovalGate.checkNode(
                        approval = approval,
                        nodeTexts = texts,
                        ledger = ledger,
                        nowMillis = nowMillis(),
                        commandTarget = commandTarget(target),
                    )
                )?.let { return it }

                val live = findLiveNode(roots, match.node)
                val performed = when {
                    live == null -> false
                    longPress && live.isLongClickable ->
                        live.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                    !longPress && live.isClickable ->
                        live.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    else -> false
                }
                if (performed) {
                    CommandOutcome.success()
                } else {
                    dispatchTap(
                        match.node.bounds.centerX.toFloat(),
                        match.node.bounds.centerY.toFloat(),
                        longPress,
                    )
                }
            }
        }
    }

    /**
     * Finds the live node behind a snapshot target. Bounds, class and (when
     * present) view id identify it; the snapshot tree and the live tree come
     * from the same read, so they agree.
     */
    private fun findLiveNode(
        roots: List<AccessibilityNodeInfo>,
        target: NodeSnapshot,
    ): AccessibilityNodeInfo? {
        roots.forEach { root ->
            findLiveNode(root, target)?.let { return it }
        }
        return null
    }

    private fun findLiveNode(
        node: AccessibilityNodeInfo,
        target: NodeSnapshot,
    ): AccessibilityNodeInfo? {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val sameBounds = rect.left == target.bounds.x &&
            rect.top == target.bounds.y &&
            rect.width() == target.bounds.width &&
            rect.height() == target.bounds.height
        val sameClass = node.className?.toString().orEmpty() == target.className
        val sameId = target.viewId.isEmpty() || node.viewIdResourceName == target.viewId
        if (sameBounds && sameClass && sameId && node.isVisibleToUser) return node

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            findLiveNode(child, target)?.let { return it }
        }
        return null
    }

    private suspend fun dispatchTap(x: Float, y: Float, longPress: Boolean): CommandOutcome {
        val path = Path().apply { moveTo(x, y) }
        val duration = if (longPress) LONG_PRESS_MILLIS else TAP_MILLIS
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        return dispatchGesture(gesture)
    }

    private suspend fun dispatchGesture(gesture: GestureDescription): CommandOutcome =
        withTimeoutOrNull(GESTURE_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                val callback = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(CommandOutcome.success())
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) {
                            continuation.resume(CommandOutcome.failure("gesto cancelado"))
                        }
                    }
                }
                val dispatched = service.dispatchGesture(gesture, callback, null)
                if (!dispatched && continuation.isActive) {
                    continuation.resume(CommandOutcome.failure("gesto rechazado"))
                }
            }
        } ?: CommandOutcome.failure("gesto sin respuesta")

    // --- type ---

    private suspend fun type(
        text: String,
        submit: Boolean,
        approval: CommandApproval?,
    ): CommandOutcome {
        if (submit) {
            refusal(ApprovalGate.checkSubmit(approval, ledger, nowMillis()))?.let { return it }
        }

        val root = service.rootInActiveWindow ?: return CommandOutcome.failure("no hay ventana activa")
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val editable = when {
            focused != null && focused.isEditable -> focused
            else -> firstEditable(focused ?: root) ?: firstEditable(root)
        } ?: return CommandOutcome.failure("no hay campo de texto activo")

        if (editable.isPassword) return CommandOutcome.failure(PASSWORD_FIELD)

        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
            return CommandOutcome.failure("no se pudo escribir")
        }

        if (submit && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Action-key press through the IME. Older APIs have no equivalent
            // and the contract says to fall back to "no submit" there.
            editable.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return CommandOutcome.success()
    }

    private fun firstEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isVisibleToUser) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            firstEditable(child)?.let { return it }
        }
        return null
    }

    // --- scroll ---

    private suspend fun scroll(direction: ScrollDirection): CommandOutcome {
        val root = service.rootInActiveWindow
        val scrollable = root?.let(::firstScrollable)
        if (scrollable != null) {
            val action = when (direction) {
                ScrollDirection.UP, ScrollDirection.LEFT -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                ScrollDirection.DOWN, ScrollDirection.RIGHT -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
            if (scrollable.performAction(action)) return CommandOutcome.success()
        }
        return swipe(direction)
    }

    private fun firstScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable && node.isVisibleToUser) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            firstScrollable(child)?.let { return it }
        }
        return null
    }

    /**
     * Fallback when nothing is scrollable: a finger drag. The direction names
     * where the viewport moves, so the finger goes the opposite way (scroll
     * down means drag up), which is what a person does on the phone.
     */
    private suspend fun swipe(direction: ScrollDirection): CommandOutcome {
        val metrics = service.resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()
        if (width <= 0f || height <= 0f) return CommandOutcome.failure("pantalla no disponible")

        val path = Path()
        when (direction) {
            ScrollDirection.DOWN -> {
                path.moveTo(width / 2f, height * 0.8f)
                path.lineTo(width / 2f, height * 0.2f)
            }
            ScrollDirection.UP -> {
                path.moveTo(width / 2f, height * 0.2f)
                path.lineTo(width / 2f, height * 0.8f)
            }
            ScrollDirection.RIGHT -> {
                path.moveTo(width * 0.8f, height / 2f)
                path.lineTo(width * 0.2f, height / 2f)
            }
            ScrollDirection.LEFT -> {
                path.moveTo(width * 0.2f, height / 2f)
                path.lineTo(width * 0.8f, height / 2f)
            }
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, SCROLL_MILLIS))
            .build()
        return dispatchGesture(gesture)
    }

    // --- global ---

    private fun global(action: GlobalAction): CommandOutcome {
        val globalAction = when (action) {
            GlobalAction.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            GlobalAction.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            GlobalAction.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
            GlobalAction.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            GlobalAction.QUICK_SETTINGS -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    return CommandOutcome.failure("acción no disponible")
                }
                AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            }
        }
        return if (service.performGlobalAction(globalAction)) {
            CommandOutcome.success()
        } else {
            CommandOutcome.failure("la acción no se pudo ejecutar")
        }
    }

    // --- open ---

    private fun open(app: String): CommandOutcome {
        val packageManager = service.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val resolved = packageManager.queryIntentActivities(launcherIntent, 0)
        val apps = resolved
            .mapNotNull { info ->
                val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                LaunchableApp(
                    packageName = packageName,
                    label = info.loadLabel(packageManager).toString(),
                )
            }
            .distinctBy { it.packageName }

        val match = AppResolver.resolve(app, apps)
            ?: return CommandOutcome.failure("app no encontrada")
        val launchIntent = packageManager.getLaunchIntentForPackage(match.packageName)
            ?: return CommandOutcome.failure("app sin pantalla de inicio")
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            service.startActivity(launchIntent)
            CommandOutcome.success()
        } catch (error: Exception) {
            Log.w(TAG, "could not launch app: ${error.javaClass.simpleName}")
            CommandOutcome.failure("no se pudo abrir la app")
        }
    }

    /** The target exactly as the server normalized it (`text`, id, or `x,y`). */
    private fun commandTarget(target: NodeTarget): String = when (target) {
        is NodeTarget.Text -> target.text
        is NodeTarget.Id -> target.id
        is NodeTarget.Point -> "${target.x},${target.y}"
    }

    /**
     * Turns a refused gate into the result the server expects. Only a refused
     * risky node carries `result.nodeText`, which the server uses to build the
     * approval request.
     */
    private fun refusal(result: ApprovalGateResult): CommandOutcome? = when (result) {
        ApprovalGateResult.Allowed -> null
        is ApprovalGateResult.Refused -> CommandOutcome(
            ok = false,
            result = result.nodeText?.takeIf { it.isNotBlank() }?.let { nodeText ->
                buildJsonObject { put("nodeText", nodeText) }
            },
            error = result.error,
        )
    }

    companion object {
        private const val TAG = "PhoneCommandExecutor"

        const val PASSWORD_FIELD = "campo de contraseña"
        private const val INTERNAL_ERROR = "error interno"

        private const val TAP_MILLIS = 50L
        private const val LONG_PRESS_MILLIS = 600L
        private const val SCROLL_MILLIS = 300L
        private const val GESTURE_TIMEOUT_MILLIS = 3_000L
    }
}
