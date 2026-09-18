package com.michalkrsik.internetrestrictor.v1

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.michalkrsik.internetrestrictor.appContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A dedicated trusted origin hosts the tested direct-WebRTC voice path. */
class V1ConversationActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var identity = "{}"
    private var microphoneRequest: PermissionRequest? = null
    private var resumed = false
    private var previewOpening = false
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        val pending = microphoneRequest
        microphoneRequest = null
        if (allowed && pending != null && appContainer.v1.api.isTrustedOrigin(pending.origin.toString())) {
            pending.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
        } else pending?.deny()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xff121812.toInt()) }
        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val back = TextView(this).apply {
            text = "‹  Back to your day"
            setTextColor(0xffdcf28a.toInt()); textSize = 16f
            setPadding(24, 24, 24, 24); setOnClickListener { finish() }
        }
        val status = TextView(this).apply { text = "Opening your setup…"; setTextColor(0xffe9ebdf.toInt()); setPadding(24, 24, 24, 24) }
        container.addView(back); container.addView(status)
        setContentView(container)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() { finish() } })
        lifecycleScope.launch {
            try {
                identity = appContainer.v1.api.bootstrap().toString()
                appContainer.v1.sync()
                val view = WebView(this@V1ConversationActivity).apply {
                    setBackgroundColor(0xff121812.toInt())
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.setSupportMultipleWindows(false)
                    addJavascriptInterface(NativeBridge(), "FrictionNative")
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
                            !appContainer.v1.api.isTrustedOrigin(request?.url?.toString())
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onPermissionRequest(request: PermissionRequest) {
                            runOnUiThread {
                                if (!appContainer.v1.api.isTrustedOrigin(request.origin.toString()) ||
                                    PermissionRequest.RESOURCE_AUDIO_CAPTURE !in request.resources) { request.deny(); return@runOnUiThread }
                                if (ContextCompat.checkSelfPermission(this@V1ConversationActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                    request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                                } else {
                                    microphoneRequest?.deny(); microphoneRequest = request
                                    microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        }
                        override fun onPermissionRequestCanceled(request: PermissionRequest) {
                            if (microphoneRequest == request) microphoneRequest = null
                        }
                    }
                }
                check(appContainer.v1.api.isTrustedOrigin(appContainer.v1.api.baseUrl)) { "Voice requires a trusted HTTPS backend." }
                webView = view
                status.textSize = 11f
                status.setPadding(24, 2, 24, 8)
                container.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
                view.loadUrl(appContainer.v1.api.baseUrl + "/")
                while (true) {
                    if (resumed) appContainer.v1.sync()
                    status.text = appContainer.v1.status.value
                    delay(2_000)
                }
            } catch (error: Exception) {
                status.text = "${error.message ?: "Unable to connect."}\nTap here to retry."
                status.setOnClickListener { recreate() }
                appContainer.eventLog.record("v1_conversation ${error.message.orEmpty()}")
            }
        }
    }

    private inner class NativeBridge {
        @JavascriptInterface fun bootstrap(): String = identity
        @JavascriptInterface fun configChanged() { lifecycleScope.launch { appContainer.v1.sync() } }
        @JavascriptInterface fun testTask(proposalId: String, version: Int, taskId: String) {
            lifecycleScope.launch {
                if (previewOpening) return@launch
                previewOpening = true
                try {
                    check(appContainer.v1.api.isTrustedOrigin(webView?.url)) { "Task testing is available from your Friction setup." }
                    val attempt = appContainer.v1.newPreviewAttempt(proposalId, version, taskId)
                    val task = com.michalkrsik.internetrestrictor.data.ConfigurationJson.parseTask(attempt.getJSONObject("task"))
                    val target = if (task.verifier == "gps") V1GpsActivity::class.java else V1VideoActivity::class.java
                    startActivity(android.content.Intent(this@V1ConversationActivity, target)
                        .putExtra("taskId", task.id).putExtra("previewAttemptId", attempt.getString("attemptId")))
                } catch (error: Exception) {
                    val quoted = org.json.JSONObject.quote(error.message ?: "This task could not be tested.")
                    webView?.evaluateJavascript("(()=>{const alert=document.getElementById('alert');if(alert){alert.textContent=$quoted;alert.hidden=false;}})()", null)
                } finally {
                    previewOpening = false
                }
            }
        }
        @JavascriptInterface fun close() { runOnUiThread { finish() } }
    }

    override fun onResume() { super.onResume(); resumed = true; webView?.onResume() }
    override fun onPause() {
        resumed = false
        // Leaving the screen must release microphone capture. Reloading starts a fresh call.
        if (microphoneRequest == null) {
            webView?.evaluateJavascript("window.dispatchEvent(new Event('pagehide'));", null)
            webView?.onPause()
        }
        super.onPause()
    }
    override fun onDestroy() {
        microphoneRequest?.deny(); microphoneRequest = null
        webView?.apply { removeJavascriptInterface("FrictionNative"); stopLoading(); loadUrl("about:blank"); destroy() }
        webView = null
        super.onDestroy()
    }
}
