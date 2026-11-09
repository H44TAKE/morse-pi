package br.pi.morse

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * A carinha. Dez emoções (olhos, sobrancelhas, boca e efeitos próprios), piscadas, olhar que vagueia
 * e uma boca que mexe junto com as palavras. Tudo muda suavemente de uma emoção para a outra.
 */
class FaceView(ctx: Context) : View(ctx) {

    enum class Mood { IDLE, LISTENING, THINKING }

    enum class Emotion {
        NEUTRO, FELIZ, ANIMADO, CURIOSO, SURPRESO, TRISTE, PENSATIVO, APAIXONADO, BRINCALHAO, RINDO;
        companion object {
            fun from(s: String): Emotion = when (s.lowercase()) {
                "feliz" -> FELIZ; "animado" -> ANIMADO; "curioso" -> CURIOSO; "surpreso" -> SURPRESO
                "triste" -> TRISTE; "pensativo" -> PENSATIVO; "apaixonado" -> APAIXONADO
                "brincalhao" -> BRINCALHAO; "rindo" -> RINDO; else -> NEUTRO
            }
        }
    }

    @Volatile var mood = Mood.IDLE
    @Volatile var emotion = Emotion.NEUTRO
    @Volatile var speaking = false
    @Volatile var glow = false          // os olhos acendem no ritmo do Morse

    // parâmetros de expressão (cada emoção é uma lista deles; a transição é suave)
    private enum class K { EYE, SQUINT, DROOP, PUPIL, BROWL, BROWR, TILT, SMILE, MOUTHW, OPEN, TONGUE, CHEEK,
        HEART, SPARK, TEAR, WINK, BOUNCE, GX, GY }

    private fun ex(vararg kv: Pair<K, Float>): FloatArray {
        val a = FloatArray(K.values().size)
        a[K.EYE.ordinal] = 1f; a[K.PUPIL.ordinal] = 1f; a[K.SMILE.ordinal] = 1f
        a[K.MOUTHW.ordinal] = 1f; a[K.CHEEK.ordinal] = 55f
        kv.forEach { a[it.first.ordinal] = it.second }
        return a
    }

    private val table: Map<Emotion, FloatArray> = mapOf(
        Emotion.NEUTRO to ex(),
        Emotion.FELIZ to ex(K.SQUINT to 0.35f, K.SMILE to 1.6f, K.MOUTHW to 1.15f, K.CHEEK to 95f,
            K.BROWL to 2f, K.BROWR to 2f),
        Emotion.ANIMADO to ex(K.EYE to 1.15f, K.SQUINT to 0.12f, K.SMILE to 1.8f, K.MOUTHW to 1.25f,
            K.OPEN to 0.25f, K.SPARK to 1f, K.CHEEK to 115f, K.BROWL to 5f, K.BROWR to 5f, K.BOUNCE to 0.6f),
        Emotion.CURIOSO to ex(K.EYE to 1.1f, K.PUPIL to 1.15f, K.BROWL to 6f, K.BROWR to 0f, K.SMILE to 0.5f,
            K.MOUTHW to 0.8f, K.GX to 0.5f, K.GY to -0.2f),
        Emotion.SURPRESO to ex(K.EYE to 1.3f, K.PUPIL to 0.55f, K.BROWL to 8f, K.BROWR to 8f, K.SMILE to 0f,
            K.MOUTHW to 0.5f, K.OPEN to 0.6f, K.CHEEK to 30f),
        Emotion.TRISTE to ex(K.EYE to 0.95f, K.DROOP to 0.8f, K.TILT to -1f, K.PUPIL to 1.15f, K.SMILE to -1.3f,
            K.MOUTHW to 0.8f, K.TEAR to 1f, K.CHEEK to 15f, K.GY to 0.55f),
        Emotion.PENSATIVO to ex(K.BROWL to 6f, K.BROWR to -1f, K.SMILE to 0.1f, K.MOUTHW to 0.7f,
            K.GX to -0.7f, K.GY to -0.7f),
        Emotion.APAIXONADO to ex(K.EYE to 1.08f, K.HEART to 1f, K.SMILE to 1.6f, K.CHEEK to 135f,
            K.BROWL to 4f, K.BROWR to 4f),
        Emotion.BRINCALHAO to ex(K.WINK to 1f, K.SMILE to 1.7f, K.TONGUE to 1f, K.MOUTHW to 1.1f,
            K.CHEEK to 95f, K.BROWL to 3f, K.BROWR to -2f),
        Emotion.RINDO to ex(K.SQUINT to 1f, K.SMILE to 1.3f, K.MOUTHW to 1.35f, K.OPEN to 0.55f,
            K.CHEEK to 125f, K.BOUNCE to 1f, K.BROWL to 3f, K.BROWR to 3f),
    )
    private val cur = ex()

    private var mouth = 0f
    private var mouthTarget = 0f
    private var lastPulse = 0L
    private var nextBlink = SystemClock.uptimeMillis() + 2000
    private var blinkStart = -1L

    private val bg = Paint()
    private val eyeP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pupilP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#10202A") }
    private val shineP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val haloP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val browP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFB020"); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val arcP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EAF6FF"); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val mouthFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2A0A12") }
    private val mouthLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFB020"); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val tongueP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E85D75") }
    private val cheekP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF7A93") }
    private val dotP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFB020") }
    private val heartP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF4D7D") }
    private val sparkP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFE9A8") }
    private val tearP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#7FD4FF") }
    private val path = Path()
    private val rect = RectF()

    /** Um "pulso" de boca aberta (chamado a cada palavra falada). */
    fun pulse() {
        mouthTarget = 0.45f + Random.nextFloat() * 0.55f
        lastPulse = SystemClock.uptimeMillis()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        bg.shader = RadialGradient(w / 2f, h / 2f, maxOf(w, h) * 0.7f,
            Color.parseColor("#16222E"), Color.parseColor("#0B0F14"), Shader.TileMode.CLAMP)
    }

    private fun heart(c: Canvas, x: Float, y: Float, s: Float) {
        path.reset()
        path.moveTo(x, y + s * 0.9f)
        path.cubicTo(x - s * 1.4f, y - s * 0.1f, x - s * 0.6f, y - s * 1.1f, x, y - s * 0.4f)
        path.cubicTo(x + s * 0.6f, y - s * 1.1f, x + s * 1.4f, y - s * 0.1f, x, y + s * 0.9f)
        path.close()
        c.drawPath(path, heartP)
    }

    private fun sparkle(c: Canvas, x: Float, y: Float, r: Float) {
        path.reset()
        path.moveTo(x, y - r)
        path.quadTo(x, y, x + r, y)
        path.quadTo(x, y, x, y + r)
        path.quadTo(x, y, x - r, y)
        path.quadTo(x, y, x, y - r)
        path.close()
        c.drawPath(path, sparkP)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val now = SystemClock.uptimeMillis()
        val t = now / 1000f
        val u = min(w, h)
        val cx = w / 2; val cy = h / 2

        c.drawRect(0f, 0f, w, h, bg)

        // ---- emoção efetiva e transição suave ----
        val mood = mood
        val eff = when (mood) {
            Mood.THINKING -> Emotion.PENSATIVO
            Mood.LISTENING -> Emotion.CURIOSO
            else -> emotion
        }
        val tgt = table[eff]!!
        for (i in cur.indices) cur[i] += (tgt[i] - cur[i]) * 0.14f
        fun p(k: K) = cur[k.ordinal]

        // ---- animação ----
        if (speaking) {
            if (now - lastPulse > 170) { if (Random.nextFloat() < 0.75f) pulse() else mouthTarget = 0.1f; lastPulse = now }
        } else mouthTarget = 0f
        mouthTarget *= 0.9f
        mouth += (mouthTarget - mouth) * 0.4f

        var open = 1f
        if (blinkStart < 0 && now > nextBlink) blinkStart = now
        if (blinkStart >= 0) {
            val f = (now - blinkStart) / 170f
            if (f >= 1f) { blinkStart = -1; nextBlink = now + 2200 + Random.nextLong(2800) }
            else open = (1f - sin(PI.toFloat() * f)).coerceAtLeast(0.06f)
        }

        val gx = sin(t * 0.7f) * 0.25f * (1f - abs(p(K.GX))) + p(K.GX)
        val gy = cos(t * 0.5f) * 0.15f * (1f - abs(p(K.GY))) + p(K.GY)

        c.save()
        c.translate(0f, sin(t * 13f) * u * 0.012f * p(K.BOUNCE))

        // ---- olhos ----
        val spacing = min(u * 0.55f, w * 0.27f)
        val eyeW = u * 0.30f * p(K.EYE)
        val listen = if (mood == Mood.LISTENING) 1.1f else 1f
        val eyeH = u * 0.36f * p(K.EYE) * listen * open
        val ey = cy - u * 0.10f
        for (side in intArrayOf(-1, 1)) {
            val ex = cx + side * spacing
            val sq = if (side == 1) max(p(K.SQUINT), p(K.WINK)) else p(K.SQUINT)
            rect.set(ex - eyeW / 2, ey - eyeH / 2, ex + eyeW / 2, ey + eyeH / 2)

            if (sq > 0.85f) {
                // olho fechado de felicidade: ^
                arcP.strokeWidth = u * 0.055f
                path.reset()
                path.moveTo(ex - eyeW * 0.45f, ey + eyeW * 0.12f)
                path.quadTo(ex, ey - eyeW * 0.5f, ex + eyeW * 0.45f, ey + eyeW * 0.12f)
                c.drawPath(path, arcP)
            } else {
                if (glow) {
                    haloP.color = Color.parseColor("#7DF9FF")
                    for (k in 3 downTo 1) {
                        haloP.alpha = 38 * (4 - k)
                        val g = eyeW * 0.18f * k
                        c.drawOval(rect.left - g, rect.top - g, rect.right + g, rect.bottom + g, haloP)
                    }
                }
                eyeP.color = if (glow) Color.WHITE else Color.parseColor("#EAF6FF")
                c.drawOval(rect, eyeP)

                path.reset(); path.addOval(rect, Path.Direction.CW)
                c.save(); c.clipPath(path)
                if (open > 0.3f) {
                    val px = ex + gx * eyeW * 0.22f
                    val py = ey + gy * eyeH * 0.22f
                    if (p(K.HEART) > 0.5f) {
                        heart(c, px, py, eyeW * 0.26f * (1f + 0.12f * sin(t * 8f)))
                    } else {
                        val pr = eyeW * 0.30f * p(K.PUPIL)
                        c.drawCircle(px, py, pr, pupilP)
                        c.drawCircle(px - pr * 0.35f, py - pr * 0.35f, pr * 0.28f, shineP)
                    }
                }
                // pálpebra de baixo subindo (sorriso nos olhos)
                if (sq > 0.02f) {
                    val rr = eyeW * 0.9f
                    c.drawCircle(ex, ey + eyeH / 2 + rr - sq * eyeH * 0.95f, rr, bg)
                }
                // pálpebra caída nos cantos de fora (tristeza)
                if (p(K.DROOP) > 0.02f) {
                    val outerLeft = side < 0
                    val offOuter = p(K.DROOP) * eyeH * 0.6f
                    val offInner = p(K.DROOP) * eyeH * 0.05f
                    val padX = eyeW * 0.2f
                    val top = rect.top - eyeH * 0.2f
                    path.reset()
                    path.moveTo(rect.left - padX, top)
                    path.lineTo(rect.right + padX, top)
                    path.lineTo(rect.right + padX, rect.top + if (outerLeft) offInner else offOuter)
                    path.lineTo(rect.left - padX, rect.top + if (outerLeft) offOuter else offInner)
                    path.close()
                    c.drawPath(path, bg)
                }
                c.restore()
            }

            // faíscas de empolgação
            if (p(K.SPARK) > 0.4f) {
                val ph = t * 3f + side
                sparkle(c, ex + side * eyeW * 0.65f, ey - eyeH * 0.55f, u * 0.035f * (0.6f + 0.4f * sin(ph)))
                sparkle(c, ex + side * eyeW * 0.85f, ey + eyeH * 0.1f, u * 0.022f * (0.6f + 0.4f * cos(ph * 1.3f)))
            }
            // lágrima
            if (p(K.TEAR) > 0.5f && side < 0) {
                val f = (t * 0.55f) % 1f
                tearP.alpha = (230 * (1f - f)).toInt()
                val ty = ey + eyeH * 0.5f + f * u * 0.22f
                c.drawCircle(ex - eyeW * 0.1f, ty, u * 0.022f, tearP)
            }

            // sobrancelha
            browP.strokeWidth = u * 0.028f
            val lift = (if (side < 0) p(K.BROWL) else p(K.BROWR)) * u * 0.01f
            val base = ey - max(eyeH, u * 0.12f) / 2 - u * 0.09f - lift - mouth * u * 0.03f
            val outerX = ex + side * eyeW * 0.45f
            val innerX = ex - side * eyeW * 0.45f
            c.drawLine(outerX, base, innerX, base + p(K.TILT) * u * 0.05f, browP)

            // bochecha
            cheekP.alpha = p(K.CHEEK).toInt().coerceIn(0, 255)
            c.drawCircle(ex + side * eyeW * 0.15f, cy + u * 0.17f, u * 0.065f, cheekP)
        }

        // ---- boca ----
        val my = cy + u * 0.22f
        val mw = u * (0.26f * p(K.MOUTHW) + 0.10f * mouth)
        val openEff = max(p(K.OPEN), mouth)
        val smile = u * 0.07f * p(K.SMILE) * (1f - mouth * 0.6f)
        val mh = u * (0.02f + 0.24f * openEff)
        val l = cx - mw / 2; val r = cx + mw / 2
        path.reset()
        if (abs(smile) < u * 0.012f && openEff > 0.3f) {
            path.addOval(l, my, r, my + mh, Path.Direction.CW)          // "O" de surpresa
        } else {
            path.moveTo(l, my)
            path.quadTo(cx, my + smile * 2f, r, my)
            path.quadTo(cx, my + smile * 2f + mh * 2f, l, my)
            path.close()
        }
        c.drawPath(path, mouthFill)
        if (mh > u * 0.08f) {
            c.save(); c.clipPath(path)
            c.drawOval(cx - mw * 0.25f, my + smile + mh * 0.55f, cx + mw * 0.25f, my + smile + mh * 1.3f, tongueP)
            c.restore()
        }
        if (p(K.TONGUE) > 0.5f) {       // língua de fora
            val ty = my + smile + mh + u * 0.025f
            c.drawOval(cx + mw * 0.02f, ty - u * 0.02f, cx + mw * 0.34f, ty + u * 0.075f, tongueP)
        }
        mouthLine.strokeWidth = u * 0.026f
        c.drawPath(path, mouthLine)
        c.restore()

        // ---- "pensando..." ----
        if (mood == Mood.THINKING) {
            for (i in 0..2) {
                val a = ((sin(t * 5f - i * 0.9f) + 1f) / 2f)
                dotP.alpha = (70 + 185 * a).toInt()
                c.drawCircle(cx + (i - 1) * u * 0.07f, cy - u * 0.50f, u * 0.022f, dotP)
            }
        }
        postInvalidateOnAnimation()
    }
}
