package ru.cobrowser.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.Base64
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executor
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
    private var peopleCount = 0
    private var connStatus = "подключение…"
    private var pendingCmd: Triple<String, Double, Long>? = null

    // --- права и участники ---
    private var hostId = ""
    private var controllerId = ""
    private var controllerSeen = false
    private var mediaKnown = false
    private var lastToastAt = 0L
    private val peopleIds = ArrayList<String>()
    private val peopleNames = HashMap<String, String>()

    // --- аватары ---
    private val avatars = HashMap<String, Bitmap>()
    private val letterCache = HashMap<String, Bitmap>()
    private var myAvatar: Bitmap? = null
    private var myAvatarB64: String? = null
    private lateinit var homeAvatar: ImageView
    private lateinit var chatBox: LinearLayout
    private var membersDialog: AlertDialog? = null

    // --- режим «зритель»: поток играет нативный плеер ---
    private var streamUrl: String? = null
    private var streamRef: String? = null
    private var streamOrigin: String? = null
    private var streamFailedUrl: String? = null
    private var player: ExoPlayer? = null
    private lateinit var playerHolder: FrameLayout
    private lateinit var topBar: LinearLayout
    private var streamInitialApplied = false
    private var streamFull = false
    private var videoW = 0
    private var videoH = 0
    private var ytFor: String? = null
    private var ytFailedId: String? = null
    private var ytTemplate: String? = null
    private var ownerUrl = ""
    private var ownerSeen = false
    private var pickingUntil = 0L
    private var seeking = false
    private lateinit var videoFrame: FrameLayout
    private lateinit var controlsBar: LinearLayout
    private lateinit var playPauseBtn: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var timeText: TextView

    // --- маршрутизация российских сайтов мимо VPN ---
    private var routeProxy: RouteProxy? = null
    private var prevSelector: java.net.ProxySelector? = null

    @Volatile
    private var extrasCache: List<String> = emptyList()

    // главный раз в 10 секунд подтверждает состояние, поэтому у зрителей ничего не «зависает»
    private val stateBeat = object : Runnable {
        override fun run() {
            if (roomCode != null && amOwner()) {
                sendState()
                handler.postDelayed(this, 10000)
            }
        }
    }

    private val uiTick = object : Runnable {
        override fun run() {
            updateStreamControls()
            if (player != null) handler.postDelayed(this, 500)
        }
    }
    private var lastManifestUrl: String? = null
    private var lastManifestAt = 0L
    private var lastFileUrl: String? = null
    private var lastFileAt = 0L
    private val mediaReq = HashMap<String, Pair<String?, String?>>()

    // --- зеркалирование экрана ---
    private lateinit var pinBtn: TextView
    private lateinit var shield: View
    private var mirrorLock = true
    private var lastScroll = -1.0

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
        loadMyAvatar()
        extrasCache = parseExtras(prefs.getString("route_extra", "") ?: "")
        applyRouting(prefs.getBoolean("route_ru", true))

        buildUi()
        bridge = SyncBridge(this, this)
        rootLayout.addView(bridge.view, FrameLayout.LayoutParams(1, 1))
        installScripts()
        showHome()
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
        bridge.destroy()
        web.destroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (customView != null) {
            exitFullscreen()
            return
        }
        if (streamFull) {
            toggleStreamFullscreen()
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

        homeAvatar = ImageView(this)
        circle(homeAvatar)
        homeAvatar.setOnClickListener { pickAvatar() }
        val hap = LinearLayout.LayoutParams(dp(96), dp(96))
        hap.topMargin = dp(28)
        col.addView(homeAvatar, hap)

        val cap = TextView(this)
        cap.text = "Нажми на круг, чтобы выбрать свой аватар"
        cap.setTextColor(0xFF9A9AB0.toInt())
        cap.textSize = 13f
        col.addView(cap, lp(6))

        nameInput = input("Твоё имя")
        nameInput.setText(myName)
        col.addView(nameInput, lp(16))

        col.addView(button("Создать комнату", ACCENT) { createRoom() }, lp(16))

        codeInput = input("Код комнаты")
        codeInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        col.addView(codeInput, lp(24))
        col.addView(button("Войти по коду", CARD) { joinByCode() }, lp(10))

        resumeBtn = button("Вернуться в комнату", 0xFF2A2A3A.toInt()) { resumeRoom() }
        resumeBtn.visibility = View.GONE
        col.addView(resumeBtn, lp(24))

        val sw = Switch(this)
        sw.text = "🇷🇺 Российские сайты напрямую, мимо VPN"
        sw.setTextColor(Color.WHITE)
        sw.textSize = 15f
        sw.isChecked = prefs.getBoolean("route_ru", true)
        sw.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean("route_ru", on).apply()
            applyRouting(on)
            Toast.makeText(this, if (on) "Маршрутизация РФ включена" else "Маршрутизация РФ выключена", Toast.LENGTH_SHORT).show()
        }
        col.addView(sw, lp(32))
        col.addView(button("Свои домены для прямого маршрута", 0xFF2A2A3A.toInt()) { editRouteDomains() }, lp(10))

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

        topBar = bar
        bar.addView(iconBtn("⌂") { onHomeClick() })
        pinBtn = iconBtn("📍") { onPinClick() }
        bar.addView(pinBtn)
        starBtn = iconBtn("☆") { toggleFavorite() }
        bar.addView(starBtn)

        roomLabel = TextView(this)
        roomLabel.setTextColor(Color.WHITE)
        roomLabel.textSize = 13f
        roomLabel.gravity = Gravity.CENTER
        roomLabel.maxLines = 2
        roomLabel.ellipsize = TextUtils.TruncateAt.END
        roomLabel.setOnClickListener { shareCode() }
        bar.addView(roomLabel, LinearLayout.LayoutParams(0, WRAP, 1f))

        chatBtn = iconBtn("💬") { toggleChat() }
        bar.addView(chatBtn)
        bar.addView(iconBtn("👥") { showMembers() })
        bar.addView(iconBtn("✕") { confirmLeave() })
        col.addView(bar, LinearLayout.LayoutParams(MATCH, dp(48)))

        val content = FrameLayout(this)
        pickerScroll = buildPicker()
        content.addView(pickerScroll, FrameLayout.LayoutParams(MATCH, MATCH))

        web = createWebView()
        web.visibility = View.GONE
        content.addView(web, FrameLayout.LayoutParams(MATCH, MATCH))

        shield = View(this)
        shield.setOnTouchListener { _, _ -> true }
        shield.visibility = View.GONE
        content.addView(shield, FrameLayout.LayoutParams(MATCH, MATCH))

        playerHolder = FrameLayout(this)
        playerHolder.setBackgroundColor(Color.BLACK)
        playerHolder.visibility = View.GONE
        videoFrame = FrameLayout(this)
        videoFrame.setOnClickListener { toggleStreamFullscreen() }
        playerHolder.addView(videoFrame, FrameLayout.LayoutParams(MATCH, MATCH))
        playerHolder.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (videoW > 0 && videoH > 0) fitVideo(videoW, videoH)
        }

        // пульт для того, у кого управление, когда браузер у главного
        controlsBar = LinearLayout(this)
        controlsBar.orientation = LinearLayout.HORIZONTAL
        controlsBar.gravity = Gravity.CENTER_VERTICAL
        controlsBar.setBackgroundColor(0xCC000000.toInt())
        controlsBar.setPadding(dp(4), 0, dp(12), 0)
        playPauseBtn = iconBtn("▶") { onPlayPauseClick() }
        seekBar = SeekBar(this)
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {}

            override fun onStartTrackingTouch(sb: SeekBar?) {
                seeking = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                seeking = false
                val sec = (sb?.progress ?: 0).toDouble()
                if (amController() && !amOwner()) {
                    sendMsg(JSONObject().put("t", "cmd").put("a", "seek").put("time", sec))
                }
            }
        })
        timeText = TextView(this)
        timeText.setTextColor(Color.WHITE)
        timeText.textSize = 12f
        controlsBar.addView(playPauseBtn)
        controlsBar.addView(seekBar, LinearLayout.LayoutParams(0, WRAP, 1f))
        controlsBar.addView(timeText)
        controlsBar.visibility = View.GONE
        playerHolder.addView(controlsBar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        content.addView(playerHolder, FrameLayout.LayoutParams(MATCH, MATCH))

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
        chatBox = LinearLayout(this)
        chatBox.orientation = LinearLayout.VERTICAL
        chatScroll.addView(chatBox)
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
                addChat(myId, myName, text)
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

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val r = request ?: return null
                val url = r.url.toString()
                val low = url.substringBefore('#').lowercase(Locale.ROOT)
                val path = low.substringBefore('?')
                val manifest = path.endsWith(".m3u8") || path.endsWith(".mpd") || low.contains(".m3u8?")
                val file = path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".m4v") ||
                    path.endsWith(".mkv") || path.endsWith(".mov")
                if (manifest || file) {
                    val h = r.requestHeaders
                    val ref = h?.get("Referer") ?: h?.get("referer")
                    val org = h?.get("Origin") ?: h?.get("origin")
                    handler.post { onMediaRequest(url, manifest, ref, org) }
                }
                return null
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                if (url == null || !url.startsWith("http")) return
                // служебные адреса нашей страницы с плеером YouTube не считаем переходом
                if (ytFor != null && (url == "https://www.youtube.com/" || url == "https://www.youtube.com")) return
                val yt = youtubeId(url)
                if (yt == null) {
                    ytFor = null
                } else if (ytFor != url && yt.first != ytFailedId) {
                    // открылась страница видео YouTube: подменяем её официальным плеером, он синхронизируется точно
                    loadYouTubePage(yt.first, yt.second, url)
                }
                if (url != currentUrl) {
                    lastManifestUrl = null
                    lastFileUrl = null
                }
                currentUrl = url
                updateStar()
                if (roomCode != null) prefs.edit().putString("last_url_" + roomCode, url).apply()
                if (url == suppressedUrl) return
                suppressedUrl = null
                if ((amOwner() || amController()) && !isReload && url != lastSentUrl) {
                    navTask?.let { handler.removeCallbacks(it) }
                    val task = Runnable {
                        if (amOwner()) sendNav(url, false) else sendCmdNav(url)
                    }
                    navTask = task
                    handler.postDelayed(task, 800)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (!docStartOk) view?.evaluateJavascript(syncJs, null)
                updateStar()
                runRole()
                runPending()
                if (!amOwner() && lastScroll >= 0) {
                    applyScroll()
                    handler.postDelayed({ applyScroll() }, 1200)
                }
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
        bindAvatar(homeAvatar, myId, if (myName.isBlank()) "?" else myName)
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
        updateShield()
    }

    private fun showWeb() {
        pickerScroll.visibility = View.GONE
        web.visibility = View.VISIBLE
        updateShield()
    }

    // Следящий видит экран управляющего и не может случайно «сломать» страницу своими нажатиями.
    private fun updateShield() {
        val on = roomCode != null && !amController() && mirrorLock && web.visibility == View.VISIBLE
        shield.visibility = if (on) View.VISIBLE else View.GONE
        pinBtn.text = if (amController()) "📍" else if (mirrorLock) "🔒" else "🔓"
    }

    private fun runRole() {
        if (roomCode != null) web.evaluateJavascript("window.__coCtl=" + amController(), null)
    }

    private fun onPinClick() {
        if (amController()) {
            pushNavToAll()
            return
        }
        mirrorLock = !mirrorLock
        updateShield()
        Toast.makeText(
            this,
            if (mirrorLock) "Режим «смотрим вместе»: экран повторяет действия управляющего"
            else "Свободный режим: можешь сам листать и нажимать",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun applyScroll() {
        if (lastScroll < 0 || amOwner()) return
        web.evaluateJavascript(
            "window.__coUi&&window.__coUi({e:'scroll',y:" + String.format(Locale.US, "%.4f", lastScroll) + "})", null
        )
    }

    private fun cleanUi(o: JSONObject): JSONObject? {
        val e = o.optString("e")
        val out = JSONObject().put("e", e)
        when (e) {
            "scroll" -> out.put("y", o.optDouble("y", 0.0).coerceIn(0.0, 1.0))
            "click" -> {
                out.put("sel", o.optString("sel").take(400))
                out.put("nx", o.optDouble("nx", 0.5).coerceIn(0.0, 1.0))
                out.put("ny", o.optDouble("ny", 0.5).coerceIn(0.0, 1.0))
            }
            "input" -> {
                out.put("sel", o.optString("sel").take(400))
                out.put("val", o.optString("val").take(500))
            }
            else -> return null
        }
        return out
    }

    private fun onLocalUi(o: JSONObject) {
        if (!amController() || roomCode == null) return
        val c = cleanUi(o) ?: return
        if (c.optString("e") == "scroll") lastScroll = c.optDouble("y", 0.0)
        // главный рассылает всем сам; у кого только пульт — просит главного повторить действие
        c.put("t", if (amOwner()) "ui" else "uic")
        sendMsg(c)
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
        pendingCmd = null
        leaveStream()
        lastManifestUrl = null
        lastFileUrl = null
        mediaKnown = false
        controllerSeen = false
        ownerSeen = false
        ownerUrl = ""
        pickingUntil = 0L
        handler.removeCallbacks(stateBeat)
        hostId = if (host) myId else ""
        controllerId = if (host) myId else ""
        peopleIds.clear()
        peopleCount = 1
        connStatus = "подключение…"
        chatBox.removeAllViews()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs.edit().putString("last_room", code).putBoolean("last_host", host)
            .putLong("last_ts", System.currentTimeMillis()).apply()

        homeLayout.visibility = View.GONE
        roomLayout.visibility = View.VISIBLE
        ytFor = null
        ytFailedId = null
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
        handler.removeCallbacks(stateBeat)
        leaveStream()
        bridge.leave()
        val code = roomCode
        roomCode = null
        if (code != null) prefs.edit().remove("last_room").remove("last_url_$code").apply()
        web.loadUrl("about:blank")
        chatPanel.visibility = View.GONE
        showHome()
    }

    private fun updateRoomLabel() {
        val hostGone = hostId.isNotEmpty() && hostId != myId && ownerSeen && !peopleIds.contains(hostId)
        val crown = if (hostGone) " · ⚠ хост не в сети"
        else if (controllerId.isNotEmpty()) " · 👑" + nameOf(controllerId) else ""
        val statusShort = if (connStatus == "онлайн") "●" else connStatus
        roomLabel.text = "${roomCode ?: ""} · 👥$peopleCount\n$statusShort$crown"
        updateShield()
        runRole()
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
        if (!amController() && controllerId.isNotEmpty()) {
            Toast.makeText(this, "Сайт выбирает тот, у кого управление: " + nameOf(controllerId), Toast.LENGTH_SHORT).show()
            return
        }
        if (amController() && !amOwner() && hostId.isNotEmpty()) {
            // браузер у главного: просим его открыть сайт, а не открываем свой
            sendCmdNav(url)
            Toast.makeText(this, "Главный открывает сайт…", Toast.LENGTH_SHORT).show()
            return
        }
        ownerNavigate(url)
    }

    // Открывает сайт в настоящем браузере (он всегда у главного) и сообщает всем.
    private fun ownerNavigate(url: String) {
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return
        showWeb()
        if (url == currentUrl && (ytFor == url || web.url == url)) return
        loadAny(url)
        if (amOwner()) sendNav(url, false)
    }

    // Обычные сайты грузим как есть, а видео YouTube — через официальный плеер.
    private fun loadAny(url: String) {
        val yt = youtubeId(url)
        if (yt != null && yt.first != ytFailedId) {
            loadYouTubePage(yt.first, yt.second, url)
        } else {
            ytFor = null
            web.loadUrl(url)
        }
    }

    private fun youtubeId(url: String): Pair<String, Int>? {
        return try {
            val u = Uri.parse(url)
            val host = (u.host ?: return null).lowercase(Locale.ROOT)
                .removePrefix("www.").removePrefix("m.").removePrefix("music.")
            var id: String? = null
            val seg = u.pathSegments
            if (host == "youtu.be") {
                id = seg.firstOrNull()
            } else if (host == "youtube.com" || host == "youtube-nocookie.com") {
                if (u.path == "/watch") {
                    id = u.getQueryParameter("v")
                } else if (seg.size >= 2 && (seg[0] == "shorts" || seg[0] == "live" || seg[0] == "embed" || seg[0] == "v")) {
                    id = seg[1]
                }
            }
            if (id == null || !Regex("^[A-Za-z0-9_-]{11}$").matches(id)) return null
            Pair(id, parseYtTime(u.getQueryParameter("t") ?: u.getQueryParameter("start")))
        } catch (e: Exception) {
            null
        }
    }

    private fun parseYtTime(t: String?): Int {
        if (t == null) return 0
        t.toIntOrNull()?.let { return it }
        val m = Regex("^(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?$").find(t) ?: return 0
        val h = m.groupValues[1].toIntOrNull() ?: 0
        val mi = m.groupValues[2].toIntOrNull() ?: 0
        val sec = m.groupValues[3].toIntOrNull() ?: 0
        return h * 3600 + mi * 60 + sec
    }

    private fun loadYouTubePage(id: String, start: Int, watchUrl: String) {
        ytFor = watchUrl
        currentUrl = watchUrl
        var tpl = ytTemplate
        if (tpl == null) {
            tpl = assets.open("yt.html").bufferedReader().use { it.readText() }
            ytTemplate = tpl
        }
        val html = tpl.replace("__VIDEO_ID__", id).replace("__START__", start.toString())
        handler.post {
            web.loadDataWithBaseURL("https://www.youtube.com/", html, "text/html", "UTF-8", watchUrl)
        }
    }

    // Автор запретил встраивание (или плеер не загрузился): показываем обычную страницу.
    private fun onYtError() {
        val u = ytFor ?: return
        ytFailedId = youtubeId(u)?.first
        ytFor = null
        toastSoon("Это видео нельзя встроить — открываю обычную страницу")
        web.loadUrl(u)
    }

    private fun sendCmdNav(url: String) {
        lastSentUrl = url
        sendMsg(JSONObject().put("t", "cmdnav").put("url", url))
    }

    private fun openRemote(url: String) {
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return
        suppressedUrl = url
        showWeb()
        if (url == currentUrl && (ytFor == url || web.url == url)) {
            runPending()
        } else {
            loadAny(url)
        }
    }

    private fun sendNav(url: String, force: Boolean) {
        lastSentUrl = url
        ownerUrl = url
        mediaPlaying = false
        mediaTime = 0.0
        mediaKnown = false
        lastScroll = -1.0
        streamUrl = null
        streamFailedUrl = null
        mediaAt = System.currentTimeMillis()
        sendMsg(JSONObject().put("t", "nav").put("url", url).put("force", force))
    }

    private fun pushNavToAll() {
        if (!amController()) {
            Toast.makeText(this, "Страницу для всех меняет тот, у кого управление: " + nameOf(controllerId), Toast.LENGTH_SHORT).show()
            return
        }
        if (!currentUrl.startsWith("http")) {
            Toast.makeText(this, "Сначала открой сайт", Toast.LENGTH_SHORT).show()
            return
        }
        if (amOwner()) sendNav(currentUrl, true) else sendCmdNav(currentUrl)
        Toast.makeText(this, "Все перейдут на эту страницу", Toast.LENGTH_SHORT).show()
    }

    // ============================== синхронизация видео ==============================

    private fun now(): Long = System.currentTimeMillis()

    private fun currentMediaTime(): Double =
        if (mediaPlaying) mediaTime + (now() - mediaAt) / 1000.0 else mediaTime

    // ============================== режим «зритель»: поток ==============================

    private fun originOf(url: String?): String? {
        if (url == null) return null
        return try {
            val u = java.net.URI(url)
            if (u.scheme == null || u.host == null) null
            else u.scheme + "://" + u.host + (if (u.port != -1) ":" + u.port else "")
        } catch (e: Exception) {
            null
        }
    }

    // Приложение видит все запросы страницы и запоминает адреса видео/плейлистов вместе с заголовками.
    private fun onMediaRequest(url: String, manifest: Boolean, ref: String?, org: String?) {
        if (mediaReq.size > 80) mediaReq.clear()
        mediaReq[url] = Pair(ref, org)
        val t = now()
        if (manifest) {
            // первым идёт главный плейлист, дальше только его варианты: оставляем первый
            if (lastManifestUrl == null || t - lastManifestAt > 8000) lastManifestUrl = url
            lastManifestAt = t
        } else {
            lastFileUrl = url
            lastFileAt = t
        }
    }

    // Страница сообщила, что в ней играет длинное видео: запоминаем источник и рассылаем зрителям.
    private fun onPageSrc(o: JSONObject) {
        if (!amOwner() || roomCode == null) return
        val url = o.optString("url")
        val page = o.optString("page")
        var chosen: String? = null
        if (url.startsWith("http://") || url.startsWith("https://")) {
            chosen = url
        } else if (lastManifestUrl != null && now() - lastManifestAt < 180000) {
            chosen = lastManifestUrl
        } else if (lastFileUrl != null && now() - lastFileAt < 180000) {
            chosen = lastFileUrl
        }
        if (chosen == null || chosen == streamUrl) return
        val h = mediaReq[chosen]
        val ref = h?.first ?: (if (page.startsWith("http")) page else currentUrl)
        val org = h?.second ?: originOf(ref)
        streamUrl = chosen
        streamRef = ref
        streamOrigin = org
        sendMsg(
            JSONObject().put("t", "src").put("url", chosen)
                .put("ref", ref).put("org", org ?: "")
        )
    }

    private fun applySrc(o: JSONObject): Boolean {
        val url = o.optString("url")
        if (!(url.startsWith("http://") || url.startsWith("https://")) || url.length > 3000) return false
        if (url == streamUrl && player != null) return false
        streamUrl = url
        streamRef = o.optString("ref").take(2000)
        streamOrigin = o.optString("org").take(300)
        if (url != streamFailedUrl) streamFailedUrl = null
        return true
    }

    private fun startStream() {
        val url = streamUrl ?: return
        if (amOwner() || roomCode == null || url == streamFailedUrl || now() < pickingUntil) return
        releasePlayer()
        streamInitialApplied = false
        val headers = HashMap<String, String>()
        val r = streamRef
        if (r != null && r.isNotEmpty()) headers["Referer"] = r
        val org = streamOrigin
        if (org != null && org.isNotEmpty()) headers["Origin"] = org
        try {
            val dsf = DefaultHttpDataSource.Factory()
                .setUserAgent(web.settings.userAgentString)
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(headers)
            val p = ExoPlayer.Builder(this)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dsf))
                .build()
            // не перехватываем звук у Discord и других приложений
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                false
            )
            val sv = SurfaceView(this)
            videoFrame.removeAllViews()
            videoFrame.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH))
            p.setVideoSurfaceView(sv)
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY && !streamInitialApplied) {
                        streamInitialApplied = true
                        exoApply(if (mediaKnown && mediaPlaying) "play" else "pause", currentMediaTime())
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    onStreamError(url)
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    videoW = videoSize.width
                    videoH = videoSize.height
                    fitVideo(videoW, videoH)
                }
            })
            p.setMediaItem(MediaItem.fromUri(url))
            p.prepare()
            player = p
            handler.removeCallbacks(uiTick)
            handler.post(uiTick)
        } catch (e: Exception) {
            onStreamError(url)
            return
        }
        // свою страницу зрителя останавливаем: никакой рекламы и двойного звука
        web.loadUrl("about:blank")
        pickerScroll.visibility = View.GONE
        web.visibility = View.GONE
        playerHolder.visibility = View.VISIBLE
        updateShield()
        toastSoon("Смотрим вместе. Управляет " + nameOf(controllerId))
    }

    private fun onStreamError(url: String) {
        streamFailedUrl = url
        releasePlayer()
        playerHolder.visibility = View.GONE
        if (streamFull) toggleStreamFullscreen()
        Toast.makeText(this, "Поток не запустился, показываю саму страницу", Toast.LENGTH_LONG).show()
        if (currentUrl.startsWith("http")) {
            suppressedUrl = currentUrl
            showWeb()
            web.loadUrl(currentUrl)
        }
    }

    private fun releasePlayer() {
        val p = player
        player = null
        if (p != null) {
            try {
                p.release()
            } catch (e: Exception) {
            }
        }
        handler.removeCallbacks(uiTick)
        if (this::videoFrame.isInitialized) videoFrame.removeAllViews()
        if (this::controlsBar.isInitialized) controlsBar.visibility = View.GONE
        videoW = 0
        videoH = 0
    }

    private fun leaveStream() {
        releasePlayer()
        if (this::playerHolder.isInitialized) playerHolder.visibility = View.GONE
        if (streamFull) toggleStreamFullscreen()
        streamUrl = null
        streamFailedUrl = null
    }

    private fun exoApply(a: String, t: Double) {
        val p = player ?: return
        val cur = p.currentPosition / 1000.0
        val ms = (t * 1000).toLong()
        when (a) {
            "pause" -> {
                p.playWhenReady = false
                if (Math.abs(cur - t) > 0.5) p.seekTo(ms)
            }
            "play" -> {
                if (Math.abs(cur - t) > 0.7) p.seekTo(ms)
                p.playWhenReady = true
            }
            "seek" -> p.seekTo(ms)
            "tick" -> {
                if (Math.abs(cur - t) > 2.0) p.seekTo(ms)
                if (!p.playWhenReady) p.playWhenReady = true
            }
        }
    }

    private fun fitVideo(vw: Int, vh: Int) {
        if (!this::videoFrame.isInitialized || videoFrame.childCount == 0) return
        val hw = videoFrame.width
        val hh = videoFrame.height
        if (hw == 0 || hh == 0 || vw == 0 || vh == 0) return
        val scale = Math.min(hw.toFloat() / vw, hh.toFloat() / vh)
        val lp = FrameLayout.LayoutParams((vw * scale).toInt(), (vh * scale).toInt(), Gravity.CENTER)
        videoFrame.getChildAt(0).layoutParams = lp
    }

    // Нажатие на видео — полный экран (боком) и обратно.
    private fun toggleStreamFullscreen() {
        streamFull = !streamFull
        topBar.visibility = if (streamFull) View.GONE else View.VISIBLE
        requestedOrientation = if (streamFull) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        hideSystemUi(streamFull)
    }

    private fun onHomeClick() {
        if (amController() || controllerId.isEmpty()) {
            if (amController() && !amOwner() && player != null) {
                // выбираем другой сайт: на время прячем плеер, главный откроет страницу для всех
                pickingUntil = now() + 60000
                releasePlayer()
                playerHolder.visibility = View.GONE
            }
            showPicker()
        } else {
            Toast.makeText(this, "Сайт выбирает тот, у кого управление: " + nameOf(controllerId), Toast.LENGTH_SHORT).show()
        }
    }

    // Передача управления НИЧЕГО не перезагружает: браузер остаётся у главного, меняется только тот, кто им управляет.
    private fun onRoleChanged() {
        if (roomCode == null) return
        updateShield()
        updateStreamControls()
        runRole()
    }

    private fun onPlayPauseClick() {
        val p = player ?: return
        if (!amController() || amOwner()) return
        sendMsg(
            JSONObject().put("t", "cmd").put("a", if (p.playWhenReady) "pause" else "play")
                .put("time", p.currentPosition / 1000.0)
        )
    }

    private fun fmtTime(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%d:%02d", m, sec)
    }

    private fun updateStreamControls() {
        if (!this::controlsBar.isInitialized) return
        val p = player
        val show = p != null && amController() && !amOwner()
        controlsBar.visibility = if (show) View.VISIBLE else View.GONE
        if (p == null || !show) return
        val dur = p.duration
        val pos = p.currentPosition
        if (dur != C.TIME_UNSET && dur > 0) seekBar.max = (dur / 1000).toInt()
        if (!seeking) seekBar.progress = (pos / 1000).toInt()
        playPauseBtn.text = if (p.playWhenReady) "⏸" else "▶"
        timeText.text = fmtTime(pos) + (if (dur != C.TIME_UNSET && dur > 0) " / " + fmtTime(dur) else "")
    }

    // ============================== маршрутизация РФ ==============================

    private fun parseExtras(raw: String): List<String> =
        raw.split("\n", ",", " ").map { it.trim() }.filter { it.isNotEmpty() }

    private fun editRouteDomains() {
        val et = EditText(this)
        et.setText(prefs.getString("route_extra", "") ?: "")
        et.hint = "по одному на строку, например: example-cdn.com"
        et.minLines = 4
        et.gravity = Gravity.TOP
        et.setTextColor(Color.WHITE)
        et.setHintTextColor(0xFF8A8AA0.toInt())
        AlertDialog.Builder(this)
            .setTitle("Домены напрямую")
            .setMessage("Сайты .ru, .su, .рф, ВК, Яндекс и другие российские уже включены. Добавь свои:")
            .setView(et)
            .setPositiveButton("Сохранить") { _, _ ->
                val raw = et.text.toString()
                prefs.edit().putString("route_extra", raw).apply()
                extrasCache = parseExtras(raw)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // Российские сайты ходят напрямую (мимо VPN), остальные — как обычно.
    private fun applyRouting(on: Boolean) {
        try {
            if (on) {
                var rp = routeProxy
                if (rp == null) {
                    rp = RouteProxy(this) { host -> RuRoutes.isDirect(host, extrasCache) }
                    routeProxy = rp
                }
                val port = rp.start()
                if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                    val cfg = ProxyConfig.Builder().addProxyRule("127.0.0.1:$port").build()
                    ProxyController.getInstance().setProxyOverride(cfg, Executor { it.run() }, Runnable { })
                }
                if (prevSelector == null) prevSelector = java.net.ProxySelector.getDefault()
                java.net.ProxySelector.setDefault(object : java.net.ProxySelector() {
                    override fun select(uri: java.net.URI?): MutableList<java.net.Proxy> {
                        return mutableListOf(
                            java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress("127.0.0.1", port))
                        )
                    }

                    override fun connectFailed(uri: java.net.URI?, sa: java.net.SocketAddress?, ioe: java.io.IOException?) {}
                })
            } else {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                    ProxyController.getInstance().clearProxyOverride(Executor { it.run() }, Runnable { })
                }
                val ps = prevSelector
                if (ps != null) {
                    java.net.ProxySelector.setDefault(ps)
                    prevSelector = null
                }
                routeProxy?.stop()
            }
        } catch (e: Exception) {
        }
    }

    private fun amController(): Boolean = controllerId.isNotEmpty() && controllerId == myId

    private fun amHost(): Boolean = hostId.isNotEmpty() && hostId == myId

    // У главного (хоста) всегда настоящий браузер; управлять им может и другой человек.
    private fun amOwner(): Boolean = amHost()

    private fun nameOf(id: String): String {
        if (id == myId) return if (myName.isBlank()) "Ты" else myName
        return peopleNames[id] ?: "участник"
    }

    private fun toastSoon(text: String) {
        if (now() - lastToastAt < 2500) return
        lastToastAt = now()
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun onPageEvent(s: String) {
        try {
            val o = JSONObject(s)
            when (o.optString("k")) {
                "media" -> onLocalMedia(o.optString("a"), o.optDouble("time", 0.0))
                "pos" -> {
                    if (amOwner() && mediaPlaying && roomCode != null) {
                        mediaTime = o.optDouble("time", 0.0)
                        mediaAt = now()
                        sendMsg(JSONObject().put("t", "media").put("a", "tick").put("time", mediaTime))
                    }
                }
                "ready" -> onVideoReady()
                "ytError" -> onYtError()
                "src" -> onPageSrc(o)
                "ui" -> onLocalUi(o)
                "blocked" -> toastSoon("Нажми ▶ в плеере, чтобы включить видео")
            }
        } catch (e: Exception) {
        }
    }

    // На странице появился плеер (например, ты сам нажал на постер): подтягиваем его к состоянию комнаты.
    private fun onVideoReady() {
        if (roomCode == null || amOwner() || !mediaKnown) return
        runCmd(if (mediaPlaying) "play" else "pause", currentMediaTime())
    }

    private fun onLocalMedia(a: String, t: Double) {
        if (roomCode == null) return
        if (a != "play" && a != "pause" && a != "seek") return
        if (amOwner()) {
            // настоящий плеер у главного: его состояние — истина для всех
            if (a == "play") mediaPlaying = true
            if (a == "pause") mediaPlaying = false
            mediaTime = t
            mediaAt = now()
            mediaKnown = true
            sendMsg(JSONObject().put("t", "media").put("a", a).put("time", t))
        } else if (amController()) {
            // пульт у меня, а плеер у главного: просим его
            sendMsg(JSONObject().put("t", "cmd").put("a", a).put("time", t))
        } else if (mediaKnown) {
            // управляешь не ты: возвращаем плеер к состоянию комнаты
            toastSoon("Видео управляет " + nameOf(controllerId))
            handler.postDelayed({
                if (!amController() && roomCode != null) {
                    runCmd(if (mediaPlaying) "play" else "pause", currentMediaTime())
                }
            }, 400)
        }
    }

    private fun remoteMedia(a: String, t: Double) {
        if (a != "play" && a != "pause" && a != "seek" && a != "tick") return
        if (a == "play") mediaPlaying = true
        if (a == "pause") mediaPlaying = false
        mediaTime = t
        mediaAt = now()
        mediaKnown = true
        if (player != null) exoApply(a, t) else runCmd(a, t)
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
        o.put("c", controllerId)
        bridge.send(o.toString())
    }

    private fun sendState() {
        if (!amOwner() || !currentUrl.startsWith("http")) return
        val o = JSONObject().put("t", "state").put("url", currentUrl)
            .put("host", hostId).put("ctrl", controllerId)
        if (mediaKnown) {
            o.put("media", JSONObject().put("playing", mediaPlaying).put("time", currentMediaTime()))
        }
        if (lastScroll >= 0) o.put("sy", lastScroll)
        val su = streamUrl
        if (su != null) {
            o.put("src", JSONObject().put("url", su).put("ref", streamRef ?: "").put("org", streamOrigin ?: ""))
        }
        sendMsg(o)
    }

    // ============================== управление комнатой ==============================

    private fun setController(id: String, announce: Boolean) {
        if (id.isEmpty() || id == controllerId) return
        controllerId = id
        controllerSeen = peopleIds.contains(id)
        updateRoomLabel()
        if (announce) sendMsg(JSONObject().put("t", "ctrl").put("to", id))
        if (id == myId) {
            Toast.makeText(this, "Управление у тебя", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Управляет " + nameOf(id), Toast.LENGTH_SHORT).show()
        }
        onRoleChanged()
    }

    // Состояние экрана всегда присылает главный (у него настоящий браузер).
    private fun fromOwner(from: String): Boolean {
        if (hostId.isEmpty() && from.isNotEmpty()) {
            hostId = from
            updateRoomLabel()
        }
        return from == hostId
    }

    private fun ownerCmd(a: String, t: Double) {
        if (a != "play" && a != "pause" && a != "seek") return
        if (a == "play") mediaPlaying = true
        if (a == "pause") mediaPlaying = false
        mediaTime = t
        mediaAt = now()
        mediaKnown = true
        runCmd(a, t)
        sendMsg(JSONObject().put("t", "media").put("a", a).put("time", t))
    }

    // Применяем состояние главного. Повторные сообщения ничего не перезагружают: меняется только то, что разошлось.
    private fun applyOwnerState(o: JSONObject) {
        val c = o.optString("ctrl")
        if (c.isNotEmpty() && c != controllerId) setController(c, false)
        val url = o.optString("url")
        val srcObj = o.optJSONObject("src")
        val m = o.optJSONObject("media")
        if (m != null) {
            mediaPlaying = m.optBoolean("playing")
            mediaTime = m.optDouble("time", 0.0)
            mediaAt = now()
            mediaKnown = true
        }
        if (!stateReceived && o.has("sy")) lastScroll = o.optDouble("sy", -1.0)
        stateReceived = true
        if (url.isEmpty()) return

        if (url != ownerUrl) {
            // главный перешёл на другую страницу (или мы только вошли)
            ownerUrl = url
            if (m != null) pendingCmd = Triple(if (mediaPlaying) "play" else "pause", mediaTime, now())
            if (srcObj != null && applySrc(srcObj)) {
                currentUrl = url
                startStream()
            } else {
                leaveStream()
                openRemote(url)
            }
            return
        }
        // та же страница: подтягиваем только расхождения
        if (srcObj != null) {
            if (applySrc(srcObj) || (player == null && streamUrl != streamFailedUrl)) startStream()
        } else if (player != null) {
            // у главного видео больше нет — показываем его страницу
            leaveStream()
            if (currentUrl.startsWith("http")) {
                suppressedUrl = currentUrl
                showWeb()
                web.loadUrl(currentUrl)
            } else {
                openRemote(url)
            }
        }
        if (m != null) {
            val a = if (mediaPlaying) "tick" else "pause"
            if (player != null) exoApply(a, mediaTime) else runCmd(a, mediaTime)
        }
    }

    private fun showMembers() {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(8), dp(16), dp(8))

        val ids = ArrayList<String>(peopleIds)
        if (!ids.contains(myId)) ids.add(0, myId)
        val canGive = amController() || amHost()

        for (id in ids) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(8), 0, dp(8))

            val av = ImageView(this)
            circle(av)
            bindAvatar(av, id, nameOf(id))
            row.addView(av, LinearLayout.LayoutParams(dp(44), dp(44)))

            val tv = TextView(this)
            var label = nameOf(id)
            if (id == myId) label += " (ты)"
            if (id == hostId) label += "  🏠"
            if (id == controllerId) label += "  👑"
            tv.text = label
            tv.setTextColor(Color.WHITE)
            tv.textSize = 17f
            val tp = LinearLayout.LayoutParams(0, WRAP, 1f)
            tp.leftMargin = dp(12)
            row.addView(tv, tp)

            if (canGive && id != controllerId) {
                row.setOnClickListener {
                    AlertDialog.Builder(this)
                        .setTitle("Передать управление?")
                        .setMessage(nameOf(id) + " сможет ставить паузу, перематывать и открывать страницы для всех.")
                        .setPositiveButton("Передать") { _, _ ->
                            membersDialog?.dismiss()
                            setController(id, true)
                        }
                        .setNegativeButton("Отмена", null)
                        .show()
                }
            }
            col.addView(row)
        }

        val hint = TextView(this)
        hint.setTextColor(0xFF9A9AB0.toInt())
        hint.textSize = 13f
        hint.text = if (canGive) "🏠 — хост, 👑 — у кого управление. Нажми на участника, чтобы передать ему управление."
        else "🏠 — хост, 👑 — у кого управление. Передать управление может тот, у кого оно есть, или хост."
        col.addView(hint, lp(8))

        if (amHost() && !amController()) {
            col.addView(button("Забрать управление себе", ACCENT) {
                membersDialog?.dismiss()
                setController(myId, true)
            }, lp(12))
        }
        col.addView(button("Поделиться кодом комнаты", 0xFF2A2A3A.toInt()) { shareCode() }, lp(10))

        val sv = ScrollView(this)
        sv.addView(col)
        membersDialog = AlertDialog.Builder(this)
            .setTitle("Участники")
            .setView(sv)
            .setPositiveButton("Закрыть", null)
            .show()
    }

    // ============================== аватары ==============================

    private fun circle(iv: ImageView) {
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        iv.clipToOutline = true
    }

    private fun letterBitmap(name: String, id: String): Bitmap {
        val letter = (name.trim().firstOrNull() ?: '?').uppercaseChar().toString()
        val key = id + "|" + letter
        val cached = letterCache[key]
        if (cached != null) return cached
        val palette = intArrayOf(
            0xFF7C3AED.toInt(), 0xFF2563EB.toInt(), 0xFF059669.toInt(), 0xFFD97706.toInt(),
            0xFFDC2626.toInt(), 0xFFDB2777.toInt(), 0xFF0891B2.toInt()
        )
        val color = palette[Math.abs(id.hashCode()) % palette.size]
        val bmp = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(color)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        paint.textSize = 72f
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        val y = 64f - (paint.ascent() + paint.descent()) / 2f
        c.drawText(letter, 64f, y, paint)
        letterCache[key] = bmp
        return bmp
    }

    private fun avatarBitmap(id: String, name: String): Bitmap {
        if (id == myId) {
            val mine = myAvatar
            if (mine != null) return mine
        }
        val other = avatars[id]
        if (other != null) return other
        return letterBitmap(name, id)
    }

    private fun bindAvatar(iv: ImageView, id: String, name: String) {
        iv.setImageBitmap(avatarBitmap(id, name))
    }

    private fun loadMyAvatar() {
        val b64 = prefs.getString("avatar", null)
        if (b64 == null) {
            myAvatar = null
            myAvatarB64 = null
            return
        }
        try {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            myAvatar = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            myAvatarB64 = b64
        } catch (e: Exception) {
            myAvatar = null
            myAvatarB64 = null
        }
    }

    private fun pickAvatar() {
        val i = Intent(Intent.ACTION_GET_CONTENT)
        i.type = "image/*"
        startActivityForResult(Intent.createChooser(i, "Выбери аватар"), 77)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 77 && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) processAvatar(uri)
        }
    }

    private fun processAvatar(uri: Uri) {
        try {
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 256 && bounds.outHeight / (sample * 2) >= 256) sample *= 2
            val opts = BitmapFactory.Options()
            opts.inSampleSize = sample
            val src = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            if (src == null) {
                Toast.makeText(this, "Не удалось открыть картинку", Toast.LENGTH_SHORT).show()
                return
            }
            val side = Math.min(src.width, src.height)
            val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
            val small = Bitmap.createScaledBitmap(square, 128, 128, true)
            val bos = ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.JPEG, 80, bos)
            prefs.edit().putString("avatar", Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)).apply()
            loadMyAvatar()
            bindAvatar(homeAvatar, myId, if (myName.isBlank()) "?" else myName)
            if (roomCode != null) sendMyAvatar()
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось загрузить аватар", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendMyAvatar() {
        val b = myAvatarB64 ?: return
        sendMsg(JSONObject().put("t", "avatar").put("img", b))
    }

    private fun storeAvatar(from: String, b64: String) {
        if (from.isEmpty() || b64.isEmpty() || b64.length > 80000) return
        try {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp != null) avatars[from] = bmp
        } catch (e: Exception) {
        }
    }

    private fun addChat(id: String, name: String, text: String) {
        val shownName = if (name.isBlank()) "?" else name
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, dp(4), 0, dp(4))

        val av = ImageView(this)
        circle(av)
        bindAvatar(av, id, shownName)
        row.addView(av, LinearLayout.LayoutParams(dp(32), dp(32)))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val n = TextView(this)
        n.text = shownName
        n.textSize = 12f
        n.setTextColor(0xFFA78BFA.toInt())
        val t = TextView(this)
        t.text = text
        t.textSize = 15f
        t.setTextColor(Color.WHITE)
        col.addView(n)
        col.addView(t)
        val cp = LinearLayout.LayoutParams(0, WRAP, 1f)
        cp.leftMargin = dp(10)
        row.addView(col, cp)

        chatBox.addView(row)
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
        if (chatPanel.visibility != View.VISIBLE) chatBtn.text = "💬•"
    }

    // ============================== SyncBridge.Callback ==============================

    override fun onReady() {
        connStatus = "онлайн"
        updateRoomLabel()
        sendMsg(JSONObject().put("t", "hello"))
        sendMyAvatar()
        if (amHost()) {
            // хост после перезапуска возвращает себе пульт; после обычного обрыва связи просто сообщает всем текущее состояние
            if (amController()) sendMsg(JSONObject().put("t", "ctrl").put("to", myId))
            sendState()
            handler.removeCallbacks(stateBeat)
            handler.postDelayed(stateBeat, 10000)
        }
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
            val arr = JSONArray(json)
            peopleIds.clear()
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                val id = p.optString("id")
                peopleIds.add(id)
                val n = p.optString("name")
                if (n.isNotBlank() && id != myId) peopleNames[id] = n
            }
            peopleCount = arr.length()
            if (hostId.isNotEmpty() && peopleIds.contains(hostId)) ownerSeen = true
            // Если управляющий ушёл — управление переходит хосту (или участнику с наименьшим id).
            if (controllerId.isNotEmpty()) {
                if (peopleIds.contains(controllerId)) {
                    controllerSeen = true
                } else if (controllerSeen && peopleIds.isNotEmpty()) {
                    val next = if (peopleIds.contains(hostId)) hostId else (peopleIds.minOrNull() ?: "")
                    if (next == myId) setController(myId, true)
                }
            }
        } catch (e: Exception) {
        }
        updateRoomLabel()
    }

    override fun onMessage(json: String) {
        try {
            val o = JSONObject(json)
            val from = o.optString("id")
            if (from == myId) return
            val nm = o.optString("name")
            if (from.isNotEmpty() && nm.isNotBlank()) peopleNames[from] = nm

            when (o.optString("t")) {
                "hello" -> {
                    if (amOwner()) sendState()
                    sendMyAvatar()
                }
                "state" -> {
                    val h = o.optString("host")
                    if (hostId.isEmpty() && h.isNotEmpty() && h == from) hostId = h
                    if (amOwner() || from != hostId) return
                    applyOwnerState(o)
                }
                "nav" -> {
                    if (!fromOwner(from) || amOwner()) return
                    val url = o.optString("url")
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        ownerUrl = url
                        mediaPlaying = false
                        mediaTime = 0.0
                        mediaKnown = false
                        lastScroll = -1.0
                        pendingCmd = null
                        pickingUntil = 0L
                        leaveStream()
                        openRemote(url)
                    }
                }
                "media" -> {
                    if (!fromOwner(from) || amOwner()) return
                    remoteMedia(o.optString("a"), o.optDouble("time", 0.0))
                }
                "src" -> {
                    if (!fromOwner(from) || amOwner()) return
                    if (applySrc(o)) startStream()
                }
                "ui" -> {
                    if (player != null || amOwner() || !fromOwner(from)) return
                    if (o.optString("by") == myId) return
                    val c = cleanUi(o) ?: return
                    if (c.optString("e") == "scroll") lastScroll = c.optDouble("y", 0.0)
                    web.evaluateJavascript("window.__coUi&&window.__coUi(" + c.toString() + ")", null)
                }
                "uic" -> {
                    // действие от того, у кого пульт: главный повторяет его в настоящем браузере и показывает всем
                    if (!amOwner() || from != controllerId) return
                    val c = cleanUi(o) ?: return
                    if (c.optString("e") == "scroll") lastScroll = c.optDouble("y", 0.0)
                    web.evaluateJavascript("window.__coUi&&window.__coUi(" + c.toString() + ")", null)
                    c.put("t", "ui")
                    c.put("by", from)
                    sendMsg(c)
                }
                "cmd" -> {
                    if (!amOwner() || from != controllerId) return
                    ownerCmd(o.optString("a"), o.optDouble("time", 0.0))
                }
                "cmdnav" -> {
                    if (!amOwner() || from != controllerId) return
                    ownerNavigate(o.optString("url"))
                }
                "ctrl" -> {
                    if (controllerId.isEmpty() || from == controllerId || from == hostId) {
                        setController(o.optString("to"), false)
                    }
                }
                "avatar" -> storeAvatar(from, o.optString("img"))
                "chat" -> addChat(from, nm, o.optString("text"))
            }
        } catch (e: Exception) {
        }
    }
}
