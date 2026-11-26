package ru.cobrowser.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity(), SyncBridge.Callback {

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val BG = 0xFF0B0B12.toInt()
    private val CARD = 0xFF16161F.toInt()
    private val ACCENT = 0xFF7C3AED.toInt()
    private val LINE = 0xFF5B3BB5.toInt()

    private lateinit var prefs: SharedPreferences
    private lateinit var favs: Favorites
    private lateinit var bridge: SyncBridge
    private lateinit var rootLayout: FrameLayout
    private lateinit var homeLayout: View
    private lateinit var roomLayout: View
    private lateinit var fullscreenHolder: FrameLayout
    private lateinit var web: WebView
    private lateinit var pickerScroll: View
    private lateinit var chatPanel: LinearLayout
    private lateinit var chatLog: TextView
    private lateinit var chatScroll: ScrollView
    private lateinit var roomLabel: TextView
    private lateinit var starBtn: TextView
    private lateinit var chatBtn: TextView
    private lateinit var nameInput: EditText
    private lateinit var codeInput: EditText
    private lateinit var resumeBtn: Button
    private lateinit var myId: String
    private var myName: String = ""

    private val handler = Handler(Looper.getMainLooper())
    private val iconCache = HashMap<String, android.graphics.Bitmap>()

    // --- состояние комнаты ---
    private var roomCode: String? = null
    private var isHost = false
    private var stateReceived = false
    private var currentUrl = ""
    private var lastSentUrl = ""
    private var suppressedUrl: String? = null
    private var mediaPlaying = false
    private var mediaTime = 0.0
    private var mediaAt = 0L
    private var lastActorMe = false
    private var peopleCount = 0
    private var connStatus = "подключение…"
    private var pendingCmd: Triple<String, Double, Long>? = null

    // --- скрипты ---
    private var syncJs = ""
    private var docStartOk = false
    private var msgListenerOk = false

    // --- полноэкранное видео ---
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null

    private var navTask: Runnable? = null

    // ============================== жизненный цикл ==============================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("cobrowser", MODE_PRIVATE)
        favs = Favorites(this)
        val saved = prefs.getString("uid", null)
        if (saved != null) {
            myId = saved
        } else {
            myId = "u" + java.util.UUID.randomUUID().toString().substring(0, 8)
            prefs.edit().putString("uid", myId).apply()
        }
        myName = prefs.getString("name", "") ?: ""

        buildUi()
        bridge = SyncBridge(this, this)
        rootLayout.addView(bridge.view, FrameLayout.LayoutParams(1, 1))
        installScripts()
        showHome()
    }

    override fun onDestroy() {
        super.onDestroy()
        bridge.destroy()
        web.destroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (customView != null) {
            exitFullscreen()
            return
        }
        if (roomLayout.visibility == View.VISIBLE) {
            if (chatPanel.visibility == View.VISIBLE) {
                chatPanel.visibility = View.GONE
            } else if (web.visibility == View.VISIBLE) {
                if (web.canGoBack()) web.goBack() else showPicker()
            } else {
                confirmLeave()
            }
            return
        }
        super.onBackPressed()
    }

    // ============================== UI ==============================

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int, stroke: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radius).toFloat()
        if (stroke != 0) g.setStroke(dp(1), stroke)
        return g
    }

    private fun button(text: String, bg: Int, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.setTextColor(Color.WHITE)
        b.textSize = 16f
        b.background = rounded(bg, 14)
        b.setPadding(dp(16), dp(12), dp(16), dp(12))
        b.setOnClickListener { onClick() }
        return b
    }

    private fun iconBtn(text: String, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 20f
        t.gravity = Gravity.CENTER
        t.setTextColor(Color.WHITE)
        t.setOnClickListener { onClick() }
        t.layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        return t
    }

    private fun input(hint: String): EditText {
        val e = EditText(this)
        e.hint = hint
        e.setHintTextColor(0xFF8A8AA0.toInt())
        e.setTextColor(Color.WHITE)
        e.textSize = 16f
        e.setSingleLine(true)
        e.background = rounded(CARD, 12, LINE)
        e.setPadding(dp(14), dp(12), dp(14), dp(12))
        return e
    }

    private fun lp(topDp: Int = 12): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(MATCH, WRAP)
        p.topMargin = dp(topDp)
        return p
    }

    private fun buildUi() {
        rootLayout = FrameLayout(this)
        rootLayout.setBackgroundColor(BG)
        homeLayout = buildHome()
        roomLayout = buildRoom()
        roomLayout.visibility = View.GONE
        fullscreenHolder = FrameLayout(this)
        fullscreenHolder.setBackgroundColor(Color.BLACK)
        fullscreenHolder.visibility = View.GONE
        rootLayout.addView(homeLayout, FrameLayout.LayoutParams(MATCH, MATCH))
        rootLayout.addView(roomLayout, FrameLayout.LayoutParams(MATCH, MATCH))
        rootLayout.addView(fullscreenHolder, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(rootLayout)
    }

    private fun buildHome(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(24), dp(56), dp(24), dp(24))

        val title = TextView(this)
        title.text = "COBROWSER"
        title.textSize = 34f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(Color.WHITE)
        col.addView(title, lp(0))

        val sub = TextView(this)
        sub.text = "Смотрите любые сайты вместе, синхронно"
        sub.setTextColor(0xFF9A9AB0.toInt())
        sub.textSize = 15f
        col.addView(sub, lp(4))

        nameInput = input("Твоё имя")
        nameInput.setText(myName)
        col.addView(nameInput, lp(32))

        col.addView(button("Создать комнату", ACCENT) { createRoom() }, lp(16))

        codeInput = input("Код комнаты")
        codeInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        col.addView(codeInput, lp(24))
        col.addView(button("Войти по коду", CARD) { joinByCode() }, lp(10))

        resumeBtn = button("Вернуться в комнату", 0xFF2A2A3A.toInt()) { resumeRoom() }
        resumeBtn.visibility = View.GONE
        col.addView(resumeBtn, lp(24))

        val sv = ScrollView(this)
        sv.setBackgroundColor(BG)
        sv.addView(col)
        return sv
    }

    private fun buildRoom(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setBackgroundColor(BG)

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(CARD)
        bar.setPadding(dp(4), 0, dp(4), 0)

        bar.addView(iconBtn("⌂") { showPicker() })
        bar.addView(iconBtn("📍") { pushNavToAll() })
        starBtn = iconBtn("☆") { toggleFavorite() }
        bar.addView(starBtn)

        roomLabel = TextView(this)
        roomLabel.setTextColor(Color.WHITE)
        roomLabel.textSize = 13f
        roomLabel.gravity = Gravity.CENTER
        roomLabel.setOnClickListener { shareCode() }
        bar.addView(roomLabel, LinearLayout.LayoutParams(0, WRAP, 1f))

        chatBtn = iconBtn("💬") { toggleChat() }
        bar.addView(chatBtn)
        bar.addView(iconBtn("↗") { shareCode() })
        bar.addView(iconBtn("✕") { confirmLeave() })
        col.addView(bar, LinearLayout.LayoutParams(MATCH, dp(48)))

        val content = FrameLayout(this)
        pickerScroll = buildPicker()
        content.addView(pickerScroll, FrameLayout.LayoutParams(MATCH, MATCH))

        web = createWebView()
        web.visibility = View.GONE
        content.addView(web, FrameLayout.LayoutParams(MATCH, MATCH))

        chatPanel = buildChat()
        chatPanel.visibility = View.GONE
        content.addView(chatPanel, FrameLayout.LayoutParams(MATCH, dp(280), Gravity.BOTTOM))

        col.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return col
    }

    private fun buildChat(): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setBackgroundColor(0xF2101018.toInt())
        box.setPadding(dp(10), dp(8), dp(10), dp(8))

        chatScroll = ScrollView(this)
        chatLog = TextView(this)
        chatLog.setTextColor(Color.WHITE)
        chatLog.textSize = 14f
        chatScroll.addView(chatLog)
        box.addView(chatScroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val et = input("Сообщение")
        et.imeOptions = EditorInfo.IME_ACTION_SEND
        row.addView(et, LinearLayout.LayoutParams(0, WRAP, 1f))
        val send = button("➤", ACCENT) {
            val text = et.text.toString().trim()
            if (text.isNotEmpty()) {
                et.setText("")
                addChat(myName, text)
                sendMsg(JSONObject().put("t", "chat").put("text", text))
            }
        }
        row.addView(send, LinearLayout.LayoutParams(WRAP, WRAP))
        box.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        return box
    }

    private fun buildPicker(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(16), dp(16), dp(24))

        val head = TextView(this)
        head.text = "Выберите сервис"
        head.textSize = 24f
        head.setTypeface(null, Typeface.BOLD)
        head.setTextColor(Color.WHITE)
        col.addView(head, lp(0))

        val urlInput = input("Ссылка на сайт или поиск")
        urlInput.imeOptions = EditorInfo.IME_ACTION_GO
        urlInput.setOnEditorActionListener { _, _, _ ->
            val t = urlInput.text.toString().trim()
            if (t.isNotEmpty()) {
                urlInput.setText("")
                openLocal(normalizeInput(t))
            }
            true
        }
        col.addView(urlInput, lp(14))

        for (s in Services.list) {
            col.addView(serviceRow(s.name, s.color, s.letter, s.domain) { openLocal(s.url) }, lp(10))
        }
        col.addView(serviceRow("Избранное", 0xFF8B5CF6.toInt(), "★", null) { showFavorites() }, lp(10))

        val sv = ScrollView(this)
        sv.setBackgroundColor(BG)
        sv.addView(col)
        return sv
    }

    private fun serviceRow(title: String, color: Int, letter: String, domain: String?, onClick: () -> Unit): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = rounded(CARD, 16, LINE)
        row.setPadding(dp(14), dp(12), dp(14), dp(12))
        row.setOnClickListener { onClick() }

        val iconBox = FrameLayout(this)
        iconBox.background = rounded(color, 12)
        val letterView = TextView(this)
        letterView.text = letter
        letterView.setTextColor(Color.WHITE)
        letterView.textSize = 20f
        letterView.setTypeface(null, Typeface.BOLD)
        letterView.gravity = Gravity.CENTER
        iconBox.addView(letterView, FrameLayout.LayoutParams(MATCH, MATCH))
        val img = ImageView(this)
        img.scaleType = ImageView.ScaleType.FIT_CENTER
        img.setPadding(dp(6), dp(6), dp(6), dp(6))
        iconBox.addView(img, FrameLayout.LayoutParams(MATCH, MATCH))
        row.addView(iconBox, LinearLayout.LayoutParams(dp(48), dp(48)))

        val name = TextView(this)
        name.text = title
        name.setTextColor(Color.WHITE)
        name.textSize = 18f
        val np = LinearLayout.LayoutParams(0, WRAP, 1f)
        np.leftMargin = dp(16)
        row.addView(name, np)

        val chev = TextView(this)
        chev.text = "›"
        chev.setTextColor(0xFF9A9AB0.toInt())
        chev.textSize = 26f
        row.addView(chev)

        if (domain != null) loadIcon(domain, img, letterView, iconBox)
        return row
    }

    // Реальные иконки сайтов (favicon); если не загрузились — остаётся буква на цветном фоне.
    private fun loadIcon(domain: String, img: ImageView, letterView: TextView, box: View) {
        val cached = iconCache[domain]
        if (cached != null) {
            img.setImageBitmap(cached)
            letterView.visibility = View.GONE
            box.background = rounded(Color.WHITE, 12)
            return
        }
        thread {
            val sources = listOf(
                "https://www.google.com/s2/favicons?domain=$domain&sz=128",
                "https://icons.duckduckgo.com/ip3/$domain.ico",
                "https://$domain/favicon.ico"
            )
            for (src in sources) {
                try {
                    val conn = URL(src).openConnection()
                    conn.connectTimeout = 4000
                    conn.readTimeout = 4000
                    val bmp = conn.getInputStream().use { BitmapFactory.decodeStream(it) }
                    if (bmp != null && bmp.width >= 16) {
                        runOnUiThread {
                            iconCache[domain] = bmp
                            img.setImageBitmap(bmp)
                            letterView.visibility = View.GONE
                            box.background = rounded(Color.WHITE, 12)
                        }
                        return@thread
                    }
                } catch (e: Exception) {
                }
            }
        }
    }

    // ============================== WebView ==============================

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val w = WebView(this)
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.setSupportMultipleWindows(false)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.userAgentString = s.userAgentString.replace("; wv", "").replace("Version/4.0 ", "")
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)

        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url?.toString() ?: return true
                // блокируем переходы в другие приложения (intent://, market:// и т.п.)
                return !(u.startsWith("http://") || u.startsWith("https://"))
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                if (url == null || !url.startsWith("http")) return
                currentUrl = url
                updateStar()
                if (roomCode != null) prefs.edit().putString("last_url_" + roomCode, url).apply()
                if (url == suppressedUrl) return
                suppressedUrl = null
                if (isHost && !isReload && url != lastSentUrl) {
                    navTask?.let { handler.removeCallbacks(it) }
                    val task = Runnable { sendNav(url, false) }
                    navTask = task
                    handler.postDelayed(task, 800)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (!docStartOk) view?.evaluateJavascript(syncJs, null)
                updateStar()
                runPending()
            }
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view != null && callback != null) enterFullscreen(view, callback)
            }

            override fun onHideCustomView() {
                exitFullscreen()
            }
        }
        return w
    }

    private fun installScripts() {
        syncJs = assets.open("sync.js").bufferedReader().use { it.readText() }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            try {
                WebViewCompat.addWebMessageListener(web, "CoBridge", setOf("*")) { _, message, _, _, _ ->
                    val data = message.data
                    if (data != null) onPageEvent(data)
                }
                msgListenerOk = true
            } catch (e: Exception) {
                msgListenerOk = false
            }
        }
        if (!msgListenerOk) {
            web.addJavascriptInterface(NativeEvents(), "CoNative")
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                WebViewCompat.addDocumentStartJavaScript(web, syncJs, setOf("*"))
                docStartOk = true
            } catch (e: Exception) {
                docStartOk = false
            }
        }
    }

    private inner class NativeEvents {
        @JavascriptInterface
        fun postEvent(s: String) {
            runOnUiThread { onPageEvent(s) }
        }
    }

    @SuppressLint("InlinedApi")
    private fun enterFullscreen(v: View, cb: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            cb.onCustomViewHidden()
            return
        }
        customView = v
        customCb = cb
        fullscreenHolder.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        fullscreenHolder.visibility = View.VISIBLE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        hideSystemUi(true)
    }

    private fun exitFullscreen() {
        val v = customView ?: return
        fullscreenHolder.removeView(v)
        fullscreenHolder.visibility = View.GONE
        customCb?.onCustomViewHidden()
        customView = null
        customCb = null
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        hideSystemUi(false)
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi(hide: Boolean) {
        window.decorView.systemUiVisibility = if (hide) {
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        } else {
            View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    // ============================== экраны ==============================

    private fun showHome() {
        roomLayout.visibility = View.GONE
        homeLayout.visibility = View.VISIBLE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val last = prefs.getString("last_room", null)
        val ts = prefs.getLong("last_ts", 0L)
        if (last != null && System.currentTimeMillis() - ts < 12L * 3600 * 1000) {
            resumeBtn.text = "Вернуться в комнату $last"
            resumeBtn.visibility = View.VISIBLE
        } else {
            resumeBtn.visibility = View.GONE
        }
    }

    private fun showPicker() {
        pickerScroll.visibility = View.VISIBLE
        web.visibility = View.GONE
    }

    private fun showWeb() {
        pickerScroll.visibility = View.GONE
        web.visibility = View.VISIBLE
    }

    private fun saveName(): Boolean {
        val n = nameInput.text.toString().trim()
        myName = if (n.isEmpty()) "Гость" + (10..99).random() else n
        prefs.edit().putString("name", myName).apply()
        return true
    }

    private fun randomCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val sb = StringBuilder()
        for (i in 0 until 6) sb.append(alphabet.random())
        return sb.toString()
    }

    private fun createRoom() {
        saveName()
        enterRoom(randomCode(), true)
    }

    private fun joinByCode() {
        val code = codeInput.text.toString().trim().uppercase(Locale.ROOT)
        if (code.length < 4) {
            Toast.makeText(this, "Введи код комнаты", Toast.LENGTH_SHORT).show()
            return
        }
        saveName()
        enterRoom(code, false)
    }

    private fun resumeRoom() {
        val last = prefs.getString("last_room", null) ?: return
        saveName()
        enterRoom(last, prefs.getBoolean("last_host", false))
    }

    private fun enterRoom(code: String, host: Boolean) {
        roomCode = code
        isHost = host
        stateReceived = false
        currentUrl = ""
        lastSentUrl = ""
        suppressedUrl = null
        mediaPlaying = false
        mediaTime = 0.0
        lastActorMe = false
        pendingCmd = null
        peopleCount = 1
        connStatus = "подключение…"
        chatLog.text = ""
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs.edit().putString("last_room", code).putBoolean("last_host", host)
            .putLong("last_ts", System.currentTimeMillis()).apply()

        homeLayout.visibility = View.GONE
        roomLayout.visibility = View.VISIBLE
        web.loadUrl("about:blank")
        showPicker()
        bridge.join(code, myId, myName)
        updateRoomLabel()

        if (host) {
            // после обновления/перезапуска хост возвращается на тот же сайт
            val last = prefs.getString("last_url_$code", null)
            if (last != null) {
                stateReceived = true
                openLocal(last)
            }
        }
    }

    private fun confirmLeave() {
        AlertDialog.Builder(this)
            .setTitle("Выйти из комнаты?")
            .setPositiveButton("Выйти") { _, _ -> leaveRoom() }
            .setNegativeButton("Остаться", null)
            .show()
    }

    private fun leaveRoom() {
        bridge.leave()
        val code = roomCode
        roomCode = null
        if (code != null) prefs.edit().remove("last_room").remove("last_url_$code").apply()
        web.loadUrl("about:blank")
        chatPanel.visibility = View.GONE
        showHome()
    }

    private fun updateRoomLabel() {
        roomLabel.text = "${roomCode ?: ""} · 👥$peopleCount\n$connStatus"
    }

    private fun shareCode() {
        val code = roomCode ?: return
        val i = Intent(Intent.ACTION_SEND)
        i.type = "text/plain"
        i.putExtra(Intent.EXTRA_TEXT, "Заходи в CoBrowser, код комнаты: $code")
        startActivity(Intent.createChooser(i, "Поделиться кодом"))
    }

    private fun toggleChat() {
        if (chatPanel.visibility == View.VISIBLE) {
            chatPanel.visibility = View.GONE
        } else {
            chatPanel.visibility = View.VISIBLE
            chatBtn.text = "💬"
        }
    }

    // ============================== избранное ==============================

    private fun updateStar() {
        starBtn.text = if (currentUrl.isNotEmpty() && favs.has(currentUrl)) "★" else "☆"
    }

    private fun toggleFavorite() {
        if (!currentUrl.startsWith("http")) {
            Toast.makeText(this, "Сначала открой сайт", Toast.LENGTH_SHORT).show()
            return
        }
        if (favs.has(currentUrl)) {
            favs.remove(currentUrl)
            Toast.makeText(this, "Убрано из избранного", Toast.LENGTH_SHORT).show()
        } else {
            val title = web.title
            favs.add(if (title.isNullOrBlank()) currentUrl else title, currentUrl)
            Toast.makeText(this, "Добавлено в избранное", Toast.LENGTH_SHORT).show()
        }
        updateStar()
    }

    private fun showFavorites() {
        val items = favs.all()
        if (items.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Избранное")
                .setMessage("Пока пусто. Открой сайт и нажми ☆ вверху, чтобы сохранить его сюда.")
                .setPositiveButton("Ок", null)
                .show()
            return
        }
        val names = items.map { if (it.first.length > 40) it.first.substring(0, 40) + "…" else it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Избранное")
            .setItems(names) { _, which ->
                val item = items[which]
                AlertDialog.Builder(this)
                    .setTitle(item.first)
                    .setPositiveButton("Открыть") { _, _ -> openLocal(item.second) }
                    .setNegativeButton("Удалить") { _, _ -> favs.remove(item.second) }
                    .setNeutralButton("Отмена", null)
                    .show()
            }
            .show()
    }

    private fun normalizeInput(t: String): String {
        if (t.startsWith("http://") || t.startsWith("https://")) return t
        if (!t.contains(" ") && t.contains(".")) return "https://$t"
        return "https://www.google.com/search?q=" + java.net.URLEncoder.encode(t, "UTF-8")
    }

    // ============================== навигация ==============================

    private fun openLocal(url: String) {
        showWeb()
        if (url == currentUrl && web.url == url) return
        web.loadUrl(url)
        if (isHost) sendNav(url, false)
    }

    private fun openRemote(url: String) {
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return
        suppressedUrl = url
        showWeb()
        if (url == currentUrl && web.url == url) {
            runPending()
        } else {
            web.loadUrl(url)
        }
    }

    private fun sendNav(url: String, force: Boolean) {
        lastSentUrl = url
        mediaPlaying = false
        mediaTime = 0.0
        mediaAt = System.currentTimeMillis()
        sendMsg(JSONObject().put("t", "nav").put("url", url).put("force", force))
    }

    private fun pushNavToAll() {
        if (!currentUrl.startsWith("http")) {
            Toast.makeText(this, "Сначала открой сайт", Toast.LENGTH_SHORT).show()
            return
        }
        sendNav(currentUrl, true)
        Toast.makeText(this, "Все перейдут на эту страницу", Toast.LENGTH_SHORT).show()
    }

    // ============================== синхронизация видео ==============================

    private fun now(): Long = System.currentTimeMillis()

    private fun currentMediaTime(): Double =
        if (mediaPlaying) mediaTime + (now() - mediaAt) / 1000.0 else mediaTime

    private fun onPageEvent(s: String) {
        try {
            val o = JSONObject(s)
            when (o.optString("k")) {
                "media" -> onLocalMedia(o.optString("a"), o.optDouble("time", 0.0))
                "pos" -> {
                    if (lastActorMe && mediaPlaying && roomCode != null) {
                        mediaTime = o.optDouble("time", 0.0)
                        mediaAt = now()
                        sendMsg(JSONObject().put("t", "media").put("a", "tick").put("time", mediaTime))
                    }
                }
                "blocked" -> Toast.makeText(this, "Нажми ▶ в плеере, чтобы включить видео", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
        }
    }

    private fun onLocalMedia(a: String, t: Double) {
        if (roomCode == null) return
        if (a != "play" && a != "pause" && a != "seek") return
        if (a == "play") mediaPlaying = true
        if (a == "pause") mediaPlaying = false
        mediaTime = t
        mediaAt = now()
        lastActorMe = true
        sendMsg(JSONObject().put("t", "media").put("a", a).put("time", t))
    }

    private fun remoteMedia(a: String, t: Double) {
        if (a != "play" && a != "pause" && a != "seek" && a != "tick") return
        if (a == "play") mediaPlaying = true
        if (a == "pause") mediaPlaying = false
        mediaTime = t
        mediaAt = now()
        if (a != "tick") lastActorMe = false
        runCmd(a, t)
    }

    private fun runCmd(a: String, t: Double) {
        if (a != "play" && a != "pause" && a != "seek" && a != "tick") return
        val js = "window.__coCmd&&window.__coCmd({a:'" + a + "',time:" + String.format(Locale.US, "%.3f", t) + "})"
        web.evaluateJavascript(js, null)
    }

    private fun runPending() {
        val p = pendingCmd ?: return
        pendingCmd = null
        var t = p.second
        if (p.first == "play") t += (now() - p.third) / 1000.0
        runCmd(p.first, t)
    }

    private fun sendMsg(o: JSONObject) {
        if (roomCode == null) return
        o.put("id", myId)
        o.put("name", myName)
        bridge.send(o.toString())
    }

    private fun sendState() {
        if (!currentUrl.startsWith("http")) return
        val m = JSONObject().put("playing", mediaPlaying).put("time", currentMediaTime())
        sendMsg(JSONObject().put("t", "state").put("url", currentUrl).put("media", m))
    }

    private fun addChat(name: String, text: String) {
        chatLog.append((if (name.isBlank()) "?" else name) + ": " + text + "\n")
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
        if (chatPanel.visibility != View.VISIBLE) chatBtn.text = "💬•"
    }

    // ============================== SyncBridge.Callback ==============================

    override fun onReady() {
        connStatus = "онлайн"
        updateRoomLabel()
        sendMsg(JSONObject().put("t", "hello"))
    }

    override fun onStatus(status: String) {
        connStatus = when {
            status.startsWith("error") -> "нет связи с сервером"
            status == "CLOSED" || status == "CHANNEL_ERROR" || status == "TIMED_OUT" -> "переподключение…"
            else -> status
        }
        updateRoomLabel()
    }

    override fun onPeople(json: String) {
        try {
            peopleCount = JSONArray(json).length()
        } catch (e: Exception) {
        }
        updateRoomLabel()
    }

    override fun onMessage(json: String) {
        try {
            val o = JSONObject(json)
            if (o.optString("id") == myId) return
            when (o.optString("t")) {
                "hello" -> sendState()
                "state" -> {
                    if (stateReceived) return
                    stateReceived = true
                    val url = o.optString("url")
                    val m = o.optJSONObject("media")
                    if (m != null) {
                        val a = if (m.optBoolean("playing")) "play" else "pause"
                        val t = m.optDouble("time", 0.0)
                        mediaPlaying = a == "play"
                        mediaTime = t
                        mediaAt = now()
                        pendingCmd = Triple(a, t, now())
                    }
                    if (url.isNotEmpty()) openRemote(url)
                }
                "nav" -> {
                    if (isHost && !o.optBoolean("force")) return
                    val url = o.optString("url")
                    if (url.isNotEmpty()) {
                        mediaPlaying = false
                        mediaTime = 0.0
                        pendingCmd = null
                        openRemote(url)
                    }
                }
                "media" -> remoteMedia(o.optString("a"), o.optDouble("time", 0.0))
                "chat" -> addChat(o.optString("name"), o.optString("text"))
            }
        } catch (e: Exception) {
        }
    }
}
