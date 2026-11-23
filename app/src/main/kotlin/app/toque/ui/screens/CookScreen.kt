package app.toque.ui.screens

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.core.i18n.tr
import app.toque.core.machine.Accessory
import app.toque.core.machine.EventType
import app.toque.core.machine.JobKind
import app.toque.core.machine.JobSpec
import app.toque.core.machine.JobState
import app.toque.core.machine.MachineEvent
import app.toque.core.machine.SafetyPolicy
import app.toque.core.machine.Snapshot
import app.toque.core.protocol.Mcc
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.StepProgram
import app.toque.ui.Gauge
import app.toque.ui.HoldButton
import app.toque.ui.Icon
import app.toque.ui.MainActivity
import app.toque.ui.ProgressLine
import app.toque.ui.Screen
import app.toque.ui.Ui

/**
 * Cuisine pas à pas. Chaque étape « machine » se lance d'un appui ; à la fin du programme,
 * l'étape suivante s'affiche toute seule. Les réglages qui se suivent sans geste à faire entre
 * eux partent ensemble, en un seul programme : la machine passe de l'un à l'autre sans arrêter
 * la lame (sauf changement de sens). La progression est mémorisée (reprise après coupure).
 */
class CookScreen(a: MainActivity, private val recipe: Recipe, startStep: Int) : Screen(a) {
    override val title = recipe.title
    private var index = startStep.coerceIn(0, maxOf(0, recipe.steps.size - 1))
    private val reviewed = HashSet<Int>().apply {
        // Réglages importés déjà vérifiés lors d'une cuisson précédente de cette recette.
        val known = a.prefs.reviewedSteps
        recipe.steps.forEachIndexed { i, st -> if (st.needsReview && reviewKey(i) in known) add(i) }
    }
    /** Bip de pesée : armé tant que le poids est sous la quantité, joué une fois en l'atteignant. */
    private var weighArmed = false
    /** Cuisson déjà comptée (« Récentes », « Les plus cuisinées ») pendant cet écran. */
    private var counted = false

    private fun countCooked() {
        if (counted) return
        counted = true
        a.app.recipes.markCooked(recipe.id)
    }
    private val done = HashSet<Int>()
    private var launchedStep = -1
    /** Étapes couvertes par le programme lancé (plusieurs quand elles sont enchaînées). */
    private var launchedCount = 1
    /** Dernière phase de la série vue à l'écran (on ne suit que les changements de phase). */
    private var seenPhase = -1
    /** « Mixer quand même » à chaud déjà accepté pendant cette recette : pas de nouvelle question. */
    private var hotBlendOk = false
    /** Message fixe de l'étape (vérification, refus), réaffiché hors cuisson. */
    private var panelInfo = ""

    private lateinit var segments: LinearLayout
    private lateinit var counter: TextView
    private lateinit var text: TextView
    private lateinit var weighLabel: TextView
    private lateinit var weighGauge: Gauge
    private lateinit var panel: LinearLayout
    private lateinit var prevBtn: LinearLayout
    private lateinit var nextBtn: LinearLayout
    private lateinit var nextLabel: TextView
    private lateinit var eta: TextView
    private lateinit var timeline: LinearLayout
    private lateinit var timelineScroll: android.widget.ScrollView

    override fun build(): View {
        // En haut, fixes : Précédent, la progression (étape, heure de fin estimée) et Suivant. Ils ne
        // bougent plus quand la barre du programme en cours apparaît en bas de l'écran.
        // Dessous : la consigne en grand et l'étape suivante à gauche, la carte de l'étape à droite.
        val root = Ui.vertical(c)
        root.setPadding(Ui.u(24), Ui.u(6), Ui.u(24), Ui.u(18))

        val header = Ui.horizontal(c, Gravity.CENTER_VERTICAL)
        prevBtn = Ui.button(c, tr("Précédent"), Ui.Style.GHOST, Icon.Kind.BACK, height = 56, textSize = 18f) { go(index - 1) }
        header.addView(prevBtn, LinearLayout.LayoutParams(Ui.u(190), Ui.u(56)))
        val mid = Ui.vertical(c)
        val info = Ui.horizontal(c, Gravity.CENTER_VERTICAL)
        counter = Ui.text(c, "", 17f, Ui.TEXT2, Ui.MEDIUM).apply { letterSpacing = 0.06f }
        info.addView(counter, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        eta = Ui.text(c, "", 16f, Ui.TEXT2, Ui.MEDIUM)
        info.addView(eta)
        mid.addView(info)
        segments = Ui.horizontal(c)
        mid.addView(segments, Ui.lp(Ui.MATCH, Ui.u(6), t = 10))
        header.addView(mid, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { leftMargin = Ui.u(20); rightMargin = Ui.u(20) })
        nextBtn = Ui.button(c, tr("Suivant"), Ui.Style.SECONDARY, Icon.Kind.CHEVRON, height = 56, textSize = 18f) { next() }
        nextLabel = nextBtn.getChildAt(1) as TextView
        header.addView(nextBtn, LinearLayout.LayoutParams(Ui.u(190), Ui.u(56)))
        root.addView(header, Ui.lp(Ui.MATCH, Ui.WRAP, b = 18))

        val body = Ui.horizontal(c, Gravity.TOP)
        val left = Ui.vertical(c)
        text = Ui.text(c, "", 34f, Ui.TEXT, Ui.LIGHT).apply { setLineSpacing(0f, 1.15f) }
        // La consigne en cours en entier (elle défile si elle est très longue), puis la frise de toute
        // la recette, recentrée sur l'étape en cours.
        left.addView(Ui.scroll(c, text).apply { isFillViewport = false }, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        val frame = android.widget.FrameLayout(c)
        frame.background = Ui.round(Ui.SURFACE, 22f)
        timeline = Ui.vertical(c)
        timeline.setPadding(Ui.u(8), Ui.u(70), Ui.u(8), Ui.u(70))
        timelineScroll = Ui.scroll(c, timeline).apply {
            isFillViewport = false
            // Les lignes s'effacent vers le haut et le bas de la frise.
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(Ui.u(70))
        }
        frame.addView(timelineScroll, Ui.flp())
        left.addView(frame, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f).apply { topMargin = Ui.u(14) })
        body.addView(left, LinearLayout.LayoutParams(0, Ui.MATCH, 1f).apply { rightMargin = Ui.u(22) })

        panel = Ui.vertical(c)
        panel.background = Ui.round(Ui.SURFACE, 26f)
        panel.setPadding(Ui.u(22), Ui.u(18), Ui.u(22), Ui.u(16))
        body.addView(panel, LinearLayout.LayoutParams(Ui.u(340), Ui.MATCH))
        root.addView(body, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        render()
        return root
    }

    /**
     * Frise de toute la recette : étapes passées cochées, étape en cours encadrée, réglage machine ou
     * pesée en pastille. Plus une ligne est loin de l'étape en cours, plus elle s'efface : l'étape en
     * cours ressort. Toucher une ligne y va. La frise se recentre sur l'étape en cours.
     */
    private fun renderTimeline() {
        timeline.removeAllViews()
        var current: View? = null
        for ((i, st) in recipe.steps.withIndex()) {
            val passed = i < index || i in done
            val now = i == index
            val row = Ui.horizontal(c, Gravity.CENTER_VERTICAL)
            row.setPadding(Ui.u(12), Ui.u(8), Ui.u(12), Ui.u(8))
            row.background = Ui.pressable(if (now) Ui.round(Ui.SURFACE3, 16f, Ui.ACCENT, 2f) else Ui.round(0, 16f), 16f)
            val badge = android.widget.FrameLayout(c)
            when {
                now -> badge.background = Ui.round(0, 15f, Ui.ACCENT, 2f)
                passed -> badge.background = Ui.round(Ui.ACCENT, 15f)
                else -> badge.background = Ui.round(0, 15f, Ui.STROKE, 1.5f)
            }
            if (passed && !now) badge.addView(Icon(c, Icon.Kind.CHECK, Ui.ON_ACCENT), Ui.flp(Ui.u(18), Ui.u(18), Gravity.CENTER))
            else badge.addView(Ui.text(c, "${i + 1}", 14f, if (now) Ui.ACCENT else Ui.TEXT2, Ui.MEDIUM), Ui.flp(Ui.WRAP, Ui.WRAP, Gravity.CENTER))
            row.addView(badge, LinearLayout.LayoutParams(Ui.u(30), Ui.u(30)).apply { rightMargin = Ui.u(14) })
            val label = Ui.ellipsize(Ui.text(c, st.text.ifBlank { st.machine?.summary ?: "" }, if (now) 18f else 17f,
                if (now) Ui.TEXT else Ui.TEXT2, if (now) Ui.MEDIUM else Ui.REGULAR))
            if (passed && !now) label.paintFlags = label.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            row.addView(label, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            val tag = (st.machine?.summary ?: st.weigh?.let { tr("Pesée · %d g", it.grams) })
                ?.takeIf { it != label.text.toString() }
            if (tag != null) {
                val chip = Ui.text(c, tag, 13f, if (now) Ui.TEXT else Ui.TEXT2, Ui.MEDIUM)
                chip.setPadding(Ui.u(10), Ui.u(4), Ui.u(10), Ui.u(4))
                chip.background = Ui.round(if (now) Ui.SURFACE else Ui.SURFACE3, 12f)
                chip.maxWidth = Ui.u(200)
                chip.setSingleLine()
                chip.ellipsize = android.text.TextUtils.TruncateAt.END
                row.addView(chip, Ui.lp(Ui.WRAP, Ui.WRAP, l = 10))
            }
            // Effacement selon la distance à l'étape en cours.
            row.alpha = when (Math.abs(i - index)) { 0 -> 1f; 1 -> 0.62f; 2 -> 0.38f; else -> 0.22f }
            row.isClickable = true
            row.setOnClickListener { if (i != index) go(i) }
            timeline.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP, b = 4))
            if (now) current = row
        }
        val target = current ?: return
        timelineScroll.post {
            val y = target.top - (timelineScroll.height - target.height) / 2
            timelineScroll.smoothScrollTo(0, y.coerceAtLeast(0))
        }
    }

    /** Heure de fin estimée : maintenant + la durée des réglages machine qui restent à faire. */
    private fun etaText(): String {
        val seconds = (index until recipe.steps.size).filter { it !in done }.sumOf { recipe.steps[it].machine?.seconds ?: 0 }
        if (seconds <= 0) return ""
        val at = java.util.Date(System.currentTimeMillis() + seconds * 1000L)
        return tr("Prêt vers %s", java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRANCE).format(at))
    }

    private fun next() {
        if (index >= recipe.steps.size - 1) {
            countCooked()
            a.prefs.guidedRecipe = ""
            a.chime(1)
            a.toast(tr("Bon appétit !"), Ui.OK)
            a.back()
        } else go(index + 1)
    }

    private fun go(i: Int) {
        if (i < 0 || i >= recipe.steps.size) return
        index = i
        weighArmed = false
        a.prefs.guidedRecipe = recipe.id
        a.prefs.guidedStep = index
        render()
    }

    private fun render() {
        val n = recipe.steps.size
        segments.removeAllViews()
        for (i in 0 until n) {
            val v = View(c)
            v.background = Ui.round(if (i < index) Ui.ACCENT else if (i == index) Ui.TEXT else Ui.SURFACE3, 4f)
            segments.addView(v, LinearLayout.LayoutParams(0, Ui.MATCH, 1f).apply { if (i < n - 1) rightMargin = Ui.u(6) })
        }
        if (n == 0) {
            counter.text = ""
            eta.text = ""
            timeline.removeAllViews()
            text.text = tr("Cette recette ne contient pas d'étapes.")
            panel.removeAllViews()
            return
        }
        val step = recipe.steps[index]
        counter.text = tr("ÉTAPE %d SUR %d", index + 1, n)
        text.text = step.text.ifBlank { step.machine?.summary ?: "" }
        eta.text = etaText()
        renderTimeline()
        Ui.setButtonEnabled(prevBtn, index > 0)
        nextLabel.text = if (index == n - 1) tr("Terminer") else tr("Suivant")
        buildPanel()
        onSnapshot(a.snapshot)
    }

    private lateinit var startBtn: View
    private var turboBtn: HoldButton? = null
    private var panelState: TextView? = null

    private fun buildPanel() {
        panel.removeAllViews()
        turboBtn = null
        val m = recipe.steps[index].machine
        val weigh = recipe.steps[index].weigh
        if (m == null) {
            if (weigh != null) {
                // Étape de pesée seule : le cadran de pesée occupe toute la carte.
                panel.addView(weighCard(weigh, big = true), LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
            } else {
                val box = Ui.vertical(c, Gravity.CENTER)
                box.addView(Icon(c, Icon.Kind.CHECK, Ui.TEXT3), LinearLayout.LayoutParams(Ui.u(48), Ui.u(48)))
                box.addView(Ui.text(c, tr("Pas de réglage machine pour cette étape"), 18f, Ui.TEXT2).apply { gravity = Gravity.CENTER }, Ui.lp(t = 12))
                panel.addView(box, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
            }
            startBtn = Ui.button(c, tr("Étape suivante"), Ui.Style.SECONDARY, Icon.Kind.CHEVRON, height = 70) { next() }
            panel.addView(startBtn)
            return
        }
        // Pesée et réglage machine sur la même étape : la pesée en version compacte, en tête de carte.
        if (weigh != null) panel.addView(weighCard(weigh, big = false), Ui.lp(Ui.MATCH, Ui.WRAP, b = 14))
        // Haut du panneau (titre, réglages, enchaînement, avertissement) : défile s'il manque de la
        // place (barre de programme en cours affichée), les boutons restent toujours visibles en bas.
        val info = Ui.vertical(c)
        val head = Ui.horizontal(c)
        val badge = FrameLayout(c)
        badge.background = Ui.round(Ui.ACCENT_DIM, 24f)
        val kindIcon = when (m.program) {
            StepProgram.KNEAD -> Icon.Kind.KNEAD
            StepProgram.STEAM -> Icon.Kind.STEAM
            StepProgram.TOAST -> Icon.Kind.FLAME
            StepProgram.TURBO -> Icon.Kind.BOLT
            StepProgram.MANUAL, StepProgram.SLOW_COOK -> Icon.Kind.POT
            StepProgram.MIXER -> Icon.Kind.SPEED
            StepProgram.SMOOTHIE -> Icon.Kind.DROP
            StepProgram.SOUS_VIDE -> Icon.Kind.THERMO
            StepProgram.FERMENT -> Icon.Kind.TIMER
        }
        badge.addView(Icon(c, kindIcon, Ui.ACCENT), Ui.flp(Ui.u(28), Ui.u(28), Gravity.CENTER))
        head.addView(badge, LinearLayout.LayoutParams(Ui.u(48), Ui.u(48)).apply { rightMargin = Ui.u(12) })
        head.addView(Ui.text(c, when (m.program) {
            StepProgram.MANUAL -> tr("Réglage machine")
            StepProgram.KNEAD -> JobKind.KNEAD.label
            StepProgram.STEAM -> JobKind.STEAM.label
            StepProgram.TOAST -> JobKind.TOAST.label
            StepProgram.TURBO -> JobKind.TURBO.label
            StepProgram.MIXER, StepProgram.SMOOTHIE, StepProgram.SOUS_VIDE, StepProgram.SLOW_COOK, StepProgram.FERMENT -> m.program.label
        }, 22f, Ui.TEXT, Ui.MEDIUM))
        info.addView(head)

        val grid = Ui.vertical(c)
        fun line(icon: Icon.Kind, label: String, value: String, color: Int = Ui.TEXT) {
            val r = Ui.horizontal(c)
            r.addView(Icon(c, icon, Ui.TEXT2), LinearLayout.LayoutParams(Ui.u(22), Ui.u(22)).apply { rightMargin = Ui.u(10) })
            r.addView(Ui.text(c, label, 17f, Ui.TEXT2), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            r.addView(Ui.text(c, value, 24f, color, Ui.LIGHT))
            grid.addView(r, Ui.lp(t = 10))
        }
        if (m.program == StepProgram.TURBO) {
            line(Icon.Kind.BOLT, tr("Impulsions"), if (m.pulses > 0) "× ${m.pulses}" else tr("au besoin"))
        } else {
            line(Icon.Kind.CLOCK, tr("Durée"), Ui.duration(m.seconds))
            if (m.program == StepProgram.MANUAL) {
                line(Icon.Kind.THERMO, tr("Température"), if (m.tempC > 0) "${Mcc.celsiusForLevel(m.temperatureLevel)} °C" else "—",
                    if (m.tempC > 0) Ui.tempColor(m.tempC) else Ui.TEXT2)
                line(Icon.Kind.SPEED, tr("Vitesse"), "${m.speed}")
                if (m.reverse) line(Icon.Kind.REVERSE, tr("Sens"), tr("Inverse"), Ui.COOL)
                if (m.accessory == Accessory.WHISK) line(Icon.Kind.WHISK, tr("Accessoire"), Accessory.WHISK.label, Ui.WARN)
            }
        }
        info.addView(grid, Ui.lp(t = 6))
        val chained = if (a.prefs.chainSteps) chainLength(index) else 0
        if (chained > 0) {
            val next = (index + 1..index + chained).map { recipe.steps[it].machine!!.summary }
            val list = next.take(4).joinToString("\n") { "• $it" } +
                if (next.size > 4) "\n" + tr("… et %d autres", next.size - 4) else ""
            info.addView(Ui.text(c, tr("Puis, enchaînés automatiquement :") + "\n" + list, 15f, Ui.TEXT2)
                .apply { setLineSpacing(0f, 1.1f) }, Ui.lp(t = 10))
        }
        panelState = Ui.text(c, "", 16f, Ui.WARN).apply { setLineSpacing(0f, 1.1f) }
        info.addView(panelState, Ui.lp(t = 12))
        panel.addView(Ui.scroll(c, info), LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))

        val violations = m.violations()
        val step = recipe.steps[index]
        panelInfo = ""
        if (violations.isNotEmpty()) {
            panelInfo = tr("Réglage refusé par la sécurité : %s. Utilisez la cuisson manuelle.", violations.first().message)
        }
        // Réglage repris du site Lidl et jamais lancé : une note juste au-dessus de « Lancer », sans
        // étape de plus. Lancer l'étape la marque comme vue, la note ne revient plus.
        launchNote = null
        if (step.needsReview && index !in reviewed && violations.isEmpty()) {
            val note = Ui.text(c, tr("Réglage importé : vérifiez-le avant de lancer."), 15f, Ui.WARN).apply { gravity = Gravity.CENTER }
            launchNote = note
            panel.addView(note, Ui.lp(Ui.MATCH, Ui.WRAP, b = 8))
        }
        panelState?.text = panelInfo
        if (m.program == StepProgram.TURBO) {
            val t = HoldButton(c, tr("Maintenir pour Turbo"), onDown = {
                markReviewed(listOf(index))
                a.turboDown(m.accessory)
            }, onUp = { a.machine.turboUp() })
            turboBtn = t
            startBtn = t
            panel.addView(t, Ui.lp(Ui.MATCH, Ui.u(70)))
        } else {
            startBtn = Ui.button(c, tr("Lancer"), Ui.Style.PRIMARY, Icon.Kind.PLAY, height = 66, textSize = 23f) { launch() }
            panel.addView(startBtn)
        }
        if (m.program == StepProgram.MANUAL) {
            panel.addView(Ui.button(c, tr("Ajuster à la main"), Ui.Style.GHOST, height = 46, textSize = 16f) {
                a.push(ManualScreen(a, m))
            }, Ui.lp(t = 6))
        }
        Ui.setButtonEnabled(startBtn, violations.isEmpty())
    }

    /**
     * Carte de pesée, dans la carte de droite. En grand (étape de pesée seule) : titre, grand cadran
     * qui se remplit pendant qu'on verse, Tare dessous. En compact (avec un réglage machine) : petit
     * cadran à gauche, quantité et Tare à droite. Le texte de l'étape reste à gauche de l'écran : la
     * carte ne le répète pas.
     */
    private fun weighCard(w: app.toque.core.recipes.Weigh, big: Boolean): View {
        val title = tr("Pesée · %d g", w.grams)
        val origin = if (w.originalGrams > 0 && w.originalGrams != w.grams) tr("recette : %d g", w.originalGrams) else ""
        weighGauge = Gauge(c)
        val tare = Ui.button(c, tr("Tare"), Ui.Style.SECONDARY, Icon.Kind.SCALE, height = if (big) 60 else 50, textSize = if (big) 19f else 17f) { a.machine.tare() }
        if (big) {
            val card = Ui.vertical(c, Gravity.CENTER_HORIZONTAL)
            val head = Ui.horizontal(c)
            head.addView(Icon(c, Icon.Kind.SCALE, Ui.COOL), LinearLayout.LayoutParams(Ui.u(28), Ui.u(28)).apply { rightMargin = Ui.u(10) })
            weighLabel = Ui.text(c, title, 22f, Ui.TEXT, Ui.MEDIUM)
            head.addView(weighLabel)
            card.addView(head, Ui.lp(Ui.MATCH, Ui.WRAP))
            if (origin.isNotEmpty()) card.addView(Ui.text(c, origin, 15f, Ui.TEXT3), Ui.lp(Ui.MATCH, Ui.WRAP, t = 2))
            card.addView(weighGauge, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f).apply { topMargin = Ui.u(6) })
            card.addView(tare, Ui.lp(Ui.MATCH, Ui.u(60), t = 6, b = 12))
            return card
        }
        val card = Ui.horizontal(c, Gravity.CENTER_VERTICAL)
        card.background = Ui.round(Ui.SURFACE2, 20f, Ui.STROKE, 1f)
        card.setPadding(Ui.u(10), Ui.u(10), Ui.u(14), Ui.u(10))
        card.addView(weighGauge, LinearLayout.LayoutParams(Ui.u(112), Ui.u(112)))
        val col = Ui.vertical(c)
        weighLabel = Ui.text(c, title, 19f, Ui.TEXT, Ui.MEDIUM)
        col.addView(weighLabel)
        if (origin.isNotEmpty()) col.addView(Ui.text(c, origin, 14f, Ui.TEXT3), Ui.lp(t = 2))
        col.addView(tare, Ui.lp(Ui.WRAP, Ui.WRAP, t = 8))
        card.addView(col, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { leftMargin = Ui.u(12) })
        return card
    }

    /**
     * Lance l'étape (et celles qui s'enchaînent), directement. Les réglages repris du site Lidl sont
     * alors marqués comme vus : la note « vérifiez-le avant de lancer » ne revient plus pour eux.
     */
    private fun launch() {
        launchNow()
    }

    /** Note « vérifiez-le avant de lancer », juste au-dessus du bouton ; null quand il n'y en a pas. */
    private var launchNote: TextView? = null

    private fun markReviewed(steps: List<Int>) {
        if (steps.contains(index)) launchNote?.visibility = View.GONE
        for (i in steps) {
            if (!recipe.steps[i].needsReview || i in reviewed) continue
            reviewed += i
            a.prefs.reviewedSteps = a.prefs.reviewedSteps + reviewKey(i)
        }
    }

    private fun launchNow() {
        val first = index
        val m = recipe.steps[first].machine ?: return
        val series = series(first)
        val job = series ?: m.toJob(tr("Étape %d · %s", first + 1, recipe.title), hotBlendConfirmed = hotBlendOk) ?: return
        val count = series?.phases?.size ?: 1
        a.prefs.guidedRecipe = recipe.id
        a.prefs.guidedStep = first
        a.startJob(job, onHotConfirmed = { hotBlendOk = true }) { v ->
            if (v.ok) {
                // Réglages repris du site Lidl : vus dès qu'ils ont démarré (pas sur un refus).
                markReviewed((first until first + count).toList())
                countCooked()
                launchedStep = first
                launchedCount = count
                seenPhase = 0
            }
        }
    }

    /**
     * Réglages enchaînés à partir de [first], réunis en un seul programme (une phase par étape) :
     * la machine passe directement au réglage suivant, sans arrêt ni nouvel appui. Uniquement
     * des réglages manuels avec le même accessoire ; sinon les étapes s'enchaînent une à une.
     */
    private fun series(first: Int): JobSpec? {
        if (!a.prefs.chainSteps) return null
        val n = chainLength(first)
        if (n == 0) return null
        val machines = (first..first + n).map { recipe.steps[it].machine ?: return null }
        if (machines.any { it.program != StepProgram.MANUAL || it.accessory != machines[0].accessory }) return null
        val phases = machines.map { it.toJob("", hotBlendOk)?.phases?.singleOrNull() ?: return null }
        val spec = JobSpec(
            kind = JobKind.MANUAL,
            label = tr("Étapes %d à %d · %s", first + 1, first + n + 1, recipe.title),
            phases = phases,
            totalMs = phases.sumOf { it.durationMs },
            cyclic = false,
            accessory = machines[0].accessory,
            hotBlendConfirmed = hotBlendOk,
        )
        return spec.takeIf { SafetyPolicy.checkJob(it, null).isEmpty() }
    }

    /**
     * L'étape [i] continue directement la précédente : un réglage machine seul (pas de texte
     * à suivre, pas de pesée), après une autre étape machine. Le Turbo reste toujours à l'appui.
     */
    private fun continues(i: Int): Boolean {
        val s = recipe.steps.getOrNull(i) ?: return false
        val m = s.machine ?: return false
        val prev = recipe.steps.getOrNull(i - 1)?.machine ?: return false
        return m.program != StepProgram.TURBO && prev.program != StepProgram.TURBO && s.weigh == null &&
            (s.text.isBlank() || s.text == m.summary) && m.violations().isEmpty()
    }

    /** Nombre d'étapes qui s'enchaîneront après l'étape [i]. */
    private fun chainLength(i: Int): Int {
        var n = 0
        while (continues(i + n + 1)) n++
        return n
    }

    override fun onSnapshot(s: Snapshot) {
        val w = recipe.steps.getOrNull(index)?.weigh
        if (w != null) {
            // Bleu pendant qu'on verse, vert entre 95 et 105 % du poids demandé, orange au-delà.
            val g = if (s.hasSample) s.weightG else 0
            val color = when {
                w.grams <= 0 -> Ui.COOL
                g > w.grams * 1.05f -> Ui.WARN
                g >= w.grams * 0.95f -> Ui.OK
                else -> Ui.COOL
            }
            weighGauge.set(if (w.grams > 0) g.toFloat() / w.grams else 0f, 0f, 1f, color,
                if (s.hasSample) "$g" else "—", "g", tr("sur %d g", w.grams), emptyList())
            if (s.hasSample && w.grams > 0) {
                // Armé seulement après avoir vu un poids sous la quantité (pas de bip en arrivant
                // sur l'étape avec un bol déjà chargé), réarmé après une tare.
                if (s.weightG < w.grams * 0.9f) weighArmed = true
                else if (weighArmed && s.weightG >= w.grams) {
                    weighArmed = false
                    a.weighBeep()
                }
            }
        }
        val running = launchedStep >= 0 && s.jobActive
        if (running && launchedCount > 1 && s.phaseIndex != seenPhase &&
            s.state != JobState.FINISHED && s.state != JobState.ABORTED) {
            // Série en cours : à chaque nouveau réglage, l'écran passe à l'étape correspondante.
            seenPhase = s.phaseIndex
            val current = launchedStep + s.phaseIndex.coerceIn(0, launchedCount - 1)
            for (k in launchedStep until current) done += k
            reviewed += current
            if (current != index) {
                go(current)
                return
            }
        }
        val m = recipe.steps.getOrNull(index)?.machine ?: return
        val mine = running && index >= launchedStep && index < launchedStep + launchedCount
        val ps = panelState
        if (m.program != StepProgram.TURBO && ::startBtn.isInitialized) {
            startBtn.visibility = if (mine && s.state != JobState.FINISHED && s.state != JobState.ABORTED) View.GONE else View.VISIBLE
            if (s.busy && !mine) Ui.setButtonEnabled(startBtn, false)
            else if (m.violations().isEmpty()) Ui.setButtonEnabled(startBtn, !s.busy)
        }
        if (!mine && ps != null) {
            if (index in done) {
                ps.setTextColor(Ui.OK)
                ps.text = tr("Étape terminée")
            } else {
                ps.setTextColor(Ui.WARN)
                ps.text = panelInfo
            }
        }
        if (mine && ps != null) {
            ps.setTextColor(Ui.TEXT2)
            ps.text = when (s.state) {
                JobState.RUNNING, JobState.STARTING -> tr("En cours… reste %s", Ui.clock(s.remainingMs))
                JobState.STOPPING -> tr("Arrêt du moteur…")
                JobState.PAUSED -> tr("En pause")
                JobState.FINISHED -> tr("Terminé")
                JobState.ABORTED -> s.endReason
                null -> ""
            }
        }
        turboBtn?.let { t ->
            t.isEnabled = !s.busy || s.turboActive
            t.alpha = if (t.isEnabled) 1f else 0.35f
        }
    }

    private fun reviewKey(i: Int): String = "${recipe.id}|$i|${recipe.steps[i].machine?.summary ?: ""}"

    /** Le programme qui se termine est le dernier réglage machine de la recette. */
    private fun endsRecipe(e: MachineEvent): Boolean {
        if (e.type != EventType.JOB_FINISHED || launchedStep < 0) return false
        if (index < launchedStep || index >= launchedStep + launchedCount) return false
        val finished = launchedStep + launchedCount - 1
        return (finished + 1 until recipe.steps.size).none { recipe.steps[it].machine != null }
    }

    override fun ownsFinishSound(e: MachineEvent): Boolean = endsRecipe(e)

    override fun onEvent(e: MachineEvent) {
        if (endsRecipe(e)) a.recipeDoneSound()
        if (e.type == EventType.JOB_FINISHED && launchedStep >= 0 && index >= launchedStep && index < launchedStep + launchedCount) {
            val finished = launchedStep + launchedCount - 1
            for (k in launchedStep..finished) done += k
            launchedStep = -1
            launchedCount = 1
            if (index != finished) go(finished)
            // Étapes non réunissables en un programme (vapeur puis réglage…) : la suivante part seule.
            val chain = a.prefs.chainSteps && continues(finished + 1)
            view.postDelayed({
                if (index == finished) {
                    a.machine.clearFinished()
                    if (index < recipe.steps.size - 1) {
                        // Étape enchaînée : le réglage a été vu avec la première étape de la série.
                        if (chain) reviewed += finished + 1
                        go(finished + 1)
                        if (chain) launch()
                    }
                }
            }, if (chain) 400L else 1_500L)
        }
    }

    override fun onKnob(delta: Int): Boolean {
        go(index + delta)
        return true
    }

    override fun onKnobPress(): Boolean {
        val m = recipe.steps.getOrNull(index)?.machine
        if (m == null) next() else if (m.program != StepProgram.TURBO && startBtn.isEnabled && startBtn.visibility == View.VISIBLE) launch()
        return true
    }
}
