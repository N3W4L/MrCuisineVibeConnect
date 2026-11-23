package app.toque.ui.screens

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import app.toque.Prefs
import app.toque.core.i18n.tr
import app.toque.core.machine.Snapshot
import app.toque.ui.Gauge
import app.toque.ui.Icon
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.Ui

/**
 * Balance intégrée : un grand cadran (le même que la pesée des recettes) qui se remplit jusqu'à la
 * capacité de la balance, l'état de la mesure, et la tare au doigt ou par appui sur le bouton rotatif.
 */
class ScaleScreen(a: MainActivity) : Screen(a) {
    override val title = tr("Balance")
    override val keepAwake: Boolean get() = true
    private lateinit var gauge: Gauge
    private lateinit var state: LinearLayout
    private var simulated = 0

    override fun build(): View {
        val root = Ui.horizontal(c, Gravity.CENTER_VERTICAL)
        root.setPadding(Ui.u(24), Ui.u(6), Ui.u(24), Ui.u(22))

        gauge = Gauge(c)
        root.addView(gauge, LinearLayout.LayoutParams(0, Ui.MATCH, 0.6f).apply { rightMargin = Ui.u(24) })

        val side = Ui.vertical(c)
        state = Ui.chip(c, "", Icon.Kind.SCALE, Ui.TEXT2, size = 20f)
        side.addView(state, Ui.lp(Ui.WRAP, Ui.WRAP))
        side.addView(Ui.text(c, tr("Posez le bol ou un récipient, appuyez sur Tare, puis versez."), 18f, Ui.TEXT2)
            .apply { setLineSpacing(0f, 1.15f) }, Ui.lp(t = 18))
        side.addView(Ui.spacer(c, weight = 1f), LinearLayout.LayoutParams(1, 0, 1f))
        if (a.prefs.mode == Prefs.Mode.DEMO) {
            // Mode Démo : poids simulés, pour essayer sans robot.
            val demo = Ui.horizontal(c)
            for (g in intArrayOf(100, 250)) {
                demo.addView(Ui.button(c, "+$g g", Ui.Style.GHOST, height = 56) {
                    simulated += g
                    a.machine.simulate(weight = simulated)
                }, LinearLayout.LayoutParams(0, Ui.u(56), 1f).apply { rightMargin = Ui.u(10) })
            }
            demo.addView(Ui.button(c, tr("Vider"), Ui.Style.GHOST, height = 56) {
                simulated = 0
                a.machine.simulate(weight = 0)
            }, LinearLayout.LayoutParams(0, Ui.u(56), 1f))
            side.addView(demo, Ui.lp(Ui.MATCH, Ui.WRAP, b = 14))
        }
        side.addView(Ui.button(c, tr("Tare (remise à zéro)"), Ui.Style.PRIMARY, Icon.Kind.SCALE, height = 88, textSize = 23f) {
            a.machine.tare()
        }, Ui.lp(Ui.MATCH, Ui.u(88)))
        side.addView(Ui.text(c, tr("Ou appuyez sur le bouton rotatif"), 15f, Ui.TEXT3).apply { gravity = Gravity.CENTER },
            Ui.lp(Ui.MATCH, Ui.WRAP, t = 8))
        root.addView(side, LinearLayout.LayoutParams(0, Ui.MATCH, 0.4f))
        onSnapshot(a.snapshot)
        return root
    }

    override fun onSnapshot(s: Snapshot) {
        val ticks = (0..5).map { it / 5f to if (it == 5) "5 kg" else "$it" }
        if (!s.hasSample) {
            gauge.set(0f, 0f, 1f, Ui.TEXT3, "—", "", tr("Base non connectée"), ticks)
            Ui.chipText(state).text = tr("Base non connectée")
            return
        }
        val (txt, col) = when {
            s.scaleState == "overload" -> tr("Surcharge") to Ui.DANGER
            s.scaleStable -> tr("Stable") to Ui.OK
            else -> tr("Mesure…") to Ui.TEXT2
        }
        // Cadran rempli jusqu'à la capacité de la balance (5 kg) ; rouge en surcharge.
        gauge.set(s.weightG / CAPACITY_G, 0f, 1f, if (col == Ui.DANGER) Ui.DANGER else Ui.ACCENT,
            "${s.weightG}", "g", txt, ticks)
        Ui.chipText(state).text = txt
        Ui.chipText(state).setTextColor(col)
        (state.getChildAt(0) as Icon).color = col
    }

    override fun onKnobPress(): Boolean {
        a.machine.tare()
        return true
    }

    private companion object {
        const val CAPACITY_G = 5000f
    }
}
