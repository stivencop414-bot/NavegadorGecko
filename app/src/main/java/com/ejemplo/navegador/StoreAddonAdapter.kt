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
    private val installedIds: Set<String>,
    private val onInstall: (StoreAddon) -> Unit,
    private val onDetails: (StoreAddon) -> Unit
) : BaseAdapter() {

    override fun getCount(): Int =
        items.size

    override fun getItem(
        position: Int
    ): StoreAddon =
        items[position]

    override fun getItemId(
        position: Int
    ): Long =
        position.toLong()

    override fun getView(
        position: Int,
        convertView: View?,
        parent: ViewGroup
    ): View {
        val addon =
            getItem(position)

        val installed =
            addon.id.isNotBlank() &&
                installedIds.contains(
                    addon.id
                )

        val palette =
            ThemeManager.palette(
                activity
            )

        val density =
            activity.resources
                .displayMetrics
                .density

        val card =
            LinearLayout(activity).apply {
                orientation =
                    LinearLayout.VERTICAL

                background =
                    ThemeManager.rounded(
                        activity,
                        palette.surface,
                        20f
                    )

                setPadding(
                    (13 * density).toInt(),
                    (13 * density).toInt(),
                    (13 * density).toInt(),
                    (11 * density).toInt()
                )

                layoutParams =
                    AbsListView.LayoutParams(
                        ViewGroup.LayoutParams
                            .MATCH_PARENT,
                        ViewGroup.LayoutParams
                            .WRAP_CONTENT
                    )
            }

        val top =
            LinearLayout(activity).apply {
                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL
            }

        val icon =
            ImageView(activity).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        (60 * density)
                            .toInt(),
                        (60 * density)
                            .toInt()
                    )

                scaleType =
                    ImageView.ScaleType
                        .CENTER_INSIDE

                background =
                    ThemeManager.rounded(
                        activity,
                        palette.elevated,
                        16f
                    )

                setPadding(
                    (7 * density).toInt(),
                    (7 * density).toInt(),
                    (7 * density).toInt(),
                    (7 * density).toInt()
                )
            }

        RemoteImageLoader.load(
            addon.iconUrl,
            icon
        )

        val info =
            LinearLayout(activity).apply {
                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    (12 * density).toInt(),
                    0,
                    0,
                    0
                )

                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams
                            .WRAP_CONTENT,
                        1f
                    )
            }

        val title =
            TextView(activity).apply {
                text = addon.name
                textSize = 16.5f

                typeface =
                    Typeface.DEFAULT_BOLD

                setTextColor(
                    palette.text
                )

                maxLines = 2
            }

        val author =
            TextView(activity).apply {
                text =
                    if (
                        addon.author
                            .isNotBlank()
                    ) {
                        "Por ${addon.author}"
                    } else {
                        "Mozilla Add-ons"
                    }

                textSize = 12.3f

                setTextColor(
                    palette.muted
                )

                maxLines = 1
            }

        val meta =
            TextView(activity).apply {
                val rating =
                    if (
                        addon.rating > 0.0
                    ) {
                        String.format(
                            Locale.US,
                            "%.1f ★",
                            addon.rating
                        )
                    } else {
                        "Sin puntuación"
                    }

                val users =
                    if (
                        addon.users > 0
                    ) {
                        "%,d usuarios"
                            .format(
                                addon.users
                            )
                    } else {
                        "Usuarios no disponibles"
                    }

                text =
                    "$rating  ·  $users"

                textSize = 12.2f

                setTextColor(
                    palette.muted
                )

                maxLines = 1
            }

        info.addView(title)
        info.addView(author)
        info.addView(meta)

        if (installed) {
            info.addView(
                TextView(activity)
                    .apply {
                        text =
                            "✓ Ya instalada"

                        textSize = 12.4f

                        setTextColor(
                            palette.accent
                        )

                        typeface =
                            Typeface.DEFAULT_BOLD
                    }
            )
        }

        top.addView(icon)
        top.addView(info)

        val summary =
            TextView(activity).apply {
                text = addon.summary
                textSize = 13.5f

                setTextColor(
                    palette.text
                )

                maxLines = 3

                setPadding(
                    0,
                    (9 * density).toInt(),
                    0,
                    (8 * density).toInt()
                )
            }

        val actions =
            LinearLayout(activity).apply {
                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.END
            }

        val details =
            Button(activity).apply {
                text = "Ver detalles"
                setAllCaps(false)

                ThemeManager.styleButton(
                    activity,
                    this
                )

                setOnClickListener {
                    onDetails(addon)
                }
            }

        val install =
            Button(activity).apply {
                text =
                    if (installed) {
                        "Instalada"
                    } else {
                        "Instalar"
                    }

                isEnabled =
                    !installed

                alpha =
                    if (installed) {
                        0.65f
                    } else {
                        1f
                    }

                setAllCaps(false)

                ThemeManager.styleButton(
                    activity,
                    this,
                    primary = true
                )

                setOnClickListener {
                    if (!installed) {
                        onInstall(addon)
                    }
                }
            }

        actions.addView(details)

        actions.addView(
            install,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams
                    .WRAP_CONTENT,
                ViewGroup.LayoutParams
                    .WRAP_CONTENT
            ).apply {
                marginStart =
                    (7 * density)
                        .toInt()
            }
        )

        card.setOnClickListener {
            onDetails(addon)
        }

        card.addView(top)
        card.addView(summary)
        card.addView(actions)

        return card
    }
}
