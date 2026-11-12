package com.ejemplo.navegador

import android.animation.ValueAnimator
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * Hoja inferior que se cierra arrastrándola hacia abajo, como en los
 * navegadores actuales. El gesto empieza en el asa/encabezado o en cualquier
 * punto cuando el contenido ya está arriba del todo; si se suelta antes de la
 * mitad del recorrido (y sin impulso) vuelve a su sitio.
 */
class DragDismissLayout(context: Context) : LinearLayout(context) {
    /** Se llama una vez, al terminar la animación de salida. */
    var onDismiss: () -> Unit = {}

    /** Contenido desplazable: mientras no esté arriba, el gesto es suyo. */
    var scrollable: View? = null

    /** > 0: solo arrastra un gesto que empiece en esta franja superior (px). */
    var dragZonePx: Int = 0

    /** Ventana cuyo oscurecido se aclara mientras se arrastra. */
    var dimWindow: Window? = null
    var baseDim = 0.45f

    private val config = ViewConfiguration.get(context)
    private val slop = config.scaledTouchSlop
    private val flingVelocity = 900f * resources.displayMetrics.density
    private var startX = 0f
    private var startY = 0f
    private var lastY = 0f
    private var candidate = false
    private var dragging = false
    private var dismissed = false
    private var tracker: VelocityTracker? = null
    private var settle: ValueAnimator? = null

    private fun inZone(event: MotionEvent): Boolean =
        dragZonePx <= 0 || event.y <= dragZonePx

    private fun contentAtTop(): Boolean = scrollable?.canScrollVertically(-1) != true

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (dismissed) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX
                startY = event.rawY
                lastY = event.rawY
                dragging = false
                candidate = inZone(event)
                settle?.cancel()
                tracker?.recycle()
                tracker = VelocityTracker.obtain().apply { addMovement(event) }
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(event)
                if (!candidate || event.pointerCount > 1) return false
                val dy = event.rawY - startY
                val dx = event.rawX - startX
                if (dy > slop && dy > abs(dx) * 1.4f && (dragZonePx > 0 || contentAtTop())) {
                    beginDrag(event)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> recycle()
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (dismissed) return true
        tracker?.addMovement(event)
        when (event.actionMasked) {
            // Toques en zonas sin hijo que los consuma (asa, márgenes): seguir el gesto.
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX
                startY = event.rawY
                lastY = event.rawY
                candidate = inZone(event)
                if (tracker == null) tracker = VelocityTracker.obtain().apply { addMovement(event) }
                return candidate
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    val dy = event.rawY - startY
                    if (candidate && dy > slop && dy > abs(event.rawX - startX) * 1.4f) beginDrag(event) else return candidate
                }
                translationY = (translationY + event.rawY - lastY).coerceAtLeast(0f)
                lastY = event.rawY
                updateDim()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) release() else performClick()
                recycle()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) animateTo(0f) {}
                recycle()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    private fun beginDrag(event: MotionEvent) {
        dragging = true
        lastY = event.rawY
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun release() {
        dragging = false
        val velocity = tracker?.let { it.computeCurrentVelocity(1000); it.yVelocity } ?: 0f
        val travel = height.coerceAtLeast(1)
        if (translationY > travel * 0.33f || velocity > flingVelocity) dismissAnimated()
        else animateTo(0f) {}
    }

    /** Cierre con la misma animación que el gesto (también desde un botón). */
    fun dismissAnimated() {
        if (dismissed) return
        dismissed = true
        // Sin la animación de la ventana: la hoja ya sale deslizándose.
        dimWindow?.setWindowAnimations(0)
        animateTo(height.toFloat().coerceAtLeast(1f)) { onDismiss() }
    }

    private fun animateTo(target: Float, end: () -> Unit) {
        settle?.cancel()
        settle = ValueAnimator.ofFloat(translationY, target).apply {
            duration = (160L + abs(target - translationY) / resources.displayMetrics.density * 0.6f).toLong().coerceAtMost(320L)
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                translationY = it.animatedValue as Float
                updateDim()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) { if (!cancelled) end() }
            })
            start()
        }
    }

    private fun updateDim() {
        val window = dimWindow ?: return
        val progress = (translationY / height.coerceAtLeast(1)).coerceIn(0f, 1f)
        runCatching { window.setDimAmount(baseDim * (1f - progress)) }
    }

    private fun recycle() {
        tracker?.recycle()
        tracker = null
        candidate = false
    }

    override fun onDetachedFromWindow() {
        settle?.cancel()
        recycle()
        super.onDetachedFromWindow()
    }
}
