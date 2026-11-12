package com.nexo.browser

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** Capturas de la interfaz nativa para revisión visual. Solo con -PnexoScreenshots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h900dp-xhdpi")
class ScreenshotTest {
    private val out = File(System.getProperty("nexo.shots") ?: "build/nexo-shots").apply { mkdirs() }

    private fun save(view: View, name: String) {
        val width = view.width.takeIf { it > 0 } ?: 824
        val height = view.height.takeIf { it > 0 } ?: 1800
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        FileOutputStream(File(out, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun measureAndSave(activity: Activity, view: View, name: String, heightPx: Int = 1800) {
        val spec = View.MeasureSpec.makeMeasureSpec(824, View.MeasureSpec.EXACTLY)
        view.measure(spec, View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        save(view, name)
    }

    @Test fun settings() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val root = activity.findViewById<View>(R.id.settingsRoot)
        val scroll = (root as android.view.ViewGroup)
        save(activity.window.decorView, "settings")
        // Contenido completo (desplazable).
        val content = (0 until scroll.childCount).map { scroll.getChildAt(it) }
            .firstOrNull { it is android.widget.ScrollView } as? android.widget.ScrollView
        content?.getChildAt(0)?.let { measureAndSave(activity, it, "settings-full", 8000) }
    }

    @Test fun history() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        HistoryStore.add(app, "https://es.wikipedia.org/wiki/Navegador_web", "Navegador web - Wikipedia")
        HistoryStore.add(app, "https://www.youtube.com/watch?v=abc", "Video de prueba - YouTube")
        HistoryStore.add(app, "https://github.com/", "GitHub")
        Thread.sleep(300)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val activity = Robolectric.buildActivity(HistoryActivity::class.java).setup().get()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        save(activity.window.decorView, "history")
    }

    @Test fun startPage() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        ThemeManager.applyWindow(activity)
        val page = StartPageView(activity, onOpen = {}, onPrivate = {}, onShield = {}, onExtensions = {})
        val frame = FrameLayout(activity).apply { setBackgroundColor(ThemeManager.palette(activity).background); addView(page) }
        activity.setContentView(frame)
        page.visibility = View.VISIBLE
        page.render(false)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        save(activity.window.decorView, "start-page")
    }

    private fun seedTabs(vararg tabs: BrowserTab) {
        val field = TabManager::class.java.getDeclaredField("tabs").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val list = field.get(TabManager) as MutableList<BrowserTab>
        list.clear(); list.addAll(tabs)
        TabManager::class.java.getDeclaredField("activeId").apply { isAccessible = true }.set(TabManager, tabs.first().id)
    }

    private fun sampleTabs() = arrayOf(
        BrowserTab(url = "https://www.youtube.com/watch?v=abc", title = "Video de prueba - YouTube", groupName = "Música"),
        BrowserTab(url = "https://es.wikipedia.org/wiki/Navegador_web", title = "Navegador web - Wikipedia", groupName = "Trabajo"),
        BrowserTab(url = "https://github.com/", title = "GitHub"),
        BrowserTab(url = "https://news.ycombinator.com/", title = "Hacker News", groupName = "Trabajo")
    )

    private fun themed(theme: String): Activity {
        BrowserPrefs.setTheme(org.robolectric.RuntimeEnvironment.getApplication(), theme)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        ThemeManager.applyWindow(activity)
        return activity
    }

    private fun chrome(theme: String, name: String) {
        val activity = themed(theme)
        val p = ThemeManager.palette(activity)
        val root = activity.layoutInflater.inflate(R.layout.activity_main, null)
        root.setBackgroundColor(p.background)
        root.findViewById<View>(R.id.browserDock).apply {
            background = ThemeManager.rounded(activity, p.surface, 28f, p.outline)
            elevation = 8f * activity.resources.displayMetrics.density
        }
        ThemeManager.styleEdit(activity, root.findViewById(R.id.omnibox))
        root.findViewById<android.widget.EditText>(R.id.omnibox).setText("es.wikipedia.org/wiki/Navegador_web")
        ThemeManager.styleIconButton(activity, root.findViewById(R.id.tabToolsButton))
        ThemeManager.styleTabCounter(activity, root.findViewById(R.id.tabButton))
        ThemeManager.styleFlatButton(activity, root.findViewById(R.id.menuButton))
        listOf(R.id.backButton, R.id.forwardButton, R.id.homeButton, R.id.reloadButton, R.id.newTabButton)
            .forEach { ThemeManager.styleIconButton(activity, root.findViewById(it)) }
        root.findViewById<View>(R.id.geckoView).setBackgroundColor(p.surface)
        activity.setContentView(root)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        save(activity.window.decorView, name)
    }

    @Test fun chromeLight() = chrome(BrowserPrefs.THEME_LIGHT, "chrome-light")
    @Test fun chromeDark() = chrome(BrowserPrefs.THEME_MIDNIGHT, "chrome-dark")

    private fun dialogShot(dialog: android.app.Dialog?, name: String) {
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val decor = (dialog ?: org.robolectric.shadows.ShadowDialog.getLatestDialog()).window!!.decorView
        measureAndSave(decor.context as? Activity ?: Robolectric.buildActivity(Activity::class.java).get(), decor, name, 2400)
    }

    @Test fun tabTools() {
        seedTabs(*sampleTabs())
        val activity = themed(BrowserPrefs.THEME_MIDNIGHT)
        activity.setContentView(FrameLayout(activity))
        TabTools.show(activity)
        dialogShot(null, "tab-tools-dark")
    }

    @Test fun tabSwitcher() {
        seedTabs(*sampleTabs())
        val activity = themed(BrowserPrefs.THEME_MIDNIGHT)
        activity.setContentView(FrameLayout(activity))
        TabSwitcher.show(activity) {}
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        save(dialog.window!!.decorView, "tab-switcher-dark")
    }

    @Test fun settingsDark() {
        BrowserPrefs.setTheme(org.robolectric.RuntimeEnvironment.getApplication(), BrowserPrefs.THEME_MIDNIGHT)
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        save(activity.window.decorView, "settings-dark")
    }

    @Test fun captionsSheet() {
        val activity = themed(BrowserPrefs.THEME_MIDNIGHT)
        activity.setContentView(FrameLayout(activity))
        BrowserPrefs.setCaptionTranslateTo(activity, "es")
        BrowserPrefs.setTranslationProvider(activity, BrowserPrefs.TRANSLATE_AI)
        BrowserPrefs.setAiTranslateProvider(activity, "claude")
        CaptionsUi.showSheet(activity)
        dialogShot(null, "captions-sheet-dark")
    }

    @Test fun captionsOverlay() {
        val activity = themed(BrowserPrefs.THEME_MIDNIGHT)
        val p = ThemeManager.palette(activity)
        val root = activity.layoutInflater.inflate(R.layout.activity_main, null)
        root.setBackgroundColor(p.background)
        root.findViewById<View>(R.id.geckoView).setBackgroundColor(0xFF1F2937.toInt())
        activity.setContentView(root)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val frame = root.findViewById<View>(R.id.browserFrame)
        val video = View(activity).apply { setBackgroundColor(0xFF374151.toInt()) }
        val rect = android.graphics.Rect(0, 160, frame.width, 160 + frame.width * 9 / 16)
        (frame as android.view.ViewGroup).addView(video, 1, FrameLayout.LayoutParams(rect.width(), rect.height()).apply { topMargin = rect.top })
        val cc = root.findViewById<android.widget.Button>(R.id.captionsButton)
        cc.visibility = View.VISIBLE
        CaptionsUi.styleChip(activity, cc, true)
        val mini = root.findViewById<android.widget.Button>(R.id.miniPlayerButton)
        mini.visibility = View.VISIBLE
        CaptionsUi.styleChip(activity, mini, false)
        CaptionsUi.render(activity, root.findViewById(R.id.captionView), frame.width, frame.height, rect,
            CaptionDisplay("Hoy vamos a ver cómo funciona el motor de un navegador.", "And the rendering",
                "Today we will see how a browser engine works."), false)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        save(activity.window.decorView, "captions-overlay")
    }

    @Test fun captionsQuick() {
        val activity = themed(BrowserPrefs.THEME_MIDNIGHT)
        activity.setContentView(FrameLayout(activity))
        BrowserPrefs.setCaptionTranslateTo(activity, "ko")
        val session = org.mozilla.geckoview.GeckoSession()
        CaptionsUi.showQuick(activity, session, onChange = {}) {}
        dialogShot(null, "captions-quick-dark")
        CaptionsUi.pickLanguage(activity, "ko") {}
        dialogShot(null, "captions-languages-dark")
    }
}
