package com.idupi.app.data.inbox

/**
 * Count of captures IDUPI cleared from the phone shade since the owner last
 * opened Alertas (alerts-flow A1).
 *
 * Process-wide on purpose: the listener posts IDUPI's single silent summary
 * with this count, and [com.idupi.app.ui.screens.AlertsScreen] resets it when
 * the owner is looking at the pile, so the summary never counts alerts the
 * owner already saw. The counter lives in memory only: a process restart with
 * a stale summary is corrected by the next capture's update.
 */
object InboxCaptureSummary {

    /** One snapshot of the counter. */
    data class Count(val total: Int, val important: Int)

    private val lock = Any()
    private var total = 0
    private var important = 0

    /** Adds one cleared capture and returns the updated count. */
    fun record(important: Boolean = false): Count = synchronized(lock) {
        total += 1
        if (important) this.important += 1
        Count(total, this.important)
    }

    /** The owner opened Alertas: the next summary starts from zero. */
    fun reset() {
        synchronized(lock) {
            total = 0
            important = 0
        }
    }

    /**
     * The summary line, e.g. "3 alertas nuevas · 1 importante". Pure so the
     * singular/plural contract is unit-tested without a notification.
     */
    fun text(total: Int, important: Int): String {
        val alerts = if (total == 1) "1 alerta nueva" else "$total alertas nuevas"
        if (important <= 0) return alerts
        val highlighted = if (important == 1) "1 importante" else "$important importantes"
        return "$alerts · $highlighted"
    }
}
