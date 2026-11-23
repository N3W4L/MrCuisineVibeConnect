package app.toque.ui

import app.toque.core.i18n.tr
import app.toque.core.i18n.trKey
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Jeux de sons de l'interface : Classique (les sons d'origine de Toque), Moderne, Cyberpunk, Zen.
 * Tout est calculé (aucun fichier audio) puis gardé en mémoire : chaque son n'est calculé qu'une
 * fois. Les sons des jeux sont les mêmes que les aperçus écoutés sur PC (même calcul, 22 050 Hz).
 */
object SoundPacks {
    enum class Pack(val key: String, private val fr: String) {
        CLASSIC("classic", trKey("Classique")),
        MODERN("modern", trKey("Moderne")),
        CYBER("cyber", trKey("Cyberpunk")),
        ZEN("zen", trKey("Zen"));

        val label: String get() = tr(fr)

        companion object {
            fun of(k: String?) = values().firstOrNull { it.key == k } ?: CLASSIC
        }
    }

    enum class Event(private val fr: String) {
        START(trKey("Lancer")),
        PROGRAM_DONE(trKey("Fin de programme")),
        WEIGH(trKey("Pesée atteinte")),
        RECIPE_DONE(trKey("Fin de recette")),
        ALERT(trKey("Alerte")),
        CLICK(trKey("Clic"));

        val label: String get() = tr(fr)
    }

    private const val RATE = 22_050

    private enum class W { SOFT, BELL, MARIMBA, GLASS, ALERT, SAW, SQUARE, SINE, KALIMBA, BOWL, CHIME, WOOD }

    /** Une note dans un son : début (ms), durée (ms), fréquence, volume, décroissance, timbre et effets. */
    private class T(
        val at: Int, val ms: Int, val hz: Double, val gain: Double, val decay: Double, val w: W,
        val glideTo: Double = 0.0, val glideExp: Boolean = false, val fm: Double = 0.0, val fmHz: Double = 0.0,
        val detune: Double = 0.0, val crush: Int = 0, val attackMs: Int = 5,
    )

    /** Un son : ses notes, un écho éventuel, et son niveau final (le clic reste discret). */
    private class Spec(val tones: List<T>, val echoMs: Int = 0, val feedback: Double = 0.0, val repeats: Int = 0, val level: Double = 0.95)

    private val cache = HashMap<String, ShortArray>()
    private val lastClick = AtomicLong(0)

    /** Calcule à l'avance les sons d'un jeu (au choix du jeu), sur le fil des sons. */
    fun warmUp(pack: Pack) = Sounds.playLater(false) {
        for (e in Event.values()) pcm(pack, e)
        null
    }

    /** Ce jeu a-t-il ce son ? (Classique : pas de son au lancement, fin et alerte par les bips d'Android.) */
    fun has(pack: Pack, e: Event): Boolean = spec(pack, e) != null

    /** Joue le son [e] du jeu [pack] s'il existe. Les clics trop rapprochés (molette) sont ignorés. */
    fun play(pack: Pack, e: Event) {
        if (e == Event.CLICK) {
            val now = System.currentTimeMillis()
            if (now - lastClick.get() < 90) return
            lastClick.set(now)
        }
        if (spec(pack, e) == null) return
        Sounds.playLater(e == Event.ALERT) { pcm(pack, e) }
    }

    @Synchronized
    private fun pcm(pack: Pack, e: Event): ShortArray? {
        val key = pack.key + "/" + e.name
        cache[key]?.let { return it }
        val s = spec(pack, e) ?: return null
        return render(s).also { cache[key] = it }
    }

    // ------------------------------------------------------------------ les jeux

    private const val C5 = 523.25; private const val E5 = 659.25; private const val G5 = 783.99; private const val A5 = 880.0
    private const val C6 = 1046.5; private const val E6 = 1318.5; private const val G6 = 1567.98; private const val A6 = 1760.0
    private const val C7 = 2093.0; private const val D7 = 2349.3; private const val A4 = 440.0; private const val D4 = 293.66
    private const val G4 = 392.0; private const val D5 = 587.33; private const val B5 = 987.77; private const val D6 = 1174.66

    private fun spec(pack: Pack, e: Event): Spec? = when (pack) {
        Pack.CLASSIC -> when (e) {
            Event.CLICK -> Spec(listOf(T(0, 14, 2200.0, 1.0, 6.0, W.SOFT)), level = 0.3)
            else -> null   // sons d'origine : voir Sounds et MainActivity.chime
        }
        Pack.MODERN -> when (e) {
            Event.START -> Spec(listOf(T(0, 200, 520.0, 0.9, 4.0, W.SOFT, glideTo = 880.0)))
            Event.PROGRAM_DONE -> Spec(listOf(C6, E6, G6).mapIndexed { k, f -> T(k * 90, 650, f, 0.75, 5.0, W.MARIMBA) })
            Event.WEIGH -> Spec(listOf(T(0, 900, G6, 0.8, 4.5, W.GLASS), T(70, 1000, C7, 0.55, 4.5, W.GLASS)))
            Event.RECIPE_DONE -> Spec(
                listOf(G5, C6, E6).mapIndexed { k, f -> T(k * 150, 400, f, 0.8, 3.5, W.MARIMBA) } +
                    listOf(C6 to 0.55, E6 to 0.45, G6 to 0.45, C7 to 0.35).map { (f, g) -> T(450, 1500, f, g, 2.6, W.BELL) })
            Event.ALERT -> Spec((0..1).flatMap { r -> listOf(T(r * 520, 170, A5, 0.85, 1.5, W.ALERT), T(r * 520 + 200, 220, E5, 0.85, 1.5, W.ALERT)) })
            Event.CLICK -> Spec(listOf(T(0, 14, 2200.0, 1.0, 6.0, W.SOFT)), level = 0.3)
        }
        Pack.CYBER -> when (e) {
            Event.START -> Spec(listOf(
                T(0, 240, 180.0, 0.8, 2.5, W.SAW, glideTo = 880.0, glideExp = true, crush = 3),
                T(200, 90, A6, 0.45, 4.0, W.SINE, fm = 0.08, fmHz = 40.0)))
            Event.PROGRAM_DONE -> Spec(listOf(A5, C6, E6).mapIndexed { k, f -> T(k * 85, 260, f, 0.6, 4.0, W.SQUARE, detune = 0.006) },
                echoMs = 150, feedback = 0.35, repeats = 3)
            Event.WEIGH -> Spec(listOf(T(0, 70, A6, 0.55, 3.0, W.SQUARE, crush = 2), T(95, 160, D7, 0.55, 3.0, W.SQUARE, crush = 2)),
                echoMs = 110, feedback = 0.3, repeats = 2)
            Event.RECIPE_DONE -> Spec(
                listOf(A4, C5, E5, A5, C6, E6, A5, C6).mapIndexed { k, f -> T(k * 85, 200, f, 0.5, 4.5, W.SAW, detune = 0.004) } +
                    listOf(A4 to 0.4, C5 to 0.32, E5 to 0.32, A5 to 0.28).map { (f, g) -> T(680, 1500, f, g, 1.8, W.SAW, detune = 0.009) },
                echoMs = 170, feedback = 0.3, repeats = 2)
            Event.ALERT -> Spec((0..1).map { r -> T(r * 480, 360, 620.0, 0.85, 1.2, W.SQUARE, fm = 0.12, fmHz = 14.0, crush = 2) })
            Event.CLICK -> Spec(listOf(T(0, 10, 3000.0, 1.0, 5.0, W.SQUARE, crush = 3)), level = 0.3)
        }
        Pack.ZEN -> when (e) {
            Event.START -> Spec(listOf(T(0, 700, D5, 0.8, 4.0, W.KALIMBA, attackMs = 4), T(120, 900, A5, 0.7, 4.0, W.KALIMBA, attackMs = 4)), level = 0.9)
            Event.PROGRAM_DONE -> Spec(listOf(T(0, 2200, G4, 0.9, 2.2, W.BOWL, attackMs = 12)), level = 0.9)
            Event.WEIGH -> Spec(listOf(E6, A6, B5).mapIndexed { k, f -> T(k * 110, 1200, f, 0.5, 3.5, W.CHIME, attackMs = 6) }, level = 0.9)
            Event.RECIPE_DONE -> Spec(
                listOf(D5, E5, G5, A5, B5, D6).mapIndexed { k, f -> T(k * 170, 900, f, 0.65, 3.5, W.KALIMBA, attackMs = 4) } +
                    listOf(T(1020, 2600, D4, 0.8, 1.8, W.BOWL, attackMs = 15), T(1020, 2400, A4, 0.35, 2.0, W.BOWL, attackMs = 15)),
                level = 0.9)
            Event.ALERT -> Spec((0..2).flatMap { r -> listOf(T(r * 420, 220, A5, 0.9, 3.0, W.WOOD, attackMs = 4), T(r * 420 + 160, 260, E5, 0.9, 3.0, W.WOOD, attackMs = 4)) }, level = 0.9)
            Event.CLICK -> Spec(listOf(T(0, 40, 1400.0, 1.0, 6.0, W.WOOD, attackMs = 4)), level = 0.3)
        }
    }

    // ------------------------------------------------------------------ synthèse

    private fun osc(w: W, p: Double, k: Double, k3: Double): Double = when (w) {
        W.BELL -> sin(p) * 0.65 + sin(p * 2.76) * 0.25 * k + sin(p * 5.4) * 0.10 * k * k
        W.MARIMBA -> sin(p) * 0.85 + sin(p * 4.0) * 0.18 * k * k
        W.GLASS -> sin(p) * 0.7 + sin(p * 2.0) * 0.2 * k + sin(p * 3.0) * 0.1 * k * k
        W.ALERT -> sin(p) * 0.8 + sin(p * 3.0) * 0.15
        W.SAW -> (1..5).sumOf { h -> sin(p * h) / h } * 0.6
        W.SQUARE -> (sin(p) + sin(p * 3) / 3 + sin(p * 5) / 5 + sin(p * 7) / 7) * 0.75
        W.SINE -> sin(p)
        W.KALIMBA -> sin(p) * 0.85 + sin(p * 3.0) * 0.18 * k * k
        W.BOWL -> sin(p) * 0.55 + sin(p * 1.004) * 0.25 + sin(p * 2.71) * 0.15 * k3 + sin(p * 5.13) * 0.05 * k
        W.CHIME -> sin(p) * 0.75 + sin(p * 2.76) * 0.2 * k
        W.WOOD -> sin(p) * 0.8 + sin(p * 2.4) * 0.2 * k
        W.SOFT -> sin(p) * 0.85 + sin(p * 2.0) * 0.12
    }

    private fun render(s: Spec): ShortArray {
        val len = s.tones.maxOf { (it.at + it.ms) * RATE / 1000 } + RATE * 120 / 1000
        var buf = DoubleArray(len)
        for (t in s.tones) {
            val start = t.at * RATE / 1000
            val n = t.ms * RATE / 1000
            val attack = maxOf(1, RATE * t.attackMs / 1000)
            val release = RATE * 25 / 1000
            // Les timbres doux (zen) laissent leurs partiels un peu plus longtemps que les autres.
            val partialDecay = if (t.w == W.KALIMBA || t.w == W.BOWL || t.w == W.CHIME || t.w == W.WOOD) 7.0 else 6.0
            var p1 = 0.0
            var p2 = 0.0
            var held = 0.0
            for (i in 0 until n) {
                val time = i.toDouble() / RATE
                var f = t.hz
                if (t.glideTo > 0) {
                    f = if (t.glideExp) t.hz * (t.glideTo / t.hz).pow(minOf(1.0, i / (n * 0.6)))
                    else t.hz + (t.glideTo - t.hz) * minOf(1.0, i / (n * 0.45))
                }
                if (t.fm != 0.0) f *= 1 + t.fm * sin(2 * PI * t.fmHz * time)
                p1 += 2 * PI * f / RATE
                p2 += 2 * PI * f * (1 + t.detune) / RATE
                val k = exp(-partialDecay * i / n)
                val k3 = exp(-3.0 * i / n)
                var v = osc(t.w, p1, k, k3)
                if (t.detune != 0.0) v = (v + osc(t.w, p2, k, k3)) * 0.55
                val env = (if (i < attack) i.toDouble() / attack else 1.0) * exp(-t.decay * i / n) *
                    (if (i > n - release) (n - i).toDouble() / release else 1.0)
                v *= env * t.gain
                if (t.crush > 0) {
                    // Grain numérique : échantillon tenu et quantifié.
                    if (i % t.crush == 0) held = (v * 24).roundToInt() / 24.0
                    v = held
                }
                if (start + i < len) buf[start + i] += v
            }
        }
        if (s.repeats > 0) {
            val d = s.echoMs * RATE / 1000
            val out = DoubleArray(len + d * s.repeats)
            for (i in buf.indices) out[i] += buf[i]
            var g = 1.0
            for (r in 1..s.repeats) {
                g *= s.feedback
                for (i in buf.indices) out[i + d * r] += buf[i] * g
            }
            buf = out
        }
        val peak = buf.maxOf { abs(it) }.coerceAtLeast(1e-9)
        val scale = s.level / peak * 32_000
        return ShortArray(buf.size) { (buf[it] * scale).roundToInt().coerceIn(-32_767, 32_767).toShort() }
    }
}
