package com.ejemplo.navegador

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * Selector de pestañas a pantalla completa: cuadrícula de tarjetas con
 * miniatura, separado en pestañas normales y privadas.
 *
 * Las tarjetas se reciclan y solo se actualiza lo que cambia (sin
 * reconstruir la cuadrícula ni recargar miniaturas). Una tarjeta se cierra
 * deslizándola hacia un lado, con opción de deshacer.
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
        val metrics = activity.resources.displayMetrics
        val columns = if (metrics.widthPixels / metrics.density >= 600f) 3 else 2
        val grid = RecyclerView(activity).apply {
            layoutManager = GridLayoutManager(activity, columns)
            setPadding(dp(activity, 10f), dp(activity, 2f), dp(activity, 10f), dp(activity, 24f))
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            // Sin fundido al actualizar una tarjeta: la miniatura no parpadea.
            itemAnimator = DefaultItemAnimator().apply { supportsChangeAnimations = false }
            val gap = dp(activity, 6f)
            addItemDecoration(object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                    outRect.set(gap, gap, gap, gap)
                }
            })
        }
        val body = FrameLayout(activity).apply {
            addView(grid)
            addView(empty)
        }
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Aviso "Pestaña cerrada · Deshacer" tras cerrar una tarjeta.
        val undoText = TextView(activity).apply {
            textSize = 14f; setTextColor(p.text); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        val undoButton = TextView(activity).apply {
            text = "Deshacer"; textSize = 14f; gravity = Gravity.CENTER
            setTextColor(if (p.isDark) androidx.core.graphics.ColorUtils.blendARGB(p.accent, Color.WHITE, 0.45f) else p.accent)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(activity, 16f), 0, dp(activity, 16f), 0)
            minWidth = dp(activity, 48f)
            background = ThemeManager.ripple(activity, null, 16f)
            isClickable = true
        }
        val undoBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 18f), 0, dp(activity, 4f), 0)
            background = ThemeManager.rounded(activity, p.elevated, 16f, p.outline)
            elevation = dp(activity, 3f).toFloat()
            visibility = View.GONE
            addView(undoText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(undoButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 48f)))
        }
        root.addView(undoBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 52f)).apply {
            setMargins(dp(activity, 16f), dp(activity, 4f), dp(activity, 16f), dp(activity, 4f))
        })

        val closeAll = Button(activity).apply {
            text = "Cerrar todas"; contentDescription = "Cerrar todas las pestañas del grupo visible"
            textSize = 14f; minHeight = 0; minimumHeight = 0
            ThemeManager.styleButton(activity, this, danger = true)
        }
        root.addView(closeAll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 48f)).apply {
            setMargins(dp(activity, 16f), dp(activity, 4f), dp(activity, 16f), dp(activity, 14f))
        })

        lateinit var refresh: () -> Unit
        val hideUndo = Runnable { undoBar.visibility = View.GONE }

        fun closeWithUndo(tab: BrowserTab) {
            val lastNormal = !tab.isPrivate && TabManager.allTabs().count { !it.isPrivate } == 1
            TabManager.closeTab(tab.id)
            // Cerrar la última pestaña abre una nueva: no hay nada más que elegir aquí.
            if (lastNormal) { dialog.dismiss(); return }
            refresh()
            undoBar.removeCallbacks(hideUndo)
            if (TabManager.lastClosed()?.id == tab.id) {
                undoText.text = "Cerrada: " + tab.title.ifBlank { "pestaña" }
                undoButton.setOnClickListener {
                    undoBar.removeCallbacks(hideUndo)
                    undoBar.visibility = View.GONE
                    TabManager.reopenClosed(tab.id, activate = false)
                    refresh()
                }
                undoBar.visibility = View.VISIBLE
                undoBar.postDelayed(hideUndo, 5000L)
            } else {
                undoBar.visibility = View.GONE
            }
        }

        val adapter = TabCardAdapter(
            activity,
            onOpen = { tab ->
                TabManager.switchTo(tab.id)
                dialog.dismiss()
            },
            onClose = { tab -> closeWithUndo(tab) },
            onLongPress = { tab -> moveSheet(activity, tab) { refresh() } }
        )
        grid.adapter = adapter

        // Deslizar una tarjeta hacia un lado la cierra (se desvanece al alejarse).
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, holder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false
            override fun getSwipeThreshold(holder: RecyclerView.ViewHolder) = 0.35f
            override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) {
                val tab = adapter.items.getOrNull(holder.bindingAdapterPosition) ?: return
                closeWithUndo(tab)
            }
            override fun onChildDraw(
                canvas: Canvas, rv: RecyclerView, holder: RecyclerView.ViewHolder,
                dX: Float, dY: Float, actionState: Int, active: Boolean
            ) {
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                    val width = holder.itemView.width.coerceAtLeast(1)
                    holder.itemView.alpha = (1f - abs(dX) / width).coerceIn(0.15f, 1f)
                    holder.itemView.rotation = (dX / width) * 4f
                }
                super.onChildDraw(canvas, rv, holder, dX, dY, actionState, active)
            }
            override fun clearView(rv: RecyclerView, holder: RecyclerView.ViewHolder) {
                super.clearView(rv, holder)
                holder.itemView.alpha = 1f
                holder.itemView.rotation = 0f
            }
        }).attachToRecyclerView(grid)

        // Una miniatura nueva (p. ej. la de la pestaña actual) se pinta en su tarjeta.
        val previewListener: (String) -> Unit = { id -> adapter.previewChanged(id) }
        TabPreviewStore.addListener(previewListener)
        dialog.setOnDismissListener {
            TabPreviewStore.removeListener(previewListener)
            undoBar.removeCallbacks(hideUndo)
        }

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
            val items = all.filter { it.isPrivate == showPrivate && (selectedGroup == null || it.groupName.orEmpty() == selectedGroup) }
            TabPreviewStore.prefetch(activity, items.filterNot { it.isPrivate }.map { it.id })
            adapter.submit(items, TabManager.activeTab()?.id)
            closeAll.text = when (selectedGroup) { null -> "Cerrar todas"; "" -> "Cerrar pestañas sin grupo"; else -> "Cerrar grupo «$selectedGroup»" }
            closeAll.isEnabled = items.isNotEmpty()
            title.text = if (items.size == 1) "1 pestaña" else "${items.size} pestañas"
            empty.text = if (showPrivate) {
                "No hay pestañas privadas.\nLas pestañas privadas no guardan historial ni cookies."
            } else "No hay pestañas abiertas."
            empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
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
        // Abrir con la pestaña actual a la vista.
        adapter.items.indexOfFirst { it.id == TabManager.activeTab()?.id }.takeIf { it > 0 }?.let { grid.scrollToPosition(it) }

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
}

/** Datos de una tarjeta: si no cambian, la tarjeta no se vuelve a dibujar. */
private data class TabCardState(
    val id: String,
    val title: String,
    val url: String,
    val groupName: String?,
    val isPrivate: Boolean,
    val burner: Boolean,
    val active: Boolean
)

private class TabCardAdapter(
    private val activity: Activity,
    private val onOpen: (BrowserTab) -> Unit,
    private val onClose: (BrowserTab) -> Unit,
    private val onLongPress: (BrowserTab) -> Unit
) : RecyclerView.Adapter<TabCardAdapter.Holder>() {
    var items: List<BrowserTab> = emptyList()
        private set
    private var states: List<TabCardState> = emptyList()

    init { setHasStableIds(true) }

    private fun dp(value: Float) = (value * activity.resources.displayMetrics.density).toInt()

    fun submit(next: List<BrowserTab>, activeId: String?) {
        val nextStates = next.map {
            TabCardState(it.id, it.title, it.url, it.groupName, it.isPrivate, it.burnerId != null, it.id == activeId)
        }
        val old = states
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = nextStates.size
            override fun areItemsTheSame(a: Int, b: Int) = old[a].id == nextStates[b].id
            override fun areContentsTheSame(a: Int, b: Int) = old[a] == nextStates[b]
        })
        items = next
        states = nextStates
        diff.dispatchUpdatesTo(this)
    }

    fun previewChanged(id: String) {
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) notifyItemChanged(index, PREVIEW)
    }

    override fun getItemCount() = items.size
    override fun getItemId(position: Int) = items[position].id.hashCode().toLong()

    class Holder(
        val card: LinearLayout,
        val icon: ImageView,
        val title: TextView,
        val close: ImageButton,
        val preview: FrameLayout,
        val placeholderIcon: ImageView,
        val placeholderLetter: TextView,
        val placeholderHost: TextView,
        val image: TopCropImageView
    ) : RecyclerView.ViewHolder(card) {
        var boundId: String? = null
        var shownPreview: Bitmap? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val p = ThemeManager.palette(activity)
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeManager.rounded(activity, p.surface, 20f)
            clipToOutline = true
            elevation = dp(1f).toFloat()
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), dp(4f), dp(2f), dp(2f))
        }
        val icon = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        header.addView(icon, LinearLayout.LayoutParams(dp(18f), dp(18f)).apply { marginEnd = dp(8f) })
        val title = TextView(activity).apply {
            textSize = 13f
            setTextColor(p.text)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val close = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_menu_close)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
            ThemeManager.styleIconButton(activity, this)
            imageTintList = ColorStateList.valueOf(p.muted)
        }
        header.addView(close, LinearLayout.LayoutParams(dp(48f), dp(48f)))
        card.addView(header)

        // Marcador (logo + dominio) debajo; la miniatura lo cubre al cargar.
        val bottomRadius = dp(19f).toFloat()
        val preview = FrameLayout(activity).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(p.accentSoft)
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, bottomRadius, bottomRadius, bottomRadius, bottomRadius)
            }
            clipToOutline = true
        }
        val placeholderIcon = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val placeholderLetter = TextView(activity).apply {
            gravity = Gravity.CENTER; textSize = 20f; setTextColor(Color.WHITE)
            typeface = Typeface.create(activity.resources.getFont(R.font.inter_variable), Typeface.BOLD)
        }
        val placeholderHost = TextView(activity).apply {
            textSize = 12f
            setTextColor(p.muted)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(dp(8f), dp(8f), dp(8f), 0)
        }
        preview.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(FrameLayout(activity).apply {
                addView(placeholderIcon, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(placeholderLetter, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }, LinearLayout.LayoutParams(dp(44f), dp(44f)))
            addView(placeholderHost)
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val image = TopCropImageView(activity).apply { visibility = View.GONE }
        preview.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        card.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190f)))

        return Holder(card, icon, title, close, preview, placeholderIcon, placeholderLetter, placeholderHost, image)
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == PREVIEW }) bindPreview(holder, items[position])
        else onBindViewHolder(holder, position)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val tab = items[position]
        val p = ThemeManager.palette(activity)
        val active = states.getOrNull(position)?.active == true
        val changedTab = holder.boundId != tab.id
        holder.boundId = tab.id

        holder.card.foreground = ThemeManager.rounded(
            activity,
            Color.TRANSPARENT,
            20f,
            if (active) p.accent else tab.groupName?.let { androidx.core.graphics.ColorUtils.setAlphaComponent(TabGroupRules.color(it), 150) } ?: p.outline,
            if (active) 2.5f else 1f
        )
        holder.card.setOnClickListener { onOpen(tab) }
        holder.card.setOnLongClickListener { onLongPress(tab); true }
        holder.card.contentDescription = tab.title + if (active) " · pestaña actual" else ""

        holder.title.text = (if (tab.burnerId != null) "🔥 " else "") + tab.title.ifBlank { "Nueva pestaña" }
        holder.title.setCompoundDrawablesRelativeWithIntrinsicBounds(
            tab.groupName?.let { group ->
                android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(TabGroupRules.color(group)); setSize(dp(8f), dp(8f))
                }
            }, null, null, null
        )
        holder.title.compoundDrawablePadding = dp(6f)
        holder.close.contentDescription = "Cerrar pestaña: ${tab.title}"
        holder.close.setOnClickListener { onClose(tab) }

        val home = BrowserPrefs.isNexoHome(tab.url)
        val host = runCatching { android.net.Uri.parse(tab.url).host }.getOrNull()?.removePrefix("www.").orEmpty()
        holder.placeholderHost.text = if (home) "Página de inicio" else host.ifBlank { tab.url }

        if (changedTab) {
            holder.icon.setImageDrawable(null)
            holder.placeholderIcon.setImageDrawable(null)
            holder.placeholderLetter.visibility = View.GONE
        }
        val origin = BrowserPagePresentation.shortcut(tab.url)?.origin
        when {
            tab.isPrivate -> {
                holder.icon.setImageResource(R.drawable.ic_menu_private)
                holder.icon.imageTintList = ColorStateList.valueOf(p.accent)
                holder.placeholderIcon.setImageResource(R.drawable.ic_menu_private)
                holder.placeholderIcon.imageTintList = ColorStateList.valueOf(p.accent)
                holder.placeholderIcon.background = null
            }
            home || origin == null -> {
                holder.icon.setImageResource(R.drawable.ic_nexo_logo)
                holder.icon.imageTintList = null
                holder.placeholderIcon.setImageResource(R.drawable.ic_nexo_logo)
                holder.placeholderIcon.imageTintList = null
                holder.placeholderIcon.background = null
            }
            else -> {
                // Inicial con un color propio del sitio mientras llega (o si no hay) su icono.
                holder.icon.imageTintList = null
                holder.placeholderIcon.imageTintList = null
                val cachedIcon = SiteIconStore.cached(origin)
                if (cachedIcon != null) {
                    showSiteIcon(holder, cachedIcon)
                } else {
                    holder.icon.setImageResource(R.drawable.ic_search)
                    holder.icon.imageTintList = ColorStateList.valueOf(p.muted)
                    holder.placeholderIcon.setImageDrawable(null)
                    holder.placeholderIcon.background = ThemeManager.rounded(activity, SiteIconStore.letterColor(host), 14f)
                    holder.placeholderLetter.text = SiteIconStore.letter(host)
                    holder.placeholderLetter.visibility = View.VISIBLE
                    SiteIconStore.load(activity, origin) { bitmap ->
                        if (holder.boundId == tab.id) showSiteIcon(holder, bitmap)
                    }
                }
            }
        }
        bindPreview(holder, tab)
    }

    private fun showSiteIcon(holder: Holder, bitmap: Bitmap) {
        holder.icon.imageTintList = null
        holder.icon.setImageBitmap(bitmap)
        holder.placeholderLetter.visibility = View.GONE
        holder.placeholderIcon.background = null
        holder.placeholderIcon.setImageBitmap(bitmap)
    }

    private fun bindPreview(holder: Holder, tab: BrowserTab) {
        val hasPreview = !tab.isPrivate && !BrowserPrefs.isNexoHome(tab.url)
        if (!hasPreview) {
            holder.shownPreview = null
            holder.image.setImageDrawable(null)
            holder.image.visibility = View.GONE
            return
        }
        fun show(bitmap: Bitmap?) {
            if (bitmap == null) {
                holder.shownPreview = null
                holder.image.setImageDrawable(null)
                holder.image.visibility = View.GONE
                return
            }
            // Misma imagen: no se toca la vista (sin parpadeo).
            if (holder.shownPreview === bitmap && holder.image.visibility == View.VISIBLE) return
            holder.shownPreview = bitmap
            holder.image.setBackgroundColor(ThemeManager.palette(activity).elevated)
            holder.image.setImageBitmap(bitmap)
            holder.image.visibility = View.VISIBLE
        }
        val cached = TabPreviewStore.cached(tab.id)
        if (cached != null) { show(cached); return }
        if (holder.shownPreview == null) holder.image.visibility = View.GONE
        TabPreviewStore.loadAsync(activity, tab.id) { bitmap ->
            if (holder.boundId == tab.id) show(bitmap)
        }
    }

    companion object {
        private const val PREVIEW = "preview"
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
