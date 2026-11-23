package app.toque.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Jauge du réglage choisi en cuisson manuelle : un arc de 270°, la valeur au centre, et la plage
 * permise par la sécurité. La partie interdite (par exemple au-dessus de la vitesse 3 en chauffe)
 * est tracée en pointillés : on voit d'un coup d'œil jusqu'où le bouton rotatif peut aller.
 *
 * Toutes les positions sont des fractions de 0 à 1 de l'arc. Rien n'est alloué pendant le dessin.
 */
class Gauge(c: Context) : View(c) {
    private var frac = 0f
    private var okFrom = 0f
    private var okTo = 1f
    private var color = Ui.ACCENT
    private var value = ""
    private var unit = ""
    private var sub = ""
    private var ticks: List<Pair<Float, String>> = emptyList()
    /** Mesure réelle (température du bol), en fine aiguille intérieure ; null = rien. */
    private var actual: Float? = null
    private var actualColor = Ui.TEXT2
    /** Sans graduations (petits cadrans) : le cercle occupe toute la place, sans marge pour elles. */
    var compact = false

    // Bouts arrondis partout : piste, valeur, zone interdite (des points ronds) et mesure réelle.
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val oval = RectF()
    private val inner = RectF()
    private var dashFor = -1f

    fun set(frac: Float, okFrom: Float, okTo: Float, color: Int, value: String, unit: String, sub: String,
            ticks: List<Pair<Float, String>>, actual: Float? = null, actualColor: Int = Ui.TEXT2) {
        this.frac = frac.coerceIn(0f, 1f)
        this.okFrom = okFrom.coerceIn(0f, 1f)
        this.okTo = okTo.coerceIn(0f, 1f)
        this.color = color
        this.value = value
        this.unit = unit
        this.sub = sub
        this.ticks = ticks
        this.actual = actual?.coerceIn(0f, 1f)
        this.actualColor = actualColor
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        if (size <= 0f) return
        val sw = size * 0.075f
        val cx = width / 2f
        // L'arc ouvert vers le bas : on le remonte un peu pour centrer l'ensemble.
        val cy = height / 2f + if (compact) size * 0.06f else size * 0.04f
        val rad = size / 2f - sw - if (compact) size * 0.01f else size * 0.07f
        oval.set(cx - rad, cy - rad, cx + rad, cy + rad)
        val start = 135f
        val sweep = 270f
        if (dashFor != sw) {
            // Avec des bouts ronds, un trait quasi nul devient un point : une rangée de points ronds.
            dashed.pathEffect = DashPathEffect(floatArrayOf(0.01f, sw * 0.75f), 0f)
            dashFor = sw
        }
        arc.strokeWidth = sw
        dashed.strokeWidth = sw * 0.38f

        // Fond de la plage permise, puis zones interdites en pointillés.
        arc.color = Ui.TRACK
        canvas.drawArc(oval, start + sweep * okFrom, sweep * (okTo - okFrom), false, arc)
        dashed.color = Ui.TRACK
        if (okFrom > 0f) canvas.drawArc(oval, start, sweep * okFrom, false, dashed)
        if (okTo < 1f) canvas.drawArc(oval, start + sweep * okTo, sweep * (1f - okTo), false, dashed)

        // Valeur réglée.
        arc.color = color
        if (frac > 0f) canvas.drawArc(oval, start, sweep * frac, false, arc)

        // Mesure réelle : arc fin à l'intérieur.
        actual?.let { f ->
            val r2 = rad - sw * 1.1f
            inner.set(cx - r2, cy - r2, cx + r2, cy + r2)
            arc.strokeWidth = sw * 0.25f
            arc.color = Ui.TRACK
            canvas.drawArc(inner, start, sweep, false, arc)
            arc.color = actualColor
            if (f > 0f) canvas.drawArc(inner, start, sweep * f, false, arc)
            arc.strokeWidth = sw
        }

        // Curseur au bout de la valeur.
        val a = Math.toRadians((start + sweep * frac).toDouble())
        val kx = cx + (rad * Math.cos(a)).toFloat()
        val ky = cy + (rad * Math.sin(a)).toFloat()
        dot.color = Ui.BG
        canvas.drawCircle(kx, ky, sw * 0.62f, dot)
        dot.color = color
        canvas.drawCircle(kx, ky, sw * 0.42f, dot)

        // Graduations à l'extérieur de l'arc.
        text.typeface = Ui.REGULAR
        text.textSize = size * 0.042f
        val rt = rad + sw * 0.5f + size * 0.045f
        for ((f, label) in ticks) {
            val ta = Math.toRadians((start + sweep * f).toDouble())
            text.color = if (f >= okFrom - 0.001f && f <= okTo + 0.001f) Ui.TEXT2 else Ui.TEXT3
            canvas.drawText(label, cx + (rt * Math.cos(ta)).toFloat(), cy + (rt * Math.sin(ta)).toFloat() + text.textSize * 0.35f, text)
        }

        // Valeur, unité et détail au centre. La valeur rapetisse pour tenir à l'intérieur de l'arc
        // de mesure (« 100 °C », « 15:00 »), sans jamais le toucher.
        val room = (rad - sw * (if (actual != null) 1.6f else 0.9f)) * 1.45f
        var valueSize = size * 0.26f
        val unitSize = size * 0.075f
        text.typeface = Ui.REGULAR
        text.textSize = unitSize
        val uw = if (unit.isEmpty()) 0f else text.measureText(" $unit")
        text.typeface = Ui.THIN
        text.textSize = valueSize
        var vw = text.measureText(value)
        while (vw + uw > room && valueSize > size * 0.12f) {
            valueSize -= size * 0.01f
            text.textSize = valueSize
            vw = text.measureText(value)
        }
        val left = cx - (vw + uw) / 2f
        val baseline = cy + valueSize * 0.32f
        text.textAlign = Paint.Align.LEFT
        text.color = Ui.TEXT
        canvas.drawText(value, left, baseline, text)
        if (unit.isNotEmpty()) {
            text.typeface = Ui.REGULAR
            text.color = Ui.TEXT2
            text.textSize = unitSize
            canvas.drawText(" $unit", left + vw, baseline, text)
        }
        text.textAlign = Paint.Align.CENTER
        text.typeface = Ui.REGULAR
        text.color = Ui.TEXT2
        // Détail sous la valeur : plus petit sur un grand cadran, lisible sur un petit.
        text.textSize = maxOf(size * 0.045f, Ui.uf(13f))
        canvas.drawText(sub, cx, baseline + text.textSize * 1.7f, text)
    }
}
