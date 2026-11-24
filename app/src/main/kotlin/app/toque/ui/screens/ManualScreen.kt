package app.toque.ui.screens

import app.toque.core.protocol.Smart
import app.toque.core.robot.Feature
import app.toque.core.robot.RobotProfiles
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
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
import app.toque.ui.Cockpit
import app.toque.ui.HoldButton
import app.toque.ui.Icon
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.Ui
import app.toque.ui.TwoWay

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

    private lateinit var cockpit: Cockpit
    private lateinit var stopBtn: LinearLayout
    private var clearing = false
    private lateinit var dirToggle: TwoWay
    private lateinit var accToggle: TwoWay
    private val hideNotice = Runnable {
        transient = false
        showNotice(hintText, hintText == tr("Réglages appliqués en direct"))
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
        // Le cadran « instrument » occupe l'écran ; en bas, trois colonnes :
        //  - à gauche, le sens (Normal | Inverse) au-dessus de la balance ;
        //  - au milieu, le Turbo ;
        //  - à droite, l'accessoire (Lame | Fouet) au-dessus de Démarrer.
        // Le bouton rotatif règle ce qui est sélectionné sur le cadran ; son appui démarre ou met en pause.
        val root = FrameLayout(c)
        root.setPadding(Ui.u(24), Ui.u(2), Ui.u(24), Ui.u(18))
        cockpit = Cockpit(c).apply {
            tempTicks = tempTickFractions
            tempMajor = tempTickMajor
            onSelect = { this@ManualScreen.selected = it; refresh() }
            onDrag = { param, f -> dragTo(param, f) }
            onTimeStep = { dir -> this@ManualScreen.selected = 0; change(0, dir) }
        }
        root.addView(cockpit, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH).apply { bottomMargin = Ui.u(84) })

        val bottom = Ui.horizontal(c, Gravity.BOTTOM)
        val leftCol = Ui.vertical(c)
        dirToggle = TwoWay(c, tr("Normal"), tr("Inverse")) { setReverse(it) }
        leftCol.addView(dirToggle, Ui.lp(Ui.MATCH, Ui.u(52), b = 22))
        // Balance : le poids reste visible, et la toucher tare (remise à zéro) sans quitter la cuisson.
        val scaleRow = Ui.horizontal(c, Gravity.CENTER)
        scaleRow.background = Ui.pressable(Ui.round(0, 14f, Ui.STROKE, 1.5f), 14f)
        scaleRow.addView(Icon(c, Icon.Kind.SCALE, Ui.TEXT2), LinearLayout.LayoutParams(Ui.u(26), Ui.u(26)).apply { rightMargin = Ui.u(12) })
        weight = Ui.text(c, "", 19f, Ui.TEXT, Ui.MEDIUM)
        scaleRow.addView(weight)
        scaleRow.isClickable = true
        scaleRow.contentDescription = tr("Tare")
        scaleRow.setOnClickListener {
            a.machine.tare()
            notice(tr("Balance remise à zéro"), info = true)
        }
        leftCol.addView(scaleRow, Ui.lp(Ui.MATCH, Ui.u(72)))
        bottom.addView(leftCol, LinearLayout.LayoutParams(0, Ui.WRAP, 0.25f))

        val mid = Ui.vertical(c, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM)
        turbo = HoldButton(c, "", onDown = { a.turboDown(accessory) }, onUp = { a.machine.turboUp() }, iconOnly = true)
        turbo.contentDescription = "Turbo"
        mid.addView(turbo, LinearLayout.LayoutParams(Ui.u(150), Ui.u(72)))
        if (onPick != null) turbo.visibility = View.INVISIBLE
        // Pendant la cuisson, Turbo (indisponible) laisse sa place à Arrêter.
        stopBtn = Ui.button(c, "", Ui.Style.DANGER, Icon.Kind.STOP, height = 72) { a.machine.stop() }
        stopBtn.background = Ui.pressable(Ui.round(Ui.DANGER, 14f), 14f)
        stopBtn.contentDescription = tr("Arrêter")
        stopBtn.visibility = View.GONE
        mid.addView(stopBtn, LinearLayout.LayoutParams(Ui.u(150), Ui.u(72)))
        bottom.addView(mid, LinearLayout.LayoutParams(0, Ui.WRAP, 0.5f))

        val rightCol = Ui.vertical(c)
        accToggle = TwoWay(c, Accessory.BLADE.label, Accessory.WHISK.label) { setAccessory(if (it) Accessory.WHISK else Accessory.BLADE) }
        rightCol.addView(accToggle, Ui.lp(Ui.MATCH, Ui.u(52), b = 22))
        startBtn = if (onPick != null) Ui.button(c, tr("Utiliser ce réglage"), Ui.Style.PRIMARY, Icon.Kind.CHECK, height = 72, textSize = 20f) { start() }
        else Ui.button(c, tr("Démarrer"), Ui.Style.PRIMARY, Icon.Kind.PLAY, height = 72, textSize = 22f) { start() }
        // Mêmes coins que Balance, Turbo et les interrupteurs (et non une pilule).
        startBtn.background = Ui.pressable(Ui.round(Ui.ACCENT, 14f), 14f)
        rightCol.addView(startBtn, Ui.lp(Ui.MATCH, Ui.u(72)))
        bottom.addView(rightCol, LinearLayout.LayoutParams(0, Ui.WRAP, 0.25f))
        root.addView(bottom, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM))
        dirToggle.set(reverse, animate = false)
        accToggle.set(accessory == Accessory.WHISK, animate = false)
        refresh()
        return root
    }

    /** Graduations de l'arc de température : une par palier du robot ; 37, 60, 100 et 130 °C en grand. */
    private val tempTickFractions: FloatArray by lazy { caps.temperaturesC.map { Mcc.levelAtMost(it) / 19f }.distinct().toFloatArray() }
    private val tempTickMajor: BooleanArray by lazy {
        caps.temperaturesC.map { Mcc.levelAtMost(it) }.distinct().map { l -> Mcc.celsiusForLevel(l) in intArrayOf(37, 60, 100, 130) }.toBooleanArray()
    }

    /** Affiche [message] sous la durée pendant quelques secondes. */
    private fun notice(message: String, info: Boolean = false) {
        transient = true
        showNotice(message, info)
        cockpit.removeCallbacks(hideNotice)
        cockpit.postDelayed(hideNotice, 2_800)
    }

    /** Message permanent ; vide = rien. Un message passager reste prioritaire jusqu'à sa fin. */
    private fun setHint(text: String) {
        if (text == hintText) return
        hintText = text
        if (transient) return
        showNotice(text, text == tr("Réglages appliqués en direct"))
    }

    private fun showNotice(message: String, info: Boolean) {
        val color = if (info) Ui.OK else Ui.WARN
        if (cockpit.notice == message && cockpit.noticeColor == color) return
        cockpit.notice = message
        cockpit.noticeColor = color
        cockpit.invalidate()
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
        when (card) {
            0 -> seconds = nextSeconds(seconds, dir)
            1 -> {
                val i = tempSteps.indexOf(tempC).coerceAtLeast(0)
                applyTemp(tempSteps[(i + dir).coerceIn(0, tempSteps.size - 1)])
            }
            2 -> applySpeed(speed + dir)
        }
        edited(card)
    }

    /** Glissé du doigt sur un arc : la température ou la vitesse la plus proche de [f]. */
    private fun dragTo(param: Int, f: Float) {
        if (param == 1) {
            val t = tempSteps.minByOrNull { Math.abs((if (it == 0) 0 else Mcc.levelAtMost(it)) / 19f - f) } ?: return
            if (t == tempC) return
            applyTemp(t)
        } else {
            val v = Math.round(f * 10)
            if (v == speed) return
            applySpeed(v)
        }
        edited(param)
    }

    private fun applyTemp(t: Int) {
        tempC = t
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

    private fun applySpeed(target: Int) {
        val (max, why) = speedLimit()
        val min = minSpeed
        if (target < min && min == 1) notice(tr("Sans chauffe, vitesse 1 minimum"))
        val next = target.coerceIn(min, 10)
        if (next > max) {
            notice(tr(why, max))
            speed = maxOf(max, min)
        } else speed = next
        capNoStirTemperature()
    }

    /** Après un réglage : bornes de durée, envoi en direct pendant une cuisson, affichage. */
    private fun edited(card: Int) {
        val s = a.snapshot
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
        if (speed == 0) { dirToggle.set(false); return } // sans mélange, pas de sens de rotation
        if (r && speed > Limits.MAX_SPEED_REVERSE) {
            speed = Limits.MAX_SPEED_REVERSE
            notice(tr("En sens inverse, vitesse %d maximum", Limits.MAX_SPEED_REVERSE))
        }
        reverse = r
        refresh()
        if (live) { pendingEdit = true; view.removeCallbacks(pushLive); view.postDelayed(pushLive, 200) }
    }

    private fun setAccessory(acc: Accessory) {
        if (live) { accToggle.set(accessory == Accessory.WHISK); return }
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
        if (s.kind?.name == "MANUAL" && (s.state == JobState.RUNNING || s.state == JobState.STARTING)) {
            a.machine.pause { a.refusal(it) }
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
        dirToggle.set(reverse && speed > 0)
        // Sans mélange, la lame ne tourne pas : le sens ne s'applique pas. Pendant une cuisson,
        // l'accessoire ne change pas. Grisés dans ces cas.
        dirToggle.alpha = if (speed == 0) 0.35f else 1f
        accToggle.set(accessory == Accessory.WHISK)
        accToggle.alpha = if (live) 0.35f else 1f

        // Pour une étape de recette, le bol d'aujourd'hui ne compte pas.
        val bowl = if (onPick == null && a.snapshot.hasSample) a.snapshot.actualTempC else null
        val v = SafetyPolicy.checkJob(spec(), bowl).filter { it.code != SafetyPolicy.HOT_BLEND }
        setHint(v.firstOrNull()?.message ?: if (onPick == null && a.snapshot.hot && speed >= 4) tr("Contenu chaud : confirmation demandée") else idleHint())
        Ui.setButtonEnabled(startBtn, v.isEmpty())
        showGauge(a.snapshot)
    }

    /**
     * Remplit le cadran. Pour la température, la consigne suit le réglage dès l'appui, sans attendre
     * la prochaine trame de la base ; en cuisson, elle revient à celle du programme une fois le réglage
     * appliqué. Le trait clair sur l'arc et la ligne sous la durée donnent la température du bol.
     */
    private fun showGauge(s: Snapshot) {
        val k = cockpit
        k.selected = selected
        k.timeText = Ui.clock(seconds * 1000L)
        k.timeCaption = if (live) tr("restant") else tr("Durée")
        val editing = pendingEdit || SystemClock.uptimeMillis() < ownTargetUntil
        val t = if (s.jobActive && s.jobTempLevel > 0 && !editing) s.jobTempC else tempC
        val level = if (t > 0) Mcc.levelAtMost(t) else 0
        k.tempFrac = level / 19f
        k.tempOkTo = if (speed == 0) Limits.MAX_TEMP_LEVEL_NO_STIR / 19f else 1f
        k.tempText = if (level == 0) null else "$t°"
        val measured = onPick == null && s.hasSample
        // Sous sa plus basse température (37 °C sur le Connect), la base renvoie 0 : on affiche
        // la température d'une cuisine (21 °C), puis la vraie dès qu'elle arrive.
        val bowlC = if (s.actualTempC <= 0) app.toque.ui.ROOM_C else s.actualTempC
        k.bowlFrac = if (measured) levelFraction(bowlC) else null
        k.bowlText = if (measured) tr("Bol %d °C", bowlC) else ""
        val (max, _) = speedLimit()
        k.speedFrac = speed / 10f
        k.speedOkFrom = minSpeed / 10f
        k.speedOkTo = max / 10f
        k.speedText = if (speed == 0) null else "$speed"
        k.invalidate()
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
        // Fin de cuisson : « Terminé » sous la durée, puis la cuisson est rangée d'elle-même
        // (plus de bouton OK à toucher, le bandeau du bas n'apparaît pas ici).
        val ended = s.kind?.name == "MANUAL" && (s.state == JobState.FINISHED || s.state == JobState.ABORTED)
        if (ended && !clearing) {
            clearing = true
            if (s.state == JobState.FINISHED) notice(tr("Terminé"), info = true)
            cockpit.postDelayed({ a.machine.clearFinished(); clearing = false }, 2_500)
        }
        val manualJob = s.kind?.name == "MANUAL" && (s.state == JobState.RUNNING || s.state == JobState.STARTING || s.state == JobState.PAUSED)
        live = manualJob && s.state != JobState.PAUSED
        accToggle.alpha = if (live) 0.35f else 1f
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
        // Le bandeau du bas est masqué ici (hidesMachineBar) : Démarrer devient Pause puis Reprendre,
        // et Arrêter prend la place du Turbo.
        startBtn.visibility = if (busy && !manualJob) View.INVISIBLE else View.VISIBLE
        val paused = manualJob && s.state == JobState.PAUSED
        (startBtn.getChildAt(1) as TextView).text = when {
            paused -> tr("Reprendre")
            manualJob -> tr("Pause")
            else -> tr("Démarrer")
        }
        (startBtn.getChildAt(0) as Icon).set(if (manualJob && !paused) Icon.Kind.PAUSE else Icon.Kind.PLAY)
        stopBtn.visibility = if (manualJob) View.VISIBLE else View.GONE
        turbo.visibility = if (manualJob) View.GONE else View.VISIBLE
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

    // Toute la vie d'une cuisson manuelle se voit ici, fin comprise : pas de bandeau en bas.
    override fun hidesMachineBar(s: Snapshot): Boolean = onPick == null &&
        (s.turboActive || s.kind?.name == "MANUAL" && s.state != null)

    override fun onKnob(delta: Int): Boolean {
        // L'arc de vitesse est à droite et monte du bas : tourner le bouton vers la droite (sens
        // horaire) descend le long de cet arc, donc baisse la vitesse. La température, à gauche,
        // monte dans le sens horaire : pas d'inversion.
        change(selected, if (selected == 2) -delta else delta)
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
