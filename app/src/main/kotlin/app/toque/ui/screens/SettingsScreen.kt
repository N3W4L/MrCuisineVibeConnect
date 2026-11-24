package app.toque.ui.screens

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import app.toque.Prefs
import app.toque.TimeSync
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.core.machine.RootShell
import app.toque.core.machine.Snapshot
import app.toque.core.time.ZoneLookup
import app.toque.core.update.Release
import app.toque.recipes.LidlSync
import app.toque.core.recipes.McOnline
import app.toque.recipes.OnlineSync
import app.toque.recipes.SyncProgress
import app.toque.recipes.RecipeFiles
import app.toque.core.recipes.UserRecipes
import app.toque.ui.Icon
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.SoundPacks
import app.toque.ui.Sounds
import app.toque.ui.Theme
import app.toque.ui.Ui
import app.toque.ui.UpdateFlow
import app.toque.update.Updater

class SettingsScreen(a: MainActivity) : Screen(a) {
    override val title = tr("Réglages")
    private lateinit var modeRow: LinearLayout
    private lateinit var twinBox: LinearLayout
    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var syncText: TextView
    private lateinit var syncBtn: View
    private lateinit var stopSyncBtn: View
    /** Absent sur un robot dont la base de l'appli d'origine n'est pas encore lue (Smart). */
    private var importBtn: View? = null
    private lateinit var bowlText: TextView
    private lateinit var linkText: TextView
    private lateinit var timeText: TextView
    private var settingTime = false
    private var mode = a.prefs.mode

    private enum class Upd { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, ERROR }
    private val updater by lazy { Updater(a) }
    private lateinit var updText: TextView
    private lateinit var updButtons: LinearLayout
    private var upd = Upd.IDLE
    private var release: Release? = null
    private var updMessage = ""

    /** Une rubrique des réglages : son entrée dans la liste de gauche et sa carte à droite. */
    private class Section(val title: String, val icon: Icon.Kind, val card: LinearLayout) {
        lateinit var row: LinearLayout
        lateinit var iconView: Icon
        lateinit var label: TextView
    }

    private val sections = ArrayList<Section>()
    private lateinit var packRow: LinearLayout
    private lateinit var weighBox: LinearLayout

    /** Barre des quatre jeux de sons ; le choix d'un jeu fait entendre sa fin de programme. */
    private fun renderPack() {
        val pack = SoundPacks.Pack.of(a.prefs.soundPack)
        packRow.removeAllViews()
        packRow.addView(Ui.segmented(c, SoundPacks.Pack.values().map { Ui.Seg(it.key, it.label) }, pack.key) { k ->
            a.prefs.soundPack = k
            SoundPacks.warmUp(SoundPacks.Pack.of(k))
            renderPack()
            preview(SoundPacks.Event.PROGRAM_DONE)
        }, LinearLayout.LayoutParams(0, Ui.MATCH, 1f))
        // Classique : le son de pesée se choisit parmi les sons d'origine ; pas de son au lancement.
        weighBox.visibility = if (pack == SoundPacks.Pack.CLASSIC) View.VISIBLE else View.GONE
    }

    /** Fait entendre un son du jeu choisi, même si les sons sont coupés. */
    private fun preview(e: SoundPacks.Event) {
        val pack = SoundPacks.Pack.of(a.prefs.soundPack)
        if (pack != SoundPacks.Pack.CLASSIC) { SoundPacks.play(pack, e); return }
        when (e) {
            SoundPacks.Event.WEIGH -> Sounds.weighReached(a.prefs.weighSound)
            SoundPacks.Event.RECIPE_DONE -> Sounds.recipeDone()
            SoundPacks.Event.PROGRAM_DONE, SoundPacks.Event.ALERT -> {
                val was = a.prefs.sound
                a.prefs.sound = true
                a.chime(if (e == SoundPacks.Event.ALERT) 3 else 2)
                a.prefs.sound = was
            }
            else -> SoundPacks.play(pack, e)
        }
    }
    private lateinit var detail: LinearLayout
    private lateinit var detailScroll: android.widget.ScrollView

    override fun build(): View {
        // Six rubriques, les plus utilisées d'abord : une liste à gauche, la rubrique choisie à
        // droite. Les explications sont derrière un petit « i » à côté de ce qu'elles expliquent.
        val left = Ui.vertical(c)

        // ---- Apparence, avec les sons : tout tient sur une page.
        val look = section(left, tr("Apparence"), Icon.Kind.STAR)
        val themeRow = Ui.horizontal(c, Gravity.TOP)
        val themeCol = Ui.vertical(c)
        themeCol.addView(label(tr("Thème")))
        themeCol.addView(dropdown(Theme.Id.values().map { it.key to it.label }, a.prefs.theme) { k ->
            if (k != a.prefs.theme && themeChangeAllowed()) {
                a.prefs.theme = k
                a.applyTheme()
            }
        }, Ui.lp(Ui.MATCH, Ui.u(56), t = 6))
        themeRow.addView(themeCol, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { rightMargin = Ui.u(14) })
        val modeCol = Ui.vertical(c)
        modeCol.addView(label(tr("Mode"), tr("En automatique, l'interface est claire de %d h à %d h et sombre le reste du temps.", Theme.dayStart, Theme.dayEnd)))
        modeCol.addView(dropdown(Theme.Mode.values().map { it.key to it.label }, a.prefs.themeMode) { k ->
            if (k != a.prefs.themeMode && themeChangeAllowed()) {
                a.prefs.themeMode = k
                a.applyTheme()
            }
        }, Ui.lp(Ui.MATCH, Ui.u(56), t = 6))
        themeRow.addView(modeCol, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        look.addView(themeRow)
        // Prénom de l'accueil : enregistré à chaque lettre, rien à valider.
        look.addView(label(tr("Votre prénom, affiché sur l'accueil")), Ui.lp(t = 12))
        look.addView(field(a.prefs.firstName, tr("Prénom")).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(e: android.text.Editable?) { a.prefs.firstName = e?.toString() ?: "" }
                override fun beforeTextChanged(s: CharSequence?, st: Int, n: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, before: Int, n: Int) {}
            })
        }, Ui.lp(Ui.MATCH, Ui.u(56), t = 6))
        look.addView(subhead(tr("Sons")), Ui.lp(t = 16))
        val soundRow = Ui.horizontal(c)
        soundRow.addView(toggle(tr("Sons activés"), a.prefs.sound) { a.prefs.sound = it }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { rightMargin = Ui.u(24) })
        soundRow.addView(toggle(tr("Clic de la molette et des boutons"), a.prefs.clickSound) { a.prefs.clickSound = it }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        look.addView(soundRow)
        // Volume : glisser règle, lâcher fait entendre le son de fin de programme au nouveau niveau.
        val volRow = Ui.horizontal(c, Gravity.CENTER_VERTICAL)
        volRow.addView(Ui.text(c, tr("Volume"), 17f, Ui.TEXT), Ui.lp(Ui.WRAP, Ui.WRAP, r = 14))
        val volText = Ui.text(c, "${a.prefs.soundVolume} %", 17f, Ui.TEXT2)
        val bar = android.widget.SeekBar(c).apply {
            max = 95
            progress = a.prefs.soundVolume - 5
            progressTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
            thumbTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Ui.TEXT3)
            minimumHeight = Ui.u(44)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar, v: Int, fromUser: Boolean) {
                    volText.text = "${v + 5} %"
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar) {
                    a.prefs.soundVolume = sb.progress + 5
                    a.applyVolume()
                    preview(SoundPacks.Event.PROGRAM_DONE)
                }
            })
        }
        volRow.addView(bar, LinearLayout.LayoutParams(0, Ui.u(48), 1f))
        volRow.addView(volText, LinearLayout.LayoutParams(Ui.u(64), Ui.WRAP).apply { leftMargin = Ui.u(8) })
        look.addView(volRow, Ui.lp(t = 4))
        // Choisir un jeu le fait entendre (son de fin de programme) : pas de boutons d'écoute à part.
        packRow = Ui.horizontal(c)
        look.addView(packRow, Ui.lp(Ui.MATCH, Ui.u(56), t = 8))
        weighBox = Ui.vertical(c)
        weighBox.addView(label(tr("Son quand la quantité à peser est atteinte")))
        weighBox.addView(dropdown(Sounds.WEIGH_SOUNDS.map { it.first to tr(it.second) }, a.prefs.weighSound) { k ->
            a.prefs.weighSound = k
            Sounds.weighReached(k)
        }, Ui.lp(Ui.MATCH, Ui.u(56), t = 6))
        look.addView(weighBox, Ui.lp(t = 12))
        renderPack()

        // ---- Général : langue, écran, date et heure.
        val general = section(left, tr("Général"), Icon.Kind.GLOBE)
        // Titre bilingue : trouvable quelle que soit la langue affichée.
        general.addView(label("Langue · Language"))
        val options = listOf(I18n.AUTO to tr("Automatique (langue du système)")) + I18n.languages.map { it.code to it.name }
        general.addView(dropdown(options, a.prefs.language) { code ->
            a.prefs.language = code
            a.applyLanguage()
        }, Ui.lp(Ui.MATCH, Ui.u(56), t = 6))
        val screenRow = Ui.horizontal(c, Gravity.TOP)
        val sleepCol = Ui.vertical(c)
        val delays = listOf("0" to tr("Jamais")) + listOf(2, 5, 10, 15, 30).map { it.toString() to tr("Après %d min", it) }
        sleepCol.addView(label(tr("Mise en veille"), tr("Sans geste ni programme en cours, l'écran s'assombrit et n'affiche plus que l'heure. Touchez-le ou tournez le bouton pour reprendre ; le couvercle et la balance le réveillent aussi.")))
        sleepCol.addView(dropdown(delays, a.prefs.standbyMinutes.toString()) { m -> a.prefs.standbyMinutes = m.toInt() }, Ui.lp(Ui.MATCH, Ui.u(56), t = 6))
        screenRow.addView(sleepCol, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { rightMargin = Ui.u(14) })
        val knobCol = Ui.vertical(c)
        val knob = listOf("normal" to tr("Normale"), "dim" to tr("Plus légère"), "off" to tr("Éteinte"))
        knobCol.addView(label(tr("Molette pendant la veille")))
        knobCol.addView(dropdown(knob, a.prefs.standbyKnob) { a.prefs.standbyKnob = it }, Ui.lp(Ui.MATCH, Ui.u(56), t = 6))
        screenRow.addView(knobCol, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        general.addView(screenRow, Ui.lp(t = 12))
        // Anti-rebond du bouton rotatif : les crans à l'envers au milieu d'un tour sont ignorés.
        val bounce = listOf("0" to tr("Désactivé"), "350" to tr("Léger"), "500" to tr("Normal"), "800" to tr("Fort"))
        general.addView(label(tr("Anti-rebond du bouton rotatif"),
            tr("Tourné vite, le bouton du robot se trompe souvent de sens. Toque garde le sens du début de chaque geste : pour repartir dans l'autre sens, marquez une courte pause (une demi-seconde en « Normal »). « Léger » demande une pause plus courte, « Fort » protège mieux les tours très rapides.")), Ui.lp(t = 12))
        general.addView(dropdown(bounce, a.prefs.knobReverseMs.toString()) { a.prefs.knobReverseMs = it.toInt() }, Ui.lp(Ui.MATCH, Ui.u(56), t = 6))
        general.addView(label(tr("Date et heure")), Ui.lp(t = 12))
        val zones = listOf(ZoneLookup.AUTO to tr("Automatique (d'après la connexion Internet)")) +
            (ZoneLookup.COMMON + listOf(TimeSync.current(), a.prefs.timeZone))
                .filter { it != ZoneLookup.AUTO }.distinct().map { it to it.replace('_', ' ') }
        val timeRow = Ui.horizontal(c)
        timeRow.addView(dropdown(zones, a.prefs.timeZone) { z ->
            a.prefs.timeZone = z
            setClock()
        }, LinearLayout.LayoutParams(0, Ui.u(56), 1f).apply { rightMargin = Ui.u(12) })
        timeRow.addView(Ui.button(c, tr("Mettre à l'heure"), Ui.Style.SECONDARY, Icon.Kind.SYNC, height = 56) { setClock() })
        general.addView(timeRow, Ui.lp(t = 6))
        timeText = Ui.text(c, "", 15f, Ui.TEXT2).apply { setLineSpacing(0f, 1.1f) }
        general.addView(timeText, Ui.lp(t = 8))
        refreshTime()

        // ---- Recettes : celles de Lidl, puis les vôtres.
        val chosen = a.prefs.robot
        val lidlIntro = if (chosen.lidlDevice == "MC2.0") tr("Toque télécharge lui-même les recettes officielles du Monsieur Cuisine connect sur monsieur-cuisine.com (sans compte).")
            else tr("Toque télécharge lui-même les recettes officielles du %s sur monsieur-cuisine.com (sans compte).", chosen.displayName)
        val lidl = section(left, tr("Recettes"), Icon.Kind.BOOK)
        lidl.addView(subhead(tr("Recettes Lidl"), lidlIntro))
        // Synchroniser, et à sa droite l'état : dernière synchro, nombre de recettes.
        val b1 = Ui.horizontal(c)
        syncBtn = Ui.button(c, tr("Synchroniser"), Ui.Style.PRIMARY, Icon.Kind.SYNC, height = 56) { sync(online = true) }
        b1.addView(syncBtn, Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
        // Visible seulement pendant une synchronisation ; ce qui est déjà fait est gardé.
        stopSyncBtn = Ui.button(c, tr("Arrêter"), Ui.Style.SECONDARY, Icon.Kind.STOP, height = 56) { SyncProgress.requestStop() }
        b1.addView(stopSyncBtn, Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
        syncText = Ui.text(c, "", 15f, Ui.TEXT2).apply { setLineSpacing(0f, 1.1f) }
        b1.addView(syncText, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { leftMargin = Ui.u(6) })
        lidl.addView(b1, Ui.lp(t = 8))
        // Appli d'origine : importer ses recettes (là où Toque sait lire sa base) et l'ouvrir, sur une ligne.
        val vendor = Ui.horizontal(c)
        if (chosen.stockImport) {
            val btn = Ui.button(c, tr("Importer de l'appli d'origine"), Ui.Style.SECONDARY, Icon.Kind.DOWNLOAD, height = 52, textSize = 17f) { sync(online = false) }
            vendor.addView(btn, Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
            importBtn = btn
        }
        vendor.addView(Ui.button(c, tr("Ouvrir l'appli d'origine"), Ui.Style.GHOST, Icon.Kind.LINK, height = 52, textSize = 17f) { a.openVendorApp() })
        if (chosen.stockImport) vendor.addView(infoButton(tr("Autre source : les recettes déjà présentes dans l'appli d'origine du robot (remplacent celles du site)."), lidl, after = vendor), Ui.lp(Ui.WRAP, Ui.WRAP, l = 8))
        lidl.addView(vendor, Ui.lp(t = 10))
        lidl.addView(label(tr("Langue des recettes")), Ui.lp(t = 12))
        val recipeLangs = McOnline.RECIPE_LANGUAGES.map { code ->
            code to when (code) {
                "auto" -> tr("Automatique (selon l'interface)")
                "es-ES" -> "Español"
                "fr-FR" -> "Français"
                "en-US" -> "English"
                "de-DE" -> "Deutsch"
                "it-IT" -> "Italiano"
                "pt-PT" -> "Português"
                "nl-NL" -> "Nederlands"
                "pl-PL" -> "Polski"
                else -> code
            }
        }
        lidl.addView(dropdown(recipeLangs, a.prefs.recipeLanguage) { code ->
            a.prefs.recipeLanguage = code
            a.toast(tr("Pris en compte à la prochaine synchronisation"))
        }, Ui.lp(Ui.MATCH, Ui.u(56), t = 6))
        lidl.addView(toggle(tr("Synchro automatique chaque nuit"), a.prefs.autoSync) { a.prefs.autoSync = it }, Ui.lp(t = 8))
        lidl.addView(toggle(tr("Recettes des autres langues"), a.prefs.foreignRecipes,
            tr("Ajoute les recettes qui n'existent pas dans votre langue, prises en anglais, français, allemand, espagnol… Chacune n'apparaît qu'une fois, avec sa langue sur la vignette.")) {
            a.prefs.foreignRecipes = it
            a.toast(tr("Pris en compte à la prochaine synchronisation"))
        })
        lidl.addView(subhead(tr("Mes recettes"), tr("Sauvegardez vos recettes, ou passez-les à un autre robot, avec le fichier %s du dossier Download.", UserRecipes.FILE_NAME)), Ui.lp(t = 16))
        val files = Ui.horizontal(c)
        files.addView(Ui.button(c, tr("Exporter"), Ui.Style.SECONDARY, Icon.Kind.UPLOAD, height = 52) { recipeFiles(export = true) }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
        files.addView(Ui.button(c, tr("Importer"), Ui.Style.SECONDARY, Icon.Kind.DOWNLOAD, height = 52) { recipeFiles(export = false) })
        lidl.addView(files, Ui.lp(t = 8))

        // ---- Sécurité
        val safety = section(left, tr("Sécurité"), Icon.Kind.WARNING)
        bowlText = Ui.text(c, "", 15f, Ui.TEXT3).apply { setLineSpacing(0f, 1.1f) }
        safety.addView(toggle(tr("Exiger la détection du bol"), a.prefs.requireBowl, extra = bowlText) { on ->
            a.prefs.requireBowl = on
            a.machine.reconfigure { a.refusal(it) }
        })
        safety.addView(toggle(tr("Inverser le capteur du bol"), a.prefs.invertBowl,
            tr("À activer si le capteur indique « bol détecté » quand le bol est retiré, et l'inverse quand il est en place.")) { on ->
            a.prefs.invertBowl = on
            a.machine.reconfigure { a.refusal(it) }
        }, Ui.lp(t = 4))
        safety.addView(toggle(tr("Montée progressive de la vitesse si le contenu est chaud"), a.prefs.hotRamp,
            tr("À partir de 60 °C, la lame atteint sa vitesse en 2 à 3 secondes pour éviter les projections. À froid, elle y va directement.")) { on ->
            a.prefs.hotRamp = on
            a.machine.reconfigure { a.refusal(it) }
        }, Ui.lp(t = 4))
        safety.addView(toggle(tr("Enchaîner les étapes machine des recettes"), a.prefs.chainSteps,
            tr("Les réglages qui se suivent sans geste à faire entre eux partent ensemble d'un seul appui sur Lancer : la lame passe directement à la vitesse et à la température suivantes.")) { a.prefs.chainSteps = it }, Ui.lp(t = 4))

        // ---- Outils : le modèle de robot, la liaison avec la base, puis les outils de diagnostic.
        val robot = section(left, tr("Outils"), Icon.Kind.SETTINGS)
        robot.addView(subhead(tr("Robot")))
        val robotRow = Ui.horizontal(c)
        robotText = Ui.text(c, "", 17f, Ui.TEXT).apply { setLineSpacing(0f, 1.1f) }
        robotRow.addView(robotText, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { rightMargin = Ui.u(12) })
        robotRow.addView(Ui.button(c, tr("Changer de robot"), Ui.Style.SECONDARY, height = 52, textSize = 17f) { a.askRobot() })
        robot.addView(robotRow, Ui.lp(t = 6))
        renderRobot()
        linkText = Ui.text(c, "", 15f, Ui.TEXT3).apply { setLineSpacing(0f, 1.1f) }
        robot.addView(subhead(tr("Machine"), hint = linkText), Ui.lp(t = 16))
        val modeLine = Ui.horizontal(c)
        modeRow = Ui.horizontal(c)
        modeLine.addView(modeRow, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        modeLine.addView(Ui.button(c, tr("Appliquer"), Ui.Style.PRIMARY, Icon.Kind.CHECK, height = 52, textSize = 17f) { applyMode() })
        robot.addView(modeLine, Ui.lp(t = 8))
        twinBox = Ui.horizontal(c)
        host = field(a.prefs.twinHost, tr("Hôte"))
        port = field(a.prefs.twinPort.toString(), "Port").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        twinBox.addView(host, LinearLayout.LayoutParams(0, Ui.u(56), 1f).apply { rightMargin = Ui.u(10) })
        twinBox.addView(port, LinearLayout.LayoutParams(Ui.u(120), Ui.u(56)))
        robot.addView(twinBox, Ui.lp(t = 10))
        renderModes()
        val version = try { a.packageManager.getPackageInfo(a.packageName, 0).versionName } catch (_: Exception) { "?" }
        val credits = tr("Toque %s · protocole d'après le Monsieur Cuisine Developer SDK (licence MIT). " +
            "Projet indépendant, non affilié à Lidl ni à SilverCrest.", version) +
            (a.prefs.robot.credit?.let { who -> "\n" + tr("%s : protocole d'après %s (licence MIT).", a.prefs.robot.displayName, who) } ?: "")
        robot.addView(subhead(tr("Avancé"), credits), Ui.lp(t = 16))
        robot.addView(link(tr("Diagnostic de la base moteur")) { a.push(DiagnosticsScreen(a)) })
        robot.addView(link(tr("Bouton rotatif")) { a.push(KnobScreen(a)) })
        robot.addView(link(tr("Journal")) { a.push(LogScreen(a)) })
        robot.addView(link(tr("Paramètres Android")) { a.openAndroidSettings() })
        robot.addView(link(tr("Revenir à l'appli d'origine")) { restoreVendor() })

        // ---- Mises à jour
        val updates = section(left, tr("Mises à jour"), Icon.Kind.SYNC)
        updText = Ui.text(c, "", 16f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f) }
        updates.addView(updText)
        updButtons = Ui.horizontal(c)
        updates.addView(updButtons, Ui.lp(t = 12))
        updates.addView(toggle(tr("Prévenir quand une version sort"), a.prefs.updateAlerts) { a.prefs.updateAlerts = it }, Ui.lp(t = 10))
        a.updates.available?.let { release = it; upd = Upd.AVAILABLE }
        renderUpdate()

        val order = listOf(look, general, lidl, safety, robot, updates)
        return layout(order.map { card -> sections.first { it.card === card } })
    }

    /** Libellé d'un réglage, avec un « i » qui déplie son explication si [info] est donné. */
    private fun label(text: String, info: String? = null): View {
        // Même hauteur avec ou sans « i » : deux menus côte à côte restent alignés.
        val row = Ui.horizontal(c, Gravity.CENTER_VERTICAL)
        row.minimumHeight = Ui.u(40)
        row.addView(Ui.text(c, text, 17f, Ui.TEXT))
        if (info == null) return row
        val box = Ui.vertical(c)
        box.addView(row)
        row.addView(infoButton(info, box, after = row), Ui.lp(Ui.WRAP, Ui.WRAP, l = 8))
        return box
    }

    /** Intertitre d'un groupe dans une rubrique (Sons, Machine, Outils…), avec son « i » éventuel. */
    private fun subhead(text: String, info: String? = null, hint: TextView? = null): View {
        val box = Ui.vertical(c)
        val row = Ui.horizontal(c)
        row.addView(Ui.text(c, text, 19f, Ui.TEXT, Ui.MEDIUM))
        box.addView(row)
        if (info != null || hint != null) row.addView(infoButton(info, box, after = row, view = hint), Ui.lp(Ui.WRAP, Ui.WRAP, l = 8))
        return box
    }

    /**
     * Petit « i » : un toucher déplie l'explication juste sous [after] (dans [parent]), un second la
     * replie. [view] sert quand le texte change en direct (capteur du bol, mode de la machine).
     */
    private fun infoButton(info: String?, parent: LinearLayout, after: View, view: TextView? = null): View {
        val hint = view ?: Ui.text(c, info ?: "", 15f, Ui.TEXT3).apply { setLineSpacing(0f, 1.1f) }
        hint.visibility = View.GONE
        val i = Icon(c, Icon.Kind.INFO, Ui.TEXT3)
        val b = android.widget.FrameLayout(c)
        b.addView(i, Ui.flp(Ui.u(24), Ui.u(24), Gravity.CENTER))
        b.minimumWidth = Ui.u(40)
        b.minimumHeight = Ui.u(40)
        b.contentDescription = tr("Plus d'informations")
        b.background = Ui.pressable(Ui.round(0, 20f), 20f)
        b.isClickable = true
        b.setOnClickListener {
            if (hint.parent == null) parent.addView(hint, parent.indexOfChild(after) + 1, Ui.lp(t = 4, b = 4))
            val show = hint.visibility != View.VISIBLE
            hint.visibility = if (show) View.VISIBLE else View.GONE
            i.color = if (show) Ui.ACCENT else Ui.TEXT3
        }
        return b
    }

    private fun layout(order: List<Section>): View {
        val root = Ui.horizontal(c, Gravity.TOP)
        root.setPadding(Ui.u(24), Ui.u(6), Ui.u(24), Ui.u(20))
        val nav = Ui.vertical(c)
        for ((i, sec) in order.withIndex()) {
            val row = Ui.horizontal(c, Gravity.CENTER_VERTICAL)
            row.setPadding(Ui.u(20), 0, Ui.u(14), 0)
            sec.iconView = Icon(c, sec.icon, Ui.TEXT2)
            row.addView(sec.iconView, LinearLayout.LayoutParams(Ui.u(32), Ui.u(32)).apply { rightMargin = Ui.u(16) })
            sec.label = Ui.ellipsize(Ui.text(c, sec.title, 22f, Ui.TEXT, Ui.MEDIUM))
            row.addView(sec.label, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            // Une nouvelle version attend : un point sur « Mises à jour ».
            if (sec.title == tr("Mises à jour") && a.updates.available != null) {
                row.addView(android.view.View(c).apply { background = Ui.round(Ui.ACCENT, 6f) }, LinearLayout.LayoutParams(Ui.u(12), Ui.u(12)))
            }
            row.isClickable = true
            row.setOnClickListener { select(order, i) }
            sec.row = row
            // Six rubriques : elles se partagent toute la hauteur de la colonne, en grandes lignes.
            nav.addView(row, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f).apply { bottomMargin = Ui.u(10) })
        }
        root.addView(nav, LinearLayout.LayoutParams(Ui.u(260), Ui.MATCH).apply { rightMargin = Ui.u(18) })
        detail = Ui.vertical(c)
        detailScroll = Ui.scroll(c, detail)
        root.addView(detailScroll, LinearLayout.LayoutParams(0, Ui.MATCH, 1f))
        select(order, order.indexOfFirst { it.title == lastSection }.coerceAtLeast(0))
        return root
    }

    private fun select(order: List<Section>, i: Int) {
        val sec = order[i]
        lastSection = sec.title
        for (o in order) {
            val on = o === sec
            o.row.background = Ui.pressable(if (on) Ui.round(Ui.SURFACE3, 20f, Ui.ACCENT, 2f) else Ui.round(Ui.SURFACE, 20f, Ui.STROKE, 1f), 20f)
            o.iconView.color = if (on) Ui.ACCENT else Ui.TEXT2
            o.label.setTextColor(if (on) Ui.TEXT else Ui.TEXT2)
        }
        detail.removeAllViews()
        (sec.card.parent as? android.view.ViewGroup)?.removeView(sec.card)
        detail.addView(sec.card, Ui.lp(Ui.MATCH, Ui.WRAP))
        detailScroll.scrollTo(0, 0)
    }

    private fun themeChangeAllowed(): Boolean {
        if (!a.snapshot.busy) return true
        a.toast(tr("Arrêtez d'abord le programme en cours"), Ui.WARN)
        return false
    }

    @Suppress("UNUSED_PARAMETER")
    private fun section(parent: LinearLayout, title: String, icon: Icon.Kind): LinearLayout {
        val card = Ui.card(c, 24f)
        card.setPadding(Ui.u(26), Ui.u(18), Ui.u(26), Ui.u(20))
        val head = Ui.horizontal(c)
        head.addView(Icon(c, icon, Ui.ACCENT), LinearLayout.LayoutParams(Ui.u(30), Ui.u(30)).apply { rightMargin = Ui.u(12) })
        head.addView(Ui.text(c, title, 24f, Ui.TEXT, Ui.MEDIUM))
        card.addView(head, Ui.lp(b = 12))
        sections += Section(title, icon, card)
        return card
    }

    /** Pastille de couleur : un disque, entouré et coché quand c'est la couleur en cours. */
    private fun field(value: String, hint: String) = EditText(c).apply {
        setText(value)
        this.hint = hint
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.uf(19f))
        setTextColor(Ui.TEXT)
        setHintTextColor(Ui.TEXT3)
        setSingleLine()
        background = Ui.round(Ui.SURFACE2, 16f, Ui.STROKE, 1f)
        setPadding(Ui.u(14), 0, Ui.u(14), 0)
    }

    /** Interrupteur ; [info] (ou [extra], texte mis à jour en direct) se déplie sous un « i ». */
    private fun toggle(label: String, initial: Boolean, info: String? = null, extra: TextView? = null,
                       onChange: (Boolean) -> Unit): LinearLayout {
        val row = Ui.horizontal(c)
        row.addView(Ui.text(c, label, 17f, Ui.TEXT), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        val box = if (info != null || extra != null) Ui.vertical(c) else null
        if (box != null) row.addView(infoButton(info, box, after = row, view = extra), Ui.lp(Ui.WRAP, Ui.WRAP, l = 6))
        var state = initial
        // Interrupteur : piste arrondie, pastille à gauche (non) ou à droite (oui).
        val sw = android.widget.FrameLayout(c)
        val knob = View(c)
        sw.addView(knob, Ui.flp(Ui.u(34), Ui.u(34), Gravity.CENTER_VERTICAL))
        sw.setPadding(Ui.u(5), 0, Ui.u(5), 0)
        fun paint() {
            sw.background = Ui.round(if (state) Ui.ACCENT else Ui.SURFACE3, 22f, if (state) Ui.ACCENT else Ui.STROKE, 1f)
            knob.background = Ui.round(if (state) Ui.ON_ACCENT else Ui.TEXT2, 17f)
            (knob.layoutParams as android.widget.FrameLayout.LayoutParams).gravity =
                Gravity.CENTER_VERTICAL or (if (state) Gravity.END else Gravity.START)
            knob.requestLayout()
            sw.contentDescription = if (state) tr("Oui") else tr("Non")
        }
        paint()
        val flip = { _: View -> state = !state; paint(); onChange(state) }
        sw.setOnClickListener(flip)
        row.setOnClickListener(flip)   // toute la ligne est touchable, pas seulement l'interrupteur
        row.minimumHeight = Ui.u(52)
        row.addView(sw, LinearLayout.LayoutParams(Ui.u(76), Ui.u(44)).apply { leftMargin = Ui.u(14) })
        if (box == null) return row
        box.addView(row)
        return box
    }

    private fun link(label: String, onClick: () -> Unit): LinearLayout {
        val row = Ui.horizontal(c)
        row.setPadding(0, Ui.u(14), 0, Ui.u(14))
        row.addView(Ui.text(c, label, 18f, Ui.TEXT), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        row.addView(Icon(c, Icon.Kind.CHEVRON, Ui.TEXT3), LinearLayout.LayoutParams(Ui.u(20), Ui.u(20)))
        row.background = Ui.pressable(Ui.round(0, 8f), 8f)
        row.isClickable = true
        row.setOnClickListener { onClick() }
        return row
    }

    /** Menu déroulant : la valeur choisie, et la liste qui s'ouvre en dessous. */
    private fun dropdown(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit): View {
        val box = Ui.horizontal(c)
        box.setPadding(Ui.u(16), 0, Ui.u(14), 0)
        box.background = Ui.pressable(Ui.round(Ui.SURFACE2, 16f, Ui.STROKE, 1f), 16f)
        var current = selected
        fun labelOf(code: String) = options.firstOrNull { it.first == code }?.second ?: options.first().second
        val label = Ui.text(c, labelOf(current), 19f, Ui.TEXT)
        box.addView(label, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        box.addView(Icon(c, Icon.Kind.CHEVRON, Ui.TEXT2).apply { rotation = 90f }, LinearLayout.LayoutParams(Ui.u(22), Ui.u(22)))
        box.isClickable = true
        // Poser le doigt hors de la liste la ferme, y compris sur la case elle-même, qui reçoit aussi cet
        // appui : il faut s'en souvenir, sinon le même appui la rouvre aussitôt.
        var shown: PopupWindow? = null
        var boxDownAt = 0L
        var closedByDownAt = -1L
        box.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) boxDownAt = e.downTime
            false
        }
        box.setOnClickListener {
            shown?.let {
                if (it.isShowing) {
                    it.dismiss()
                    return@setOnClickListener
                }
            }
            if (closedByDownAt == boxDownAt) {
                closedByDownAt = -1L
                return@setOnClickListener
            }
            val list = Ui.vertical(c)
            list.setPadding(0, Ui.u(6), 0, Ui.u(6))
            val scroll = ScrollView(c).apply {
                addView(list)
                background = Ui.round(Ui.SURFACE3, 16f, Ui.STROKE, 1f)
            }
            val height = if (options.size > 7) Ui.u(400) else ViewGroup.LayoutParams.WRAP_CONTENT
            val popup = PopupWindow(scroll, box.width, height, false)
            for ((code, name) in options) {
                val row = Ui.text(c, name, 19f, if (code == current) Ui.ACCENT else Ui.TEXT)
                row.setPadding(Ui.u(16), Ui.u(14), Ui.u(16), Ui.u(14))
                row.background = Ui.pressable(Ui.round(0, 0f), 0f)
                row.isClickable = true
                row.setOnClickListener {
                    popup.dismiss()
                    if (code != current) {
                        // Le choix s'affiche tout de suite (avant, il fallait rouvrir les réglages).
                        current = code
                        label.text = labelOf(code)
                        onPick(code)
                    }
                }
                list.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP))
            }
            popup.setBackgroundDrawable(ColorDrawable(0))
            popup.isOutsideTouchable = true
            popup.setTouchInterceptor { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) closedByDownAt = e.downTime
                false
            }
            popup.setOnDismissListener { if (shown === popup) shown = null }
            popup.elevation = Ui.uf(8f)
            popup.showAsDropDown(box, 0, Ui.u(6))
            shown = popup
        }
        return box
    }

    private lateinit var robotText: TextView

    private fun renderRobot() {
        val p = a.prefs.robot
        robotText.text = if (!a.prefs.robotConfirmed) tr("Pas encore choisi : la liaison avec la base reste fermée en « Machine réelle ».")
            else p.displayName + " (" + p.models.joinToString(", ") + ")" + if (p.tested) "" else
                "\n" + tr("En test : le projet ne l'a pas essayé lui-même, vos retours sont attendus sur GitHub.")
    }

    private fun renderModes() {
        modeRow.removeAllViews()
        for (m in Prefs.Mode.values()) {
            modeRow.addView(Ui.toggleChip(c, when (m) {
                Prefs.Mode.DEMO -> tr("Démo")
                Prefs.Mode.TWIN -> tr("Jumeau (PC)")
                Prefs.Mode.HARDWARE -> tr("Machine réelle")
            }, mode == m) { mode = m; renderModes() }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
        }
        twinBox.visibility = if (mode == Prefs.Mode.TWIN) View.VISIBLE else View.GONE
        linkText.text = when (mode) {
            Prefs.Mode.DEMO -> tr("Base moteur simulée : pour essayer l'appli sur n'importe quel appareil Android.")
            Prefs.Mode.TWIN -> tr("Jumeau numérique du SDK lancé sur un PC (tools/twin.py). Avec « adb reverse tcp:8765 tcp:8765 », gardez 127.0.0.1.")
            Prefs.Mode.HARDWARE -> tr("Pilote le vrai moteur par le port série. Exige le root. L'appli d'origine est coupée du moteur (réversible).")
        }
    }

    private fun applyMode() {
        if (a.snapshot.busy) {
            a.toast(tr("Arrêtez d'abord le programme en cours"), Ui.WARN)
            return
        }
        if (mode == Prefs.Mode.HARDWARE && !a.prefs.robotConfirmed) {
            // Jamais de trame vers la base sans savoir quel robot c'est.
            a.toast(tr("Choisissez d'abord votre robot"), Ui.WARN)
            a.askRobot()
            return
        }
        if (mode == Prefs.Mode.HARDWARE && a.prefs.mode != Prefs.Mode.HARDWARE) {
            a.confirm(
                tr("Piloter la vraie machine"),
                tr("Toque va prendre le contrôle du moteur et de la chauffe à la place de l'appli d'origine.\n\n" +
                    "Premier essai : bol vide, couvercle fermé, à portée de l'interrupteur principal. " +
                    "Si quoi que ce soit semble anormal, coupez l'interrupteur."),
                tr("Continuer"), danger = true,
            ) { prepareHardware() }
            return
        }
        save()
    }

    private fun save() {
        a.prefs.mode = mode
        a.prefs.bootLaunch = true
        a.prefs.twinHost = host.text.toString().trim().ifEmpty { "127.0.0.1" }
        a.prefs.twinPort = port.text.toString().trim().toIntOrNull() ?: 8765
        a.machine.reconfigure { v -> if (v.ok) a.toast(tr("Mode : %s", mode.label), Ui.OK) else a.refusal(v) }
        a.refreshTitle()
    }

    private fun prepareHardware() {
        a.toast(tr("Vérifications…"))
        Thread {
            val problems = ArrayList<String>()
            if (!RootShell.available()) problems += tr("accès root refusé")
            val sync = LidlSync(a, a.prefs, a.app.recipes, a.app.log)
            if (problems.isEmpty() && sync.installed()) {
                if (sync.components().hardwareService != null && !sync.setVendorHardware(false))
                    problems += tr("impossible de couper l'appli d'origine du moteur")
                sync.setVendorHome(false)
                sync.forceStop()
            }
            view.post {
                if (problems.isNotEmpty()) {
                    a.confirm(tr("Machine réelle indisponible"), problems.joinToString("\n") { "• $it" }, "OK", tr("Fermer")) {}
                } else save()
            }
        }.start()
    }

    private fun restoreVendor() {
        a.confirm(
            tr("Revenir à l'appli d'origine"),
            tr("Toque libère le moteur, rend l'accès à l'appli Monsieur Cuisine d'origine et passe en mode Démo. " +
                "Toque ne s'ouvrira plus au démarrage du robot."),
            tr("Revenir"),
        ) {
            a.prefs.mode = Prefs.Mode.DEMO
            a.prefs.bootLaunch = false
            a.machine.reconfigure {
                Thread {
                    val sync = LidlSync(a, a.prefs, a.app.recipes, a.app.log)
                    sync.setVendorHardware(true)
                    sync.setVendorHome(true)
                    val launch = sync.components().launch
                    view.post {
                        if (launch != null) a.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        else a.toast(tr("Appli d'origine introuvable"), Ui.WARN)
                    }
                }.start()
            }
        }
    }

    private fun sync(online: Boolean) {
        if (SyncProgress.running) return
        if (!online && a.prefs.mode != Prefs.Mode.HARDWARE) {
            a.toast(tr("Disponible une fois Toque installé sur le robot (mode Machine réelle)"), Ui.WARN)
            return
        }
        if (!online && !a.prefs.robot.stockImport) {
            // Base de l'appli d'origine du Smart : format pas encore relevé.
            a.toast(tr("Pas encore possible sur ce robot : utilisez « Synchroniser »"), Ui.WARN)
            return
        }
        // L'avancement vit dans SyncProgress, pas dans cet écran : on peut le quitter et y revenir.
        SyncProgress.start(tr("Synchronisation en cours… (quelques minutes)"), stoppable = online)
        val app = a.app
        Thread {
            val r = if (online) OnlineSync(app, app.prefs, app.recipes, app.log).sync()
            else try {
                LidlSync(app, app.prefs, app.recipes, app.log).importNow { SyncProgress.report(it) }
            } finally {
                SyncProgress.finished()
            }
            a.runOnUiThread { a.toast(r.message, if (r.ok) Ui.OK else Ui.WARN) }
        }.start()
    }

    private fun recipeFiles(export: Boolean) {
        a.withStorage { ok ->
            if (!ok) { a.toast(tr("Accès au dossier Download refusé"), Ui.WARN); return@withStorage }
            Thread {
                val files = RecipeFiles(a.app.recipes, a.app.log)
                val r = if (export) files.export() else files.import()
                view.post { a.toast(r.message, if (r.ok) Ui.OK else Ui.WARN) }
            }.start()
        }
    }

    private fun refreshTime(message: String? = null) {
        val zone = TimeSync.current()
        val mode = if (a.prefs.timeZone == ZoneLookup.AUTO) tr("automatique") else tr("choisi à la main")
        timeText.text = tr("Il est %s · fuseau %s (%s)", TimeSync.clock(), zone.replace('_', ' '), mode) +
            (message?.let { "\n" + it } ?: "")
    }

    private fun setClock() {
        if (settingTime) return
        settingTime = true
        timeText.text = tr("Réglage de l'heure…")
        Thread {
            val r = try {
                TimeSync(a, a.prefs, a.app.log).run()
            } catch (e: Exception) {
                TimeSync.Result(false, TimeSync.current(), e.message ?: e.toString())
            }
            a.runOnUiThread {
                settingTime = false
                refreshTime(if (r.ok) null else r.message)
                if (r.ok) a.toast(r.message, Ui.OK)
            }
        }.start()
    }

    private fun refreshSync() {
        val progress = SyncProgress.message
        val running = progress != null
        // Pendant une synchronisation : rien à relancer, et un bouton pour l'arrêter.
        Ui.setButtonEnabled(syncBtn, !running)
        importBtn?.let { Ui.setButtonEnabled(it, !running) }
        stopSyncBtn.visibility = if (running && SyncProgress.stoppable) View.VISIBLE else View.GONE
        Ui.setButtonEnabled(stopSyncBtn, !SyncProgress.stopRequested)
        val last = a.prefs.lastSyncResult
        val first = when {
            progress == null -> if (last.isEmpty()) tr("Jamais synchronisé") else tr("Dernière synchro le %s", last)
            SyncProgress.stopRequested -> tr("Arrêt en cours…")
            else -> progress
        }
        // Le nombre de recettes reste affiché sous l'avancement : « Recettes : 343 / 1667 » compte les fiches relues, pas le catalogue.
        val repo = a.app.recipes
        syncText.text = first + "\n" + if (repo.loaded) tr("%d recettes dans Toque", repo.count) +
            // Recettes enregistrées mais pas faisables sur ce robot (autre robot, programme absent).
            (if (repo.hiddenCount > 0) "\n" + tr("%d recettes masquées : pas faites pour ce robot", repo.hiddenCount) else "")
            else tr("Chargement des recettes…")
    }

    // ------------------------------------------------------------------ mises à jour

    private fun renderUpdate() {
        val r = release
        val installed = tr("Version installée : %s", updater.installedVersion)
        updText.setTextColor(if (upd == Upd.ERROR) Ui.WARN else if (upd == Upd.AVAILABLE) Ui.TEXT else Ui.TEXT2)
        updText.text = when (upd) {
            Upd.IDLE -> installed + "\n" + tr("Les nouvelles versions sont publiées sur GitHub.")
            Upd.CHECKING -> installed + "\n" + tr("Recherche sur GitHub…")
            Upd.UP_TO_DATE -> installed + "\n" + tr("Toque est à jour.")
            Upd.AVAILABLE -> installed + "\n" + tr("Nouvelle version disponible : %s", r?.version) +
                UpdateFlow.notes(r).let { if (it.isEmpty()) "" else "\n\n" + it }
            Upd.ERROR -> installed + "\n" + updMessage
        }
        updButtons.removeAllViews()
        when (upd) {
            Upd.IDLE, Upd.UP_TO_DATE, Upd.ERROR ->
                updButtons.addView(Ui.button(c, tr("Rechercher une mise à jour"), Ui.Style.PRIMARY, Icon.Kind.SYNC, height = 58) { checkUpdate() })
            Upd.AVAILABLE -> {
                updButtons.addView(Ui.button(c, tr("Installer la version %s", r?.version), Ui.Style.PRIMARY, Icon.Kind.CHECK, height = 58) { installUpdate() },
                    Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
                updButtons.addView(Ui.button(c, tr("Plus tard"), Ui.Style.GHOST, height = 58) { upd = Upd.IDLE; renderUpdate() })
            }
            Upd.CHECKING -> {}
        }
    }

    private fun checkUpdate() {
        if (upd == Upd.CHECKING) return
        upd = Upd.CHECKING
        renderUpdate()
        Thread {
            try {
                val r = updater.latest()
                view.post {
                    release = r
                    val newer = updater.isNewer(r)
                    a.updates.known(r, newer)
                    upd = if (newer) Upd.AVAILABLE else Upd.UP_TO_DATE
                    renderUpdate()
                    if (upd == Upd.AVAILABLE) installUpdate()
                }
            } catch (e: Updater.Failure) {
                view.post { upd = Upd.ERROR; updMessage = e.message ?: tr("Erreur"); renderUpdate() }
            } catch (e: Exception) {
                a.app.log.crash("maj", e)
                view.post { upd = Upd.ERROR; updMessage = tr("Erreur inattendue : %s", e.message); renderUpdate() }
            }
        }.start()
    }

    /** Propose l'installation dans une fenêtre (toujours visible, même si la carte est hors écran). */
    private fun installUpdate() {
        val r = release ?: return
        UpdateFlow.offer(a, r) { msg -> upd = Upd.ERROR; updMessage = msg; renderUpdate() }
    }

    /** Désabonnement du suivi des recettes (le nombre s'affiche une fois la base chargée). */
    private var unsubRecipes: (() -> Unit)? = null
    private var unsubSync: (() -> Unit)? = null

    override fun onShow() {
        mode = a.prefs.mode
        renderModes()
        renderRobot()
        refreshSync()
        // Changement de robot : les recettes sont refiltrées, puis cet écran se met à jour.
        unsubRecipes = a.app.recipes.onChange { renderRobot(); refreshSync() }
        unsubSync = SyncProgress.onChange { refreshSync() }
    }

    override fun onHide() {
        unsubRecipes?.invoke()
        unsubRecipes = null
        unsubSync?.invoke()
        unsubSync = null
    }

    override fun onSnapshot(s: Snapshot) {
        bowlText.text = if (s.hasSample) tr("Capteur du bol en ce moment : %s. Vérifiez qu'il change quand vous retirez le bol avant d'activer cette option.",
            if (s.bowlDetected) tr("bol détecté") else tr("bol NON détecté")) else tr("Base non connectée")
    }

    private companion object {
        /** Dernière rubrique ouverte : on y revient après un changement de thème ou de langue. */
        var lastSection = ""
    }
}
