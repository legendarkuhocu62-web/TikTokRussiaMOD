package com.watchparty

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.*
import okhttp3.*
import org.json.JSONObject
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.net.URL

class MainActivity : Activity() {
    // Адрес вашего сервера. Для эмулятора 10.0.2.2, в проде: https://ваш.домен
    private val server = "http://10.0.2.2:8080"
    private lateinit var web: WebView
    private lateinit var urlBox: EditText
    private lateinit var main: LinearLayout
    private lateinit var renderer: SurfaceViewRenderer
    private lateinit var shareBtn: Button
    private val rtc by lazy { Rtc(this, { sendWs(it.toString()) }, { runOnUiThread { onRemote(it) } }) }
    private val http = OkHttpClient()
    private var ws: WebSocket? = null
    private var wsOpen = false
    private var myId = ""
    private var remoteUrl: String? = "https://m.youtube.com"
    private var lastSt: String? = null
    private var syncJs = ""
    private var curTrack: VideoTrack? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        syncJs = assets.open("sync.js").bufferedReader().readText()

        urlBox = EditText(this).apply {
            hint = "Ссылка или поиск"; setSingleLine(); imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> open(text.toString()); true }
        }
        val roomBtn = Button(this).apply { text = "Комната"; setOnClickListener { createRoom() } }
        shareBtn = Button(this).apply { text = "Эфир"; setOnClickListener { toggleShare() } }
        val bar = LinearLayout(this).apply {
            addView(urlBox, LinearLayout.LayoutParams(0, -2, 1f)); addView(roomBtn); addView(shareBtn)
        }

        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = userAgentString.replace("; wv", "")
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.addJavascriptInterface(object { @JavascriptInterface fun send(s: String) { sendWs(s) } }, "Android")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) =
                r.url.scheme.let { it != "http" && it != "https" }
            override fun onPageFinished(v: WebView, u: String?) {
                v.evaluateJavascript(syncJs, null)
                lastSt?.let { v.evaluateJavascript("window.__wp.apply($it)", null) }
            }
            override fun doUpdateVisitedHistory(v: WebView, u: String?, reload: Boolean) {
                if (u == null) return
                urlBox.setText(u)
                if (u != remoteUrl) {
                    remoteUrl = u; lastSt = null
                    if (!rtc.watching) sendWs(JSONObject().put("a", "nav").put("url", u).toString())
                }
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(v: View, cb: CustomViewCallback) {
                customCb = cb; setContentView(v)
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            }
            override fun onHideCustomView() {
                setContentView(main); customCb?.onCustomViewHidden(); customCb = null
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }
        }

        renderer = SurfaceViewRenderer(this).apply {
            init(rtc.egl.eglBaseContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            visibility = View.GONE
        }
        rtc.onHostStopped = {
            runOnUiThread { stopService(Intent(this, ShareService::class.java)); shareBtn.text = "Эфир" }
        }

        main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar)
            addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(renderer, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(main)
        if (!handleIntent(intent)) web.loadUrl(remoteUrl!!)
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); handleIntent(i) }

    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c); if (rtc.hosting) rtc.resize()
    }

    private fun handleIntent(i: Intent?): Boolean {
        val d = i?.data ?: return false
        if (d.scheme != "watchparty") return false
        d.lastPathSegment?.let { join(it, false) }
        return true
    }

    private fun open(s: String) {
        val t = s.trim()
        web.loadUrl(when {
            t.startsWith("http") -> t
            t.contains(".") && !t.contains(" ") -> "https://$t"
            else -> "https://www.google.com/search?q=" + Uri.encode(t)
        })
    }

    // ---- Режим «Эфир»: ведущий показывает свой экран всем в комнате ----
    private fun toggleShare() {
        if (rtc.hosting) { rtc.stopHost(); return }
        if (!wsOpen) { Toast.makeText(this, "Сначала создайте комнату", Toast.LENGTH_SHORT).show(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2); return
        }
        startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), 1)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        if (rc == 2 && r.firstOrNull() == PackageManager.PERMISSION_GRANTED) toggleShare()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(rq: Int, rc: Int, data: Intent?) {
        if (rq == 1 && rc == RESULT_OK && data != null) {
            ShareService.onStarted = { rtc.startHost(rc, data); shareBtn.text = "Стоп" }
            startForegroundService(Intent(this, ShareService::class.java))
        }
    }

    private fun onRemote(t: VideoTrack?) {
        curTrack?.removeSink(renderer); curTrack = t
        if (t != null) {
            t.addSink(renderer); renderer.visibility = View.VISIBLE
            web.visibility = View.GONE; web.onPause()
        } else {
            renderer.visibility = View.GONE; web.visibility = View.VISIBLE; web.onResume()
        }
    }

    // ---- Комнаты ----
    private fun createRoom() {
        Thread {
            try {
                val id = URL("$server/create").readText().trim()
                runOnUiThread {
                    join(id, true)
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "Смотрим вместе: $server/r/$id")
                    }, null))
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Нет связи с сервером", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun join(id: String, announce: Boolean) {
        rtc.stopHost(); rtc.stopWatch()
        ws?.close(1000, null); wsOpen = false
        Toast.makeText(this, "Комната $id", Toast.LENGTH_SHORT).show()
        ws = http.newWebSocket(Request.Builder().url(server.replaceFirst("http", "ws")).build(),
            object : WebSocketListener() {
                override fun onOpen(w: WebSocket, r: Response) {
                    w.send(JSONObject().put("a", "join").put("room", id).toString()); wsOpen = true
                    if (announce) runOnUiThread {
                        sendWs(JSONObject().put("a", "nav").put("url", web.url ?: remoteUrl).toString())
                    }
                }
                override fun onMessage(w: WebSocket, t: String) { runOnUiThread { onMsg(t) } }
                override fun onFailure(w: WebSocket, e: Throwable, r: Response?) { wsOpen = false }
            })
    }

    private fun onMsg(t: String) {
        val j = JSONObject(t)
        when (j.getString("a")) {
            "hello" -> myId = j.getString("id")
            "sig" -> rtc.onSignal(j)
            "share" -> {
                val h = if (j.isNull("host")) null else j.getString("host")
                if (h != null && h != myId && !rtc.hosting) rtc.watch(h) else rtc.stopWatch()
            }
            "nav" -> if (!rtc.watching) { remoteUrl = j.getString("url"); lastSt = null; web.loadUrl(remoteUrl!!) }
            else -> if (!rtc.watching) {
                lastSt = t; web.evaluateJavascript("window.__wp&&window.__wp.apply($t)", null)
            }
        }
    }

    private fun sendWs(s: String) { if (wsOpen) ws?.send(s) }

    override fun onDestroy() { rtc.stopHost(); renderer.release(); super.onDestroy() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else super.onBackPressed() }
}
