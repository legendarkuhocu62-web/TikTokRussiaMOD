package ru.cobrowser.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors

/** Какие сайты считаются «российскими» и ходят напрямую, мимо VPN. */
object RuRoutes {
    private val builtin = listOf(
        ".ru", ".su", ".xn--p1ai", ".xn--80asehdb", ".xn--80aswg",
        "vk.com", "vk.me", "vk-cdn.net", "vkuseraudio.net", "vkuservideo.net", "userapi.com", "mycdn.me",
        "yandex.com", "yandex.net", "yandex.by", "yandex.kz", "yandex.uz", "yastatic.net", "yandexcloud.net",
        "okko.tv", "ivi.tv", "wink.tv", "premier.one", "kinopoisk.net", "kodik.info", "kodik.biz", "kodik.cc"
    )

    fun isDirect(host: String, extra: List<String>): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (h.isEmpty()) return false
        for (rule in builtin + extra) {
            val t = rule.trim().lowercase()
            if (t.isEmpty()) continue
            if (t.startsWith(".")) {
                if (h.endsWith(t)) return true
            } else if (h == t || h.endsWith(".$t")) {
                return true
            }
        }
        return false
    }
}

/**
 * Маленький локальный прокси внутри приложения. WebView и плеер ходят через него,
 * а он для каждого сайта решает: напрямую через Wi-Fi/мобильную сеть (мимо VPN) или обычным путём (через VPN).
 */
class RouteProxy(private val ctx: Context, private val isDirect: (String) -> Boolean) {

    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    @Volatile
    private var cachedNet: Network? = null

    @Volatile
    private var cachedAt = 0L

    @Synchronized
    fun start(): Int {
        val s = server
        if (s != null && !s.isClosed) return s.localPort
        val ss = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        server = ss
        pool.execute {
            try {
                acceptLoop(ss)
            } catch (t: Throwable) {
            }
        }
        return ss.localPort
    }

    @Synchronized
    fun stop() {
        try {
            server?.close()
        } catch (e: Exception) {
        }
        server = null
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val c = try {
                ss.accept()
            } catch (e: Exception) {
                break
            }
            pool.execute {
                try {
                    handle(c)
                } catch (t: Throwable) {
                    closeQuiet(c)
                }
            }
        }
    }

    private fun readHead(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var state = 0
        while (buf.size() < 16384) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            state = if (b == 13) {
                if (state == 0 || state == 2) state + 1 else 1
            } else if (b == 10) {
                if (state == 1 || state == 3) state + 1 else 0
            } else {
                0
            }
            if (state == 4) return buf.toString("ISO-8859-1")
        }
        return null
    }

    private fun handle(client: Socket) {
        var remote: Socket? = null
        try {
            client.tcpNoDelay = true
            client.soTimeout = 20000
            val cin = client.getInputStream()
            val cout = client.getOutputStream()
            val head = readHead(cin)
            if (head == null) {
                closeQuiet(client)
                return
            }
            client.soTimeout = 0
            val lines = head.split("\r\n")
            val first = lines[0].split(" ")
            if (first.size < 3) {
                closeQuiet(client)
                return
            }
            val method = first[0]
            val target = first[1]
            if (method.equals("CONNECT", ignoreCase = true)) {
                val idx = target.lastIndexOf(':')
                val host = if (idx > 0) target.substring(0, idx) else target
                val port = if (idx > 0) (target.substring(idx + 1).toIntOrNull() ?: 443) else 443
                remote = open(host, port)
                cout.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                cout.flush()
            } else {
                val url = URL(target)
                val host = url.host
                val port = if (url.port == -1) 80 else url.port
                remote = open(host, port)
                val path = if (url.file.isEmpty()) "/" else url.file
                val sb = StringBuilder()
                sb.append(method).append(' ').append(path).append(' ').append(first[2]).append("\r\n")
                for (i in 1 until lines.size) {
                    val l = lines[i]
                    if (l.isEmpty()) continue
                    val low = l.lowercase()
                    if (low.startsWith("proxy-connection:") || low.startsWith("connection:")) continue
                    sb.append(l).append("\r\n")
                }
                sb.append("Connection: close\r\n\r\n")
                val rout = remote.getOutputStream()
                rout.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
                rout.flush()
            }
            val r = remote
            pool.execute {
                try {
                    pipe(r.getInputStream(), cout, client, r)
                } catch (t: Throwable) {
                    closeQuiet(client)
                    closeQuiet(r)
                }
            }
            pipe(cin, r.getOutputStream(), client, r)
        } catch (e: Exception) {
            try {
                client.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
            } catch (x: Exception) {
            }
            closeQuiet(client)
            closeQuiet(remote)
        }
    }

    private fun pipe(input: InputStream, output: OutputStream, a: Socket, b: Socket) {
        val buf = ByteArray(16384)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                output.flush()
            }
        } catch (e: Exception) {
        }
        closeQuiet(a)
        closeQuiet(b)
    }

    private fun closeQuiet(s: Socket?) {
        try {
            s?.close()
        } catch (e: Exception) {
        }
    }

    private fun open(host: String, port: Int): Socket {
        if (isDirect(host)) {
            val net = physicalNetwork()
            if (net != null) {
                try {
                    val addr = net.getByName(host)
                    val s = net.socketFactory.createSocket()
                    s.connect(InetSocketAddress(addr, port), 12000)
                    s.tcpNoDelay = true
                    return s
                } catch (e: Exception) {
                    // не вышло напрямую — идём обычным путём
                }
            }
        }
        val s = Socket()
        s.connect(InetSocketAddress(host, port), 12000)
        s.tcpNoDelay = true
        return s
    }

    // Сеть без VPN (Wi-Fi или мобильная).
    @Suppress("DEPRECATION")
    private fun physicalNetwork(): Network? {
        val t = System.currentTimeMillis()
        val c = cachedNet
        if (c != null && t - cachedAt < 5000) return c
        var best: Network? = null
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue
                if (best == null || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) best = n
            }
        } catch (e: Exception) {
        }
        cachedNet = best
        cachedAt = t
        return best
    }
}
