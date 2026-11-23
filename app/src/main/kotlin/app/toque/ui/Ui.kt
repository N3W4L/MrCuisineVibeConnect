package app.toque.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Couleurs du thème en service (voir [Theme]) et utilitaires de mise en page, à l'échelle d'un écran
 * de référence 1024×600. Les couleurs sont lues au moment où chaque vue est construite : changer de
 * thème reconstruit l'interface (MainActivity.applyTheme).
 */
object Ui {
    private val p get() = Theme.p
    val BG: Int get() = p.bg
    val SURFACE: Int get() = p.surface
    val SURFACE2: Int get() = p.surface2
    val SURFACE3: Int get() = p.surface3
    val STROKE: Int get() = p.stroke
    val TEXT: Int get() = p.text
    val TEXT2: Int get() = p.text2
    val TEXT3: Int get() = p.text3
    val ACCENT: Int get() = p.accent
    val ACCENT_DIM: Int get() = dim(p.accent)
    /** Texte et icônes posés sur ACCENT. */
    val ON_ACCENT: Int get() = p.onAccent
    /** Second accent du thème (étoiles, flammes). */
    val SECOND: Int get() = p.second
    val COOL: Int get() = p.cool
    val ON_COOL: Int get() = p.onCool
    val OK: Int get() = p.ok
    val WARN: Int get() = p.warn
    val WARN_DIM: Int get() = dim(p.warn)
    val DANGER: Int get() = p.danger
    val FAULT_BG: Int get() = p.faultBg
    val SCRIM: Int get() = p.scrim
    val LIGHT_THEME: Boolean get() = p.light
    /** Appelé à chaque bouton touché (clic sonore, voir MainActivity.click). */
    var onTap: (() -> Unit)? = null
    /**
     * Piste d'un anneau ou d'une jauge (partie non choisie). En clair, le texte du thème à faible
     * opacité : visible sur n'importe quel fond, y compris les formes colorées du verre liquide.
     */
    val TRACK: Int get() = if (p.light) (p.text and 0x00FFFFFF) or 0x24000000 else p.surface3

    /** Même couleur, opacité 20 % : fonds de pastilles et de badges. */
    fun dim(color: Int): Int = (color and 0x00FFFFFF) or 0x33000000

    private val SANS_THIN: Typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    /** Texte courant léger (étapes de recette…) : toujours sans empattements, pour rester lisible. */
    val LIGHT: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    /** Grands chiffres (horloge, minuteur, jauges) : fine par défaut, ou à empattements (chaleureux). */
    val THIN: Typeface get() = Theme.face(p.display, SANS_THIN)
    val REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

    /** Facteur d'échelle : 1 unité = 1 px sur un écran 1024×600. */
    var scale = 1f

    fun init(widthPx: Int, heightPx: Int) {
        val w = maxOf(widthPx, heightPx)
        val h = minOf(widthPx, heightPx)
        scale = minOf(w / 1024f, h / 600f)
    }

    fun u(v: Float): Int = Math.round(v * scale)
    fun u(v: Int): Int = Math.round(v * scale)
    fun uf(v: Float): Float = v * scale

    fun round(color: Int, radius: Float, strokeColor: Int = 0, strokeWidth: Float = 0f): GradientDrawable =
        GradientDrawable().apply {
            // Le rayon avant la couleur : sous Android 6, setCornerRadius ne recalcule pas l'opacité.
            // Dans l'autre ordre, la forme se dit opaque sur tout son rectangle, et l'effet d'appui
            // dont elle est le masque (voir pressable) éclaire un carré au lieu du bouton arrondi.
            setCornerRadius(uf(radius * p.corners))
            setColor(color)
            if (strokeWidth > 0) setStroke(u(strokeWidth), strokeColor)
        }

    fun pressable(base: Drawable, radius: Float, ripple: Int = p.ripple): Drawable =
        RippleDrawable(ColorStateList.valueOf(ripple), base, round(Color.WHITE, radius))

    fun text(c: Context, s: CharSequence = "", size: Float = 18f, color: Int = TEXT, face: Typeface = REGULAR): TextView =
        TextView(c).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_PX, uf(size))
            setTextColor(color)
            typeface = face
            includeFontPadding = false
        }

    /** Texte d'une seule ligne, rapetissé de [size] jusqu'à [min] pour tenir dans sa largeur (jamais de retour à la ligne). */
    fun fitText(c: Context, size: Float, min: Float, color: Int = TEXT, face: Typeface = REGULAR): TextView =
        object : TextView(c) {
            // Pinceau de mesure gardé d'une mise en page à l'autre (rien d'alloué pendant la mesure).
            private val probe = android.graphics.Paint()

            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val avail = View.MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
                if (View.MeasureSpec.getMode(widthMeasureSpec) != View.MeasureSpec.UNSPECIFIED && avail > 0) {
                    val p = probe.apply { set(paint) }
                    var px = uf(size)
                    p.textSize = px
                    val s = text.toString()
                    while (px > uf(min) && p.measureText(s) > avail) { px -= 1f; p.textSize = px }
                    if (px != textSize) setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }

            override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
                super.onTextChanged(text, start, lengthBefore, lengthAfter)
                requestLayout()
            }
        }.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, uf(size))
            setTextColor(color)
            typeface = face
            includeFontPadding = false
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
        }

    fun ellipsize(t: TextView, lines: Int = 1): TextView {
        t.maxLines = lines
        t.ellipsize = TextUtils.TruncateAt.END
        return t
    }

    fun vertical(c: Context, gravity: Int = Gravity.START): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setGravity(gravity)
    }

    fun horizontal(c: Context, gravity: Int = Gravity.CENTER_VERTICAL): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        setGravity(gravity)
    }

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f,
           l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(u(l), u(t), u(r), u(b)) }

    fun flp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.MATCH_PARENT,
            gravity: Int = Gravity.NO_GRAVITY): FrameLayout.LayoutParams = FrameLayout.LayoutParams(w, h, gravity)

    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun pad(v: View, all: Int): View {
        v.setPadding(u(all), u(all), u(all), u(all))
        return v
    }

    fun pad(v: View, h: Int, vert: Int): View {
        v.setPadding(u(h), u(vert), u(h), u(vert))
        return v
    }

    fun scroll(c: Context, child: View): ScrollView = ScrollView(c).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(child)
    }

    fun spacer(c: Context, w: Int = 0, h: Int = 0, weight: Float = 0f): View =
        View(c).apply { layoutParams = LinearLayout.LayoutParams(if (w == 0 && weight > 0) 0 else u(w), u(h), weight) }

    fun divider(c: Context): View = View(c).apply {
        background = ColorDrawable(STROKE)
        layoutParams = LinearLayout.LayoutParams(MATCH, maxOf(1, u(1)))
    }

    enum class Style { PRIMARY, SECONDARY, DANGER, GHOST, COOL }

    /** Bouton arrondi avec icône facultative. */
    fun button(c: Context, label: String, style: Style = Style.SECONDARY, icon: Icon.Kind? = null,
               height: Int = 64, textSize: Float = 20f, onClick: (View) -> Unit): LinearLayout {
        val (bg, fg) = when (style) {
            Style.PRIMARY -> ACCENT to ON_ACCENT
            Style.SECONDARY -> SURFACE3 to TEXT
            Style.DANGER -> DANGER to Color.WHITE
            Style.GHOST -> Color.TRANSPARENT to TEXT2
            Style.COOL -> COOL to ON_COOL
        }
        val row = horizontal(c, Gravity.CENTER)
        row.minimumHeight = u(height)
        row.setPadding(u(22), 0, u(22), 0)
        val base = if (style == Style.GHOST) round(Color.TRANSPARENT, height / 2f, STROKE, 1.5f) else round(bg, height / 2f)
        row.background = pressable(base, height / 2f)
        if (icon != null) {
            row.addView(Icon(c, icon, fg), LinearLayout.LayoutParams(u(textSize * 1.3f), u(textSize * 1.3f)).apply {
                if (label.isNotEmpty()) rightMargin = u(10)
            })
        }
        if (label.isNotEmpty()) row.addView(text(c, label, textSize, fg, MEDIUM))
        row.isClickable = true
        row.isFocusable = true
        row.setOnClickListener { onTap?.invoke(); onClick(it) }
        return row
    }

    fun setButtonEnabled(b: View, enabled: Boolean) {
        b.isEnabled = enabled
        b.alpha = if (enabled) 1f else 0.35f
    }

    /** Pastille d'information (icône + texte). */
    fun chip(c: Context, label: String, icon: Icon.Kind? = null, color: Int = TEXT2, bg: Int = SURFACE2, size: Float = 16f): LinearLayout {
        val row = horizontal(c, Gravity.CENTER_VERTICAL)
        row.setPadding(u(12), u(6), u(14), u(6))
        row.background = round(bg, 40f)
        if (icon != null) row.addView(Icon(c, icon, color), LinearLayout.LayoutParams(u(size * 1.2f), u(size * 1.2f)).apply { rightMargin = u(6) })
        row.addView(text(c, label, size, color, MEDIUM))
        return row
    }

    fun chipText(chip: LinearLayout): TextView = chip.getChildAt(chip.childCount - 1) as TextView

    /** Puce sélectionnable (filtres, choix). */
    fun toggleChip(c: Context, label: String, selected: Boolean, onClick: () -> Unit): TextView {
        val t = text(c, label, 17f, if (selected) ON_ACCENT else TEXT, MEDIUM)
        t.setPadding(u(18), u(10), u(18), u(10))
        t.background = pressable(if (selected) round(ACCENT, 40f) else round(SURFACE2, 40f, STROKE, 1f), 40f)
        t.isClickable = true
        t.setOnClickListener { onClick() }
        return t
    }

    /** Une option d'un [segmented]. */
    class Seg(val key: String, val label: String, val icon: Icon.Kind? = null)

    /**
     * Choix exclusif en grand, sur toute la largeur : chaque option occupe une moitié (ou un tiers)
     * de la barre, ce qui fait des cibles larges et hautes, faciles à toucher avec des doigts mouillés.
     * L'option choisie est pleine (accent), les autres restent dans le rail.
     */
    fun segmented(c: Context, options: List<Seg>, selected: String, height: Int = 64, onPick: (String) -> Unit): LinearLayout {
        val box = horizontal(c)
        val inset = 5
        box.setPadding(u(inset), u(inset), u(inset), u(inset))
        box.background = round(SURFACE, height / 2f, STROKE, 1f)
        val r = (height - 2 * inset) / 2f
        for ((i, o) in options.withIndex()) {
            val on = o.key == selected
            val fg = if (on) ON_ACCENT else TEXT
            val cell = horizontal(c, Gravity.CENTER)
            cell.background = pressable(round(if (on) ACCENT else Color.TRANSPARENT, r), r)
            if (o.icon != null) cell.addView(Icon(c, o.icon, fg), LinearLayout.LayoutParams(u(30), u(30)).apply { rightMargin = u(10) })
            cell.addView(text(c, o.label, 19f, fg, MEDIUM))
            cell.contentDescription = o.label
            cell.isClickable = true
            cell.setOnClickListener { onTap?.invoke(); onPick(o.key) }
            box.addView(cell, LinearLayout.LayoutParams(0, MATCH, 1f).apply { if (i > 0) leftMargin = u(inset) })
        }
        return box
    }

    fun card(c: Context, radius: Float = 24f, color: Int = SURFACE): LinearLayout = vertical(c).apply {
        background = round(color, radius)
        setPadding(u(20), u(18), u(20), u(18))
    }

    fun clock(ms: Long): String {
        val s = (ms + 999) / 1000
        return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
        else String.format("%02d:%02d", s / 60, s % 60)
    }

    fun duration(seconds: Int): String = when {
        seconds < 60 -> "$seconds s"
        seconds % 60 == 0 -> "${seconds / 60} min"
        else -> "${seconds / 60} min ${seconds % 60} s"
    }

    /**
     * Couleur liée à une température : froid -> bleu, tiède -> jaune, chaud -> orange, très chaud -> rouge.
     * L'orange est fixe : l'accent du thème (vert citron du bento, bleu du verre) ne dit pas « chaud ».
     */
    fun tempColor(c: Int): Int = when {
        c >= 90 -> DANGER
        c >= 60 -> if (LIGHT_THEME) 0xFFD2601C.toInt() else 0xFFFF8A3D.toInt()
        c >= 40 -> WARN
        else -> COOL
    }

    /** Couleur de repli stable pour une vignette de recette sans photo. */
    fun placeholderColors(seed: String): IntArray {
        val palette = intArrayOf(
            0xFF3A2416.toInt(), 0xFF16302A.toInt(), 0xFF2B2140.toInt(), 0xFF3B1E26.toInt(),
            0xFF1C2B3E.toInt(), 0xFF35301A.toInt(), 0xFF1E3320.toInt(), 0xFF3A2A1C.toInt(),
        )
        val i = Math.abs(seed.hashCode()) % palette.size
        val a = palette[i]
        val b = palette[(i + 3) % palette.size]
        return intArrayOf(a, b)
    }

    fun initials(title: String): String {
        val words = title.split(' ', '-', '\'').filter { it.length > 2 }
        return words.take(2).joinToString("") { it.first().uppercaseChar().toString() }.ifEmpty { title.take(1).uppercase() }
    }

    /**
     * Fond de l'appli : dégradé en diagonale du thème, plus clair en haut à gauche. Le verre liquide y
     * ajoute deux grandes formes colorées, que ses surfaces translucides laissent deviner.
     */
    fun background(): Drawable {
        val base = GradientDrawable(GradientDrawable.Orientation.TL_BR, p.gradient)
        val blobs = p.blobs ?: return base
        return object : Drawable() {
            // Rien n'est alloué pendant le dessin : deux ovales pleins, calculés quand la taille change.
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            private val oval1 = android.graphics.RectF()
            private val oval2 = android.graphics.RectF()
            override fun onBoundsChange(b: android.graphics.Rect) {
                base.bounds = b
                val w = b.width().toFloat()
                val h = b.height().toFloat()
                oval1.set(b.left - 0.10f * w, b.top - 0.25f * h, b.left + 0.45f * w, b.top + 0.70f * h)
                oval2.set(b.left + 0.58f * w, b.top + 0.45f * h, b.left + 1.08f * w, b.top + 1.30f * h)
            }
            override fun draw(canvas: android.graphics.Canvas) {
                base.draw(canvas)
                paint.color = blobs[0]
                canvas.drawOval(oval1, paint)
                paint.color = blobs[1]
                canvas.drawOval(oval2, paint)
            }
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
            @Suppress("OVERRIDE_DEPRECATION")
            override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
        }
    }

    fun gradient(colors: IntArray, radius: Float): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply { setCornerRadius(uf(radius)) }
}
