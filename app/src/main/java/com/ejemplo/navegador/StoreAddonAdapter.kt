package com.ejemplo.navegador

import android.app.Activity
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils

class StoreAddonAdapter(
    private val activity: Activity,
    private val items: List<StoreAddon>,
    private val installedIds: Set<String>,
    private val onInstall: (StoreAddon) -> Unit,
    private val onDetails: (StoreAddon) -> Unit
) : BaseAdapter() {

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): StoreAddon = items[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val addon = items[position]
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
            if (addon.iconUrl.isNotBlank()) {
                RemoteImageLoader.load(addon.iconUrl, icon)
            }
        }

        val titleCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = (10 * d).toInt()
            }
        }

        val name = TextView(activity).apply {
            text = addon.name
            textSize = 15.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(p.text)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        val desc = TextView(activity).apply {
            text = addon.summary
            textSize = 12.5f
            setTextColor(p.muted)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }

        titleCol.addView(name)
        titleCol.addView(desc)

        val isInstalled = installedIds.contains(addon.id)
        val btn = Button(activity).apply {
            text = if (isInstalled) "Instalada" else "Instalar"
            isEnabled = !isInstalled
            textSize = 13f
            val hPad = (14 * d).toInt()
            setPadding(hPad, 0, hPad, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (38 * d).toInt()
            )
            ThemeManager.styleButton(activity, this, !isInstalled)
            setOnClickListener { onInstall(addon) }
        }

        header.addView(icon)
        header.addView(titleCol)
        header.addView(btn)
        root.addView(header)

        root.setOnClickListener { onDetails(addon) }

        return root
    }
}
