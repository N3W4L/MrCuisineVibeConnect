package app.toque.ui.screens

import app.toque.core.protocol.Smart
import app.toque.core.robot.Feature
import app.toque.core.robot.RobotProfiles
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.core.i18n.tr
import app.toque.core.i18n.trKey
import app.toque.core.machine.Accessory
import app.toque.core.machine.JobKind
import app.toque.core.machine.JobState
import app.toque.core.machine.Limits
import app.toque.core.machine.Programs
import app.toque.core.machine.SafetyPolicy
import app.toque.core.machine.Snapshot
import app.toque.core.protocol.Direction
import app.toque.core.protocol.Mcc
import app.toque.ui.Gauge
import app.toque.ui.HoldButton
import app.toque.ui.Icon
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.Ui
import app.toque.ui.ValueCard

/**
 * Cuisson manuelle. Les réglages incompatibles sont corrigés à la saisie (vitesse limitée en
 * chauffe, en sens inverse ou avec le fouet) plutôt que refusés au démarrage.
 */
/**
 * Cuisson manuelle. Avec [onPick], sert à choisir le réglage d'une étape de recette perso : mêmes
 * cartes et mêmes limites de sécurité, mais rien ne démarre ; « Utiliser ce réglage » le renvoie.
 */
class ManualScreen(
    a: MainActivity,
    preset: app.toque.core.recipes.StepMachine? = null,
    private val onPick: ((app.toque.core.recipes.StepMachine) -> Unit)? = null,
) : Screen(a) {
    override val title = if (onPick != null) tr("Réglage de l'étape") else JobKind.MANUAL.label

    private var seconds = preset?.seconds?.takeIf { it > 0 } ?: 60
    /** Températures du robot choisi (le Smart a 40 °C en plus) ; 0 = sans chauffe. */
    private val caps = RobotProfiles.active.capabilities
    private var tempC = preset?.tempC?.let { c -> if (c == 0) 0 else caps.temperaturesC.lastOrNull { it <= c } ?: caps.temperaturesC.first() } ?: 0
    /** Niveau de la table de Toque (règles de sécurité) : 40 °C compte pour 37 °C. */
    private val tempLevel: Int get() = Mcc.levelAtMost(tempC)
    /** Smart, 40 °C : niveau de sa table ; null sinon. */
    private val smartLevel: Int? get() =
        if (tempC > 0 && Mcc.levelForCelsius(tempC) < 0 && tempC in Smart.TEMPERATURE_LEVELS) Smart.TEMPERATURE_LEVELS.indexOf(tempC) else null
    /** Chauffe lame à l'arrêt (vitesse 0) : seulement sur un robot qui la sait faire (Connect). */
    private val noStir: Boolean get() = caps.has(Feature.HEAT_NO_STIR)
    // Vitesse 0 = chauffe sans mélange : seulement avec une température, et si le robot le permet.
    private var speed = (preset?.speed ?: 2).coerceIn(if (tempC > 0 && caps.has(Feature.HEAT_NO_STIR)) 0 else 1, 10)
    private var reverse = preset?.reverse ?: false
    private var accessory = preset?.accessory ?: Accessory.BLADE
    private var selected = 0

    /** Les deux réglages qui ne sont pas au centre, en petits cercles à gauche (dans l'ordre Durée, Température, Vitesse). */
    private lateinit var satellites: List<Gauge>
    private val satLabels = ArrayList<TextView>()
    private lateinit var dirRow: LinearLayout
    private lateinit var accRow: LinearLayout
    private lateinit var gauge: Gauge
    private lateinit var noticeBox: LinearLayout
    private lateinit var noticeIcon: Icon
    private lateinit var noticeText: TextView
    private val hideNotice = Runnable {
        transient = false
        if (hintText.isNotEmpty()) showNotice(hintText, hintText == tr("Réglages appliqués en direct"))
        else noticeBox.animate().alpha(0f).setDuration(220).withEndAction { noticeBox.visibility = View.INVISIBLE }
    }
    private lateinit var weight: TextView
    private lateinit var startBtn: LinearLayout
    /** Message permanent (limite, cuisson en direct) : affiché dans la carte au-dessus du cadran. */
    private var hintText = ""
    private var transient = false
    private lateinit var turbo: HoldButton
    private var live = false
    /** État des barres Sens / Accessoire déjà affiché (voir refresh). */
    private var lastBars = ""
    private var wasLive = false
    private var pendingEdit = false
    private var timeEdited = false
    private var configuredSeconds = seconds
    private val pushLive = Runnable { sendAdjust() }
    /** Jusqu'à cette heure, l'anneau montre la consigne réglée ici plutôt que celle du programme en cours. */
    private var ownTargetUntil = 0L

    override fun build(): View {
        // Trois colonnes, dans l'ordre où on s'en sert :
        //  - à gauche, les deux réglages qui ne sont pas en cours, en petits cercles : les toucher les
        //    fait passer au centre ;
        //  - au centre, le grand cadran du réglage en cours, avec − / + posés dans son ouverture, juste
        //    sous la valeur qu'ils changent, et la raison d'une limite de sécurité en dessous ;
        //  - à droite, les options (sens, accessoire) puis les actions, Démarrer en bas à droite.
        // Le bouton rotatif règle le grand cadran ; son appui démarre ou met en pause.
        val root = Ui.horizontal(c, Gravity.TOP)
        root.setPadding(Ui.u(24), Ui.u(8), Ui.u(24), Ui.u(20))

        val left = Ui.vertical(c)
        val sats = ArrayList<Gauge>()
        satLabels.clear()
        for (k in 0..1) {
            val card = Ui.vertical(c, Gravity.CENTER_HORIZONTAL)
            card.background = Ui.pressable(Ui.round(Ui.SURFACE, 24f, Ui.STROKE, 1f), 24f)
            card.setPadding(Ui.u(8), Ui.u(10), Ui.u(8), Ui.u(10))
            val g = Gauge(c).apply { compact = true }
            card.addView(g, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
            // Nom du réglage en bas de la carte, hors du cercle, pour l'écrire en grand.
            val label = Ui.text(c, "", 19f, Ui.TEXT2, Ui.MEDIUM).apply { gravity = Gravity.CENTER }
            card.addView(label, Ui.lp(Ui.MATCH, Ui.WRAP, t = 4))
            satLabels += label
            card.isClickable = true
            card.setOnClickListener { selected = otherParams()[k]; refresh() }
            sats += g
            left.addView(card, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f).apply { if (k == 0) bottomMargin = Ui.u(14) })
        }
        satellites = sats
        root.addView(left, LinearLayout.LayoutParams(0, Ui.MATCH, 0.22f).apply { rightMargin = Ui.u(18) })

        val center = Ui.vertical(c, Gravity.CENTER_HORIZONTAL)
        // Messages (limites de sécurité, tare) : une carte au-dessus du cadran, dans l'espace libre,
        // au lieu d'un message flottant qui cacherait − / +. La place reste réservée : rien ne bouge.
        noticeBox = Ui.horizontal(c, Gravity.CENTER_VERTICAL)
        noticeBox.setPadding(Ui.u(18), 0, Ui.u(22), 0)
        noticeBox.background = Ui.round(Ui.SURFACE2, 28f, Ui.ACCENT, 2f)
        noticeIcon = Icon(c, Icon.Kind.WARNING, Ui.WARN)
        noticeBox.addView(noticeIcon, LinearLayout.LayoutParams(Ui.u(26), Ui.u(26)).apply { rightMargin = Ui.u(12) })
        noticeText = Ui.ellipsize(Ui.text(c, "", 18f, Ui.TEXT, Ui.MEDIUM), 2)
        noticeBox.addView(noticeText)
        noticeBox.visibility = View.INVISIBLE
        center.addView(noticeBox, Ui.lp(Ui.WRAP, Ui.u(56), t = 2, b = 4))
        val dial = android.widget.FrameLayout(c)
        gauge = Gauge(c)
        // Le cercle s'arrête au-dessus de la rangée − / + : ni l'arc ni ses graduations ne la touchent.
        dial.addView(gauge, Ui.flp().apply { bottomMargin = Ui.u(86) })
        val minus = Ui.button(c, "", Ui.Style.SECONDARY, Icon.Kind.MINUS, height = 80) { change(selected, -1) }
        val plus = Ui.button(c, "", Ui.Style.SECONDARY, Icon.Kind.PLUS, height = 80) { change(selected, 1) }
        // Répétition à l'appui long, sauf vitesse et température pendant une cuisson (comme avant).
        ValueCard.repeatOnHold(minus, { !live || selected == 0 }) { change(selected, -1) }
        ValueCard.repeatOnHold(plus, { !live || selected == 0 }) { change(selected, 1) }
        // Dans l'ouverture du cadran, de part et d'autre de son axe.
        dial.addView(minus, android.widget.FrameLayout.LayoutParams(Ui.u(80), Ui.u(80), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { rightMargin = Ui.u(110) })
        dial.addView(plus, android.widget.FrameLayout.LayoutParams(Ui.u(80), Ui.u(80), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { leftMargin = Ui.u(110) })
        center.addView(dial, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        root.addView(center, LinearLayout.LayoutParams(0, Ui.MATCH, 0.46f).apply { rightMargin = Ui.u(18) })

        val right = Ui.vertical(c)
        right.addView(Ui.text(c, tr("Sens"), 15f, Ui.TEXT2), Ui.lp(t = 4))
        dirRow = Ui.horizontal(c)
        right.addView(dirRow, Ui.lp(Ui.MATCH, Ui.u(64), t = 6))
        right.addView(Ui.text(c, tr("Accessoire"), 15f, Ui.TEXT2), Ui.lp(t = 14))
        accRow = Ui.horizontal(c)
        right.addView(accRow, Ui.lp(Ui.MATCH, Ui.u(64), t = 6))
        right.addView(Ui.spacer(c, weight = 1f), LinearLayout.LayoutParams(1, 0, 1f))
        // Balance : le poids reste visible sans quitter la cuisson manuelle, et toucher la carte tare
        // (remise à zéro), sans passer par l'écran Balance. « Tare » à droite le dit.
        val scaleRow = Ui.horizontal(c, Gravity.CENTER_VERTICAL)
        scaleRow.setPadding(Ui.u(18), 0, Ui.u(18), 0)
        scaleRow.background = Ui.pressable(Ui.round(Ui.SURFACE, 20f, Ui.STROKE, 1f), 20f)
        scaleRow.addView(Icon(c, Icon.Kind.SCALE, Ui.TEXT2), LinearLayout.LayoutParams(Ui.u(26), Ui.u(26)).apply { rightMargin = Ui.u(12) })
        weight = Ui.text(c, "", 20f, Ui.TEXT, Ui.MEDIUM)
        scaleRow.addView(weight, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        scaleRow.addView(Ui.text(c, tr("Tare"), 17f, Ui.ACCENT, Ui.MEDIUM))
        scaleRow.isClickable = true
        scaleRow.contentDescription = tr("Tare")
        scaleRow.setOnClickListener {
            a.machine.tare()
            notice(tr("Balance remise à zéro"), info = true)
        }
        right.addView(scaleRow, Ui.lp(Ui.MATCH, Ui.u(60), b = 14))
        turbo = HoldButton(c, "Turbo", onDown = {
            a.turboDown(accessory)
        }, onUp = { a.machine.turboUp() })
        right.addView(turbo, Ui.lp(Ui.MATCH, Ui.u(72)))
        if (onPick != null) turbo.visibility = View.GONE
        startBtn = if (onPick != null) Ui.button(c, tr("Utiliser ce réglage"), Ui.Style.PRIMARY, Icon.Kind.CHECK, height = 88, textSize = 22f) { start() }
        else Ui.button(c, tr("Démarrer"), Ui.Style.PRIMARY, Icon.Kind.PLAY, height = 88, textSize = 24f) { start() }
        right.addView(startBtn, Ui.lp(Ui.MATCH, Ui.u(88), t = 12))
        root.addView(right, LinearLayout.LayoutParams(0, Ui.MATCH, 0.32f))
        refresh()
        return root
    }

    /** Affiche [message] dans la carte au-dessus du cadran pendant quelques secondes. */
    private fun notice(message: String, info: Boolean = false) {
        transient = true
        showNotice(message, info)
        noticeBox.removeCallbacks(hideNotice)
        noticeBox.postDelayed(hideNotice, 2_800)
    }

    /** Message permanent ; vide = rien. Un message passager reste prioritaire jusqu'à sa fin. */
    private fun setHint(text: String) {
        if (text == hintText) return
        hintText = text
        if (transient) return
        if (text.isNotEmpty()) showNotice(text, text == tr("Réglages appliqués en direct"))
        else {
            noticeBox.animate().cancel()
            noticeBox.visibility = View.INVISIBLE
        }
    }

    private fun showNotice(message: String, info: Boolean) {
        noticeText.text = message
        noticeIcon.set(if (info) Icon.Kind.CHECK else Icon.Kind.WARNING)
        noticeIcon.color = if (info) Ui.OK else Ui.WARN
        noticeBox.animate().cancel()
        noticeBox.alpha = 1f
        noticeBox.visibility = View.VISIBLE
    }

    /** Les deux réglages qui ne sont pas au centre, dans l'ordre Durée (0), Température (1), Vitesse (2). */
    private fun otherParams(): IntArray = (0..2).filter { it != selected }.toIntArray()

    private fun paramName(i: Int): String = when (i) {
        0 -> tr("Durée")
        1 -> tr("Température")
        else -> tr("Vitesse")
    }

    /** Vitesse maximale permise par les réglages en cours, et la raison à afficher. */
    private fun speedLimit(): Pair<Int, String> {
        var max = 10
        var why = trKey("Vitesse %d maximum")
        if (tempLevel > 0) { max = Limits.MAX_SPEED_HEATED; why = trKey("En chauffe, vitesse %d maximum") }
        if (reverse && max > Limits.MAX_SPEED_REVERSE) { max = Limits.MAX_SPEED_REVERSE; why = trKey("En sens inverse, vitesse %d maximum") }
        if (accessory.maxSpeed < max) { max = accessory.maxSpeed; why = trKey("Avec le fouet, vitesse %d maximum") }
        return max to why
    }

    /** En chauffe, la vitesse descend jusqu'à 0 (lame à l'arrêt), si le robot sait chauffer sans mélanger. */
    private val minSpeed: Int get() = if (tempLevel > 0 && noStir) 0 else 1

    private fun speedName(v: Int): String = when (v) {
        0 -> tr("Sans mélange")
        1 -> tr("Mijoter")
        2, 3 -> tr("Mélanger")
        in 4..6 -> tr("Hacher")
        else -> tr("Mixer")
    } + if (reverse && v > 0) " · " + tr("inverse") else ""

    private fun tempName(t: Int): String = if (t >= 100) tr("Ébullition") else if (t >= 60) tr("Cuisson") else tr("Tiédir")

    private val tempSteps: IntArray get() = intArrayOf(0) + caps.temperaturesC.toIntArray()

    private fun nextSeconds(s: Int, dir: Int): Int {
        val step = when {
            dir > 0 && s < 60 || dir < 0 && s <= 60 -> 5
            dir > 0 && s < 600 || dir < 0 && s <= 600 -> 30
            else -> 60
        }
        val max = ((if (speed > 6) Limits.MAX_MS_HIGH_SPEED else Limits.MAX_MS_DEFAULT) / 1000).toInt()
        return (s + dir * step).coerceIn(5, max)
    }

    private fun change(card: Int, dir: Int) {
        val s = a.snapshot
        when (card) {
            0 -> seconds = nextSeconds(seconds, dir)
            1 -> {
                val i = tempSteps.indexOf(tempC).coerceAtLeast(0)
                tempC = tempSteps[(i + dir).coerceIn(0, tempSteps.size - 1)]
                if (tempLevel > 0 && speed > Limits.MAX_SPEED_HEATED) {
                    speed = Limits.MAX_SPEED_HEATED
                    notice(tr("En chauffe, vitesse %d maximum", Limits.MAX_SPEED_HEATED))
                }
                if (tempLevel == 0 && speed == 0) {
                    speed = 1
                    notice(tr("Sans chauffe, vitesse 1 minimum"))
                }
                capNoStirTemperature()
            }
            2 -> {
                val (max, why) = speedLimit()
                val min = minSpeed
                val next = (speed + dir).coerceIn(min, 10)
                if (dir < 0 && speed == min && min == 1) notice(tr("Sans chauffe, vitesse 1 minimum"))
                if (next > max) notice(tr(why, max)) else speed = next
                capNoStirTemperature()
            }
        }
        val maxS = ((if (speed > 6) Limits.MAX_MS_HIGH_SPEED else Limits.MAX_MS_DEFAULT) / 1000).toInt()
        if (seconds > maxS) seconds = maxS
        val liveEdit = live && s.kind?.name == "MANUAL"
        if (liveEdit) {
            if (card == 0) timeEdited = true
            pendingEdit = true
            // Le temps que la base applique le réglage et le renvoie dans son état.
            ownTargetUntil = SystemClock.uptimeMillis() + 1_500
        }
        refresh()
        if (liveEdit) {
            view.removeCallbacks(pushLive)
            view.postDelayed(pushLive, 350)
        }
    }

    /** Sans mélange, la base chauffe par l'opération vapeur : 120 °C au plus, comme le programme Vapeur. */
    private fun capNoStirTemperature() {
        if (speed == 0 && tempLevel > Limits.MAX_TEMP_LEVEL_NO_STIR) {
            tempC = Mcc.celsiusForLevel(Limits.MAX_TEMP_LEVEL_NO_STIR)
            notice(tr("Sans mélange, %d °C maximum", tempC))
        }
    }

    private fun setReverse(r: Boolean) {
        if (speed == 0) return // sans mélange, pas de sens de rotation
        if (r && speed > Limits.MAX_SPEED_REVERSE) {
            speed = Limits.MAX_SPEED_REVERSE
            notice(tr("En sens inverse, vitesse %d maximum", Limits.MAX_SPEED_REVERSE))
        }
        reverse = r
        refresh()
        if (live) { pendingEdit = true; view.removeCallbacks(pushLive); view.postDelayed(pushLive, 200) }
    }

    private fun setAccessory(acc: Accessory) {
        if (live) return
        accessory = acc
        if (speed > acc.maxSpeed) {
            speed = acc.maxSpeed
            notice(tr("%s : vitesse %d maximum", acc.label, acc.maxSpeed))
        }
        refresh()
    }

    private fun spec(hot: Boolean = false) = Programs.manual(
        speed, tempLevel, if (reverse) Direction.COUNTER_CLOCKWISE else Direction.CLOCKWISE,
        seconds * 1000L, accessory, hot, smartLevel = smartLevel,
    )

    private fun start() {
        onPick?.let { pick ->
            pick(app.toque.core.recipes.StepMachine(seconds = seconds, speed = speed, tempC = tempC,
                reverse = reverse && speed > 0, accessory = accessory))
            a.back()
            return
        }
        val s = a.snapshot
        if (s.state == JobState.PAUSED && s.kind?.name == "MANUAL") {
            a.machine.resume(s.hotBlendConfirmed) { a.refusal(it) }
            return
        }
        configuredSeconds = seconds
        a.startJob(spec())
    }

    private fun sendAdjust() {
        pendingEdit = false
        val s = a.snapshot
        if (!s.jobActive || s.kind?.name != "MANUAL") return
        // La durée n'est renvoyée que si elle a été modifiée : changer la vitesse ne relance pas le minuteur.
        val total: Long? = if (timeEdited) s.elapsedMs + seconds * 1000L else null
        timeEdited = false
        a.machine.adjust(speed, tempLevel, reverse, total, s.hotBlendConfirmed, smartLevel) { v ->
            if (!v.ok && v.has(SafetyPolicy.HOT_BLEND)) {
                val q = if (RobotProfiles.active.capabilities.measuresTemperature) tr("Le bol est à %d °C. Passer à la vitesse %d ?", a.snapshot.actualTempC, speed)
                    else tr("Ce robot ne mesure pas toujours la température du bol : Toque considère le contenu comme chaud. Passer à la vitesse %d ?", speed)
                a.confirm(tr("Contenu chaud"), q, tr("Confirmer"), icon = Icon.Kind.THERMO) {
                    a.machine.adjust(speed, tempLevel, reverse, total, true, smartLevel) { a.refusal(it) }
                }
            } else a.refusal(v)
        }
    }

    private fun refresh() {
        // Barres Sens et Accessoire reconstruites seulement quand leur état change (pas à chaque
        // cran de molette) : moins de mises en page, plus fluide sur la tablette du robot.
        val barsKey = "$reverse|$accessory|${speed == 0}|$live"
        if (barsKey != lastBars) {
            lastBars = barsKey
            dirRow.removeAllViews()
            // Sans mélange, la lame ne tourne pas : le sens ne s'applique pas (grisé, setReverse l'ignore).
            dirRow.alpha = if (speed == 0) 0.35f else 1f
            dirRow.addView(Ui.segmented(c, listOf(
                Ui.Seg("normal", tr("Normal"), Icon.Kind.FORWARD),
                Ui.Seg("reverse", tr("Inverse"), Icon.Kind.REVERSE),
            ), if (reverse) "reverse" else "normal") { k -> setReverse(k == "reverse") }, LinearLayout.LayoutParams(0, Ui.MATCH, 1f))
            accRow.removeAllViews()
            // L'accessoire ne change pas pendant une cuisson (setAccessory l'ignore) : grisé.
            accRow.alpha = if (live) 0.35f else 1f
            accRow.addView(Ui.segmented(c, listOf(
                Ui.Seg(Accessory.BLADE.name, Accessory.BLADE.label, Icon.Kind.BLADE),
                Ui.Seg(Accessory.WHISK.name, Accessory.WHISK.label, Icon.Kind.WHISK),
            ), accessory.name) { k -> setAccessory(Accessory.valueOf(k)) }, LinearLayout.LayoutParams(0, Ui.MATCH, 1f))
        }

        // Pour une étape de recette, le bol d'aujourd'hui ne compte pas.
        val bowl = if (onPick == null && a.snapshot.hasSample) a.snapshot.actualTempC else null
        val v = SafetyPolicy.checkJob(spec(), bowl).filter { it.code != SafetyPolicy.HOT_BLEND }
        setHint(v.firstOrNull()?.message ?: if (onPick == null && a.snapshot.hot && speed >= 4) tr("Contenu chaud : confirmation demandée") else idleHint())
        Ui.setButtonEnabled(startBtn, v.isEmpty())
        showGauge(a.snapshot)
    }

    /**
     * Jauge du réglage choisi, avec la plage permise par la sécurité. Pour la température, le repère
     * suit le réglage dès l'appui, sans attendre la prochaine trame de la base (jusqu'à une seconde au
     * repos) ; en cuisson, il revient à la consigne du programme une fois le réglage appliqué. L'arc
     * intérieur montre la mesure réelle : température du bol, ou vitesse de la lame en marche.
     */
    private fun showGauge(s: Snapshot) {
        show(gauge, selected, s, small = false)
        otherParams().forEachIndexed { k, i ->
            show(satellites[k], i, s, small = true)
            satLabels[k].text = paramName(i)
        }
    }

    /**
     * Remplit une jauge avec le réglage [param]. En petit (satellite), pas de graduations, et le nom
     * du réglage sous la valeur ; en grand, les graduations et le détail (« Ébullition », « restant »…).
     */
    private fun show(g: Gauge, param: Int, s: Snapshot, small: Boolean) {
        when (param) {
            0 -> {
                val full = (Limits.MAX_MS_DEFAULT / 1000).toFloat()
                val maxS = ((if (speed > 6) Limits.MAX_MS_HIGH_SPEED else Limits.MAX_MS_DEFAULT) / 1000).toFloat()
                // Échelle en racine carrée : les premières minutes, les plus réglées, prennent de la place.
                fun f(sec: Float) = Math.sqrt((sec / full).toDouble()).toFloat()
                g.set(f(seconds.toFloat()), 0f, f(maxS), Ui.ACCENT, Ui.clock(seconds * 1000L), "",
                    if (small) "" else if (live) tr("restant") else if (speed > 6) tr("15 min maximum au-delà de la vitesse 6") else tr("99 min maximum"),
                    if (small) emptyList() else listOf(f(60f) to "1", f(600f) to "10", f(1800f) to "30", 1f to "99 min"))
            }
            1 -> {
                val editing = pendingEdit || SystemClock.uptimeMillis() < ownTargetUntil
                // °C exacts (40 °C sur le Smart) ; la position sur le cadran suit la table de Toque.
                val t = if (s.jobActive && s.jobTempLevel > 0 && !editing) s.jobTempC else tempC
                val level = if (t > 0) Mcc.levelAtMost(t) else 0
                val okTo = if (speed == 0) Limits.MAX_TEMP_LEVEL_NO_STIR / 19f else 1f
                val ticks = listOf(37, 60, 100, 130).map { v -> Mcc.TEMPERATURE_LEVELS.indexOf(v) / 19f to "$v" }
                g.set(level / 19f, 0f, okTo, if (level == 0) Ui.TEXT3 else Ui.tempColor(t),
                    if (level == 0) "—" else "$t", if (level == 0) "" else "°C",
                    if (small) "" else if (level == 0) tr("Sans chauffe") else tempName(t),
                    if (small) emptyList() else ticks,
                    if (s.hasSample) levelFraction(s.actualTempC) else null, Ui.tempColor(if (s.hasSample) s.actualTempC else 0))
            }
            else -> {
                val (max, _) = speedLimit()
                g.set(speed / 10f, minSpeed / 10f, max / 10f, Ui.ACCENT, "$speed", "",
                    if (small) "" else speedName(speed),
                    if (small) emptyList() else (0..10).map { it / 10f to "$it" },
                    if (s.jobActive && s.hasSample) s.speedLevel / 10f else null, Ui.COOL)
            }
        }
    }

    /** Position d'une température mesurée sur l'échelle des niveaux (interpolée entre deux niveaux). */
    private fun levelFraction(celsius: Int): Float {
        val t = Mcc.TEMPERATURE_LEVELS
        if (celsius <= 0) return 0f
        if (celsius < t[1]) return celsius.toFloat() / t[1] / 19f
        for (i in 1 until t.size - 1) {
            if (celsius < t[i + 1]) return (i + (celsius - t[i]).toFloat() / (t[i + 1] - t[i])) / 19f
        }
        return 1f
    }

    override fun onSnapshot(s: Snapshot) {
        weight.text = if (s.hasSample) tr("Balance : %d g", s.weightG) else tr("Base non connectée")
        if (onPick != null) return
        val manualJob = s.kind?.name == "MANUAL" && (s.state == JobState.RUNNING || s.state == JobState.STARTING || s.state == JobState.PAUSED)
        live = manualJob && s.state != JobState.PAUSED
        accRow.alpha = if (live) 0.35f else 1f
        if (live && !pendingEdit) {
            // Pendant la cuisson, la carte Durée affiche le temps restant.
            val remaining = ((s.remainingMs + 999) / 1000).toInt().coerceAtLeast(1)
            if (remaining != seconds) {
                seconds = remaining
            }
        }
        if (wasLive && !live && !manualJob) {
            seconds = configuredSeconds
            refresh()
        }
        wasLive = live
        val busy = s.busy
        startBtn.visibility = if (busy && s.state != JobState.PAUSED) View.INVISIBLE else View.VISIBLE
        (startBtn.getChildAt(1) as TextView).text = if (s.state == JobState.PAUSED && manualJob) tr("Reprendre") else tr("Démarrer")
        turbo.isEnabled = !busy || s.turboActive
        turbo.alpha = if (turbo.isEnabled) 1f else 0.35f
        if (live) setHint(tr("Réglages appliqués en direct"))
        else if (!busy) refreshHintOnly()
        showGauge(s)
    }

    private fun refreshHintOnly() {
        val v = SafetyPolicy.checkJob(spec(), if (a.snapshot.hasSample) a.snapshot.actualTempC else null)
            .filter { it.code != SafetyPolicy.HOT_BLEND }
        setHint(v.firstOrNull()?.message ?: if (a.snapshot.hot && speed >= 4) tr("Contenu chaud : confirmation demandée") else idleHint())
    }

    /** Rappel affiché quand rien ne s'oppose au démarrage : sans mélange, rien ne décolle le fond du bol. */
    private fun idleHint(): String = if (speed == 0 && tempLevel > 0) tr("Sans mélange : surveillez la cuisson") else ""

    override fun onKnob(delta: Int): Boolean {
        change(selected, delta)
        return true
    }

    override fun onKnobPress(): Boolean {
        if (onPick != null) { start(); return true }
        val s = a.snapshot
        when {
            s.state == JobState.RUNNING -> a.machine.pause { a.refusal(it) }
            else -> start()
        }
        return true
    }
}
