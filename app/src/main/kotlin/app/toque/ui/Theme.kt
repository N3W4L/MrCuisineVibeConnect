package app.toque.ui

import android.graphics.Typeface
import app.toque.core.i18n.tr
import app.toque.core.i18n.trKey
import java.util.Calendar

/**
 * Thèmes de l'interface : chacun existe en sombre et en clair. Le choix est lu au démarrage de
 * l'activité (voir MainActivity.onCreate) ; en changer reconstruit l'interface, comme la langue.
 * Le processus machine n'est jamais concerné : une cuisson continue pendant un changement de thème.
 *
 * Les couleurs de sécurité gardent leur sens partout : OK = prête, WARN = attention, DANGER = alerte.
 */
object Theme {
    enum class Id(val key: String, private val fr: String) {
        GLASS("glass", trKey("Verre liquide")),
        BENTO("bento", trKey("Bento expressif")),
        WARM("warm", trKey("Minimal chaleureux"));

        val label: String get() = tr(fr)

        companion object {
            fun of(k: String?) = values().firstOrNull { it.key == k } ?: BENTO
        }
    }

    /** Sombre, clair, ou automatique selon l'heure (le robot n'a ni capteur de lumière ni mode sombre système). */
    enum class Mode(val key: String, private val fr: String) {
        DARK("dark", trKey("Sombre")),
        LIGHT("light", trKey("Clair")),
        AUTO("auto", trKey("Automatique (clair le jour, sombre le soir)"));

        val label: String get() = tr(fr)

        companion object {
            fun of(k: String?) = values().firstOrNull { it.key == k } ?: DARK
        }
    }

    enum class Face { SANS, SERIF }

    class Palette(
        val light: Boolean,
        val bg: Int,
        val gradient: IntArray,
        val surface: Int,
        val surface2: Int,
        val surface3: Int,
        val stroke: Int,
        val text: Int,
        val text2: Int,
        val text3: Int,
        val accent: Int,
        val onAccent: Int,
        val second: Int,
        val cool: Int,
        val onCool: Int,
        val ok: Int,
        val warn: Int,
        val danger: Int,
        val faultBg: Int,
        /** Arrondis : multiplie tous les rayons. */
        val corners: Float = 1f,
        /** Police des grands chiffres (horloge, minuteur, jauges). */
        val display: Face = Face.SANS,
        /** Couleur de fond et d'encre des six tuiles de l'accueil (null = tuile normale). */
        val tiles: List<Pair<Int, Int>?> = emptyList(),
        /** Deux grandes formes colorées derrière l'interface (verre liquide). */
        val blobs: IntArray? = null,
    ) {
        val scrim: Int get() = if (light) 0x99000000.toInt() else 0xCC000000.toInt()
        val ripple: Int get() = if (light) 0x1F000000 else 0x33FFFFFF
    }

    private fun c(v: Long): Int = v.toInt()

    private val GLASS_DARK = Palette(
        light = false, bg = c(0xFF1B1F3B), gradient = intArrayOf(c(0xFF232850), c(0xFF1B1F3B), c(0xFF141730)),
        // Surfaces translucides (pas de flou : trop lourd pour Android 6) ; celles des fenêtres restent opaques.
        surface = c(0x1FFFFFFF), surface2 = c(0xFF2A2F55), surface3 = c(0xFF363C68), stroke = c(0x38FFFFFF),
        text = c(0xFFFFFFFF), text2 = c(0xFFB8BCD6), text3 = c(0xFF8187AD),
        accent = c(0xFF7CC4FF), onAccent = c(0xFF04263D), second = c(0xFFC9A7FF),
        cool = c(0xFF6FE0D0), onCool = c(0xFF032B26),
        ok = c(0xFF5BE3A0), warn = c(0xFFFFD166), danger = c(0xFFFF6B81), faultBg = c(0xFF4A1A2C),
        corners = 1.15f, blobs = intArrayOf(c(0xFF3B2C6B), c(0xFF0E4D6B)),
    )
    private val GLASS_LIGHT = Palette(
        light = true, bg = c(0xFFE9ECF8), gradient = intArrayOf(c(0xFFF1F3FB), c(0xFFE9ECF8), c(0xFFDFE3F4)),
        surface = c(0x99FFFFFF), surface2 = c(0xFFF7F8FD), surface3 = c(0xFFD6DCF1), stroke = c(0xFFC3CAE6),
        text = c(0xFF1B1F3B), text2 = c(0xFF4F557A), text3 = c(0xFF7C82A6),
        accent = c(0xFF2F6FD6), onAccent = c(0xFFFFFFFF), second = c(0xFF7A4FD6),
        cool = c(0xFF0E8C9A), onCool = c(0xFFFFFFFF),
        ok = c(0xFF138A57), warn = c(0xFFA56A00), danger = c(0xFFD0344F), faultBg = c(0xFFFBE1E7),
        corners = 1.15f, blobs = intArrayOf(c(0xFFC9B8F2), c(0xFFA8DDF0)),
    )
    private val BENTO_DARK = Palette(
        light = false, bg = c(0xFF111113), gradient = intArrayOf(c(0xFF151517), c(0xFF111113), c(0xFF0C0C0E)),
        surface = c(0xFF1E1E22), surface2 = c(0xFF26262B), surface3 = c(0xFF303036), stroke = c(0xFF2E2E35),
        text = c(0xFFF5F5F7), text2 = c(0xFFA1A1A8), text3 = c(0xFF6E6E77),
        accent = c(0xFFC6F432), onAccent = c(0xFF1F2A00), second = c(0xFFFF7AB6),
        cool = c(0xFF7C8CFF), onCool = c(0xFF0D1240),
        ok = c(0xFF5EE6A0), warn = c(0xFFFFB547), danger = c(0xFFFF5C6C), faultBg = c(0xFF3A1418),
        tiles = listOf(
            c(0xFFC6F432) to c(0xFF1F2A00), c(0xFFFF7AB6) to c(0xFF3A0020), c(0xFF7C8CFF) to c(0xFF0D1240),
            c(0xFFFFB547) to c(0xFF3A2200), c(0xFF5EE6D0) to c(0xFF00302A), null,
        ),
    )
    private val BENTO_LIGHT = Palette(
        light = true, bg = c(0xFFF2F2F4), gradient = intArrayOf(c(0xFFF7F7F9), c(0xFFF2F2F4), c(0xFFEAEAEE)),
        surface = c(0xFFFFFFFF), surface2 = c(0xFFF4F4F6), surface3 = c(0xFFE7E7EB), stroke = c(0xFFE2E2E6),
        text = c(0xFF111113), text2 = c(0xFF5C5C66), text3 = c(0xFF8A8A94),
        accent = c(0xFF5E8C00), onAccent = c(0xFFFFFFFF), second = c(0xFFB8326E),
        cool = c(0xFF4F5BD5), onCool = c(0xFFFFFFFF),
        ok = c(0xFF138A57), warn = c(0xFFA56A00), danger = c(0xFFD0344F), faultBg = c(0xFFFBE1E5),
        tiles = listOf(
            c(0xFFC6F432) to c(0xFF1F2A00), c(0xFFFFB3D6) to c(0xFF3A0020), c(0xFFB4BCFF) to c(0xFF0D1240),
            c(0xFFFFD08A) to c(0xFF3A2200), c(0xFFA6F0E3) to c(0xFF00302A), null,
        ),
    )
    private val WARM_DARK = Palette(
        light = false, bg = c(0xFF1E1814), gradient = intArrayOf(c(0xFF241D18), c(0xFF1E1814), c(0xFF17120F)),
        surface = c(0xFF2A221C), surface2 = c(0xFF332A23), surface3 = c(0xFF3E332B), stroke = c(0xFF4A3D33),
        text = c(0xFFF4EEE6), text2 = c(0xFFC2B3A5), text3 = c(0xFF8E7F72),
        accent = c(0xFFE07A5F), onAccent = c(0xFF2B0F07), second = c(0xFF8FB08A),
        cool = c(0xFF7FB3C8), onCool = c(0xFF0F2630),
        ok = c(0xFF8FC48A), warn = c(0xFFE9B949), danger = c(0xFFE5534B), faultBg = c(0xFF3E1A16),
        corners = 0.8f, display = Face.SERIF,
        tiles = listOf(c(0xFFE07A5F) to c(0xFF2B0F07), null, null, null, null, null),
    )
    private val WARM_LIGHT = Palette(
        light = true, bg = c(0xFFF4EEE6), gradient = intArrayOf(c(0xFFF8F3EC), c(0xFFF4EEE6), c(0xFFEDE4D8)),
        surface = c(0xFFFFFFFF), surface2 = c(0xFFFAF6F0), surface3 = c(0xFFEDE4D8), stroke = c(0xFFE6DCCF),
        text = c(0xFF2B211B), text2 = c(0xFF6B5B4F), text3 = c(0xFF9A8A7C),
        accent = c(0xFFC8553D), onAccent = c(0xFFFFFFFF), second = c(0xFF5E7D5A),
        cool = c(0xFF3F7891), onCool = c(0xFFFFFFFF),
        ok = c(0xFF4E7D4A), warn = c(0xFFA8690F), danger = c(0xFFB8322A), faultBg = c(0xFFF8E0DB),
        corners = 0.8f, display = Face.SERIF,
        tiles = listOf(c(0xFFC8553D) to c(0xFFFFFFFF), null, null, null, null, null),
    )

    /** Palette en service. Par défaut le bento en sombre. */
    var p: Palette = BENTO_DARK
        private set
    var id: Id = Id.BENTO
        private set
    var mode: Mode = Mode.DARK
        private set

    fun palette(id: Id, light: Boolean): Palette = when (id) {
        Id.GLASS -> if (light) GLASS_LIGHT else GLASS_DARK
        Id.BENTO -> if (light) BENTO_LIGHT else BENTO_DARK
        Id.WARM -> if (light) WARM_LIGHT else WARM_DARK
    }

    /** Heures du mode automatique : clair de [dayStart] h (incluse) à [dayEnd] h (exclue). */
    var dayStart = 7
    var dayEnd = 20

    fun lightNow(mode: Mode): Boolean = when (mode) {
        Mode.DARK -> false
        Mode.LIGHT -> true
        Mode.AUTO -> Calendar.getInstance().get(Calendar.HOUR_OF_DAY).let { it >= dayStart && it < dayEnd }
    }

    fun select(themeKey: String, modeKey: String) {
        id = Id.of(themeKey)
        mode = Mode.of(modeKey)
        p = palette(id, lightNow(mode))
    }

    /** Vrai quand le mode automatique doit passer de clair à sombre (ou l'inverse). */
    fun autoSwitchDue(): Boolean = mode == Mode.AUTO && lightNow(mode) != p.light

    // Polices du système (aucun fichier ajouté à l'APK), créées une seule fois.
    private val SERIF: Typeface by lazy { Typeface.create("serif", Typeface.NORMAL) }

    fun face(face: Face, fallback: Typeface): Typeface = when (face) {
        Face.SANS -> fallback
        Face.SERIF -> SERIF
    }
}
