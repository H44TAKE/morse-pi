package br.pi.morse

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * A carinha, em estilo "motion graphic": olhos de pílula com pálpebras, molas (squash & stretch),
 * fundo que muda de cor com a emoção, anéis de som, partículas (corações, faíscas, "?", "!"...).
 */
class FaceView(ctx: Context) : View(ctx) {

    enum class Mood { IDLE, LISTENING, THINKING }

    enum class Emotion {
        NEUTRO, FELIZ, ANIMADO, CURIOSO, SURPRESO, TRISTE, PENSATIVO, APAIXONADO, BRINCALHAO, RINDO;
        companion object {
            fun from(s: String): Emotion = when (s.trim().lowercase()) {
                "feliz" -> FELIZ; "animado" -> ANIMADO; "curioso" -> CURIOSO; "surpreso" -> SURPRESO
                "triste" -> TRISTE; "pensativo" -> PENSATIVO; "apaixonado" -> APAIXONADO
                "brincalhao" -> BRINCALHAO; "rindo" -> RINDO; else -> NEUTRO
            }
        }
    }

    @Volatile var mood = Mood.IDLE
    @Volatile var emotion = Emotion.NEUTRO
    @Volatile var speaking = false
    @Volatile var glow = false

    private enum class K { EW, EH, LIDTOP, LIDTILT, LIDBOT, ARC, HEART, WINK, SMILE, MW, OPEN, TONGUE, CHEEK,
        TEAR, BOUNCE, GX, GY, ASYM }

    private fun ex(vararg kv: Pair<K, Float>): FloatArray {
        val a = FloatArray(K.values().size)
        a[K.EW.ordinal] = 1f; a[K.EH.ordinal] = 1f; a[K.SMILE.ordinal] = 0.6f; a[K.MW.ordinal] = 1f
        kv.forEach { a[it.first.ordinal] = it.second }
        return a
    }

    private val table: Map<Emotion, FloatArray> = mapOf(
        Emotion.NEUTRO to ex(),
        Emotion.FELIZ to ex(K.EH to 0.95f, K.LIDBOT to 0.4f, K.SMILE to 1.5f, K.MW to 1.15f, K.CHEEK to 0.5f),
        Emotion.ANIMADO to ex(K.EW to 1.1f, K.EH to 1.15f, K.LIDBOT to 0.22f, K.SMILE to 1.8f, K.OPEN to 0.3f,
            K.MW to 1.25f, K.CHEEK to 0.6f, K.BOUNCE to 0.7f),
        Emotion.CURIOSO to ex(K.EW to 1.05f, K.EH to 1.12f, K.SMILE to 0.2f, K.MW to 0.7f, K.GX to 0.5f,
            K.GY to -0.25f, K.ASYM to 1f),
        Emotion.SURPRESO to ex(K.EW to 1.12f, K.EH to 1.4f, K.SMILE to 0f, K.MW to 0.5f, K.OPEN to 0.65f),
        Emotion.TRISTE to ex(K.EH to 0.95f, K.LIDTOP to 0.24f, K.LIDTILT to 1f, K.SMILE to -1.2f, K.MW to 0.8f,
            K.TEAR to 1f, K.GY to 0.6f),
        Emotion.PENSATIVO to ex(K.LIDTOP to 0.18f, K.LIDTILT to -0.3f, K.SMILE to 0.1f, K.MW to 0.6f,
            K.GX to -0.7f, K.GY to -0.7f, K.ASYM to 0.5f),
        Emotion.APAIXONADO to ex(K.EW to 1.1f, K.EH to 1.1f, K.HEART to 1f, K.LIDBOT to 0.3f, K.SMILE to 1.5f,
            K.CHEEK to 0.8f),
        Emotion.BRINCALHAO to ex(K.WINK to 1f, K.LIDBOT to 0.2f, K.LIDTILT to -0.15f, K.SMILE to 1.7f,
            K.TONGUE to 1f, K.CHEEK to 0.6f),
        Emotion.RINDO to ex(K.ARC to 1f, K.SMILE to 1.4f, K.MW to 1.35f, K.OPEN to 0.6f, K.CHEEK to 0.8f,
            K.BOUNCE to 1.2f),
    )

    // cor de destaque de cada emoção (o fundo e os olhos se tingem com ela)
    private val accents: Map<Emotion, Int> = mapOf(
        Emotion.NEUTRO to 0xFF5EE1FF.toInt(), Emotion.FELIZ to 0xFFFFC94D.toInt(),
        Emotion.ANIMADO to 0xFFFF6AD5.toInt(), Emotion.CURIOSO to 0xFF7CFFB2.toInt(),
        Emotion.SURPRESO to 0xFFFFF27A.toInt(), Emotion.TRISTE to 0xFF6C8CFF.toInt(),
        Emotion.PENSATIVO to 0xFFB084FF.toInt(), Emotion.APAIXONADO to 0xFFFF5C8A.toInt(),
        Emotion.BRINCALHAO to 0xFFFF9A4D.toInt(), Emotion.RINDO to 0xFFFFD93D.toInt(),
    )

    // física das molas
    private val cur = ex()
    private val vel = FloatArray(cur.size)
    private val sp = FloatArray(2)       // [gaze x, gaze y] extra (olhar que passeia)
    private val spv = FloatArray(2)
    private var accent = accents[Emotion.NEUTRO]!!
    private var squash = 0f              // pulso de "mastigar" ao falar
    private var squashV = 0f
    private var mouth = 0f
    private var mouthTarget = 0f

    private var last = System.nanoTime()
    private var clock = 0f
    private var lastPulse = 0f
    private var nextBlink = 2f
    private var blinkT = -1f
    private var nextLook = 1.5f
    private var lookX = 0f
    private var lookY = 0f
    private var partTimer = 0f

    private class Pt(val kind: Int, var x: Float, var y: Float, var vx: Float, var vy: Float,
                     val life: Float, val size: Float, var age: Float = 0f, val ch: String = "")
    private val parts = ArrayList<Pt>()
    private class Ring(val born: Float, val strong: Boolean)
    private val rings = ArrayList<Ring>()

    private val bgP = Paint()
    private val blobP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eyeP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokeP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val eyePath = Path()
    private val lidPath = Path()
    private val botPath = Path()
    private val path = Path()
    private val rect = RectF()

    /** Um "pulso" a cada palavra falada: boca abre, rosto dá um soquinho e nasce um anel de som. */
    fun pulse() {
        mouthTarget = 0.5f + Random.nextFloat() * 0.5f
        squashV += 2.2f
        lastPulse = clock
        if (rings.size < 6) rings.add(Ring(clock, false))
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        fun ch(s: Int) = (((a shr s) and 255) * (1 - u) + ((b shr s) and 255) * u).toInt()
        return (255 shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun blob(c: Canvas, x: Float, y: Float, r: Float, color: Int, a: Int) {
        for (i in 0..5) {
            blobP.color = color; blobP.alpha = a
            c.drawCircle(x, y, r * (1f - i * 0.15f), blobP)
        }
    }

    private fun heart(c: Canvas, x: Float, y: Float, s: Float, p: Paint) {
        path.reset()
        path.moveTo(x, y + s * 0.9f)
        path.cubicTo(x - s * 1.4f, y - s * 0.1f, x - s * 0.6f, y - s * 1.1f, x, y - s * 0.4f)
        path.cubicTo(x + s * 0.6f, y - s * 1.1f, x + s * 1.4f, y - s * 0.1f, x, y + s * 0.9f)
        path.close()
        c.drawPath(path, p)
    }

    private fun sparkle(c: Canvas, x: Float, y: Float, r: Float, p: Paint) {
        path.reset()
        path.moveTo(x, y - r); path.quadTo(x, y, x + r, y); path.quadTo(x, y, x, y + r)
        path.quadTo(x, y, x - r, y); path.quadTo(x, y, x, y - r); path.close()
        c.drawPath(path, p)
    }

    private fun spawn(kind: Int, x: Float, y: Float, vx: Float, vy: Float, life: Float, size: Float, ch: String = "") {
        if (parts.size < 40) parts.add(Pt(kind, x, y, vx, vy, life, size, 0f, ch))
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val nowN = System.nanoTime()
        val dt = ((nowN - last) / 1e9f).coerceIn(0.001f, 0.033f)
        last = nowN; clock += dt
        val t = clock
        val u = min(w, h)
        val cx = w / 2; val cy = h / 2

        val mood = mood
        val eff = when (mood) { Mood.THINKING -> Emotion.PENSATIVO; Mood.LISTENING -> Emotion.CURIOSO; else -> emotion }
        val tgt = table[eff]!!
        // molas
        for (i in cur.indices) {
            val acc = 190f * (tgt[i] - cur[i]) - 15f * vel[i]
            vel[i] += acc * dt; cur[i] += vel[i] * dt
        }
        fun p(k: K) = cur[k.ordinal]
        accent = mix(accent, accents[eff]!!, 0.07f)

        // soquinho ao falar
        val sa = -260f * squash - 13f * squashV
        squashV += sa * dt; squash += squashV * dt
        if (speaking) {
            if (t - lastPulse > 0.2f) { if (Random.nextFloat() < 0.7f) pulse() else mouthTarget = 0.1f; lastPulse = t }
        } else mouthTarget = 0f
        mouthTarget *= 0.9f
        mouth += (mouthTarget - mouth) * min(1f, dt * 22f)

        // olhar que passeia
        if (t > nextLook) {
            lookX = Random.nextFloat() * 1.6f - 0.8f; lookY = Random.nextFloat() * 1.0f - 0.5f
            nextLook = t + 1.4f + Random.nextFloat() * 2.2f
        }
        for (i in 0..1) {
            val target = if (i == 0) lookX else lookY
            spv[i] += (140f * (target - sp[i]) - 16f * spv[i]) * dt; sp[i] += spv[i] * dt
        }
        val gx = sp[0] * 0.35f * (1f - abs(p(K.GX))) + p(K.GX)
        val gy = sp[1] * 0.3f * (1f - abs(p(K.GY))) + p(K.GY)

        // piscada
        var open = 1f
        if (blinkT < 0 && t > nextBlink) blinkT = 0f
        if (blinkT >= 0) {
            blinkT += dt
            val f = blinkT / 0.16f
            if (f >= 1f) { blinkT = -1f; nextBlink = t + 2.2f + Random.nextFloat() * 2.8f }
            else open = (1f - sin(PI.toFloat() * f)).coerceAtLeast(0.04f)
        }

        // ---------- fundo ----------
        val bgA = mix(0xFF0B0F14.toInt(), accent, 0.20f)
        val bgB = mix(0xFF05070A.toInt(), accent, 0.05f)
        bgP.shader = LinearGradient(0f, 0f, w, h, bgA, bgB, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, bgP)
        for (i in 0..2) {
            val a = t * (0.25f + i * 0.13f) + i * 2.1f
            blob(c, cx + cos(a) * w * 0.32f, cy + sin(a * 1.3f) * h * 0.28f, u * (0.55f + 0.12f * i),
                mix(accent, Color.WHITE, 0.15f * i), if (glow) 14 else 7)
        }

        // anéis de som
        val it = rings.iterator()
        while (it.hasNext()) {
            val r = it.next(); val age = t - r.born
            if (age > 1.1f) { it.remove(); continue }
            strokeP.color = accent; strokeP.alpha = ((1f - age / 1.1f) * 110).toInt()
            strokeP.strokeWidth = u * 0.012f
            c.drawCircle(cx, cy, u * (0.30f + age * 0.55f), strokeP)
        }

        // ---------- rosto (flutua, respira, quica) ----------
        c.save()
        val breathe = 1f + 0.012f * sin(t * 1.6f)
        c.translate(cx, cy + sin(t * 1.1f) * u * 0.01f + sin(t * 14f) * u * 0.012f * p(K.BOUNCE))
        c.scale(breathe * (1f - squash * 0.03f), breathe * (1f + squash * 0.05f))
        c.translate(-cx, -cy)

        val spacing = min(u * 0.52f, w * 0.26f)
        val hw0 = u * 0.14f * p(K.EW)
        val ey = cy - u * 0.10f
        val tintEye = mix(Color.WHITE, accent, 0.18f)
        val ox = gx * u * 0.05f; val oy = gy * u * 0.04f

        for (side in intArrayOf(-1, 1)) {
            val exx = cx + side * spacing + ox
            val asym = 1f + 0.22f * p(K.ASYM) * (if (side < 0) 1f else -1f)
            val hh = u * 0.19f * p(K.EH) * asym * open
            val hw = hw0
            val arcA = (if (side == 1) max(p(K.ARC), p(K.WINK)) else p(K.ARC)).coerceIn(0f, 1f)
            val heartA = p(K.HEART).coerceIn(0f, 1f)
            val eyeA = (1f - arcA) * (1f - heartA)

            if (glow) {
                haloP.color = Color.parseColor("#7DF9FF")
                for (k in 4 downTo 1) {
                    haloP.alpha = 30 * (5 - k)
                    val g = u * 0.025f * k
                    rect.set(exx - hw - g, ey + oy - hh - g, exx + hw + g, ey + oy + hh + g)
                    c.drawRoundRect(rect, hw + g, hw + g, haloP)
                }
            } else {
                // brilho suave da cor da emoção atrás do olho
                haloP.color = accent
                for (k in 3 downTo 1) {
                    haloP.alpha = (14 * eyeA).toInt()
                    val g = u * 0.03f * k
                    rect.set(exx - hw - g, ey + oy - hh - g, exx + hw + g, ey + oy + hh + g)
                    c.drawRoundRect(rect, hw + g, hw + g, haloP)
                }
            }

            if (eyeA > 0.02f) {
                val rad = min(hw, hh) * 0.95f
                rect.set(exx - hw, ey + oy - hh, exx + hw, ey + oy + hh)
                eyePath.reset(); eyePath.addRoundRect(rect, rad, rad, Path.Direction.CW)
                c.save(); c.clipPath(eyePath)
                // pálpebra de cima (triste inclina para fora, bravo para dentro)
                if (p(K.LIDTOP) > 0.01f || abs(p(K.LIDTILT)) > 0.01f) {
                    val edge = ey + oy - hh + p(K.LIDTOP) * 2f * hh
                    val tilt = p(K.LIDTILT) * hh * 0.8f
                    val yOut = edge + tilt; val yIn = edge - tilt
                    val xo = exx + side * hw * 1.3f; val xi = exx - side * hw * 1.3f
                    lidPath.reset()
                    lidPath.moveTo(xo, ey + oy - hh * 2f); lidPath.lineTo(xo, yOut)
                    lidPath.lineTo(xi, yIn); lidPath.lineTo(xi, ey + oy - hh * 2f); lidPath.close()
                    c.clipOutPath(lidPath)
                }
                // pálpebra de baixo (bochecha subindo = sorriso nos olhos)
                if (p(K.LIDBOT) > 0.01f) {
                    val rr = hw * 1.25f
                    botPath.reset()
                    botPath.addCircle(exx, ey + oy + hh + rr - p(K.LIDBOT) * 1.7f * hh, rr, Path.Direction.CW)
                    c.clipOutPath(botPath)
                }
                eyeP.color = tintEye; eyeP.alpha = (255 * eyeA).toInt()
                c.drawRect(rect, eyeP)
                // brilho do olhar
                fillP.color = Color.WHITE; fillP.alpha = (230 * eyeA).toInt()
                val sx = exx - hw * 0.35f + gx * hw * 0.25f
                val sy = ey + oy - hh * 0.4f + gy * hh * 0.2f
                c.drawCircle(sx, sy, hw * 0.2f, fillP)
                c.drawCircle(sx + hw * 0.5f, sy + hh * 0.55f, hw * 0.09f, fillP)
                c.restore()
            }
            if (arcA > 0.02f) {                       // olho fechado de felicidade: ^
                strokeP.color = tintEye; strokeP.alpha = (255 * arcA).toInt(); strokeP.strokeWidth = u * 0.05f
                path.reset()
                path.moveTo(exx - hw * 0.9f, ey + oy + hw * 0.35f)
                path.quadTo(exx, ey + oy - hw * 1.1f, exx + hw * 0.9f, ey + oy + hw * 0.35f)
                c.drawPath(path, strokeP)
            }
            if (heartA > 0.02f) {                     // olho de coração que pulsa
                fillP.color = 0xFFFF4D7D.toInt(); fillP.alpha = (255 * heartA).toInt()
                heart(c, exx, ey + oy, hw * 1.05f * heartA * (1f + 0.1f * sin(t * 9f)), fillP)
            }
            // lágrima
            if (p(K.TEAR) > 0.5f && side < 0) {
                val f = (t * 0.5f) % 1f
                fillP.color = 0xFF7FD4FF.toInt(); fillP.alpha = (230 * (1f - f)).toInt()
                c.drawCircle(exx - hw * 0.2f, ey + oy + hh * 0.7f + f * u * 0.25f, u * 0.024f, fillP)
            }
            // bochecha rosada
            fillP.color = 0xFFFF7A93.toInt(); fillP.alpha = (p(K.CHEEK).coerceIn(0f, 1f) * 110).toInt()
            rect.set(exx + side * hw * 0.2f - u * 0.07f, cy + u * 0.15f, exx + side * hw * 0.2f + u * 0.07f, cy + u * 0.23f)
            c.drawOval(rect, fillP)
        }

        // ---------- boca ----------
        val mx = cx + ox * 0.5f
        val my = cy + u * 0.20f + oy * 0.4f
        val mw = u * (0.24f * p(K.MW) + 0.10f * mouth)
        val openEff = max(p(K.OPEN), mouth)
        val smile = u * 0.075f * p(K.SMILE) * (1f - mouth * 0.5f)
        val mh = u * (0.012f + 0.26f * openEff)
        val l = mx - mw / 2; val r = mx + mw / 2
        path.reset()
        if (abs(smile) < u * 0.015f && openEff > 0.3f) {
            path.addOval(l, my, r, my + mh, Path.Direction.CW)
        } else {
            path.moveTo(l, my)
            path.quadTo(mx, my + smile * 2f, r, my)
            path.quadTo(mx, my + smile * 2f + mh * 2f, l, my)
            path.close()
        }
        fillP.color = 0xFF1B0A14.toInt(); fillP.alpha = 255
        c.drawPath(path, fillP)
        if (mh > u * 0.07f) {
            c.save(); c.clipPath(path)
            fillP.color = 0xFFE85D75.toInt()
            c.drawOval(mx - mw * 0.25f, my + smile + mh * 0.6f, mx + mw * 0.25f, my + smile + mh * 1.4f, fillP)
            c.restore()
        }
        if (p(K.TONGUE) > 0.5f) {
            fillP.color = 0xFFE85D75.toInt()
            val ty = my + smile + mh + u * 0.02f
            c.drawOval(mx + mw * 0.02f, ty - u * 0.02f, mx + mw * 0.32f, ty + u * 0.07f, fillP)
        }
        strokeP.color = accent; strokeP.alpha = 255; strokeP.strokeWidth = u * 0.03f
        c.drawPath(path, strokeP)
        c.restore()

        // ---------- partículas ----------
        partTimer += dt
        val e = eff
        val interval = when (e) {
            Emotion.APAIXONADO -> 0.35f; Emotion.ANIMADO, Emotion.RINDO -> 0.25f; Emotion.FELIZ, Emotion.BRINCALHAO -> 0.6f
            Emotion.CURIOSO, Emotion.SURPRESO -> 1.1f; Emotion.TRISTE -> 0.9f; Emotion.PENSATIVO -> 0.8f; else -> 99f
        }
        if (partTimer > interval) {
            partTimer = 0f
            val sx = cx + (Random.nextFloat() - 0.5f) * w * 0.7f
            when (e) {
                Emotion.APAIXONADO -> spawn(0, sx, cy + u * 0.3f, (Random.nextFloat() - 0.5f) * u * 0.05f, -u * 0.22f, 2.2f, u * 0.035f)
                Emotion.ANIMADO, Emotion.RINDO, Emotion.FELIZ, Emotion.BRINCALHAO ->
                    spawn(1, sx, cy - u * 0.1f + (Random.nextFloat() - 0.5f) * u * 0.6f, 0f, -u * 0.03f, 1.2f, u * 0.04f)
                Emotion.CURIOSO -> spawn(2, cx + w * 0.3f, cy - u * 0.3f, 0f, -u * 0.05f, 1.6f, u * 0.12f, "?")
                Emotion.SURPRESO -> spawn(2, cx + w * 0.3f, cy - u * 0.3f, 0f, -u * 0.05f, 1.2f, u * 0.14f, "!")
                Emotion.TRISTE -> spawn(2, sx, cy - u * 0.5f, 0f, u * 0.12f, 1.8f, u * 0.05f, "·")
                Emotion.PENSATIVO -> spawn(2, cx + w * 0.28f, cy - u * 0.35f, 0f, -u * 0.04f, 1.8f, u * 0.09f, "…")
                else -> {}
            }
        }
        val pi = parts.iterator()
        while (pi.hasNext()) {
            val q = pi.next()
            q.age += dt
            if (q.age > q.life) { pi.remove(); continue }
            q.x += q.vx * dt; q.y += q.vy * dt
            val f = q.age / q.life
            val alpha = ((if (f < 0.15f) f / 0.15f else 1f - (f - 0.15f) / 0.85f) * 255).toInt().coerceIn(0, 255)
            when (q.kind) {
                0 -> { fillP.color = 0xFFFF4D7D.toInt(); fillP.alpha = alpha; heart(c, q.x + sin(t * 3f + q.size) * u * 0.02f, q.y, q.size, fillP) }
                1 -> { fillP.color = mix(accent, Color.WHITE, 0.6f); fillP.alpha = alpha; sparkle(c, q.x, q.y, q.size * (0.5f + 0.5f * sin(q.age * 9f)), fillP) }
                else -> { textP.color = accent; textP.alpha = alpha; textP.textSize = q.size * 2f; c.drawText(q.ch, q.x, q.y, textP) }
            }
        }

        // pensando: três bolinhas ondulando
        if (mood == Mood.THINKING) {
            for (i in 0..2) {
                val a = (sin(t * 6f - i * 0.9f) + 1f) / 2f
                fillP.color = accent; fillP.alpha = (80 + 175 * a).toInt()
                c.drawCircle(cx + (i - 1) * u * 0.08f, cy - u * 0.48f - a * u * 0.02f, u * 0.024f, fillP)
            }
        }
        postInvalidateOnAnimation()
    }
}
