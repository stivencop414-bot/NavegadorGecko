package com.ejemplo.navegador

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Typeface
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import org.mozilla.geckoview.WebExtension

/**
 * Tarjeta de extensión instalada: icono real, nombre, versión/autor,
 * etiquetas de estado e interruptor para activarla sin abrir menús.
 */
class InstalledExtensionAdapter(
    private val activity: Activity,
    private val items: List<WebExtension>,
    private val onToggle: (WebExtension, Boolean) -> Unit = { _, _ -> }
) : BaseAdapter() {

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): WebExtension = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    private fun dp(value: Float): Int = (value * activity.resources.displayMetrics.density).toInt()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val ext = items[position]
        val meta = ext.metaData
        val p = ThemeManager.palette(activity)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f), dp(14f), dp(10f), dp(14f))
            minimumHeight = dp(76f)
            background = ThemeManager.ripple(
                activity,
                ThemeManager.rounded(activity, p.surface, 20f, if (p.isDark) p.outline else null),
                20f
            )
            layoutParams = AbsListView.LayoutParams(
                AbsListView.LayoutParams.MATCH_PARENT,
                AbsListView.LayoutParams.WRAP_CONTENT
            )
            alpha = if (meta.enabled) 1f else 0.72f
        }

        val icon = ImageView(activity).apply {
            val pad = dp(9f)
            setPadding(pad, pad, pad, pad)
            background = ThemeManager.rounded(activity, p.elevated, 14f)
            setImageResource(R.drawable.ic_extension_placeholder)
            imageTintList = ColorStateList.valueOf(p.muted)
            layoutParams = LinearLayout.LayoutParams(dp(46f), dp(46f)).apply { marginEnd = dp(14f) }
        }
        bindIcon(ext, icon)

        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(activity).apply {
            text = meta.name ?: ext.id
            textSize = 15.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(p.text)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        texts.addView(TextView(activity).apply {
            text = listOfNotNull(
                meta.version?.takeIf { it.isNotBlank() }?.let { "v$it" },
                meta.creatorName?.takeIf { it.isNotBlank() }
            ).joinToString(" · ").ifBlank { ext.id }
            textSize = 12.5f
            setTextColor(p.muted)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2f), 0, 0)
        })

        val chips = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6f), 0, 0)
        }
        if (!meta.enabled) chips.addView(chip("Desactivada", p.elevated, p.muted))
        if (meta.allowedInPrivateBrowsing) chips.addView(chip("Privado", p.accentSoft, p.accent))
        if (meta.isRecommended) chips.addView(chip("Recomendada", p.accentSoft, p.accent))
        if (chips.childCount > 0) texts.addView(chips)

        val toggle = Switch(activity).apply {
            isChecked = meta.enabled
            contentDescription = if (meta.enabled) "Desactivar" else "Activar"
            ThemeManager.styleSwitch(activity, this)
            setOnCheckedChangeListener { _, checked -> onToggle(ext, checked) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8f) }
            // Que pulsar el interruptor no abra también el detalle.
            isFocusable = false
        }

        root.addView(icon)
        root.addView(texts)
        root.addView(toggle)
        root.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        return root
    }

    private fun chip(label: String, bg: Int, fg: Int): TextView =
        TextView(activity).apply {
            text = label
            textSize = 11f
            setTextColor(fg)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(8f), dp(3f), dp(8f), dp(3f))
            background = ThemeManager.rounded(activity, bg, 10f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6f) }
        }

    private fun bindIcon(ext: WebExtension, view: ImageView) {
        view.tag = ext.id
        icons.get(ext.id)?.let { show(view, it); return }
        ExtensionManager.loadIcon(ext, dp(48f)) { bitmap ->
            if (bitmap == null) return@loadIcon
            icons.put(ext.id, bitmap)
            activity.runOnUiThread { if (view.tag == ext.id) show(view, bitmap) }
        }
    }

    private fun show(view: ImageView, bitmap: Bitmap) {
        view.imageTintList = null
        view.setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
        view.setImageBitmap(bitmap)
    }

    companion object {
        /** Iconos ya cargados, compartidos entre pantallas. */
        private val icons = LruCache<String, Bitmap>(64)
    }
}
