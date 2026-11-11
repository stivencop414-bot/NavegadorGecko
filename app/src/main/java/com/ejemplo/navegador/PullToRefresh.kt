package com.ejemplo.navegador

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.PanZoomController
import kotlin.math.abs
import kotlin.math.min

/**
 * GeckoView que pregunta a Gecko, al tocar, si la página está arriba del todo
 * y si el gesto lo maneja el navegador (no un mapa, juego o carrusel).
 */
class NexoGeckoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GeckoView(context, attrs) {
    /** true solo cuando un deslizamiento hacia abajo debe recargar la página. */
    @Volatile var canPullToRefresh = false
        private set

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return super.onTouchEvent(event)
        canPullToRefresh = false
        onTouchEventForDetailResult(event).accept { detail ->
            if (detail == null) return@accept
            val handledByBrowser = detail.handledResult() == PanZoomController.INPUT_RESULT_HANDLED
            val canScrollUp = detail.scrollableDirections() and PanZoomController.SCROLLABLE_FLAG_TOP != 0
            val canOverscroll = detail.overscrollDirections() and PanZoomController.OVERSCROLL_FLAG_VERTICAL != 0
            canPullToRefresh = handledByBrowser && !canScrollUp && canOverscroll
        }
        return true
    }
}

/**
 * Contenedor del navegador con "deslizar hacia abajo para recargar": un
 * círculo con el icono de recarga baja con el dedo y, al soltarlo pasado el
 * umbral, recarga la pestaña.
 */
class PullRefreshLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    var enabledForPage: () -> Boolean = { true }
    var onRefresh: () -> Unit = {}

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val threshold = 76f * density
    private val maxPull = 120f * density
    private val size = (40 * density).toInt()
    private var downX = 0f
    private var downY = 0f
    private var pulling = false
    private var refreshing = false
    private var animator: ValueAnimator? = null

    private val indicator = ImageView(context).apply {
        setImageResource(R.drawable.ic_nav_reload)
        scaleType = ImageView.ScaleType.CENTER
        elevation = 6f * density
        alpha = 0f
        visibility = GONE
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        // Siempre por encima de la página y de los controles flotantes.
        addView(indicator, LayoutParams(size, size, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        indicator.translationY = -size.toFloat()
    }

    fun applyPalette(surface: Int, accent: Int, outline: Int) {
        indicator.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(surface)
            setStroke((density).toInt().coerceAtLeast(1), outline)
        }
        indicator.imageTintList = ColorStateList.valueOf(accent)
    }

    /** La página terminó de cargar: oculta el indicador si estaba girando. */
    fun finishRefresh() {
        if (!refreshing) return
        refreshing = false
        hideIndicator()
    }

    private fun geckoView(): NexoGeckoView? =
        (0 until childCount).map { getChildAt(it) }.firstOrNull { it is NexoGeckoView } as? NexoGeckoView

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                pulling = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (pulling) return true
                if (refreshing || event.pointerCount > 1 || !enabledForPage()) return false
                val view = geckoView() ?: return false
                val dy = event.y - downY
                val dx = event.x - downX
                if (dy > touchSlop * 2 && dy > abs(dx) * 1.6f && view.canPullToRefresh) {
                    pulling = true
                    downY = event.y
                    animator?.cancel()
                    indicator.visibility = VISIBLE
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!pulling) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> showPull(event.y - downY)
            MotionEvent.ACTION_UP -> {
                pulling = false
                val distance = dampen(event.y - downY)
                if (distance >= threshold) startRefresh() else hideIndicator()
            }
            MotionEvent.ACTION_CANCEL -> {
                pulling = false
                hideIndicator()
            }
        }
        return true
    }

    /** Resistencia progresiva: el círculo baja menos que el dedo. */
    private fun dampen(raw: Float): Float {
        if (raw <= 0f) return 0f
        return min(maxPull, raw * 0.55f)
    }

    private fun showPull(raw: Float) {
        val distance = dampen(raw)
        val progress = (distance / threshold).coerceIn(0f, 1f)
        indicator.translationY = distance - size * 0.5f
        indicator.alpha = 0.35f + progress * 0.65f
        indicator.rotation = progress * 270f
        val scale = 0.6f + progress * 0.4f
        indicator.scaleX = scale
        indicator.scaleY = scale
        indicator.imageAlpha = if (progress >= 1f) 255 else 170
    }

    private fun startRefresh() {
        refreshing = true
        indicator.animate().translationY(threshold * 0.55f).scaleX(1f).scaleY(1f).alpha(1f).setDuration(150L).start()
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 700L
            repeatCount = ValueAnimator.INFINITE
            interpolator = null
            addUpdateListener { indicator.rotation = it.animatedValue as Float }
            start()
        }
        performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
        onRefresh()
        // Si la carga no avisa (página en caché), no dejar el indicador girando.
        postDelayed({ finishRefresh() }, 2500L)
    }

    private fun hideIndicator() {
        animator?.cancel()
        animator = null
        indicator.animate().translationY(-size.toFloat()).alpha(0f).scaleX(0.6f).scaleY(0.6f)
            .setDuration(180L).setInterpolator(DecelerateInterpolator())
            .withEndAction { if (!pulling && !refreshing) indicator.visibility = GONE }
            .start()
    }
}
