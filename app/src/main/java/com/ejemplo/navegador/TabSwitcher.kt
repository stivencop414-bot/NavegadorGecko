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
        var selectedGroup: String? = null
        var groupValues = listOf<String?>(null)
        var groupLabels = emptyList<String>()
        var selectingGroup = false

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

        val groupRow = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(activity,16f),0,dp(activity,16f),0) }
        val groups = android.widget.Spinner(activity).apply { contentDescription = "Filtrar grupo de pestañas"; ThemeManager.styleSpinner(activity,this) }
        groupRow.addView(groups,LinearLayout.LayoutParams(0,dp(activity,48f),1f))
        val groupEdit = Button(activity).apply { text = "Grupos"; ThemeManager.styleButton(activity,this) }
        groupRow.addView(groupEdit)
        root.addView(groupRow)

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
            ThemeManager.styleButton(activity, this, danger = true)
        }
        root.addView(closeAll)
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
                        val tab = items[position]
                        val names = TabManager.allTabs().filter { it.isPrivate == tab.isPrivate }.mapNotNull { it.groupName }.distinct().sorted()
                        val labels = listOf("Sin grupo", "Nuevo grupo…") + names
                        android.app.AlertDialog.Builder(activity).setTitle("Mover pestaña a grupo")
                            .setItems(labels.toTypedArray()) { _, choice ->
                                if (choice == 1) {
                                    val input = android.widget.EditText(activity).apply { hint = "Nombre del grupo"; ThemeManager.styleEdit(activity,this) }
                                    android.app.AlertDialog.Builder(activity).setTitle("Nuevo grupo").setView(input)
                                        .setNegativeButton("Cancelar",null).setPositiveButton("Mover") { _, _ ->
                                            TabManager.moveToGroup(tab.id,input.text.toString()); refresh()
                                        }.show()
                                } else { TabManager.moveToGroup(tab.id,if (choice == 0) null else names[choice-2]); refresh() }
                            }.show()
                        true
                    }
                }
        }
        grid.adapter = adapter

        refresh = {
            val all = TabManager.allTabs()
            val names = all.filter { it.isPrivate == showPrivate }.mapNotNull { it.groupName }.distinct().sorted()
            if (selectedGroup != null && selectedGroup != "" && selectedGroup !in names) selectedGroup = null
            val labels = listOf("Todas", "Sin grupo") + names
            groupValues = listOf(null, "") + names
            if (labels != groupLabels) {
                selectingGroup = true; groupLabels = labels
                groups.adapter = ThemeManager.spinnerAdapter(activity,labels)
                groups.setSelection(groupValues.indexOf(selectedGroup).coerceAtLeast(0))
                selectingGroup = false
            }
            adapter.items = all.filter { it.isPrivate == showPrivate && (selectedGroup == null || it.groupName.orEmpty() == selectedGroup) }
            closeAll.text = if (selectedGroup == null) "Cerrar todas" else "Cerrar grupo"
            closeAll.isEnabled = adapter.items.isNotEmpty()
            adapter.notifyDataSetChanged()
            title.text = "${adapter.items.size} pestañas"
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
        groups.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!selectingGroup && position in groupValues.indices && selectedGroup != groupValues[position]) {
                    selectedGroup = groupValues[position]; refresh()
                }
            }
        }
        groupEdit.setOnClickListener {
            val options = if (selectedGroup.isNullOrEmpty()) arrayOf("Nuevo grupo") else arrayOf("Nuevo grupo", "Renombrar grupo", "Quitar grupo y conservar pestañas")
            android.app.AlertDialog.Builder(activity).setTitle("Grupos de pestañas").setItems(options) { _, choice ->
                if (choice == 2) {
                    selectedGroup?.let { TabManager.renameGroup(it,null,showPrivate) }; selectedGroup = null; refresh()
                } else {
                    val input = android.widget.EditText(activity).apply { hint = "Nombre del grupo"; ThemeManager.styleEdit(activity,this); if(choice==1)setText(selectedGroup) }
                    android.app.AlertDialog.Builder(activity).setTitle(options[choice]).setView(input).setNegativeButton("Cancelar",null)
                        .setPositiveButton("Guardar") { _, _ ->
                            val name = TabGroupRules.name(input.text.toString())
                            if(name != null) {
                                if(choice==1) selectedGroup?.let { TabManager.renameGroup(it,name,showPrivate) }
                                else {
                                    val tab = TabManager.activeTab()?.takeIf { it.isPrivate == showPrivate }
                                        ?: adapter.items.firstOrNull() ?: TabManager.createTab(BrowserPrefs.homePage(activity),showPrivate,false)
                                    TabManager.moveToGroup(tab.id,name)
                                }
                                selectedGroup=name; refresh()
                            }
                        }.show()
                }
            }.show()
        }
        normalSeg.setOnClickListener { showPrivate = false; refresh() }
        privateSeg.setOnClickListener { showPrivate = true; refresh() }
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
                if (active) p.accent else p.outline,
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
