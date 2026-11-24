package app.toque.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import app.toque.core.i18n.tr

/**
 * Cadran « instrument » de la cuisson manuelle : la durée au centre, la température sur l'arc de
 * gauche (dégradé de chaleur, qui monte du bas), la vitesse sur l'arc de droite (qui monte aussi du
 * bas). Arcs fins, graduations à l'extérieur, valeurs en chiffres fins en haut du cadran.
 *
 * Réglage au doigt : toucher un arc (ou sa valeur) le sélectionne, le faire glisser le règle ;
 * toucher le centre sélectionne la durée, glisser vers le haut ou le bas la change. Le bouton
 * rotatif règle ce qui est sélectionné (voir ManualScreen).
 *
 * Positions en fractions de 0 à 1 de chaque arc. Rien n'est alloué pendant le dessin.
 */
class Cockpit(c: Context) : View(c) {
    /** 0 = durée, 1 = température, 2 = vitesse. */
    var selected = 0
        set(v) { field = v; invalidate() }
    var onSelect: ((Int) -> Unit)? = null
    /** Glissé sur un arc : réglage (1 ou 2) et fraction visée. */
    var onDrag: ((Int, Float) -> Unit)? = null
    /** Glissé vertical au centre : un cran de durée (+1 vers le haut). */
    var onTimeStep: ((Int) -> Unit)? = null

    // État affiché, réglé par l'écran puis invalidate().
    var timeText = ""
    var timeCaption = ""
    var tempFrac = 0f
    var tempOkTo = 1f
    /** Valeur de température (« 100° ») ; null = sans chauffe, l'icône thermomètre à la place. */
    var tempText: String? = null
    var tempTicks = FloatArray(0)
    var tempMajor = BooleanArray(0)
    var speedFrac = 0f
    var speedOkFrom = 0f
    var speedOkTo = 1f
    /** Valeur de vitesse ; null = lame à l'arrêt, l'icône lame à la place. */
    var speedText: String? = null
    /** Température réelle du bol sur l'arc ; null = pas de mesure. */
    var bowlFrac: Float? = null
    var bowlText = ""
    var notice = ""
    var noticeColor = 0

    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val heatArc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { fontFeatureSettings = "tnum" }
    private val noticePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()
    private val gradMatrix = Matrix()
    private val thermo = Icon(c, Icon.Kind.THERMO, Ui.TEXT3)
    private val blade = Icon(c, Icon.Kind.BLADE, Ui.TEXT3)
    private var noticeLayout: StaticLayout? = null
    private var noticeFor = ""
    private var noticeWidth = 0

    private var cx = 0f
    private var cy = 0f
    private var r = 0f
    private var iconSize = 0

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // Hauteur de l'ensemble (graduations comprises) : ~1,76 rayon. Largeur bornée pour laisser
        // les côtés aux interrupteurs et aux boutons.
        val pad = Ui.uf(26f)
        r = minOf(h / 1.78f - pad, w * 0.22f)
        cx = w / 2f
        cy = 0.885f * (r + pad) + Ui.uf(4f)
        oval.set(cx - r, cy - r, cx + r, cy + r)
        // Dégradé de chaleur le long de l'arc gauche (de 120° à 242° en angles Android).
        val from = TEMP_START / 360f
        val span = TEMP_SWEEP / 360f
        heatArc.shader = SweepGradient(cx, cy, HEAT, floatArrayOf(0f, from, from + span * 0.45f, from + span * 0.75f, from + span, 1f))
        gradMatrix.reset()
        heatArc.shader.setLocalMatrix(gradMatrix)
        iconSize = (r * 0.2f).toInt()
        val spec = MeasureSpec.makeMeasureSpec(iconSize, MeasureSpec.EXACTLY)
        for (i in arrayOf(thermo, blade)) { i.measure(spec, spec); i.layout(0, 0, iconSize, iconSize) }
        noticeFor = "\u0000"
    }

    override fun onDraw(canvas: Canvas) {
        if (r <= 0f) return
        val sw = maxOf(Ui.uf(7f), r * 0.04f)
        arc.strokeWidth = sw
        heatArc.strokeWidth = sw

        // Pistes.
        arc.color = Ui.TRACK
        canvas.drawArc(oval, TEMP_START, TEMP_SWEEP, false, arc)
        canvas.drawArc(oval, SPEED_TOP, TEMP_SWEEP, false, arc)

        // Graduations : température (une par palier du robot), vitesse (1 à 10).
        val r1 = r + sw * 0.5f + Ui.uf(7f)
        for (i in tempTicks.indices) {
            val f = tempTicks[i]
            val lit = tempText != null && f <= tempFrac + 0.001f
            tick.strokeWidth = if (tempMajor[i]) Ui.uf(2f) else Ui.uf(1.2f)
            tick.color = when {
                lit -> heatAt(f)
                f > tempOkTo + 0.001f -> dim(Ui.TEXT3)
                else -> Ui.TEXT3
            }
            drawTick(canvas, tempAngle(f), r1, r1 + if (tempMajor[i]) Ui.uf(12f) else Ui.uf(7f))
        }
        for (v in 1..10) {
            val f = v / 10f
            tick.strokeWidth = Ui.uf(2f)
            tick.color = when {
                speedText != null && f <= speedFrac + 0.001f -> speedColor
                f > speedOkTo + 0.001f -> dim(Ui.TEXT3)
                else -> Ui.TEXT3
            }
            drawTick(canvas, speedAngle(f), r1, r1 + Ui.uf(12f))
        }

        // Remplissages : chaleur jusqu'à la consigne, vitesse depuis le bas.
        if (tempText != null && tempFrac > 0f) canvas.drawArc(oval, TEMP_START, TEMP_SWEEP * tempFrac, false, heatArc)
        if (speedText != null && speedFrac > 0f) {
            arc.color = speedColor
            // Thème néon : la vitesse passe du rose (bas) au bleu (haut).
            Theme.p.accentEnd?.let { end ->
                arc.shader = android.graphics.LinearGradient(0f, cy + r, 0f, cy - r, Ui.ACCENT, end, android.graphics.Shader.TileMode.CLAMP)
            }
            canvas.drawArc(oval, SPEED_BOTTOM, -TEMP_SWEEP * speedFrac, false, arc)
            arc.shader = null
        }

        // Température réelle du bol : un trait clair en travers de l'arc.
        bowlFrac?.let { f ->
            tick.color = Ui.TEXT
            tick.strokeWidth = Ui.uf(3f)
            drawTick(canvas, tempAngle(f), r - sw * 1.6f, r + sw * 0.6f)
        }

        // En haut du cadran : TEMPÉRATURE | VITESSE, valeur ou icône dessous.
        val gap = r * 0.09f
        tick.color = Ui.STROKE
        tick.strokeWidth = Ui.uf(1f)
        canvas.drawLine(cx, cy - r * 0.74f, cx, cy - r * 0.38f, tick)
        caption(canvas, tr("Température"), cx - gap, cy - r * 0.64f, Paint.Align.RIGHT, selected == 1)
        caption(canvas, tr("Vitesse"), cx + gap, cy - r * 0.64f, Paint.Align.LEFT, selected == 2)
        val valueSize = r * 0.19f
        val valueBase = cy - r * 0.42f
        if (tempText != null) value(canvas, tempText!!, cx - gap, valueBase, valueSize, Paint.Align.RIGHT)
        else drawIcon(canvas, thermo, cx - gap - iconSize, valueBase - iconSize * 0.95f)
        if (speedText != null) value(canvas, speedText!!, cx + gap, valueBase, valueSize, Paint.Align.LEFT)
        else drawIcon(canvas, blade, cx + gap, valueBase - iconSize * 0.95f)

        // Durée au centre, sa légende, puis la température du bol.
        text.textAlign = Paint.Align.CENTER
        text.typeface = Ui.THIN
        text.color = Ui.TEXT
        text.textSize = r * 0.44f
        while (text.measureText(timeText) > r * 1.3f && text.textSize > r * 0.2f) text.textSize *= 0.94f
        canvas.drawText(timeText, cx, cy + r * 0.24f, text)
        caption(canvas, timeCaption, cx, cy + r * 0.39f, Paint.Align.CENTER, selected == 0)
        if (bowlText.isNotEmpty()) {
            text.typeface = Ui.MEDIUM
            text.textSize = maxOf(r * 0.068f, Ui.uf(12f))
            text.letterSpacing = 0.12f
            text.color = bowlFrac?.let { heatAt(it) } ?: Ui.TEXT2
            canvas.drawText(bowlText, cx, cy + r * 0.52f, text)
            text.letterSpacing = 0f
        }

        // Message (limite de sécurité, tare…) sous le tout, sur deux lignes au plus.
        if (notice.isNotEmpty()) {
            val w = (r * 1.25f).toInt()
            if (notice != noticeFor || w != noticeWidth) {
                noticePaint.typeface = Ui.MEDIUM
                noticePaint.textSize = maxOf(r * 0.075f, Ui.uf(14f))
                @Suppress("DEPRECATION")
                noticeLayout = StaticLayout(notice, noticePaint, w, Layout.Alignment.ALIGN_CENTER, 1f, 0f, false)
                noticeFor = notice
                noticeWidth = w
            }
            noticePaint.color = noticeColor
            canvas.save()
            canvas.translate(cx - w / 2f, cy + r * 0.59f)
            noticeLayout?.draw(canvas)
            canvas.restore()
        }
    }

    private val speedColor: Int get() = Theme.p.accentEnd ?: if (Ui.LIGHT_THEME) 0xFF3D7E99.toInt() else 0xFF9FC6D6.toInt()

    private fun caption(canvas: Canvas, s: String, x: Float, y: Float, align: Paint.Align, on: Boolean) {
        text.textAlign = align
        text.typeface = Ui.MEDIUM
        text.textSize = maxOf(r * 0.056f, Ui.uf(11f))
        text.letterSpacing = 0.12f
        text.color = if (on) Ui.ACCENT else Ui.TEXT2
        canvas.drawText(s.uppercase(), x, y, text)
        text.letterSpacing = 0f
    }

    private fun value(canvas: Canvas, s: String, x: Float, y: Float, size: Float, align: Paint.Align) {
        text.textAlign = align
        text.typeface = Ui.LIGHT
        text.textSize = size
        text.color = Ui.TEXT
        canvas.drawText(s, x, y, text)
    }

    private fun drawIcon(canvas: Canvas, icon: Icon, x: Float, y: Float) {
        if (icon.color != Ui.TEXT3) icon.color = Ui.TEXT3
        canvas.save()
        canvas.translate(x, y)
        icon.draw(canvas)
        canvas.restore()
    }

    private fun drawTick(canvas: Canvas, deg: Float, from: Float, to: Float) {
        val a = Math.toRadians(deg.toDouble())
        val cs = Math.cos(a).toFloat()
        val sn = Math.sin(a).toFloat()
        canvas.drawLine(cx + from * cs, cy + from * sn, cx + to * cs, cy + to * sn, tick)
    }

    /** Angle Android (0° = 3 h, sens horaire) d'une fraction de chaque arc. */
    private fun tempAngle(f: Float) = TEMP_START + TEMP_SWEEP * f
    private fun speedAngle(f: Float) = SPEED_BOTTOM - TEMP_SWEEP * f

    private fun heatAt(f: Float): Int {
        val stops = floatArrayOf(0f, 0.45f, 0.75f, 1f)
        val k = f.coerceIn(0f, 1f)
        for (i in 1 until stops.size) {
            if (k <= stops[i]) return mix(HEAT[i], HEAT[i + 1], (k - stops[i - 1]) / (stops[i] - stops[i - 1]))
        }
        return HEAT[4]
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) + ((((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * t)).toInt() shl s
        return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
    }

    private fun dim(c: Int) = (c and 0x00FFFFFF) or 0x55000000

    // ---- toucher
    private var dragParam = -1
    private var lastY = 0f
    private var travel = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val dx = e.x - cx
        val dy = e.y - cy
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val dist = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
                val deg = ((Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())) + 360) % 360).toFloat()
                val onRing = dist > r * 0.72f && dist < r + Ui.uf(48f)
                dragParam = when {
                    onRing && deg >= TEMP_START - 8 && deg <= TEMP_START + TEMP_SWEEP + 8 -> 1
                    onRing && (deg >= 360 + SPEED_TOP - 8 || deg <= SPEED_BOTTOM + 8) -> 2
                    dist <= r * 0.72f && e.y < cy - r * 0.34f -> if (dx < 0) 1 else 2
                    dist <= r * 0.72f -> 0
                    else -> -1
                }
                if (dragParam < 0) return false
                // Un seul doigt suffit : sélection immédiate, puis réglage si on glisse.
                if (dragParam != selected) onSelect?.invoke(dragParam)
                lastY = e.y
                travel = 0f
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                travel += Math.abs(e.y - lastY)
                when (dragParam) {
                    0 -> {
                        val step = Ui.uf(26f)
                        val d = lastY - e.y
                        if (Math.abs(d) >= step) {
                            onTimeStep?.invoke(if (d > 0) 1 else -1)
                            lastY = e.y
                        }
                    }
                    1, 2 -> if (travel > Ui.uf(6f) || Math.hypot(dx.toDouble(), dy.toDouble()) > r * 0.72f) {
                        val deg = ((Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())) + 360) % 360).toFloat()
                        val f = if (dragParam == 1) (deg - TEMP_START) / TEMP_SWEEP
                        else ((SPEED_BOTTOM - (if (deg > 180) deg - 360 else deg)) / TEMP_SWEEP)
                        onDrag?.invoke(dragParam, f.coerceIn(0f, 1f))
                        lastY = e.y
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragParam = -1
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private companion object {
        /** Arc gauche : de 210° à 332° sur le cadran (0° en haut), soit 120° à 242° en angles Android. */
        const val TEMP_START = 120f
        const val TEMP_SWEEP = 122f
        /** Arc droit : du bas (60° Android) vers le haut (-62°). */
        const val SPEED_BOTTOM = 60f
        const val SPEED_TOP = -62f
        /** Chaleur : gris tiède, ambre, orange, brique (sans arc-en-ciel). Premier et dernier = bornes du dégradé circulaire. */
        val HEAT = intArrayOf(0xFF78808C.toInt(), 0xFF78808C.toInt(), 0xFFD6A050.toInt(), 0xFFE26E3C.toInt(), 0xFFD64034.toInt(), 0xFFD64034.toInt())
    }
}

/**
 * Interrupteur à deux choix, les deux noms dans la piste ; une pastille en couleur d'accent glisse
 * sous le choix actif, l'autre nom reste neutre.
 */
class TwoWay(c: Context, private val left: String, private val right: String, private val onChange: (Boolean) -> Unit) : View(c) {
    var on = false
        private set
    private var pos = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Ui.MEDIUM }
    private val rect = RectF()

    fun set(v: Boolean, animate: Boolean = true) {
        if (v == on && (pos == if (v) 1f else 0f)) return
        on = v
        val to = if (v) 1f else 0f
        if (!animate) { pos = to; invalidate(); return }
        ValueAnimator.ofFloat(pos, to).apply {
            duration = 160
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val rad = minOf(h / 2f, Ui.uf(12f * Theme.p.corners))
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = Ui.uf(1.5f)
        paint.color = Ui.STROKE
        rect.set(paint.strokeWidth, paint.strokeWidth, w - paint.strokeWidth, h - paint.strokeWidth)
        canvas.drawRoundRect(rect, rad, rad, paint)
        val inset = Ui.uf(4f)
        val half = w / 2f
        val x = inset + (half - inset) * pos
        rect.set(x, inset, x + half - inset, h - inset)
        paint.style = Paint.Style.FILL
        // Le choix actif est toujours en couleur d'accent (Normal comme Inverse, Lame comme Fouet).
        paint.color = Ui.ACCENT_DIM
        canvas.drawRoundRect(rect, rad - inset * 0.6f, rad - inset * 0.6f, paint)
        label.textSize = Ui.uf(17f)
        val base = h / 2f - (label.descent() + label.ascent()) / 2f
        label.color = if (!on) Ui.ACCENT else Ui.TEXT2
        canvas.drawText(left, w / 4f, base, label)
        label.color = if (on) Ui.ACCENT else Ui.TEXT2
        canvas.drawText(right, w * 0.75f, base, label)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            // Toucher un côté le choisit ; toucher le côté déjà actif ne change rien.
            val want = e.x > width / 2f
            if (want != on) { Ui.onTap?.invoke(); onChange(want) }
        }
        return true
    }
}
