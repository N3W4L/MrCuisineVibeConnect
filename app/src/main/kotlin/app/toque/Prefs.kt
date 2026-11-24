package app.toque

import android.content.Context
import android.content.SharedPreferences
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.core.i18n.trKey
import app.toque.core.robot.RobotProfile
import app.toque.core.robot.RobotProfiles

/**
 * Réglages persistants, lisibles par les deux processus (interface et machine).
 * Écriture synchrone (commit) : le processus machine relit le fichier juste après.
 */
class Prefs(context: Context) {
    @Suppress("DEPRECATION")
    private val p: SharedPreferences = context.getSharedPreferences("toque", Context.MODE_MULTI_PROCESS)

    enum class Mode(val key: String, private val fr: String) {
        DEMO("demo", trKey("Démo (base simulée)")),
        TWIN("twin", trKey("Jumeau numérique (PC)")),
        HARDWARE("hardware", trKey("Machine réelle"));

        val label: String get() = tr(fr)

        companion object {
            fun of(k: String?) = values().firstOrNull { it.key == k } ?: DEMO
        }
    }

    var mode: Mode
        get() = Mode.of(p.getString("mode", null))
        set(v) {
            p.edit().putString("mode", v.key).commit()
        }

    /** Langue de l'interface : « auto » (celle du système), « fr » ou « en ». */
    var language: String
        get() = p.getString("language", I18n.AUTO) ?: I18n.AUTO
        set(v) {
            p.edit().putString("language", v).commit()
        }

    /** Thème de l'interface (voir ui.Theme.Id) : « bento » par défaut. */
    var theme: String
        get() = p.getString("theme", "bento") ?: "bento"
        set(v) {
            p.edit().putString("theme", v).commit()
        }

    /** Prénom affiché après la salutation de l'accueil (« Bonsoir, Camille ») ; vide = pas de prénom. */
    var firstName: String
        get() = p.getString("firstName", "") ?: ""
        set(v) {
            // apply : écrit en arrière-plan, le champ est enregistré à chaque lettre tapée.
            p.edit().putString("firstName", v.trim()).apply()
        }

    /** Sombre, clair ou automatique (voir ui.Theme.Mode). */
    var themeMode: String
        get() = p.getString("themeMode", "dark") ?: "dark"
        set(v) {
            p.edit().putString("themeMode", v).commit()
        }

    var twinHost: String
        get() = p.getString("twinHost", "127.0.0.1") ?: "127.0.0.1"
        set(v) {
            p.edit().putString("twinHost", v).commit()
        }

    var twinPort: Int
        get() = p.getInt("twinPort", 8765)
        set(v) {
            p.edit().putInt("twinPort", v).commit()
        }

    /**
     * Robot confirmé par la personne (identifiant de [RobotProfiles]) ; vide tant qu'elle ne l'a pas
     * confirmé : la liaison avec la base reste alors fermée en « Machine réelle ».
     */
    var robotId: String
        get() = p.getString("robotId", "") ?: ""
        set(v) {
            p.edit().putString("robotId", v).commit()
        }

    val robotConfirmed: Boolean get() = RobotProfiles.byId(robotId) != null

    /** Fiche du robot choisi (le Connect tant que rien n'est confirmé, comme avant les fiches). */
    val robot: RobotProfile get() = RobotProfiles.byId(robotId) ?: RobotProfiles.MC_CONNECT

    /** Port série : réglage manuel sur le Connect (ancien réglage), sinon celui de la fiche. */
    var serialDevice: String
        get() = (if (robot === RobotProfiles.MC_CONNECT) p.getString("serialDevice", null) else null) ?: robot.serialDevice
        set(v) {
            p.edit().putString("serialDevice", v).commit()
        }

    /** Appli d'origine : réglage manuel sur le Connect (ancien réglage), sinon celle de la fiche. */
    var vendorPackage: String
        get() = (if (robot === RobotProfiles.MC_CONNECT) p.getString("vendorPackage", null) else null) ?: robot.vendorPackage
        set(v) {
            p.edit().putString("vendorPackage", v).commit()
        }

    var requireBowl: Boolean
        get() = p.getBoolean("requireBowl", false)
        set(v) {
            p.edit().putBoolean("requireBowl", v).commit()
        }

    /** Capteur du bol lu à l'envers (bases qui signalent « bol » quand il est retiré). */
    var invertBowl: Boolean
        get() = p.getBoolean("invertBowl", false)
        set(v) {
            p.edit().putBoolean("invertBowl", v).commit()
        }

    /** Montée progressive de la vitesse quand le contenu est chaud (≥ 60 °C). */
    var hotRamp: Boolean
        get() = p.getBoolean("hotRamp", true)
        set(v) {
            p.edit().putBoolean("hotRamp", v).commit()
        }

    /** Recettes : enchaîner sans appui les étapes machine qui se suivent sans geste à faire. */
    var chainSteps: Boolean
        get() = p.getBoolean("chainSteps", true)
        set(v) {
            p.edit().putBoolean("chainSteps", v).commit()
        }

    var sound: Boolean
        get() = p.getBoolean("sound", true)
        set(v) {
            p.edit().putBoolean("sound", v).commit()
        }

    /** Volume de tous les sons de Toque, de 5 à 100 %. */
    var soundVolume: Int
        get() = p.getInt("soundVolume", 80).coerceIn(5, 100)
        set(v) {
            p.edit().putInt("soundVolume", v.coerceIn(5, 100)).commit()
        }

    /** Jeu de sons (voir ui.SoundPacks.Pack) : « classic » par défaut, les sons d'origine. */
    var soundPack: String
        get() = p.getString("soundPack", "classic") ?: "classic"
        set(v) {
            p.edit().putString("soundPack", v).commit()
        }

    /** Petit clic à chaque cran de la molette et à chaque bouton touché. */
    var clickSound: Boolean
        get() = p.getBoolean("clickSound", false)
        set(v) {
            p.edit().putBoolean("clickSound", v).commit()
        }

    /** Mise en veille de l'écran après ce nombre de minutes sans geste (0 : jamais). */
    var standbyMinutes: Int
        get() = p.getInt("standbyMinutes", 10)
        set(v) {
            p.edit().putInt("standbyMinutes", v).commit()
        }

    /**
     * Molette pendant la veille : « normal » (inchangée), « dim » (veilleuse blanche faible) ou
     * « off » (éteinte). Reprend l'ancien interrupteur « standbyKnobLight » s'il a été réglé.
     */
    var standbyKnob: String
        get() = p.getString("standbyKnob", null)
            ?: if (p.getBoolean("standbyKnobLight", true)) "dim" else "normal"
        set(v) {
            p.edit().putString("standbyKnob", v).commit()
        }

    /** Fuseau horaire : « auto » (d'après la connexion Internet) ou un identifiant IANA choisi à la main. */
    var timeZone: String
        get() = p.getString("timeZone", "auto") ?: "auto"
        set(v) {
            p.edit().putString("timeZone", v).commit()
        }

    /** Dernier fuseau appliqué avec succès (affiché dans les réglages). */
    var lastZone: String
        get() = p.getString("lastZone", "") ?: ""
        set(v) {
            p.edit().putString("lastZone", v).commit()
        }

    var autoSync: Boolean
        get() = p.getBoolean("autoSync", true)
        set(v) {
            p.edit().putBoolean("autoSync", v).commit()
        }

    var lastSyncMs: Long
        get() = p.getLong("lastSyncMs", 0)
        set(v) {
            p.edit().putLong("lastSyncMs", v).commit()
        }

    var lastSyncResult: String
        get() = p.getString("lastSyncResult", "") ?: ""
        set(v) {
            p.edit().putString("lastSyncResult", v).commit()
        }

    var vendorSession: Boolean
        get() = p.getBoolean("vendorSession", false)
        set(v) {
            p.edit().putBoolean("vendorSession", v).commit()
        }

    /** Bouton « Retour à Toque » à afficher : Toque a ouvert une autre appli (robot sans bouton Accueil). */
    var returnBubble: Boolean
        get() = p.getBoolean("returnBubble", false)
        set(v) {
            p.edit().putBoolean("returnBubble", v).commit()
        }

    /** Ouvrir Toque au démarrage du robot (désactivé par « Revenir à l'appli d'origine »). */
    var bootLaunch: Boolean
        get() = p.getBoolean("bootLaunch", true)
        set(v) {
            p.edit().putBoolean("bootLaunch", v).commit()
        }

    var knobLeft: Int
        get() = p.getInt("knobLeft", -1)
        set(v) {
            p.edit().putInt("knobLeft", v).commit()
        }

    var knobRight: Int
        get() = p.getInt("knobRight", -1)
        set(v) {
            p.edit().putInt("knobRight", v).commit()
        }

    var knobPress: Int
        get() = p.getInt("knobPress", -1)
        set(v) {
            p.edit().putInt("knobPress", v).commit()
        }

    /** Vrai une fois le bouton rotatif réglé, vu en marche, ou déclaré absent de l'appareil. */
    var knobSetupDone: Boolean
        get() = p.getBoolean("knobSetupDone", false)
        set(v) {
            p.edit().putBoolean("knobSetupDone", v).commit()
        }

    /** Le réglage guidé n'est proposé d'office qu'une fois ; ensuite, l'accueil le rappelle. */
    var knobSetupShown: Boolean
        get() = p.getBoolean("knobSetupShown", false)
        set(v) {
            p.edit().putBoolean("knobSetupShown", v).commit()
        }

    /** Faux tant que rien ne dit que le bouton rotatif fonctionne : l'accueil affiche alors un rappel. */
    val knobReady: Boolean get() = knobSetupDone || knobLeft >= 0 || knobRight >= 0 || knobPress >= 0

    var lastCrashMs: Long
        get() = p.getLong("lastCrashMs", 0)
        set(v) {
            p.edit().putLong("lastCrashMs", v).commit()
        }

    var welcomeDone: Boolean
        get() = p.getBoolean("welcomeDone", false)
        set(v) {
            p.edit().putBoolean("welcomeDone", v).commit()
        }

    var builtinVersion: Int
        get() = p.getInt("builtinVersion", 0)
        set(v) {
            p.edit().putInt("builtinVersion", v).commit()
        }

    /** Recette guidée en cours (id + étape), pour reprendre après un redémarrage. */
    var guidedRecipe: String
        get() = p.getString("guidedRecipe", "") ?: ""
        set(v) {
            p.edit().putString("guidedRecipe", v).commit()
        }

    var guidedStep: Int
        get() = p.getInt("guidedStep", 0)
        set(v) {
            p.edit().putInt("guidedStep", v).commit()
        }

    /** Réglages importés déjà vérifiés (« J'ai vérifié ce réglage »), clé = recette, étape et réglage. */
    var reviewedSteps: Set<String>
        get() = HashSet(p.getStringSet("reviewedSteps", emptySet()) ?: emptySet())
        set(v) {
            p.edit().putStringSet("reviewedSteps", HashSet(v)).commit()
        }

    /** Son joué quand la quantité à peser est atteinte (voir Sounds.WEIGH_SOUNDS). */
    var weighSound: String
        get() = p.getString("weighSound", "ding") ?: "ding"
        set(v) {
            p.edit().putString("weighSound", v).commit()
        }
    /** Prévenir quand une nouvelle version de Toque est publiée (au démarrage et au réveil). */
    var updateAlerts: Boolean
        get() = p.getBoolean("updateAlerts", true)
        set(v) {
            p.edit().putBoolean("updateAlerts", v).commit()
        }

    var lastUpdateCheckMs: Long
        get() = p.getLong("lastUpdateCheckMs", 0)
        set(v) {
            p.edit().putLong("lastUpdateCheckMs", v).commit()
        }

    /** Dernière version proposée par l'alerte, et quand (« Plus tard » ne la repropose pas tout de suite). */
    var updateAskedVersion: String
        get() = p.getString("updateAskedVersion", "") ?: ""
        set(v) {
            p.edit().putString("updateAskedVersion", v).commit()
        }

    var updateAskedMs: Long
        get() = p.getLong("updateAskedMs", 0)
        set(v) {
            p.edit().putLong("updateAskedMs", v).commit()
        }

    /** Ajouter les recettes en ligne publiées seulement dans une autre langue. */
    var foreignRecipes: Boolean
        get() = p.getBoolean("foreignRecipes", false)
        set(v) {
            p.edit().putBoolean("foreignRecipes", v).commit()
        }

    /**
     * Langue du catalogue Lidl : « auto » (d'après l'interface ; le catalan → espagnol) ou un code
     * du site (es-ES, fr-FR, …). Indépendant de la langue de l'interface.
     */
    var recipeLanguage: String
        get() = p.getString("recipeLanguage", "auto") ?: "auto"
        set(v) {
            p.edit().putString("recipeLanguage", v).commit()
        }

}
