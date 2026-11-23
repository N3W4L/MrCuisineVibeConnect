package app.toque.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Cadran de l'accueil : un anneau de secteurs, un par fonction, et au centre le nom et le détail de
 * celle qui est choisie. Le bouton rotatif fait tourner le choix ([rotate]) et son appui l'ouvre
 * ([open]) ; au doigt, toucher un secteur l'ouvre directement, toucher le centre ouvre le choix.
 *
 * Dessin léger : des arcs pleins et une icône par secteur, rien d'alloué pendant le dessin.
 */
class Dial(c: Context) : FrameLayout(c) {
    class Item(val icon: Icon.Kind, val title: String, val onOpen: () -> Unit) {
        /** Détail sous le titre (nombre de recettes, version disponible…), mis à jour par l'écran. */
        var sub: String = ""
        /** Fond et encre du secteur choisi ; null = accent du thème. */
        var colors: Pair<Int, Int>? = null
        internal lateinit var iconView: Icon
    }

    private val items = ArrayList<Item>()
    var selected = 0
        private set

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    private val oval = RectF()
    private val title: TextView = Ui.fitText(c, 30f, 18f, Ui.TEXT, Ui.MEDIUM).apply { gravity = Gravity.CENTER }
    private val sub: TextView = Ui.ellipsize(Ui.text(c, "", 18f, Ui.TEXT2).apply { gravity = Gravity.CENTER }, 2)
    private val hint: TextView = Ui.text(c, "", 15f, Ui.TEXT3).apply { gravity = Gravity.CENTER }
    private val center = Ui.vertical(c, Gravity.CENTER)

    // Géométrie, recalculée à chaque mise en page.
    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private var band = 0f

    init {
        setWillNotDraw(false)
        center.addView(title, Ui.lp(Ui.MATCH, Ui.WRAP))
        center.addView(sub, Ui.lp(Ui.MATCH, Ui.WRAP, t = 6))
        center.addView(hint, Ui.lp(Ui.MATCH, Ui.WRAP, t = 12))
        addView(center, LayoutParams(0, 0))
    }

    fun setItems(list: List<Item>, hintText: String) {
        items.forEach { removeView(it.iconView) }
        items.clear()
        items.addAll(list)
        for (it in items) {
            it.iconView = Icon(context, it.icon, Ui.TEXT2)
            addView(it.iconView, LayoutParams(0, 0))
        }
        hint.text = hintText
        selected = selected.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        refresh()
        requestLayout()
    }

    /** Met à jour le détail affiché au centre (et les couleurs) après un changement de [Item.sub]. */
    fun refresh() {
        val item = items.getOrNull(selected) ?: return
        title.text = item.title
        sub.text = item.sub
        items.forEachIndexed { i, it -> it.iconView.color = if (i == selected) (it.colors?.second ?: Ui.ON_ACCENT) else Ui.TEXT2 }
        invalidate()
    }

    fun select(i: Int) {
        if (items.isEmpty()) return
        selected = ((i % items.size) + items.size) % items.size
        refresh()
    }

    /** Un cran du bouton rotatif : secteur suivant (1) ou précédent (-1), en boucle. */
    fun rotate(delta: Int) = select(selected + delta)

    fun open() {
        Ui.onTap?.invoke()
        items.getOrNull(selected)?.onOpen?.invoke()
    }

    private fun angleOf(i: Int): Float = 360f * i / items.size   // 0° = en haut, sens horaire

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = (r - l).toFloat()
        val h = (b - t).toFloat()
        cx = w / 2f
        cy = h / 2f
        band = minOf(w, h) * BAND
        radius = minOf(w, h) / 2f - band / 2f - Ui.uf(10f)
        val iconSize = (band * ICON).toInt()
        for ((i, it) in items.withIndex()) {
            val a = Math.toRadians((angleOf(i) - 90f).toDouble())
            val x = (cx + radius * Math.cos(a)).toInt()
            val y = (cy + radius * Math.sin(a)).toInt()
            it.iconView.layout(x - iconSize / 2, y - iconSize / 2, x + iconSize / 2, y + iconSize / 2)
        }
        // Le texte du centre tient dans le carré inscrit dans l'anneau.
        val inner = ((radius - band / 2f) * 1.35f).toInt()
        center.measure(MeasureSpec.makeMeasureSpec(inner, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(inner, MeasureSpec.AT_MOST))
        val ch = center.measuredHeight
        center.layout((cx - inner / 2f).toInt(), (cy - ch / 2f).toInt(), (cx + inner / 2f).toInt(), (cy + ch / 2f).toInt())
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        val iconSize = (minOf(measuredWidth, measuredHeight) * BAND * ICON).toInt()
        val spec = MeasureSpec.makeMeasureSpec(iconSize, MeasureSpec.EXACTLY)
        items.forEach { it.iconView.measure(spec, spec) }
    }

    override fun onDraw(canvas: Canvas) {
        if (items.isEmpty()) return
        val n = items.size
        val step = 360f / n
        val gap = 4f
        ring.strokeWidth = band
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
        for (i in 0 until n) {
            val item = items[i]
            ring.color = if (i == selected) (item.colors?.first ?: Ui.ACCENT) else Ui.TRACK
            // drawArc compte depuis 3 h ; nos angles depuis midi.
            canvas.drawArc(oval, angleOf(i) - 90f - step / 2f + gap / 2f, step - gap, false, ring)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (items.isEmpty()) return false
        if (e.actionMasked == MotionEvent.ACTION_DOWN) return true
        if (e.actionMasked != MotionEvent.ACTION_UP) return true
        val dx = e.x - cx
        val dy = e.y - cy
        val dist = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (dist < radius - band / 2f) {
            open()   // centre : ouvre la fonction choisie
            return true
        }
        if (dist > radius + band) return false
        // Angle depuis midi, sens horaire, puis secteur le plus proche.
        var deg = Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
        if (deg < 0) deg += 360f
        val i = Math.round(deg / (360f / items.size)) % items.size
        select(i)
        open()
        return true
    }

    private companion object {
        /** Épaisseur de l'anneau (part du diamètre) : assez pour viser au doigt, sans alourdir. */
        const val BAND = 0.095f
        /** Taille de l'icône dans son secteur. */
        const val ICON = 0.62f
    }
}
