package app.toque.ui

import app.toque.core.machine.RootShell
import java.util.concurrent.Executors

/**
 * Rétroéclairage de l'écran, par le root : en veille, l'écran s'éteint vraiment (0) au lieu de
 * rester gris au minimum. Le tactile reste actif : le premier toucher réveille. La valeur d'avant
 * est gardée dans un fichier et remise au réveil ; au redémarrage, Android la remet de lui-même.
 */
object Backlight {
    private const val NODE = "/sys/class/leds/lcd-backlight/brightness"
    private const val SAVED = "/data/local/tmp/toque-backlight.saved"

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "toque-ecran").apply { isDaemon = true } }
    @Volatile var off = false
        private set

    fun turnOff() {
        if (off) return
        off = true
        worker.execute {
            RootShell.run("[ -f $SAVED ] || cat $NODE > $SAVED; echo 0 > $NODE", 8_000)
        }
    }

    fun turnOn() {
        if (!off) return
        off = false
        worker.execute {
            RootShell.run(
                "if [ -f $SAVED ]; then cat $SAVED > $NODE; rm -f $SAVED; fi; v=$(cat $NODE); [ \"${'$'}v\" -gt 0 ] || echo 120 > $NODE; true",
                8_000,
            )
        }
    }
}
