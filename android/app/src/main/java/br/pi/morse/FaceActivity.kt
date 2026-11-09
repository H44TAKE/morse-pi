package br.pi.morse

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.Locale
import java.util.concurrent.Executors

/**
 * App "Morse Rosto": o segundo celular. Mostra uma carinha que fala as respostas da IA e
 * as mensagens em Morse, e acende os olhos no ritmo do Morse.
 */
@SuppressLint("MissingPermission")
class FaceActivity : AppCompatActivity(), PiLink.Callbacks {

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var link: PiLink
    private lateinit var face: FaceView
    private lateinit var caption: TextView
    private lateinit var panel: LinearLayout
    private lateinit var status: TextView
    private lateinit var btnConnect: Button
    private lateinit var swMorse: SwitchCompat
    private var tts: TextToSpeech? = null
    private var ttsOk = false

    private val cCard = Color.parseColor("#151B23")
    private val cAccent = Color.parseColor("#FFB020")
    private val cText = Color.parseColor("#E8EDF2")
    private val cMuted = Color.parseColor("#8A96A3")
    private val cOk = Color.parseColor("#3DDC84")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        link = PiLink(this, ui, this)
        buildUi()
        hideBars()
        Updater.check(this)

        tts = TextToSpeech(this) { code ->
            if (code == TextToSpeech.SUCCESS) {
                tts?.language = Locale("pt", "BR")
                tts?.setSpeechRate(1.05f)
                tts?.setPitch(1.15f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { face.speaking = true; link.send("F1") }
                    override fun onDone(id: String?) { face.speaking = false; link.send("F0"); relax() }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { face.speaking = false; link.send("F0"); relax() }
                    override fun onRangeStart(id: String?, start: Int, end: Int, frame: Int) { face.pulse() }
                })
                ttsOk = true
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    private fun hideBars() {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onDestroy() {
        super.onDestroy()
        link.close()
        tts?.shutdown()
    }

    // ---------------- interface ----------------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

    private fun tv(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color)
        typeface = Typeface.create("sans-serif-medium", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun btn(t: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(if (primary) Color.parseColor("#1A1100") else cText)
        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF),
            rounded(if (primary) cAccent else Color.parseColor("#1F2833"), 14), null)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0B0F14")) }
        face = FaceView(this).apply { setOnClickListener { togglePanel() } }
        root.addView(face, FrameLayout.LayoutParams(-1, -1))

        caption = tv("", 22f, Color.WHITE).apply {
            gravity = Gravity.CENTER; maxLines = 4
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = rounded(0x99000000.toInt(), 16)
            visibility = View.GONE
        }
        root.addView(caption, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(20); leftMargin = dp(40); rightMargin = dp(40)
        })

        status = tv("Desconectado", 14f, cMuted)
        btnConnect = btn("Conectar na Pi", true) { onConnectClick() }
        swMorse = SwitchCompat(this).apply {
            text = "Falar as mensagens em Morse"; isChecked = true; setTextColor(cText); textSize = 15f
            setPadding(0, dp(8), 0, dp(8))
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(cAccent, cMuted))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(0x66FFB020.toInt(), 0x44FFFFFF))
        }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(18)); background = rounded(cCard, 22)
            addView(tv("Morse Rosto", 22f, cText, bold = true))
            addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(14) })
            addView(btnConnect)
            addView(swMorse, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(btn("Testar a fala", false) {
                speak("Olá! Eu sou o Morse Pi. Pode me fazer uma pergunta.")
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(tv("Toque na carinha para esconder ou mostrar este painel.", 12f, cMuted),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        root.addView(panel, FrameLayout.LayoutParams(dp(340), -2, Gravity.CENTER))
        setContentView(root)
    }

    private fun togglePanel() {
        panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun showCaption(t: String) {
        caption.text = t; caption.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
    }

    // ---------------- Bluetooth ----------------
    private fun onConnectClick() {
        if (link.isBusy) { link.disconnect(); return }
        if (!link.hasPermissions()) { ActivityCompat.requestPermissions(this, link.neededPermissions(), 1); return }
        link.connect()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (link.hasPermissions()) link.connect() else status.text = "Permissão de Bluetooth negada"
    }

    override fun onState(connected: Boolean, text: String) {
        status.text = text
        status.setTextColor(if (connected) cOk else cMuted)
        btnConnect.text = if (link.isBusy) "Desconectar" else "Conectar na Pi"
        if (connected) ui.postDelayed({ if (link.isReady) panel.visibility = View.GONE }, 2500)
        else panel.visibility = View.VISIBLE
    }

    override fun onReady() {}

    override fun onLine(line: String) {
        val body = line.substring(1)
        when (line[0]) {
            'H' -> ui.post {
                face.mood = FaceView.Mood.LISTENING; showCaption("Ouvi: $body")
                ui.postDelayed({ if (face.mood == FaceView.Mood.LISTENING) face.mood = FaceView.Mood.IDLE }, 2500)
            }
            'Q' -> ui.post { face.mood = FaceView.Mood.THINKING; showCaption(body) }
            'A' -> ui.post {
                val i = body.indexOf('\t')
                val emo = if (i >= 0) body.substring(0, i) else "neutro"
                val txt = if (i >= 0) body.substring(i + 1) else body
                face.mood = FaceView.Mood.IDLE
                face.emotion = FaceView.Emotion.from(emo)
                speak(txt)
            }
            'S' -> {
                val p = body.split("\t")
                if (p.size >= 3) playMorse(p[0].toIntOrNull() ?: 120, p[2], p[1])
            }
        }
    }

    /** Depois de falar, a expressão volta ao normal depois de uns instantes. */
    private fun relax() {
        ui.postDelayed({ if (!face.speaking) face.emotion = FaceView.Emotion.NEUTRO }, 3500)
    }

    // ---------------- fala e Morse ----------------
    private fun speak(text: String) {
        showCaption(text)
        if (ttsOk) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "fala")
    }

    /** Os olhos acendem no ritmo do Morse; no fim, a carinha fala a tradução. */
    private fun playMorse(unit: Int, morse: String, plain: String) {
        val steps = Morse.steps(morse, unit)
        ui.post { face.mood = FaceView.Mood.IDLE; face.emotion = FaceView.Emotion.ANIMADO; showCaption(plain) }
        worker.execute {
            for (s in steps) {
                face.glow = s.on
                SystemClock.sleep(s.ms.toLong())
            }
            face.glow = false
            if (swMorse.isChecked && plain.isNotBlank()) ui.post { speak(plain) }
        }
    }
}
