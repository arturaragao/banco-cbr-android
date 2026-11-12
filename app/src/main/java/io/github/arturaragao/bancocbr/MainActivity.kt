package io.github.arturaragao.bancocbr

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        webView = WebView(this).apply {
            setBackgroundColor(Color.rgb(34, 30, 26))
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true // Includes IndexedDB; the HTTPS origin also enables SW.
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                allowFileAccess = false
                allowContentAccess = false
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                setSupportMultipleWindows(true)
                javaScriptCanOpenWindowsAutomatically = false
            }
            CookieManager.getInstance().setAcceptCookie(true)
            addJavascriptInterface(NativeBridge(applicationContext, ::applyBarColor), "BCBRNativo")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (isInternal(request.url)) return false
                    if (request.isForMainFrame) openExternal(request.url)
                    return true
                }

                // Copia a chave do GitHub do site (localStorage) para o widget Ideia enviar sem abrir o app.
                override fun onPageFinished(view: WebView, url: String) {
                    if (Uri.parse(url).host != "arturaragao.github.io") return
                    view.evaluateJavascript(BAR_COLOR_SCRIPT, null)
                    view.evaluateJavascript("localStorage.getItem('bcbr_gh_token')") { result ->
                        Ideas.tokenFromJs(result)?.let {
                            IdeaStore.saveToken(applicationContext, it)
                            if (IdeaStore.lines(applicationContext).isNotEmpty()) IdeaUpload.enqueue(applicationContext)
                        }
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(view: WebView, dialog: Boolean, gesture: Boolean, resultMsg: Message): Boolean {
                    if (!gesture) return false
                    val popup = WebView(this@MainActivity)
                    popup.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            if (isInternal(request.url)) webView.loadUrl(request.url.toString())
                            else openExternal(request.url)
                            view.post { view.destroy() }
                            return true
                        }
                    }
                    (resultMsg.obj as WebView.WebViewTransport).webView = popup
                    resultMsg.sendToTarget()
                    return true
                }
            }
        }
        ServiceWorkerController.getInstance().apply {
            serviceWorkerWebSettings.apply {
                allowFileAccess = false
                allowContentAccess = false
                blockNetworkLoads = false
            }
            setServiceWorkerClient(object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? = null
            })
        }
        setContentView(webView)
        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            loadAction(intent)
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
        askNotificationsOnce()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        loadAction(intent)
    }

    private fun loadAction(intent: Intent) {
        val action = intent.getStringExtra("acao") ?: intent.data?.let {
            if (it.isHierarchical) it.getQueryParameter("acao") else null
        }
        val uri = Uri.parse(BASE_URL).buildUpon()
        if (action == "tarefa" || action == "ri") uri.appendQueryParameter("acao", action)
        webView.loadUrl(uri.build().toString())
    }

    private fun isInternal(uri: Uri): Boolean = uri.scheme == "https" &&
        (uri.host == "arturaragao.github.io" || uri.host == "api.github.com")

    private fun applyBarColor(hex: String) {
        if (!Regex("^#[0-9a-fA-F]{6}$").matches(hex)) return
        val color = Color.parseColor(hex)
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            val page = Uri.parse(webView.url ?: return@runOnUiThread)
            if (page.scheme != "https" || page.host != "arturaragao.github.io") return@runOnUiThread
            window.statusBarColor = color
            window.navigationBarColor = color
            if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
            val darkIcons = ColorUtils.calculateLuminance(color) > 0.5
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = darkIcons
                isAppearanceLightNavigationBars = darkIcons
            }
        }
    }

    private fun openExternal(uri: Uri) {
        if (uri.scheme !in setOf("http", "https", "mailto", "tel")) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            // A device may have no browser/mail/dialer installed.
        }
    }

    private fun askNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (!prefs.getBoolean("notificationAsked", false)) {
            prefs.edit().putBoolean("notificationAsked", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        RefreshCoordinator.enqueue(applicationContext)
        val force = intent.getBooleanExtra(Updater.EXTRA, false)
        if (force) intent.removeExtra(Updater.EXTRA)
        Updater.checkOnOpen(this, force)
        // traz na hora as tarefas criadas pela voz/widget enquanto o app estava aberto por baixo
        webView.evaluateJavascript("window.smNativoImporta&&smNativoImporta()", null)
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        webView.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        webView.removeJavascriptInterface("BCBRNativo")
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val BASE_URL = "https://arturaragao.github.io/radio/"
        private val BAR_COLOR_SCRIPT = """
            (() => {
                if (location.origin !== 'https://arturaragao.github.io') return;
                if (window.__radioBarColors) window.__radioBarColors();
                const canvas = document.createElement('canvas');
                canvas.width = canvas.height = 1;
                const ctx = canvas.getContext('2d');
                if (!ctx) return;
                const toHex = value => {
                    if (!value || !CSS.supports('color', value)) return null;
                    ctx.clearRect(0, 0, 1, 1);
                    ctx.fillStyle = value;
                    ctx.fillRect(0, 0, 1, 1);
                    const rgba = ctx.getImageData(0, 0, 1, 1).data;
                    if (rgba[3] !== 255) return null;
                    return '#' + Array.from(rgba).slice(0, 3)
                        .map(n => n.toString(16).padStart(2, '0')).join('');
                };
                let frame = 0;
                let last = '';
                const sync = () => {
                    frame = 0;
                    const meta = Array.from(document.querySelectorAll('meta[name="theme-color"]'))
                        .find(el => !el.media || matchMedia(el.media).matches);
                    const color = (meta && toHex(meta.content)) ||
                        (document.body && toHex(getComputedStyle(document.body).backgroundColor)) ||
                        toHex(getComputedStyle(document.documentElement).backgroundColor) || '#221e1a';
                    if (color !== last) {
                        window.BCBRNativo.corBarras(color);
                        last = color;
                    }
                };
                const schedule = () => {
                    if (!frame) frame = requestAnimationFrame(sync);
                };
                const observer = new MutationObserver(schedule);
                observer.observe(document.documentElement, { attributes: true, childList: true });
                if (document.body) observer.observe(document.body, { attributes: true });
                if (document.head) observer.observe(document.head, {
                    attributes: true, childList: true, subtree: true,
                    attributeFilter: ['content', 'name', 'media', 'href', 'rel']
                });
                const scheme = matchMedia('(prefers-color-scheme: dark)');
                scheme.addEventListener('change', schedule);
                document.addEventListener('transitionend', schedule);
                window.__radioBarColors = () => {
                    observer.disconnect();
                    cancelAnimationFrame(frame);
                    scheme.removeEventListener('change', schedule);
                    document.removeEventListener('transitionend', schedule);
                };
                sync();
            })();
        """.trimIndent()
    }
}

class NativeBridge(
    private val context: android.content.Context,
    private val onBarColor: (String) -> Unit = {}
) {
    @JavascriptInterface
    fun corBarras(hex: String) {
        onBarColor(hex)
    }

    @JavascriptInterface
    fun dados(json: String) {
        RefreshCoordinator.accept(context, json)
    }

    @JavascriptInterface
    fun tarefas(json: String) {
        RefreshCoordinator.enqueue(context, before = { NativeTaskStore.save(context, json) })
    }

    @JavascriptInterface
    fun versao(): String = BuildConfig.VERSION_NAME

    /** 🎤 do site: a voz do navegador não funciona na WebView; usa o reconhecedor do Android e grava na fila. */
    @JavascriptInterface
    fun falar() {
        context.startActivity(Intent(context, VoiceTaskActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @JavascriptInterface
    fun pendentes(): String = PendingStore.read(context)

    @JavascriptInterface
    fun confirmar(idsJson: String) {
        PendingStore.remove(context, idsJson)
        RefreshCoordinator.enqueue(context)
    }
}
