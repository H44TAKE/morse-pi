package br.pi.morse

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.*
import android.os.*
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.sin

/**
 * Morse Pi: conecta por Bluetooth LE na Raspberry Pi ("MorsePi").
 * Protocolo (linhas UTF-8 terminadas em \n):
 *   Pi -> celular:  S<unidade>\t<texto>\t<morse>  |  H<ouvido>  |  N<ssid>\t<sinal>\t<seg>  |  Z  |
 *                   I<ssid>\t<ip>\t<internet 0/1>  |  R<ok|erro>\t<msg>
 *   celular -> Pi:  T<texto>  L1/L0  K1/K0 (tecla)  W? (buscar redes)  WS  WC<ssid>\t<senha>  U<unidade_ms>
 */
@SuppressLint("MissingPermission", "ClickableViewAccessibility")
class MainActivity : AppCompatActivity(), PiLink.Callbacks {

    companion object {
        val SPEEDS = listOf(300, 200, 150, 120, 80)
        val SPEED_NAMES = listOf("muito lenta", "lenta", "calma", "normal", "rápida")
    }

    // paleta
    private val cBg = Color.parseColor("#0B0F14")
    private val cCard = Color.parseColor("#151B23")
    private val cCard2 = Color.parseColor("#1F2833")
    private val cAccent = Color.parseColor("#FFB020")
    private val cText = Color.parseColor("#E8EDF2")
    private val cMuted = Color.parseColor("#8A96A3")
    private val cOk = Color.parseColor("#3DDC84")
    private val cBad = Color.parseColor("#FF6B6B")

    private val ui = Handler(Looper.getMainLooper())
    private val player = Executors.newSingleThreadExecutor()

    // telas
    private lateinit var statusPill: TextView
    private lateinit var btnConnect: Button
    private lateinit var input: EditText
    private lateinit var heard: TextView
    private lateinit var textView: TextView
    private lateinit var morseView: TextView
    private lateinit var btnMic: Button
    private lateinit var swTorch: SwitchCompat
    private lateinit var swScreen: SwitchCompat
    private lateinit var swSound: SwitchCompat
    private lateinit var swVib: SwitchCompat
    private lateinit var swSpell: SwitchCompat
    private lateinit var swVoice: SwitchCompat
    private lateinit var speedLabel: TextView
    private lateinit var flash: View
    private lateinit var pages: List<View>
    private lateinit var tabs: List<TextView>
    // manual
    private lateinit var keyMorseView: TextView
    private lateinit var keyTextView: TextView
    private lateinit var swKeyPi: SwitchCompat
    // wi-fi
    private lateinit var wifiNow: TextView
    private lateinit var wifiMsg: TextView
    private lateinit var netList: LinearLayout
    private lateinit var btnScan: Button

    // modo pergunta (IA)
    private lateinit var btnModeMorse: Button
    private lateinit var btnModeAsk: Button
    private lateinit var swAnswerMorse: SwitchCompat
    private lateinit var swSpeakAnswer: SwitchCompat

    private lateinit var link: PiLink
    private var askMode = false
    private var micOn = false
    private var unitMs = 120
    private var tts: TextToSpeech? = null
    private var torchId: String? = null

    // tecla manual
    private var keyTrack: AudioTrack? = null
    private var keyDownAt = 0L
    private val keySymbols = StringBuilder()
    private val keyText = StringBuilder()
    private val keyMorse = StringBuilder()
    private val letterRun = Runnable { commitLetter() }
    private val wordRun = Runnable {
        if (keyText.isNotEmpty() && !keyText.endsWith(" ")) { keyText.append(' '); keyMorse.append("/ "); updateKeyViews() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        link = PiLink(this, ui, this)
        buildUi()
        tts = TextToSpeech(this) { code ->
            if (code == TextToSpeech.SUCCESS) {
                tts?.language = Locale("pt", "BR")
                tts?.setSpeechRate(1.2f)
            }
        }
        torchId = findTorch()
        showTab(0)
        setStatus("Desconectado", false)
        Updater.check(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        link.close()
        setTorch(false)
        tts?.shutdown()
        keyTrack?.release()
    }

    // ================= visual =================
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int = 16, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun tv(t: String, size: Float = 15f, color: Int = cText, bold: Boolean = false, mono: Boolean = false) =
        TextView(this).apply {
            text = t; textSize = size; setTextColor(color)
            typeface = when {
                mono -> Typeface.MONOSPACE
                bold -> Typeface.create("sans-serif-medium", Typeface.BOLD)
                else -> Typeface.create("sans-serif", Typeface.NORMAL)
            }
        }

    private fun lp(w: Int = -1, h: Int = -2, top: Int = 0, bottom: Int = 0, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    private fun styleBtn(b: Button, primary: Boolean) {
        b.setTextColor(if (primary) Color.parseColor("#1A1100") else cText)
        b.background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), rounded(if (primary) cAccent else cCard2, 14), null)
    }

    private fun btn(t: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        styleBtn(this, primary)
        stateListAnimator = null
        minHeight = dp(48); minimumHeight = dp(48)
        setOnClickListener { onClick() }
    }

    private fun card(title: String?, vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(16))
        background = rounded(cCard, 20)
        if (title != null) addView(tv(title.uppercase(), 11f, cMuted, bold = true).apply { letterSpacing = 0.12f }, lp(bottom = 10))
        views.forEach { addView(it) }
    }

    private fun sw(label: String, checked: Boolean) = SwitchCompat(this).apply {
        text = label; isChecked = checked; setTextColor(cText); textSize = 15f
        setPadding(0, dp(8), 0, dp(8))
        thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(cAccent, cMuted))
        trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(0x66FFB020.toInt(), 0x44FFFFFF))
    }

    private fun scroll(vararg views: View) = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(16))
            views.forEach { addView(it, lp(bottom = 12)) }
        })
    }

    private fun setStatus(t: String, ok: Boolean) {
        statusPill.text = "●  $t"
        statusPill.setTextColor(if (ok) cOk else cMuted)
    }

    private fun buildUi() {
        val outer = FrameLayout(this).apply { setBackgroundColor(cBg) }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // cabeçalho
        statusPill = tv("", 13f, cMuted, bold = true).apply {
            setPadding(dp(12), dp(6), dp(12), dp(6)); background = rounded(cCard, 20)
        }
        btnConnect = btn("Conectar", true) { onConnectClick() }.apply { minHeight = dp(40); minimumHeight = dp(40); setPadding(dp(18), 0, dp(18), 0) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(16), dp(12))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(tv("Morse Pi", 26f, cText, bold = true))
                addView(statusPill, lp(top = 6))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(btnConnect, LinearLayout.LayoutParams(-2, dp(40)))
        }
        content.addView(header)

        // ---------- aba 0: mensagem ----------
        input = EditText(this).apply {
            hint = "Digite uma mensagem"; setTextColor(cText); setHintTextColor(cMuted); textSize = 18f
            background = rounded(cCard2, 14); setPadding(dp(14), dp(12), dp(14), dp(12))
            minLines = 2; gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btn("Traduzir e tocar", true) { translateLocal() }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
            addView(btn("Enviar para a Pi", false) { sendText(input.text.toString()) }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        val composeCard = card("Mensagem", input)
        composeCard.addView(row, lp(top = 10))
        composeCard.addView(btn("🤖  Perguntar à IA (o rosto responde)", false) { askAi() }, lp(top = 8))

        heard = tv("", 13f, cMuted)
        textView = tv("—", 26f, cText, bold = true)
        morseView = tv("—", 22f, cAccent, mono = true)
        val resultCard = card("Tradução", heard, textView.also { it.setPadding(0, dp(4), 0, dp(4)) }, morseView)

        btnMic = btn("🎤  Microfone da Pi: desligado", false) { toggleMic() }
        btnModeMorse = btn("Traduzir em Morse", true) { setMode(false) }
        btnModeAsk = btn("Responder perguntas", false) { setMode(true) }
        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btnModeMorse, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
            addView(btnModeAsk, LinearLayout.LayoutParams(0, -2, 1f))
        }
        swAnswerMorse = sw("Responder também em Morse", false).apply {
            setOnCheckedChangeListener { _, on -> if (link.isReady) link.send(if (on) "Y1" else "Y0") }
        }
        val micCard = card("Ouvir pela Pi", tv("O que o microfone da Pi ouve vira:", 13f, cMuted), modeRow, btnMic, swAnswerMorse)
        (modeRow.layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(8)

        swTorch = sw("Lanterna", true); swScreen = sw("Tela piscando (se não houver lanterna)", true)
        swSound = sw("Som no celular", true); swVib = sw("Vibração", true)
        swSpell = sw("Soletrar cada letra com o bip", true); swVoice = sw("Falar a frase inteira no final", true)
        swSpeakAnswer = sw("Falar respostas da IA neste celular", false)
        speedLabel = tv("Velocidade: normal", 14f, cMuted)
        val speed = SeekBar(this).apply {
            max = SPEEDS.size - 1; progress = 3
            progressTintList = ColorStateList.valueOf(cAccent); thumbTintList = ColorStateList.valueOf(cAccent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) {
                    unitMs = SPEEDS[p]; speedLabel.text = "Velocidade: ${SPEED_NAMES[p]}"
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) { link.send("U$unitMs") }
            })
        }
        val optCard = card("Opções", swTorch, swScreen, swSound, swVib, swSpell, swVoice, swSpeakAnswer,
            speedLabel.also { it.setPadding(0, dp(10), 0, 0) }, speed,
            btn("Testar com SOS", false) { playLocal(unitMs, Morse.textToMorse("SOS"), "SOS") }.also {
                it.layoutParams = lp(top = 10)
            })

        val page0 = scroll(composeCard, resultCard, micCard, optCard)

        // ---------- aba 1: manual ----------
        val keyBg = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(Color.parseColor("#FFE2A0")); setStroke(dp(6), cAccent)
            })
            addState(intArrayOf(), GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(dp(6), Color.parseColor("#33FFFFFF"))
            })
        }
        val bigKey = TextView(this).apply {
            text = "SEGURE"; textSize = 22f; gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#1A1100")); typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = 0.1f; background = keyBg
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { v.isPressed = true; keyPress() }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; keyRelease() }
                }
                true
            }
        }
        val keyHolder = FrameLayout(this).apply {
            addView(bigKey, FrameLayout.LayoutParams(dp(220), dp(220), Gravity.CENTER))
        }
        keyMorseView = tv("—", 22f, cAccent, mono = true)
        keyTextView = tv("—", 26f, cText, bold = true)
        swKeyPi = sw("Tocar na caixa da Pi também", true)
        val page1 = scroll(
            tv("Segure o botão para apitar. Toque rápido = ponto, segurar = traço.", 14f, cMuted),
            keyHolder.also { it.minimumHeight = dp(260) },
            card("Seu código", keyMorseView, keyTextView.also { it.setPadding(0, dp(6), 0, 0) },
                btn("Limpar", false) { clearKey() }.also { it.layoutParams = lp(top = 12) }),
            card(null, swKeyPi),
        )

        // ---------- aba 2: wi-fi ----------
        wifiNow = tv("Conecte na Pi para ver a internet dela.", 15f, cText)
        wifiMsg = tv("", 13f, cMuted)
        btnScan = btn("Buscar redes Wi-Fi", true) { scanWifi() }
        netList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val page2 = scroll(
            card("Internet da Pi", wifiNow, wifiMsg.also { it.setPadding(0, dp(6), 0, 0) }),
            card("Redes encontradas pela Pi", btnScan, netList.also { it.setPadding(0, dp(8), 0, 0) },
                btn("Outra rede (digitar nome)", false) { askNetwork(null) }.also { it.layoutParams = lp(top = 8) }),
            card("Dica", tv("Na escola, o jeito mais fácil é ligar o ponto de acesso (hotspot) do seu celular, " +
                "na banda 2,4 GHz, e escolher a rede dele aqui. A Pi 3 não enxerga redes 5 GHz.", 14f, cMuted)),
        )

        pages = listOf(page0, page1, page2)
        val frame = FrameLayout(this)
        pages.forEach { frame.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        content.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))

        // barra de abas
        val names = listOf("Mensagem", "Manual", "Wi-Fi")
        tabs = names.mapIndexed { i, n ->
            tv(n, 14f, cMuted, bold = true).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(12)); setOnClickListener { showTab(i) }
            }
        }
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(cCard, 0)
            tabs.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4); marginEnd = dp(4) }) }
        }
        content.addView(tabBar)

        flash = View(this).apply { setBackgroundColor(Color.WHITE); alpha = 0f }
        outer.addView(content, FrameLayout.LayoutParams(-1, -1))
        outer.addView(flash, FrameLayout.LayoutParams(-1, -1))
        setContentView(outer)

        WindowCompat.getInsetsController(window, outer).isAppearanceLightStatusBars = false
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
    }

    private fun showTab(i: Int) {
        pages.forEachIndexed { k, p -> p.visibility = if (k == i) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { k, t ->
            t.setTextColor(if (k == i) cAccent else cMuted)
            t.background = if (k == i) rounded(cCard2, 14) else null
        }
        if (i == 2 && link.isReady) link.send("WS")
    }

    // ================= Bluetooth =================
    private fun onConnectClick() {
        if (link.isBusy) { link.disconnect(); return }
        if (!link.hasPermissions()) { ActivityCompat.requestPermissions(this, link.neededPermissions(), 1); return }
        link.connect()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (link.hasPermissions()) link.connect() else setStatus("Permissão negada", false)
    }

    // ---- PiLink.Callbacks ----
    override fun onState(connected: Boolean, text: String) {
        setStatus(text, connected)
        btnConnect.text = if (link.isBusy) "Desconectar" else "Conectar"
        if (!connected && !link.isBusy) { micOn = false; btnMic.text = "🎤  Microfone da Pi: desligado" }
    }

    override fun onReady() {
        link.send("WS")                              // pergunta a rede/internet da Pi
        link.send("U$unitMs")                        // alinha a velocidade
        link.send(if (askMode) "D1" else "D0")       // alinha o modo do microfone
        link.send(if (swAnswerMorse.isChecked) "Y1" else "Y0")
    }

    override fun onLine(line: String) = handleLine(line)

    private fun handleLine(line: String) {
        val body = line.substring(1)
        when (line[0]) {
            'H' -> ui.post { heard.text = "Ouvido: $body" }
            'Q' -> ui.post { heard.text = "Pergunta: $body"; textView.text = "pensando…"; morseView.text = "" }
            'A' -> {
                val i = body.indexOf('\t')
                val txt = if (i >= 0) body.substring(i + 1) else body
                ui.post { textView.text = txt; morseView.text = "" }
                if (swSpeakAnswer.isChecked) tts?.speak(txt, TextToSpeech.QUEUE_FLUSH, null, "resposta")
            }
            'S' -> {
                val p = body.split("\t")
                if (p.size >= 3) playLocal(p[0].toIntOrNull() ?: 120, p[2], p[1])
            }
            'N' -> {
                val p = body.split("\t")
                ui.post { addNetworkRow(p[0], p.getOrNull(1)?.toIntOrNull() ?: 0, p.getOrNull(2) ?: "") }
            }
            'Z' -> ui.post {
                btnScan.isEnabled = true; btnScan.text = "Buscar redes Wi-Fi"
                if (netList.childCount == 0) wifiMsg.text = "Nenhuma rede encontrada."
            }
            'I' -> {
                val p = body.split("\t")
                ui.post {
                    val ssid = p.getOrNull(0).orEmpty(); val ip = p.getOrNull(1).orEmpty()
                    val online = p.getOrNull(2) == "1"
                    if (ssid.isEmpty()) {
                        wifiNow.text = "Pi sem Wi-Fi conectado"; wifiNow.setTextColor(cBad)
                    } else {
                        wifiNow.text = "$ssid\n$ip  ·  " + if (online) "com internet ✔" else "SEM internet"
                        wifiNow.setTextColor(if (online) cOk else cAccent)
                    }
                }
            }
            'R' -> {
                val p = body.split("\t")
                ui.post {
                    wifiMsg.text = (if (p[0] == "ok") "✔ " else "✖ ") + p.getOrNull(1).orEmpty()
                    wifiMsg.setTextColor(if (p[0] == "ok") cOk else cBad)
                }
            }
        }
    }

    private fun requireConnection(): Boolean {
        if (!link.isReady) { setStatus("Conecte na Pi primeiro", false); return false }
        return true
    }

    private fun sendText(t: String) {
        val s = t.trim().replace("\n", " ")
        if (s.isNotEmpty() && requireConnection()) link.send("T$s")
    }

    /** Pergunta digitada: a Pi responde com a IA e o rosto fala. */
    private fun askAi() {
        val s = input.text.toString().trim().replace("\n", " ")
        if (s.isNotEmpty() && requireConnection()) {
            link.send("P$s")
            heard.text = "Pergunta: $s"; textView.text = "pensando…"; morseView.text = ""
        }
    }

    private fun toggleMic() {
        if (!requireConnection()) return
        micOn = !micOn
        link.send(if (micOn) "L1" else "L0")
        btnMic.text = "🎤  Microfone da Pi: " + if (micOn) "LIGADO" else "desligado"
    }

    private fun setMode(ask: Boolean) {
        askMode = ask
        styleBtn(btnModeMorse, !ask); styleBtn(btnModeAsk, ask)
        if (link.isReady) link.send(if (ask) "D1" else "D0")
    }

    // ================= Wi-Fi da Pi =================
    private fun scanWifi() {
        if (!requireConnection()) return
        netList.removeAllViews()
        wifiMsg.text = "Buscando redes… (uns 5 segundos)"; wifiMsg.setTextColor(cMuted)
        btnScan.isEnabled = false; btnScan.text = "Buscando…"
        link.send("W?")
        ui.postDelayed({ btnScan.isEnabled = true; btnScan.text = "Buscar redes Wi-Fi" }, 20000)
    }

    private fun addNetworkRow(ssid: String, signal: Int, sec: String) {
        val bars = when { signal >= 75 -> "▂▄▆█"; signal >= 50 -> "▂▄▆ "; signal >= 25 -> "▂▄  "; else -> "▂   " }
        val locked = sec.isNotBlank() && sec != "ABERTA" && sec != "--"
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), rounded(cCard2, 12), null)
            addView(tv(ssid, 16f, cText, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(tv((if (locked) "🔒  " else "") + bars, 14f, cMuted, mono = true))
            setOnClickListener { if (locked) askNetwork(ssid) else connectWifi(ssid, "") }
        }
        netList.addView(r, lp(bottom = 8))
    }

    private fun askNetwork(ssid: String?) {
        val pad = dp(20)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, dp(8), pad, 0) }
        val etName = EditText(this).apply { hint = "Nome da rede"; setText(ssid ?: ""); isEnabled = ssid == null }
        val etPass = EditText(this).apply {
            hint = "Senha"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        box.addView(etName); box.addView(etPass)
        AlertDialog.Builder(this)
            .setTitle(if (ssid == null) "Conectar a uma rede" else "Conectar em $ssid")
            .setView(box)
            .setPositiveButton("Conectar") { _, _ ->
                val n = etName.text.toString().trim()
                if (n.isNotEmpty()) connectWifi(n, etPass.text.toString())
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun connectWifi(ssid: String, pass: String) {
        if (!requireConnection()) return
        wifiMsg.text = "Conectando em $ssid… (até 30 segundos)"; wifiMsg.setTextColor(cMuted)
        link.send("WC$ssid\t$pass")
    }

    // ================= tecla manual =================
    private fun buildKeyTrack(): AudioTrack {
        val rate = 22050
        val pcm = ShortArray(rate) { i -> (0.6 * sin(2 * PI * 700.0 * i / rate) * 32767).toInt().toShort() } // 700 ciclos exatos
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(pcm.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
        t.write(pcm, 0, pcm.size)
        return t
    }

    private fun keyPress() {
        ui.removeCallbacks(letterRun); ui.removeCallbacks(wordRun)
        keyDownAt = SystemClock.uptimeMillis()
        if (swTorch.isChecked && torchId != null) setTorch(true)
        else if (swScreen.isChecked) flash.alpha = 1f
        if (swSound.isChecked) {
            try {
                val t = keyTrack ?: buildKeyTrack().also { keyTrack = it }
                t.stop(); t.reloadStaticData(); t.setLoopPoints(0, 22050, -1); t.play()
            } catch (_: Exception) {}
        }
        if (swVib.isChecked) vibrator()?.vibrate(VibrationEffect.createOneShot(10000, 255))
        if (swKeyPi.isChecked && link.isReady) link.send("K1", true)
    }

    private fun keyRelease() {
        val dur = SystemClock.uptimeMillis() - keyDownAt
        setTorch(false); flash.alpha = 0f
        try { keyTrack?.stop() } catch (_: Exception) {}
        vibrator()?.cancel()
        if (swKeyPi.isChecked && link.isReady) link.send("K0", true)
        val sym = if (dur < 300) '.' else '-'
        keySymbols.append(sym); keyMorse.append(sym)
        updateKeyViews()
        ui.postDelayed(letterRun, 900)    // pausa: fecha a letra
        ui.postDelayed(wordRun, 2400)     // pausa longa: espaço entre palavras
    }

    private fun commitLetter() {
        if (keySymbols.isEmpty()) return
        val letter = Morse.decodeLetter(keySymbols.toString())
        keySymbols.clear(); keyMorse.append(' ')
        keyText.append(letter)
        updateKeyViews()
        if (swSpell.isChecked && letter != "?") tts?.speak(letter, TextToSpeech.QUEUE_FLUSH, null, "tecla")
    }

    private fun updateKeyViews() {
        keyMorseView.text = if (keyMorse.isEmpty()) "—" else keyMorse.toString()
        keyTextView.text = if (keyText.isEmpty()) "—" else keyText.toString()
    }

    private fun clearKey() {
        ui.removeCallbacks(letterRun); ui.removeCallbacks(wordRun)
        keySymbols.clear(); keyText.clear(); keyMorse.clear(); updateKeyViews()
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31)
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    // ================= lanterna, som, voz =================
    private fun findTorch(): String? {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return cm.cameraIdList.firstOrNull {
            val ch = cm.getCameraCharacteristics(it)
            ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    }

    private fun setTorch(on: Boolean) {
        val id = torchId ?: return
        try { (getSystemService(Context.CAMERA_SERVICE) as CameraManager).setTorchMode(id, on) } catch (_: Exception) {}
    }

    private fun tonePcm(tl: List<Pair<Boolean, Int>>): ShortArray {
        val rate = 22050
        val total = tl.sumOf { it.second } * rate / 1000
        val pcm = ShortArray(total + 1)
        var pos = 0
        for ((on, ms) in tl) {
            val n = ms * rate / 1000
            if (on) {
                val fade = minOf(200, n / 4).coerceAtLeast(1)
                for (i in 0 until n) {
                    var a = 0.6 * sin(2 * PI * 700.0 * i / rate)
                    if (i < fade) a *= i / fade.toDouble()
                    if (i >= n - fade) a *= (n - i) / fade.toDouble()
                    pcm[pos + i] = (a * 32767).toInt().toShort()
                }
            }
            pos += n
        }
        return pcm
    }

    /** Botão "Traduzir e tocar": funciona só no celular, sem a Pi. */
    private fun translateLocal() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        val morse: String; val plain: String
        if (Morse.isMorse(t)) { morse = t; plain = Morse.fromMorse(t) }
        else { morse = Morse.textToMorse(t); plain = t }
        if (morse.isEmpty()) { Toast.makeText(this, "Nada para traduzir", Toast.LENGTH_SHORT).show(); return }
        playLocal(unitMs, morse, plain)
    }

    private fun playLocal(unit: Int, morse: String, plain: String) {
        ui.post { textView.text = plain; morseView.text = morse }
        val steps = Morse.steps(morse, unit)
        val tl = steps.map { it.on to it.ms }
        if (tl.isEmpty()) return
        val useTorch = swTorch.isChecked && torchId != null
        val useScreen = swScreen.isChecked && !useTorch
        val useSound = swSound.isChecked
        val useVib = swVib.isChecked
        val spell = swSpell.isChecked
        val sayAll = swVoice.isChecked && plain.isNotBlank()
        tts?.stop()

        player.execute {
            var track: AudioTrack? = null
            if (useSound) {
                val pcm = tonePcm(tl)
                track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(22050)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC).build()
                track.write(pcm, 0, pcm.size)
            }
            if (useVib) vibratePattern(tl)
            track?.play()
            for (s in steps) {
                if (useTorch) setTorch(s.on)
                if (useScreen) ui.post { flash.alpha = if (s.on) 1f else 0f }
                if (spell && s.spoken != null) tts?.speak(s.spoken, TextToSpeech.QUEUE_FLUSH, null, "letra")
                SystemClock.sleep(s.ms.toLong())
            }
            setTorch(false)
            ui.post { flash.alpha = 0f }
            track?.stop(); track?.release()
            if (sayAll) tts?.speak(plain, TextToSpeech.QUEUE_ADD, null, "frase")
        }
    }

    private fun vibratePattern(tl: List<Pair<Boolean, Int>>) {
        val pattern = ArrayList<Long>().apply { add(0L); tl.forEach { add(it.second.toLong()) } }.toLongArray()
        val amps = IntArray(pattern.size) { if (it % 2 == 1) 255 else 0 }
        vibrator()?.vibrate(VibrationEffect.createWaveform(pattern, amps, -1))
    }
}
