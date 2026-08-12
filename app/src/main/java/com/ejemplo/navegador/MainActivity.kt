package com.ejemplo.navegador

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension

private const val TAG = "GeckoBrowser"
private const val NATIVE_APP_ID = "browser"
private const val EXTENSION_ID = "my_extension@com.ejemplo.navegador"
private const val EXTENSION_LOCATION = "resource://android/assets/extensions/my_extension/"

private object GeckoRuntimeHolder {
    @Volatile
    private var instance: GeckoRuntime? = null

    fun get(context: Context): GeckoRuntime = instance ?: synchronized(this) {
        instance ?: GeckoRuntime.create(
            context.applicationContext,
            GeckoRuntimeSettings.Builder()
                .remoteDebuggingEnabled(false)
                .javaScriptEnabled(true)
                .build()
        ).also { instance = it }
    }
}

class MainActivity : Activity() {
    private lateinit var geckoView: GeckoView
    private lateinit var geckoSession: GeckoSession
    private lateinit var urlEditText: EditText
    private lateinit var goButton: Button
    private lateinit var progressBar: ProgressBar

    private val messageDelegate = object : WebExtension.MessageDelegate {
        override fun onMessage(
            nativeApp: String,
            message: Any,
            sender: WebExtension.MessageSender
        ): GeckoResult<Any>? {
            if (nativeApp != NATIVE_APP_ID) return null
            if (sender.session != null && sender.session !== geckoSession) return null

            Log.d(TAG, "WebExtension -> Android: $message")

            return GeckoResult.fromValue(
                JSONObject().apply {
                    put("ok", true)
                    put("message", "Hola desde Kotlin")
                    put("received", message.toString())
                }
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        geckoView = findViewById(R.id.geckoView)
        urlEditText = findViewById(R.id.urlEditText)
        goButton = findViewById(R.id.goButton)
        progressBar = findViewById(R.id.pageProgress)

        geckoSession = GeckoSession()

        geckoSession.setContentDelegate(
            object : GeckoSession.ContentDelegate {}
        )

        geckoSession.setProgressDelegate(
            object : GeckoSession.ProgressDelegate {
                override fun onPageStart(session: GeckoSession, url: String) {
                    urlEditText.setText(url)
                    progressBar.progress = 0
                    progressBar.visibility = View.VISIBLE
                }

                override fun onProgressChange(session: GeckoSession, progress: Int) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = progress.coerceIn(0, 100)
                }

                override fun onPageStop(session: GeckoSession, success: Boolean) {
                    progressBar.progress = 100
                    progressBar.postDelayed(
                        { progressBar.visibility = View.GONE },
                        150
                    )
                }
            }
        )

        val runtime = GeckoRuntimeHolder.get(applicationContext)

        geckoSession.open(runtime)
        geckoView.setSession(geckoSession)

        goButton.setOnClickListener {
            navigateFromBar()
        }

        urlEditText.setOnEditorActionListener { _, actionId, event ->
            val enterPressed =
                event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                    event.action == KeyEvent.ACTION_DOWN

            if (actionId == EditorInfo.IME_ACTION_GO || enterPressed) {
                navigateFromBar()
                true
            } else {
                false
            }
        }

        installBuiltInExtension(runtime, savedInstanceState)
    }

    private fun installBuiltInExtension(
        runtime: GeckoRuntime,
        savedInstanceState: Bundle?
    ) {
        runtime.webExtensionController
            .ensureBuiltIn(EXTENSION_LOCATION, EXTENSION_ID)
            .accept(
                { extension ->
                    if (extension == null) {
                        Log.e(TAG, "GeckoView devolvió una WebExtension nula")
                    } else {
                        extension.setMessageDelegate(
                            messageDelegate,
                            NATIVE_APP_ID
                        )

                        geckoSession.webExtensionController.setMessageDelegate(
                            extension,
                            messageDelegate,
                            NATIVE_APP_ID
                        )
                    }

                    if (savedInstanceState == null) {
                        geckoSession.loadUri(HOME_PAGE)
                    }
                },
                { error ->
                    Log.e(TAG, "No se pudo instalar la extensión", error)

                    // El navegador continúa funcionando aunque falle
                    // la extensión local.
                    if (savedInstanceState == null) {
                        geckoSession.loadUri(HOME_PAGE)
                    }
                }
            )
    }

    private fun navigateFromBar() {
        val raw = urlEditText.text?.toString()?.trim().orEmpty()

        val url = when {
            raw.isBlank() -> HOME_PAGE
            raw.startsWith("https://", ignoreCase = true) -> raw
            raw.startsWith("http://", ignoreCase = true) -> raw
            raw.startsWith("about:", ignoreCase = true) -> raw
            else -> "https://$raw"
        }

        geckoSession.loadUri(url)

        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(urlEditText.windowToken, 0)

        urlEditText.clearFocus()
    }

    override fun onDestroy() {
        if (::geckoView.isInitialized) {
            geckoView.releaseSession()
        }

        if (::geckoSession.isInitialized) {
            geckoSession.close()
        }

        super.onDestroy()
    }

    companion object {
        private const val HOME_PAGE = "https://www.mozilla.org/"
    }
}
