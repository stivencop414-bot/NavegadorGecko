package com.ejemplo.navegador

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.mozilla.geckoview.WebExtension

class InstalledExtensionAdapter(
    private val activity: Activity,
    private val items:
        List<WebExtension>
) : BaseAdapter() {

    override fun getCount(): Int =
        items.size

    override fun getItem(
        position: Int
    ): WebExtension =
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
        val extension =
            getItem(position)

        val meta =
            extension.metaData

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
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL

                background =
                    ThemeManager.rounded(
                        activity,
                        palette.surface,
                        18f
                    )

                setPadding(
                    (11 * density).toInt(),
                    (11 * density).toInt(),
                    (11 * density).toInt(),
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

        val icon =
            ImageView(activity).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        (52 * density)
                            .toInt(),
                        (52 * density)
                            .toInt()
                    )

                scaleType =
                    ImageView.ScaleType
                        .CENTER_INSIDE

                background =
                    ThemeManager.rounded(
                        activity,
                        palette.elevated,
                        14f
                    )

                setPadding(
                    (7 * density).toInt(),
                    (7 * density).toInt(),
                    (7 * density).toInt(),
                    (7 * density).toInt()
                )

                setImageResource(
                    R.drawable
                        .ic_extension_placeholder
                )

                tag = extension.id
            }

        runCatching {
            meta.icon
                .getBitmap(64)
                .accept(
                    { bitmap ->
                        if (
                            bitmap != null &&
                            icon.tag ==
                                extension.id
                        ) {
                            icon.setImageBitmap(
                                bitmap
                            )
                        }
                    },
                    { _ -> Unit }
                )
        }

        val textBox =
            LinearLayout(activity).apply {
                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    (12 * density).toInt(),
                    0,
                    0,
                    0
                )
            }

        val title =
            TextView(activity).apply {
                text =
                    meta.name
                        ?: extension.id

                textSize = 16f

                typeface =
                    Typeface.DEFAULT_BOLD

                setTextColor(
                    palette.text
                )

                maxLines = 2
            }

        val state =
            TextView(activity).apply {
                val status =
                    if (meta.enabled) {
                        "Activa"
                    } else {
                        "Desactivada"
                    }

                val privateText =
                    if (
                        meta
                            .allowedInPrivateBrowsing
                    ) {
                        " · Permitida en privado"
                    } else {
                        ""
                    }

                text =
                    "v${meta.version ?: "?"} · " +
                        status +
                        privateText

                textSize = 12.4f

                setTextColor(
                    if (meta.enabled) {
                        palette.accent
                    } else {
                        palette.muted
                    }
                )
            }

        val description =
            TextView(activity).apply {
                text =
                    meta.description
                        ?.takeIf {
                            it.isNotBlank()
                        }
                        ?: "Toca para administrar"

                textSize = 12.4f

                setTextColor(
                    palette.muted
                )

                maxLines = 2
            }

        textBox.addView(title)
        textBox.addView(state)
        textBox.addView(description)

        card.addView(icon)

        card.addView(
            textBox,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams
                    .WRAP_CONTENT,
                1f
            )
        )

        return card
    }
}
