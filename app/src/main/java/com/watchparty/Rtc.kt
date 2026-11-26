package com.watchparty

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule

open class Sdp(val ok: (SessionDescription?) -> Unit = {}) : SdpObserver {
    override fun onCreateSuccess(d: SessionDescription?) = ok(d)
    override fun onSetSuccess() = ok(null)
    override fun onCreateFailure(e: String?) {}
    override fun onSetFailure(e: String?) {}
}

/** Режим «Rave»: ведущий шлёт экран+звук каждому зрителю по WebRTC (mesh), сигналинг идёт через наш WebSocket. */
class Rtc(
    private val ctx: Context,
    private val send: (JSONObject) -> Unit,
    private val onRemote: (VideoTrack?) -> Unit,
) {
    val egl: EglBase = EglBase.create()
    var hosting = false; private set
    var watching = false; private set
    var onHostStopped: (() -> Unit)? = null
    private val pcs = HashMap<String, PeerConnection>()
    private val pendingIce = HashMap<String, MutableList<IceCandidate>>()
    private var hostId: String? = null
    private var mp: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var helper: SurfaceTextureHelper? = null
    private var rec: AudioRecord? = null
    private var vTrack: VideoTrack? = null
    private var aTrack: org.webrtc.AudioTrack? = null

    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx).createInitializationOptions())
        // Микрофонный буфер WebRTC подменяем звуком, который играет на телефоне ведущего
        val adm = JavaAudioDeviceModule.builder(ctx)
            .setAudioRecordDataCallback { _, _, _, buf -> feedPlaybackAudio(buf) }
            .createAudioDeviceModule()
        PeerConnectionFactory.builder().setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private fun feedPlaybackAudio(buf: java.nio.ByteBuffer) {
        val r = rec ?: return
        val tmp = ByteArray(buf.remaining()); var n = 0
        while (n < tmp.size) { val k = r.read(tmp, n, tmp.size - n); if (k <= 0) break; n += k }
        val p = buf.position(); buf.put(tmp); buf.position(p)
    }

    private fun size(): Pair<Int, Int> {
        val dm = ctx.resources.displayMetrics
        val k = minOf(1f, 1280f / maxOf(dm.widthPixels, dm.heightPixels))
        return Pair((dm.widthPixels * k).toInt() and 1.inv(), (dm.heightPixels * k).toInt() and 1.inv())
    }

    fun startHost(code: Int, data: Intent) {
        val m = (ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(code, data)
        mp = m
        m.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopHost() } }, Handler(Looper.getMainLooper()))
        val (w, h) = size()
        helper = SurfaceTextureHelper.create("cap", egl.eglBaseContext).also { it.setTextureSize(w, h) }
        val src = factory.createVideoSource(true)
        vd = m.createVirtualDisplay("share", w, h, ctx.resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, Surface(helper!!.surfaceTexture), null, null)
        helper!!.startListening { f -> src.capturerObserver.onFrameCaptured(f) }
        src.capturerObserver.onCapturerStarted(true)
        vTrack = factory.createVideoTrack("v", src)
        if (Build.VERSION.SDK_INT >= 29) {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(m)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
            rec = AudioRecord.Builder().setAudioPlaybackCaptureConfig(cfg)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()).build()
                .also { it.startRecording() }
        }
        val mc = MediaConstraints().apply {
            listOf("googEchoCancellation", "googNoiseSuppression", "googAutoGainControl", "googHighpassFilter")
                .forEach { mandatory.add(MediaConstraints.KeyValuePair(it, "false")) }
        }
        aTrack = factory.createAudioTrack("a", factory.createAudioSource(mc))
        hosting = true
        send(JSONObject().put("a", "share").put("on", true))
    }

    fun resize() { val (w, h) = size(); helper?.setTextureSize(w, h); vd?.resize(w, h, ctx.resources.displayMetrics.densityDpi) }

    fun stopHost() {
        if (!hosting) return
        hosting = false
        send(JSONObject().put("a", "share").put("on", false))
        vd?.release(); helper?.stopListening(); helper?.dispose(); mp?.stop()
        rec?.stop(); rec?.release(); rec = null
        pcs.values.forEach { it.close() }; pcs.clear()
        onHostStopped?.invoke()
    }

    fun watch(host: String) {
        stopWatch(); watching = true; hostId = host
        sig(host, JSONObject().put("type", "want"))
    }

    fun stopWatch() {
        if (!watching) return
        watching = false; pcs.remove(hostId)?.close(); onRemote(null)
    }

    fun onSignal(j: JSONObject) {
        val from = j.getString("from"); val d = j.getJSONObject("d")
        when (d.getString("type")) {
            "want" -> if (hosting) offerTo(from)
            "offer" -> answerTo(from, d.getString("sdp"))
            "answer" -> pcs[from]?.setRemoteDescription(Sdp { flush(from) },
                SessionDescription(SessionDescription.Type.ANSWER, d.getString("sdp")))
            "ice" -> {
                val c = IceCandidate(d.getString("mid"), d.getInt("idx"), d.getString("c"))
                val pc = pcs[from]
                if (pc?.remoteDescription != null) pc.addIceCandidate(c)
                else pendingIce.getOrPut(from) { mutableListOf() }.add(c)
            }
        }
    }

    private fun flush(peer: String) { pendingIce.remove(peer)?.forEach { pcs[peer]?.addIceCandidate(it) } }
    private fun sig(to: String, d: JSONObject) { send(JSONObject().put("a", "sig").put("to", to).put("d", d)) }

    private fun newPc(peer: String): PeerConnection {
        // Для мобильных сетей нужен TURN-сервер: добавьте его сюда (coturn)
        val cfg = PeerConnection.RTCConfiguration(listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()))
        cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        val pc = factory.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(c: IceCandidate) {
                sig(peer, JSONObject().put("type", "ice").put("mid", c.sdpMid).put("idx", c.sdpMLineIndex).put("c", c.sdp))
            }
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(d: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onTrack(t: RtpTransceiver) { (t.receiver.track() as? VideoTrack)?.let { onRemote(it) } }
        })!!
        pcs[peer]?.close(); pcs[peer] = pc
        return pc
    }

    private fun offerTo(peer: String) {
        val pc = newPc(peer)
        pc.addTrack(vTrack, listOf("s")); pc.addTrack(aTrack, listOf("s"))
        pc.createOffer(Sdp { o ->
            pc.setLocalDescription(Sdp(), o)
            sig(peer, JSONObject().put("type", "offer").put("sdp", o!!.description))
        }, MediaConstraints())
    }

    private fun answerTo(peer: String, sdp: String) {
        val pc = newPc(peer)
        pc.setRemoteDescription(Sdp {
            flush(peer)
            pc.createAnswer(Sdp { a ->
                pc.setLocalDescription(Sdp(), a)
                sig(peer, JSONObject().put("type", "answer").put("sdp", a!!.description))
            }, MediaConstraints())
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }
}
