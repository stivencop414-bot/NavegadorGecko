package com.ejemplo.navegador

import android.app.Activity
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import org.mozilla.geckoview.WebExtension

class InstalledExtensionAdapter(
    private val activity: Activity,
    private val items: List<WebExtension>
) : BaseAdapter() {

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): WebExtension = items[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val ext = items[position]
        val p = ThemeManager.palette(activity)
        val d = activity.resources.displayMetrics.density

        val root = (convertView as? LinearLayout) ?: LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * d).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = AbsListView.LayoutParams(
                AbsListView.LayoutParams.MATCH_PARENT,
                AbsListView.LayoutParams.WRAP_CONTENT
            )
        }
        root.removeAllViews()
        root.background = ThemeManager.rounded(
            activity,
            p.surface,
            16f,
            ColorUtils.setAlphaComponent(p.muted, 40)
        )

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val icon = ImageView(activity).apply {
            setImageResource(R.drawable.ic_extension_placeholder)
            val sz = (42 * d).toInt()
            val padIcon = (8 * d).toInt()
            setPadding(padIcon, padIcon, padIcon, padIcon)
            background = ThemeManager.rounded(activity, p.elevated, 12f)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                rightMargin = (14 * d).toInt()
            }
        }

        val titleCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val name = TextView(activity).apply {
            text = ext.metaData.name ?: ext.id
            textSize = 15.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(p.text)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        val versionPart = ext.metaData.version?.takeIf { it.isNotBlank() }?.let { "v$it · " } ?: ""
        val idView = TextView(activity).apply {
            text = "$versionPart${ext.id}"
            textSize = 12.5f
            setTextColor(p.muted)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        val statusBadge = TextView(activity).apply {
            val enabled = ext.metaData.enabled
            text = if (enabled) "Activa" else "Inactiva"
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (enabled) p.onAccent else p.muted)
            val hPad = (10 * d).toInt()
            val vPad = (5 * d).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            background = ThemeManager.rounded(
                activity,
                if (enabled) p.accent else p.elevated,
                10f
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = (10 * d).toInt()
            }
        }

        titleCol.addView(name)
        titleCol.addView(idView)

        header.addView(icon)
        header.addView(titleCol)
        header.addView(statusBadge)
        root.addView(header)

        return root
    }
}
