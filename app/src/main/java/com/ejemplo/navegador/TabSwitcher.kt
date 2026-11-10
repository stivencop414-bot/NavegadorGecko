package com.ejemplo.navegador

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Selector de pestañas a pantalla completa: cuadrícula de tarjetas con
 * miniatura, separado en pestañas normales y privadas.
 */
object TabSwitcher {
    private fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun show(
        activity: Activity,
        onNewTab: (private: Boolean) -> Unit
    ) {
        val p = ThemeManager.palette(activity)
        val dialog = Dialog(activity, ThemeManager.themeRes(activity))
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        var showPrivate = TabManager.activeTab()?.isPrivate == true
        // null = todas; "" = sin grupo; cualquier otro valor = nombre del grupo.
        var selectedGroup: String? = null

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.background)
        }

        // --- Barra superior
        val top = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 8f), dp(activity, 8f), dp(activity, 16f), dp(activity, 4f))
        }
        val back = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_nav_back)
            contentDescription = activity.getString(R.string.back)
            layoutParams = LinearLayout.LayoutParams(dp(activity, 48f), dp(activity, 48f))
            ThemeManager.styleIconButton(activity, this)
            setOnClickListener { dialog.dismiss() }
        }
        val title = TextView(activity).apply {
            textSize = 22f
            setTextColor(p.text)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(activity, 4f), 0, 0, 0)
        }
        val newTab = Button(activity).apply {
            text = "+  Nueva"
            textSize = 14f
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(activity, 18f), 0, dp(activity, 18f), 0)
            ThemeManager.styleButton(activity, this, primary = true)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(activity, 40f)
            )
            setOnClickListener {
                dialog.dismiss()
                onNewTab(showPrivate)
                TabManager.activeTab()?.let { TabManager.moveToGroup(it.id, selectedGroup) }
            }
        }
        top.addView(back)
        top.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(newTab)
        root.addView(top)

        // --- Selector Normales / Privadas
        val segments = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ThemeManager.rounded(activity, p.elevated, 22f)
            setPadding(dp(activity, 4f), dp(activity, 4f), dp(activity, 4f), dp(activity, 4f))
        }
        val normalSeg = segmentButton(activity, "Pestañas")
        val privateSeg = segmentButton(activity, "Privadas")
        segments.addView(normalSeg, LinearLayout.LayoutParams(0, dp(activity, 36f), 1f))
        segments.addView(privateSeg, LinearLayout.LayoutParams(0, dp(activity, 36f), 1f))
        root.addView(segments, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(dp(activity, 16f), dp(activity, 6f), dp(activity, 16f), dp(activity, 8f))
        })

        // Grupos: píldoras con color y contador (toca para filtrar, mantén pulsado para editar).
        val groupBar = FrameLayout(activity)
        root.addView(groupBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 46f)).apply {
            bottomMargin = dp(activity, 2f)
        })

        // --- Cuadrícula
        val empty = TextView(activity).apply {
            gravity = Gravity.CENTER
            textSize = 15f
            setTextColor(p.muted)
            setPadding(dp(activity, 32f), dp(activity, 32f), dp(activity, 32f), dp(activity, 32f))
        }
        val grid = GridView(activity).apply {
            numColumns = 2
            horizontalSpacing = dp(activity, 12f)
            verticalSpacing = dp(activity, 12f)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setPadding(dp(activity, 16f), dp(activity, 8f), dp(activity, 16f), dp(activity, 24f))
            clipToPadding = false
            selector = ColorDrawable(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
        }
        val body = FrameLayout(activity).apply {
            addView(grid)
            addView(empty)
        }
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val closeAll = Button(activity).apply {
            text = "Cerrar todas"; contentDescription = "Cerrar todas las pestañas del grupo visible"
            textSize = 14f; minHeight = 0; minimumHeight = 0
            ThemeManager.styleButton(activity, this, danger = true)
        }
        root.addView(closeAll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 48f)).apply {
            setMargins(dp(activity, 16f), dp(activity, 4f), dp(activity, 16f), dp(activity, 14f))
        })
        lateinit var refresh: () -> Unit
        val adapter = object : BaseAdapter() {
            var items: List<BrowserTab> = emptyList()
            override fun getCount() = items.size
            override fun getItem(position: Int) = items[position]
            override fun getItemId(position: Int) = items[position].id.hashCode().toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                tabCard(activity, items[position], onOpen = {
                    TabManager.switchTo(it.id)
                    dialog.dismiss()
                }, onClose = {
                    val lastNormal = !showPrivate && items.size == 1
                    TabManager.closeTab(it.id)
                    if (lastNormal) dialog.dismiss() else refresh()
                }).apply {
                    setOnLongClickListener {
                        moveSheet(activity, items[position]) { refresh() }
                        true
                    }
                }
        }
        grid.adapter = adapter

        refresh = {
            val all = TabManager.allTabs()
            val visibleMode = all.filter { it.isPrivate == showPrivate }
            val names = visibleMode.mapNotNull { it.groupName }.distinct().sorted()
            if (selectedGroup != null && selectedGroup != "" && selectedGroup !in names) selectedGroup = null
            if (selectedGroup == "" && (names.isEmpty() || visibleMode.none { it.groupName == null })) selectedGroup = null
            val chips = mutableListOf<View>()
            chips += NexoUi.chip(activity, "Todas · ${visibleMode.size}", selectedGroup == null) { selectedGroup = null; refresh() }
            names.forEach { name ->
                val count = visibleMode.count { it.groupName == name }
                chips += NexoUi.chip(activity, "$name · $count", selectedGroup == name, TabGroupRules.color(name)) {
                    selectedGroup = name; refresh()
                }.apply {
                    setOnLongClickListener { groupSheet(activity, name, showPrivate) { renamed -> selectedGroup = renamed; refresh() }; true }
                }
            }
            val ungrouped = visibleMode.count { it.groupName == null }
            if (names.isNotEmpty() && ungrouped > 0) {
                chips += NexoUi.chip(activity, "Sin grupo · $ungrouped", selectedGroup == "") { selectedGroup = ""; refresh() }
            }
            chips += NexoUi.chip(activity, "+  Grupo", false) {
                NexoUi.askText(activity, "Nuevo grupo", "Nombre del grupo", action = "Crear") { value ->
                    val name = TabGroupRules.name(value) ?: return@askText
                    val tab = TabManager.activeTab()?.takeIf { it.isPrivate == showPrivate }
                        ?: adapter.items.firstOrNull() ?: TabManager.createTab(BrowserPrefs.homePage(activity), showPrivate, false)
                    TabManager.moveToGroup(tab.id, name)
                    selectedGroup = name; refresh()
                }
            }
            groupBar.removeAllViews()
            groupBar.addView(NexoUi.chipRow(activity, chips, sidePaddingDp = 16f), FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
            adapter.items = all.filter { it.isPrivate == showPrivate && (selectedGroup == null || it.groupName.orEmpty() == selectedGroup) }
            closeAll.text = when (selectedGroup) { null -> "Cerrar todas"; "" -> "Cerrar pestañas sin grupo"; else -> "Cerrar grupo «$selectedGroup»" }
            closeAll.isEnabled = adapter.items.isNotEmpty()
            adapter.notifyDataSetChanged()
            title.text = if (adapter.items.size == 1) "1 pestaña" else "${adapter.items.size} pestañas"
            empty.text = if (showPrivate) {
                "No hay pestañas privadas.\nLas pestañas privadas no guardan historial ni cookies."
            } else "No hay pestañas abiertas."
            empty.visibility = if (adapter.items.isEmpty()) View.VISIBLE else View.GONE
            styleSegment(activity, normalSeg, !showPrivate)
            styleSegment(activity, privateSeg, showPrivate)
            privateSeg.text = "Privadas (${all.count { it.isPrivate }})"
        }
        closeAll.setOnClickListener {
            val ids = adapter.items.map { it.id }
            android.app.AlertDialog.Builder(activity).setTitle("¿Cerrar ${ids.size} pestañas?")
                .setNegativeButton("Cancelar", null).setPositiveButton("Cerrar") { _, _ ->
                    ids.forEach { TabManager.closeTab(it) }; dialog.dismiss()
                }.show()
        }
        normalSeg.setOnClickListener { if (showPrivate) { showPrivate = false; selectedGroup = null; refresh() } }
        privateSeg.setOnClickListener { if (!showPrivate) { showPrivate = true; selectedGroup = null; refresh() } }
        refresh()

        dialog.setContentView(root)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(p.background))
            statusBarColor = p.background
            navigationBarColor = p.background
            setWindowAnimations(android.R.style.Animation_Activity)
        }
        dialog.show()
    }

    /** Hoja para mover una pestaña de grupo (mantener pulsada su tarjeta). */
    private fun moveSheet(activity: Activity, tab: BrowserTab, done: () -> Unit) {
        val names = TabManager.allTabs().filter { it.isPrivate == tab.isPrivate }.mapNotNull { it.groupName }.distinct().sorted()
        lateinit var sheet: Dialog
        val body = NexoUi.column(activity)
        val chips = mutableListOf<View>()
        chips += NexoUi.chip(activity, "Sin grupo", tab.groupName == null) { TabManager.moveToGroup(tab.id, null); sheet.dismiss(); done() }
        names.forEach { name ->
            chips += NexoUi.chip(activity, name, tab.groupName == name, TabGroupRules.color(name)) {
                TabManager.moveToGroup(tab.id, name); sheet.dismiss(); done()
            }
        }
        body.addView(NexoUi.section(activity, "Mover a"))
        body.addView(NexoUi.chipRow(activity, chips))
        body.addView(NexoUi.actionRow(activity, R.drawable.ic_nav_add, "Nuevo grupo con esta pestaña") {
            sheet.dismiss()
            NexoUi.askText(activity, "Nuevo grupo", "Nombre del grupo", action = "Crear y mover") { value ->
                TabManager.moveToGroup(tab.id, value); done()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(activity, 10f)
        })
        body.addView(NexoUi.actionRow(activity, R.drawable.ic_menu_close, "Cerrar pestaña", danger = true) {
            sheet.dismiss(); TabManager.closeTab(tab.id); done()
        })
        sheet = NexoSheets.show(activity, NexoSheets.column(activity,
            NexoSheets.header(activity, tab.title.ifBlank { "Nueva pestaña" }, tab.groupName?.let { "Grupo: $it" } ?: "Sin grupo"), body))
    }

    /** Hoja de un grupo: renombrar, desagrupar o cerrar sus pestañas. */
    private fun groupSheet(activity: Activity, name: String, privateMode: Boolean, done: (String?) -> Unit) {
        val count = TabManager.allTabs().count { it.isPrivate == privateMode && it.groupName == name }
        val actions = listOf(
            SheetAction(R.drawable.ic_menu_copy, "Renombrar grupo") {
                NexoUi.askText(activity, "Renombrar grupo", "Nombre del grupo", name) { value ->
                    val clean = TabGroupRules.name(value) ?: return@askText
                    TabManager.renameGroup(name, clean, privateMode); done(clean)
                }
            },
            SheetAction(R.drawable.ic_tab_tools, "Desagrupar y conservar pestañas") {
                TabManager.renameGroup(name, null, privateMode); done(null)
            },
            SheetAction(R.drawable.ic_menu_close, "Cerrar las $count pestañas", danger = true) {
                android.app.AlertDialog.Builder(activity).setTitle("¿Cerrar el grupo «$name»?")
                    .setMessage("Se cerrarán $count pestañas.")
                    .setNegativeButton("Cancelar", null).setPositiveButton("Cerrar") { _, _ ->
                        TabManager.allTabs().filter { it.isPrivate == privateMode && it.groupName == name }.map { it.id }
                            .forEach { TabManager.closeTab(it) }
                        done(null)
                    }.show()
            }
        )
        lateinit var sheet: Dialog
        sheet = NexoSheets.show(activity, NexoSheets.column(activity,
            NexoSheets.header(activity, name, if (count == 1) "1 pestaña" else "$count pestañas"),
            NexoSheets.list(activity, actions) { sheet.dismiss() }))
    }

    private fun segmentButton(activity: Activity, label: String): TextView =
        TextView(activity).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 14f
            isClickable = true
        }

    private fun styleSegment(activity: Activity, view: TextView, selected: Boolean) {
        val p = ThemeManager.palette(activity)
        view.background = if (selected) ThemeManager.rounded(activity, p.surface, 18f) else null
        view.setTextColor(if (selected) p.text else p.muted)
        view.typeface = if (selected) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
        view.elevation = if (selected) dp(activity, 1f).toFloat() else 0f
    }

    private fun tabCard(
        activity: Activity,
        tab: BrowserTab,
        onOpen: (BrowserTab) -> Unit,
        onClose: (BrowserTab) -> Unit
    ): View {
        val p = ThemeManager.palette(activity)
        val active = tab.id == TabManager.activeTab()?.id

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeManager.rounded(activity, p.surface, 20f)
            // El borde va por encima del contenido: la miniatura ya no lo tapa
            // ni deja esquinas cuadradas en la parte inferior.
            foreground = ThemeManager.rounded(
                activity,
                Color.TRANSPARENT,
                20f,
                if (active) p.accent else tab.groupName?.let { androidx.core.graphics.ColorUtils.setAlphaComponent(TabGroupRules.color(it), 150) } ?: p.outline,
                if (active) 2.5f else 1f
            )
            clipToOutline = true
            elevation = dp(activity, 1f).toFloat()
            setOnClickListener { onOpen(tab) }
        }

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 12f), dp(activity, 4f), dp(activity, 2f), dp(activity, 2f))
        }
        header.addView(TextView(activity).apply {
            text = (if(tab.burnerId != null) "🔥 " else "") + tab.title.ifBlank { "Nueva pestaña" }
            textSize = 13f
            tab.groupName?.let { group ->
                setCompoundDrawablesRelativeWithIntrinsicBounds(android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(TabGroupRules.color(group)); setSize(dp(activity, 8f), dp(activity, 8f))
                }, null, null, null)
                compoundDrawablePadding = dp(activity, 6f)
                contentDescription = "${tab.title} · grupo $group"
            }
            setTextColor(p.text)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(ImageButton(activity).apply {
            setImageResource(R.drawable.ic_menu_close)
            contentDescription = "Cerrar pestaña: ${tab.title}"
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(activity, 10f), dp(activity, 10f), dp(activity, 10f), dp(activity, 10f))
            ThemeManager.styleIconButton(activity, this)
            imageTintList = ColorStateList.valueOf(p.muted)
            setOnClickListener { onClose(tab) }
        }, LinearLayout.LayoutParams(dp(activity, 48f), dp(activity, 48f)))
        card.addView(header)

        val previewHeight = dp(activity, 190f)
        val hasPreview = !tab.isPrivate && !BrowserPrefs.isNexoHome(tab.url)
        val host = runCatching { android.net.Uri.parse(tab.url).host }.getOrNull()
            ?.removePrefix("www.").orEmpty()

        // Marcador (logo + dominio) debajo; la miniatura lo cubre al cargar.
        val preview = FrameLayout(activity).apply { setBackgroundColor(p.accentSoft) }
        preview.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(ImageView(activity).apply {
                setImageResource(if (tab.isPrivate) R.drawable.ic_menu_private else R.drawable.ic_nexo_logo)
                if (tab.isPrivate) imageTintList = ColorStateList.valueOf(p.accent)
                layoutParams = LinearLayout.LayoutParams(dp(activity, 40f), dp(activity, 40f))
            })
            addView(TextView(activity).apply {
                text = if (BrowserPrefs.isNexoHome(tab.url)) "Página de inicio" else host.ifBlank { tab.url }
                textSize = 12f
                setTextColor(p.muted)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setPadding(dp(activity, 8f), dp(activity, 8f), dp(activity, 8f), 0)
            })
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        if (hasPreview) {
            // Decodificación en segundo plano con caché: el hilo principal no
            // lee JPEG del disco al abrir o desplazar el selector.
            val image = TopCropImageView(activity).apply { tag = tab.id }
            fun show(bitmap: android.graphics.Bitmap) {
                image.setBackgroundColor(p.elevated)
                image.setImageBitmap(bitmap)
            }
            TabPreviewStore.cached(tab.id)?.let { show(it) } ?: TabPreviewStore.loadAsync(activity, tab.id) { bitmap ->
                if (bitmap != null && image.tag == tab.id) show(bitmap)
            }
            preview.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        card.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, previewHeight))

        return card
    }
}

/**
 * Muestra la parte superior de la miniatura (como un navegador real).
 * La app usa Activity del framework (no AppCompat), así que extender
 * ImageView directamente es lo correcto.
 */
@android.annotation.SuppressLint("AppCompatCustomView")
private class TopCropImageView(context: Context) : ImageView(context) {
    init {
        scaleType = ScaleType.MATRIX
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateMatrix()
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        updateMatrix()
    }

    private fun updateMatrix() {
        val d = drawable ?: return
        if (width == 0 || d.intrinsicWidth <= 0) return
        val scale = width.toFloat() / d.intrinsicWidth.toFloat()
        imageMatrix = Matrix().apply { setScale(scale, scale) }
    }
}
