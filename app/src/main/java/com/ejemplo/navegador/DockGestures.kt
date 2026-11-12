package com.ejemplo.navegador

import android.content.Context
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * Barra inferior con gestos, como en los navegadores móviles actuales:
 *  - deslizar a la izquierda/derecha cambia a la pestaña siguiente/anterior;
 *  - deslizar hacia arriba abre el selector de pestañas.
 * Los toques normales siguen llegando a los botones y a la barra de dirección.
 */
class GestureDock @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    /** +1 = pestaña siguiente (deslizar a la izquierda), -1 = anterior. */
    var onSwipeTab: (Int) -> Unit = {}
    var onSwipeUp: () -> Unit = {}
    /** false mientras se escribe en la barra de dirección. */
    var gesturesEnabled: () -> Boolean = { true }

    private val config = ViewConfiguration.get(context)
    private val slop = config.scaledTouchSlop
    private val density = resources.displayMetrics.density
    private val distance = 56f * density
    private val flingVelocity = 700f * density
    private var startX = 0f
    private var startY = 0f
    private var tracking = false
    private var intercepted = false
    private var tracker: VelocityTracker? = null

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX
                startY = event.rawY
                intercepted = false
                tracking = gesturesEnabled()
                tracker?.recycle()
                tracker = if (tracking) VelocityTracker.obtain().apply { addMovement(event) } else null
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking || event.pointerCount > 1) return false
                tracker?.addMovement(event)
                val dx = event.rawX - startX
                val dy = event.rawY - startY
                // Solo gestos claros: horizontales o hacia arriba, más allá del margen de toque.
                if ((abs(dx) > slop * 2 && abs(dx) > abs(dy) * 1.5f) || (-dy > slop * 2 && -dy > abs(dx) * 1.5f)) {
                    intercepted = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> recycle()
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!intercepted) {
            // Toques sobre el fondo de la barra: también pueden iniciar un gesto.
            if (event.actionMasked == MotionEvent.ACTION_DOWN && gesturesEnabled()) {
                startX = event.rawX
                startY = event.rawY
                tracking = true
                intercepted = true
                tracker?.recycle()
                tracker = VelocityTracker.obtain().apply { addMovement(event) }
                return true
            }
            return super.onTouchEvent(event)
        }
        tracker?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_UP -> {
                val dx = event.rawX - startX
                val dy = event.rawY - startY
                val velocity = tracker?.let { it.computeCurrentVelocity(1000); it.xVelocity to it.yVelocity } ?: (0f to 0f)
                when {
                    abs(dx) > abs(dy) && (abs(dx) > distance || abs(velocity.first) > flingVelocity) && abs(dx) > slop -> {
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onSwipeTab(if (dx < 0) 1 else -1)
                    }
                    -dy > abs(dx) && (-dy > distance * 0.8f || -velocity.second > flingVelocity) && -dy > slop -> {
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onSwipeUp()
                    }
                }
                recycle()
            }
            MotionEvent.ACTION_CANCEL -> recycle()
        }
        return true
    }

    private fun recycle() {
        tracker?.recycle()
        tracker = null
        tracking = false
        intercepted = false
    }
}
