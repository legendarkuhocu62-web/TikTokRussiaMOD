package ru.cobrowser.app

import android.annotation.SuppressLint
import android.app.Activity
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/**
 * Невидимый WebView, который держит realtime-канал Supabase (WebSocket).
 * Через него ходят только короткие команды: ссылка, пауза/плей/перемотка, чат.
 * WebRTC не нужен, поэтому работает и через VPN, и в мобильных сетях.
 */
class SyncBridge(private val activity: Activity, private val cb: Callback) {

    interface Callback {
        fun onMessage(json: String)
        fun onPeople(json: String)
        fun onStatus(status: String)
        fun onReady()
    }

    val view: WebView = WebView(activity)
    private var pageLoaded = false
    private var ready = false
    private val pendingJs = ArrayList<String>()
    private val queue = ArrayList<String>()

    init {
        setup()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setup() {
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.addJavascriptInterface(Api(), "Android")
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView?, url: String?) {
                pageLoaded = true
                for (js in pendingJs) v?.evaluateJavascript(js, null)
                pendingJs.clear()
            }
        }
        val html = activity.assets.open("bridge.html").bufferedReader().use { it.readText() }
        view.loadDataWithBaseURL("https://cobrowser.app/", html, "text/html", "UTF-8", null)
    }

    private fun eval(js: String) {
        if (!pageLoaded) pendingJs.add(js) else view.evaluateJavascript(js, null)
    }

    fun join(room: String, id: String, name: String) {
        ready = false
        eval("coJoin(" + JSONObject.quote(room) + "," + JSONObject.quote(id) + "," + JSONObject.quote(name) + ")")
    }

    fun send(json: String) {
        if (!ready) {
            queue.add(json)
            return
        }
        eval("coSend(" + JSONObject.quote(json) + ")")
    }

    fun leave() {
        ready = false
        queue.clear()
        eval("coLeave()")
    }

    fun destroy() {
        try {
            view.destroy()
        } catch (e: Exception) {
        }
    }

    private fun flush() {
        val items = ArrayList(queue)
        queue.clear()
        for (m in items) eval("coSend(" + JSONObject.quote(m) + ")")
    }

    private inner class Api {
        @JavascriptInterface
        fun onMessage(json: String) {
            activity.runOnUiThread { cb.onMessage(json) }
        }

        @JavascriptInterface
        fun onPeople(json: String) {
            activity.runOnUiThread { cb.onPeople(json) }
        }

        @JavascriptInterface
        fun onStatus(status: String) {
            activity.runOnUiThread { cb.onStatus(status) }
        }

        @JavascriptInterface
        fun onReady() {
            activity.runOnUiThread {
                ready = true
                flush()
                cb.onReady()
            }
        }
    }
}
