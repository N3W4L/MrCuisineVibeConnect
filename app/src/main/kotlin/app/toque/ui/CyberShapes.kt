package app.toque.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable

/**
 * Forme du thème néon : rectangle aux coins coupés à 45° (deux coins opposés, comme une plaque de
 * métal découpée), aplat d'accent en dégradé, et contour lumineux sur les cartes. Prend la place
 * des formes arrondies (Ui.round) sans rien changer ailleurs.
 */
class Chamfer(private val fill: Int, private val radius: Float, private val strokeColor: Int, private val strokeWidth: Float) : GradientDrawable() {
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val box = RectF()

    override fun onBoundsChange(b: Rect) {
        super.onBoundsChange(b)
        // Les cartes (surface sans contour) reçoivent le contour néon du thème.
        val sw = if (strokeWidth > 0f) strokeWidth else if (fill == Theme.p.surface) Ui.uf(1.5f) else 0f
        box.set(b.left + sw / 2f, b.top + sw / 2f, b.right - sw / 2f, b.bottom - sw / 2f)
        val cut = minOf(maxOf(radius * 0.55f, Ui.uf(6f)), box.height() / 3f, box.width() / 3f)
        path.reset()
        path.moveTo(box.left + cut, box.top)
        path.lineTo(box.right, box.top)
        path.lineTo(box.right, box.bottom - cut)
        path.lineTo(box.right - cut, box.bottom)
        path.lineTo(box.left, box.bottom)
        path.lineTo(box.left, box.top + cut)
        path.close()
        val end = Theme.p.accentEnd
        paint.shader = if (end != null && fill == Theme.p.accent) LinearGradient(box.left, 0f, box.right, 0f, fill, end, Shader.TileMode.CLAMP) else null
        paint.color = fill
        line.strokeWidth = sw
        line.color = if (strokeWidth > 0f) strokeColor else Theme.p.stroke
    }

    override fun draw(canvas: Canvas) {
        if (Color.alpha(fill) > 0) canvas.drawPath(path, paint)
        if (line.strokeWidth > 0f) canvas.drawPath(path, line)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Fond du thème néon : dégradé de nuit, grille fine aux couleurs du thème, lignes de balayage
 * d'écran cathodique, lueur au ras du bas et bandes obliques en haut à droite. Tout est calculé
 * quand la taille change ; le dessin ne crée rien.
 */
class CyberBackground(private val base: GradientDrawable) : Drawable() {
    private val grid = Paint().apply { strokeWidth = 1f }
    private val scan = Paint()
    private val stripe = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint()
    private val stripes = Path()
    private var w = 0f
    private var h = 0f

    override fun onBoundsChange(b: Rect) {
        base.bounds = b
        w = b.width().toFloat()
        h = b.height().toFloat()
        val p = Theme.p
        grid.color = if (p.light) 0x14E0218A else 0x1A29D3FF
        scan.color = if (p.light) 0x0A000000 else 0x2A000000
        stripe.color = (p.accent and 0x00FFFFFF) or (if (p.light) 0x30000000 else 0x48000000)
        val end = p.accentEnd ?: p.accent
        glow.shader = LinearGradient(0f, h, 0f, h * 0.7f, (end and 0x00FFFFFF) or 0x38000000, end and 0x00FFFFFF, Shader.TileMode.CLAMP)
        stripes.reset()
        val s = Ui.uf(14f)
        val d = Ui.uf(60f)
        for (i in 0 until 4) {
            val x = w - Ui.uf(200f) + i * s * 2.2f
            stripes.moveTo(x, 0f)
            stripes.lineTo(x + s, 0f)
            stripes.lineTo(x + s - d, d)
            stripes.lineTo(x - d, d)
            stripes.close()
        }
    }

    override fun draw(canvas: Canvas) {
        base.draw(canvas)
        // Lueur au ras du bas, grille, puis balayage par-dessus.
        canvas.drawRect(0f, h * 0.7f, w, h, glow)
        val step = Ui.uf(48f)
        var x = 0f
        while (x < w) { canvas.drawLine(x, 0f, x, h, grid); x += step }
        var y = 0f
        while (y < h) { canvas.drawLine(0f, y, w, y, grid); y += step }
        val line = Ui.uf(2f)
        y = 0f
        while (y < h) { canvas.drawRect(0f, y, w, y + line / 2f, scan); y += line * 2f }
        canvas.drawPath(stripes, stripe)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
