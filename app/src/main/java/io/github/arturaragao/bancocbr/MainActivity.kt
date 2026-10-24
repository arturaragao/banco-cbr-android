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

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        webView = WebView(this).apply {
            setBackgroundColor(Color.rgb(15, 23, 42))
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
            addJavascriptInterface(NativeBridge(applicationContext), "BCBRNativo")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (isInternal(request.url)) return false
                    if (request.isForMainFrame) openExternal(request.url)
                    return true
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
        const val BASE_URL = "https://arturaragao.github.io/banco-cbr-app2/"
    }
}

class NativeBridge(private val context: android.content.Context) {
    @JavascriptInterface
    fun dados(json: String) {
        RefreshCoordinator.accept(context, json)
    }

    @JavascriptInterface
    fun versao(): String = BuildConfig.VERSION_NAME

    @JavascriptInterface
    fun pendentes(): String = PendingStore.read(context)

    @JavascriptInterface
    fun confirmar(idsJson: String) {
        PendingStore.remove(context, idsJson)
        RefreshCoordinator.enqueue(context)
    }
}
