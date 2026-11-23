package app.toque.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Chargement d'images économe en mémoire (sous-échantillonnage + cache) pour 1 Go de RAM.
 *
 * Les petites tailles (vignettes de la liste, photo de la fiche) sont gardées sur disque, réduites à
 * la taille affichée, dans images/thumbs : la photo d'origine (souvent 1 000 px et plus) n'est décodée
 * qu'une fois ; ensuite on relit une petite image, plus vite et avec moins de mémoire.
 */
object ImageLoader {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8 / 1024).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val exec = Executors.newFixedThreadPool(2) { r -> Thread(r, "toque-images").apply { priority = Thread.MIN_PRIORITY } }
    private val main = Handler(Looper.getMainLooper())

    /** Au-delà, pas de vignette sur disque : on décode directement la photo. */
    private const val MAX_THUMB = 640

    fun load(view: ImageView, file: File?, targetWidth: Int) {
        view.tag = file?.absolutePath
        view.setImageDrawable(null)
        if (file == null) return
        val key = file.absolutePath + "@" + targetWidth
        cache.get(key)?.let { view.setImageBitmap(it); return }
        exec.execute {
            // Liste défilée vite : la case a déjà changé d'image, inutile de décoder celle-ci.
            if (view.tag != file.absolutePath) return@execute
            val bmp = decode(file, targetWidth) ?: return@execute
            cache.put(key, bmp)
            main.post { if (view.tag == file.absolutePath) view.setImageBitmap(bmp) }
        }
    }

    private fun decode(file: File, targetWidth: Int): Bitmap? {
        if (targetWidth > MAX_THUMB) return decodeSampled(file, targetWidth)
        val thumb = File(file.parentFile, "thumbs/${file.name}.$targetWidth.jpg")
        if (thumb.isFile && thumb.lastModified() >= file.lastModified()) {
            decodeSampled(thumb, targetWidth)?.let { return it }
        }
        val full = decodeSampled(file, targetWidth) ?: return null
        // Réduite à la largeur affichée : moins de mémoire dans le cache, et une vignette légère sur disque.
        val small = if (full.width > targetWidth * 5 / 4) {
            Bitmap.createScaledBitmap(full, targetWidth, maxOf(1, full.height * targetWidth / full.width), true)
                .also { if (it !== full) full.recycle() }
        } else full
        try {
            thumb.parentFile?.mkdirs()
            val tmp = File(thumb.path + ".part")
            FileOutputStream(tmp).use { small.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            if (!tmp.renameTo(thumb)) tmp.delete()
        } catch (_: Exception) {
            // Pas de vignette (disque plein…) : l'image s'affiche quand même, décodée à chaque fois.
        }
        return small
    }

    private fun decodeSampled(file: File, targetWidth: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    } catch (_: Throwable) {
        null
    }
}
