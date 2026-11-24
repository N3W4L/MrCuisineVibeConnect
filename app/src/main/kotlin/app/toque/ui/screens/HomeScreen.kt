package app.toque.ui.screens

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.TimeSync
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.core.recipes.UserRecipes
import app.toque.ui.Dial
import app.toque.ui.Icon
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.Theme
import app.toque.ui.Ui
import java.text.SimpleDateFormat
import java.util.Date

class HomeScreen(a: MainActivity) : Screen(a) {
    // Pas de titre sur l'accueil : la barre du haut ne garde que l'état de la machine et l'heure.
    override val title = ""
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var greeting: TextView
    private lateinit var dial: Dial
    private lateinit var recipesItem: Dial.Item
    private lateinit var favItem: Dial.Item
    private lateinit var settingsItem: Dial.Item
    private lateinit var resumeCard: LinearLayout
    private lateinit var resumeText: TextView
    private lateinit var knobChip: LinearLayout
    private var unsub: (() -> Unit)? = null
    private val tick = object : Runnable {
        override fun run() {
            a.switchAutoThemeIfDue()
            refreshClock()
            view.postDelayed(this, 10_000)
        }
    }

    override fun build(): View {
        val root = Ui.horizontal(c, Gravity.TOP)
        root.setPadding(Ui.u(28), Ui.u(10), Ui.u(24), Ui.u(24))

        val left = Ui.vertical(c)
        greeting = Ui.text(c, "", 32f, Ui.TEXT2, Ui.LIGHT)
        left.addView(greeting, Ui.lp(t = 8))
        clock = Ui.text(c, "", 132f, Ui.TEXT, Ui.THIN)
        left.addView(clock, Ui.lp(t = 2))
        date = Ui.text(c, "", 28f, Ui.TEXT2, Ui.LIGHT)
        left.addView(date, Ui.lp(t = 2))

        // Rappel tant que le bouton rotatif n'est ni réglé ni vu en marche.
        knobChip = Ui.chip(c, tr("Bouton rotatif à régler"), Icon.Kind.WARNING, Ui.WARN, Ui.WARN_DIM, 18f)
        knobChip.isClickable = true
        knobChip.setOnClickListener { a.push(KnobSetupScreen(a)) }
        left.addView(knobChip, Ui.lp(Ui.WRAP, Ui.WRAP, t = 12))

        val search = Ui.horizontal(c)
        search.setPadding(Ui.u(24), 0, Ui.u(24), 0)
        search.background = Ui.pressable(Ui.round(Ui.SURFACE, 36f, Ui.STROKE, 1f), 36f)
        search.addView(Icon(c, Icon.Kind.SEARCH, Ui.TEXT2), LinearLayout.LayoutParams(Ui.u(28), Ui.u(28)).apply { rightMargin = Ui.u(14) })
        search.addView(Ui.text(c, tr("Rechercher une recette…"), 21f, Ui.TEXT3))
        search.isClickable = true
        search.setOnClickListener { a.push(RecipesScreen(a, focusSearch = true)) }
        left.addView(search, Ui.lp(Ui.MATCH, Ui.u(72), t = 34))

        // Raccourcis sous la recherche : dernières recettes cuisinées, et les plus cuisinées.
        val shortcuts = Ui.horizontal(c)
        shortcuts.addView(Ui.button(c, tr("Récentes"), Ui.Style.SECONDARY, Icon.Kind.CLOCK, height = 56, textSize = 18f) {
            a.push(RecipesScreen(a, initialUsage = RecipesScreen.Usage.RECENT))
        }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { rightMargin = Ui.u(12) })
        shortcuts.addView(Ui.button(c, tr("Fréquentes"), Ui.Style.SECONDARY, Icon.Kind.FLAME, height = 56, textSize = 18f) {
            a.push(RecipesScreen(a, initialUsage = RecipesScreen.Usage.MOST))
        }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        left.addView(shortcuts, Ui.lp(Ui.MATCH, Ui.WRAP, t = 14))

        resumeCard = Ui.horizontal(c)
        resumeCard.setPadding(Ui.u(22), Ui.u(16), Ui.u(18), Ui.u(16))
        resumeCard.background = Ui.pressable(Ui.round(Ui.ACCENT_DIM, 24f), 24f)
        resumeCard.addView(Icon(c, Icon.Kind.PLAY, Ui.ACCENT), LinearLayout.LayoutParams(Ui.u(26), Ui.u(26)).apply { rightMargin = Ui.u(14) })
        resumeText = Ui.ellipsize(Ui.text(c, "", 19f, Ui.TEXT, Ui.MEDIUM), 1)
        resumeCard.addView(resumeText, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        // Zone de toucher plus grande que l'icône (au doigt, sur la carte).
        val cancel = android.widget.FrameLayout(c)
        cancel.addView(Icon(c, Icon.Kind.CLOSE, Ui.TEXT2), Ui.flp(Ui.u(24), Ui.u(24), Gravity.CENTER))
        cancel.background = Ui.pressable(Ui.round(0, 28f), 28f)
        cancel.isClickable = true
        cancel.contentDescription = tr("Abandonner la recette")
        cancel.setOnClickListener { abandonGuided() }
        resumeCard.addView(cancel, LinearLayout.LayoutParams(Ui.u(56), Ui.u(56)).apply { leftMargin = Ui.u(8) })
        resumeCard.isClickable = true
        resumeCard.setOnClickListener {
            val r = a.app.recipes.getAdjusted(a.prefs.guidedRecipe)
            if (r != null) a.push(CookScreen(a, r, a.prefs.guidedStep)) else { a.prefs.guidedRecipe = ""; refresh() }
        }
        left.addView(resumeCard, Ui.lp(Ui.MATCH, Ui.WRAP, t = 16))
        root.addView(left, LinearLayout.LayoutParams(0, Ui.MATCH, 0.42f).apply { rightMargin = Ui.u(28) })

        // Cadran : un secteur par fonction, piloté par le bouton rotatif (onKnob) ou au doigt.
        // Les couleurs du secteur choisi suivent la fonction quand le thème en donne (bento).
        recipesItem = Dial.Item(Icon.Kind.BOOK, tr("Recettes")) { a.push(RecipesScreen(a)) }.also { it.colors = tileColors(0) }
        favItem = Dial.Item(Icon.Kind.STAR, tr("Favoris")) { a.push(RecipesScreen(a, favorites = true)) }.also { it.colors = tileColors(4) }
        val scale = Dial.Item(Icon.Kind.SCALE, tr("Balance")) { a.push(ScaleScreen(a)) }.also {
            it.sub = tr("Peser au gramme"); it.colors = tileColors(3)
        }
        settingsItem = Dial.Item(Icon.Kind.SETTINGS, tr("Réglages")) { a.push(SettingsScreen(a)) }.also { it.colors = tileColors(5) }
        val programs = Dial.Item(Icon.Kind.KNEAD, tr("Programmes")) { a.push(ProgramsScreen(a)) }.also {
            it.sub = tr("Pétrir, vapeur, rissoler, nettoyage"); it.colors = tileColors(2)
        }
        val manual = Dial.Item(Icon.Kind.HAND, tr("Cuisson manuelle")) { a.push(ManualScreen(a)) }.also {
            it.sub = tr("Durée, température, vitesse"); it.colors = tileColors(1)
        }
        dial = Dial(c)
        dial.setItems(listOf(recipesItem, favItem, scale, settingsItem, programs, manual), tr("Tournez le bouton, appuyez pour ouvrir"))
        root.addView(dial, LinearLayout.LayoutParams(0, Ui.MATCH, 0.58f))
        return root
    }

    /** Fond et encre de la fonction [index] dans le thème en service (bento : une couleur par fonction), ou null. */
    private fun tileColors(index: Int): Pair<Int, Int>? = Theme.p.tiles.getOrNull(index)

    override fun onKnob(delta: Int): Boolean {
        dial.rotate(delta)
        return true
    }

    override fun onKnobPress(): Boolean {
        dial.open()
        return true
    }

    private fun refreshClock() {
        // Heure et date masquées tant qu'elles ne sont pas réglées (voir TimeSync.showClock) ; la
        // place reste réservée pour que l'accueil ne saute pas quand elles apparaissent.
        val show = TimeSync.showClock(a)
        clock.visibility = if (show) View.VISIBLE else View.INVISIBLE
        date.visibility = if (show) View.VISIBLE else View.INVISIBLE
        clock.text = MainActivity.now()
        date.text = SimpleDateFormat(tr("EEEE d MMMM"), I18n.locale).format(Date()).replaceFirstChar { it.uppercaseChar() }
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val hello = when (h) {
            in 5..11 -> tr("Bonjour")
            in 12..17 -> tr("Bon après-midi")
            else -> tr("Bonsoir")
        }
        val name = a.prefs.firstName
        greeting.text = if (name.isEmpty()) hello else tr("%s, %s", hello, name)
    }

    private fun refresh() {
        val repo = a.app.recipes
        val n = repo.count
        val perso = repo.index.entries.count { UserRecipes.isUser(it.id) }
        val lidl = n - perso
        recipesItem.sub = when {
            !repo.loaded -> tr("Chargement…")
            n == 0 -> tr("Aucune recette Lidl pour l'instant")
            else -> listOfNotNull(
                when (lidl) { 0 -> null; 1 -> tr("1 recette Lidl"); else -> tr("%d recettes Lidl", lidl) },
                when (perso) { 0 -> null; 1 -> tr("1 recette perso"); else -> tr("%d recettes perso", perso) },
            ).joinToString("\n")
        }
        val favs = repo.index.entries.count { it.favorite }
        favItem.sub = if (favs == 0) tr("Aucun favori") else if (favs == 1) tr("1 favori") else tr("%d favoris", favs)
        knobChip.visibility = if (a.prefs.knobReady) View.GONE else View.VISIBLE
        val update = a.updates.available
        // Une nouvelle version de Toque attend d'être installée : le cadran le dit sous « Réglages ».
        settingsItem.sub = if (update != null) tr("Version %s disponible", update.version) else tr("Écran, thème, langue")
        dial.refresh()
        val guided = a.prefs.guidedRecipe
        val title = if (guided.isEmpty()) null else repo.index.entries.firstOrNull { it.id == guided }?.title
        resumeCard.visibility = if (title != null) View.VISIBLE else View.GONE
        if (title != null) resumeText.text = tr("Reprendre « %s » — étape %d", title, a.prefs.guidedStep + 1)
    }

    override fun onShow() {
        refreshClock()
        refresh()
        unsub = a.app.recipes.onChange { refresh() }
        a.updates.onChange = { refresh() }
        view.removeCallbacks(tick)
        view.postDelayed(tick, 10_000)
    }

    override fun onHide() {
        unsub?.invoke()
        a.updates.onChange = null
        view.removeCallbacks(tick)
    }

    /** Retire la recette en cours de l'accueil (sans toucher à un programme qui tourne). */
    private fun abandonGuided() {
        if (a.snapshot.busy) {
            a.toast(tr("Arrêtez d'abord le programme en cours"), Ui.WARN)
            return
        }
        a.confirm(tr("Abandonner la recette"), tr("Elle ne sera plus proposée sur l'accueil. Vous pourrez la recommencer depuis les recettes."),
            tr("Abandonner"), icon = Icon.Kind.CLOSE) {
            a.prefs.guidedRecipe = ""
            a.prefs.guidedStep = 0
            refresh()
        }
    }
}
