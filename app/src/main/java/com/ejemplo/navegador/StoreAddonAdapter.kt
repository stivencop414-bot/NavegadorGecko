package com.ejemplo.navegador

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

class StoreAddonAdapter(
    private val activity: Activity,
    private val items: List<StoreAddon>,
    private val onInstall: (StoreAddon) -> Unit,
    private val onDetails: (StoreAddon) -> Unit
) : BaseAdapter() {
    override fun getCount(): Int = items.size
    override fun getItem(position: Int): StoreAddon = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val addon = getItem(position)
        val p = ThemeManager.palette(activity)
        val d = activity.resources.displayMetrics.density

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeManager.rounded(activity, p.surface, 20f)
            setPadding((12*d).toInt(), (12*d).toInt(), (12*d).toInt(), (10*d).toInt())
            layoutParams = AbsListView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val top = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val icon = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams((62*d).toInt(), (62*d).toInt())
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = ThemeManager.rounded(activity, p.elevated, 16f)
            setPadding((7*d).toInt(), (7*d).toInt(), (7*d).toInt(), (7*d).toInt())
        }
        RemoteImageLoader.load(addon.iconUrl, icon)

        val info = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12*d).toInt(), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val title = TextView(activity).apply {
            text = addon.name
            textSize = 16.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(p.text)
            maxLines = 2
        }

        val author = TextView(activity).apply {
            text = addon.author.ifBlank { "Mozilla Add-ons" }
            textSize = 12.5f
            setTextColor(p.muted)
            maxLines = 1
        }

        val meta = TextView(activity).apply {
            val ratingText =
                if (addon.rating > 0.0) String.format(Locale.US, "%.1f ★", addon.rating)
                else "Sin puntuación"
            val usersText =
                if (addon.users > 0) "%,d usuarios".format(addon.users)
                else "Usuarios no disponibles"
            text = "$ratingText  ·  $usersText"
            textSize = 12.5f
            setTextColor(p.muted)
            maxLines = 1
        }

        info.addView(title)
        info.addView(author)
        info.addView(meta)
        top.addView(icon)
        top.addView(info)

        val summary = TextView(activity).apply {
            text = addon.summary
            textSize = 13.5f
            setTextColor(p.text)
            maxLines = 3
            setPadding(0, (9*d).toInt(), 0, (8*d).toInt())
        }

        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        val details = Button(activity).apply {
            text = "Detalles"
            setAllCaps(false)
            ThemeManager.styleButton(activity, this)
            setOnClickListener { onDetails(addon) }
        }

        val install = Button(activity).apply {
            text = "Instalar"
            setAllCaps(false)
            ThemeManager.styleButton(activity, this, primary = true)
            setOnClickListener { onInstall(addon) }
        }

        actions.addView(details)
        actions.addView(
            install,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (7*d).toInt() }
        )

        card.setOnClickListener { onDetails(addon) }
        card.addView(top)
        card.addView(summary)
        card.addView(actions)
        return card
    }
}
