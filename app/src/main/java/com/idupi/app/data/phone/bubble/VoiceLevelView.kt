package com.idupi.app.data.phone.bubble

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.sin

/**
 * Five glass bars that dance to the microphone level (phone-bubble T11).
 *
 * A cheap custom View: one [ValueAnimator] drives a phase, `onDraw` turns the
 * phase plus the normalized level into bar heights, and the animator runs only
 * while the view is attached. The overlay feeds `onRmsChanged` through
 * [setLevel]; all calls stay on the main thread.
 */
class VoiceLevelView(
    context: Context,
    private val barColor: Int,
    private val barCount: Int = 5,
) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = barColor }

    private var level = 0f
    private var phase = 0f

    private val animator = ValueAnimator.ofFloat(0f, (2 * PI).toFloat()).apply {
        duration = WAVE_MILLIS
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    /** Normalized 0..1 loudness; the reducer hands over the mapped RMS value. */
    fun setLevel(value: Float) {
        level = value.coerceIn(0f, 1f)
    }

    /**
     * Starts the wave only while the row is on screen, so a closed panel never
     * pays for an animation nobody sees.
     */
    fun setActive(active: Boolean) {
        if (active) {
            if (!animator.isStarted) animator.start()
        } else {
            animator.cancel()
            phase = 0f
        }
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0f || height <= 0f) return
        val gap = width * GAP_FRACTION
        val barWidth = ((width - gap * (barCount - 1)) / barCount).coerceAtLeast(1f)
        val radius = barWidth / 2f
        for (index in 0 until barCount) {
            // Each bar lags the previous one, so the row reads as a wave rather
            // than as five bars breathing in lockstep.
            val wave = (sin(phase + index * WAVE_OFFSET) + 1f) / 2f
            val fraction = (MIN_FRACTION +
                (1f - MIN_FRACTION) * level * (0.35f + 0.65f * wave)).coerceIn(0f, 1f)
            val barHeight = (height * fraction).coerceAtLeast(1f)
            val left = index * (barWidth + gap)
            val top = (height - barHeight) / 2f
            canvas.drawRoundRect(left, top, left + barWidth, top + barHeight, radius, radius, paint)
        }
    }

    private companion object {
        const val WAVE_MILLIS = 900L
        const val WAVE_OFFSET = 0.9f
        const val GAP_FRACTION = 0.04f
        const val MIN_FRACTION = 0.18f
    }
}
