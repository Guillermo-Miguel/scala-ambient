package es.scala.ambient

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Una marca de la comunidad: no viene con la app, se descarga al elegirla. */
data class CommunityBrand(val id: String, val name: String, val file: String, val mono: Boolean)

/**
 * Marca de arranque (el logotipo de la bienvenida y la cabecera).
 * La app solo trae dos logotipos propios. Las demás marcas las comparte la comunidad
 * en GitHub y se descargan al móvil únicamente si el usuario las elige.
 */
object Brands {
    const val AMBIENT = "ambient"
    const val HALO = "halo"
    private const val COMMUNITY = "c:"

    /** Catálogos de la comunidad, por orden de preferencia. */
    private val SOURCES = listOf(
        "https://raw.githubusercontent.com/Guillermo-Miguel/scala-ambient-marcas/main/",
        "https://raw.githubusercontent.com/Guillermo-Miguel/scala-ambient/marcas/",
    )
    private var base = SOURCES.last()

    private val _custom = MutableStateFlow<ImageBitmap?>(null)
    /** Logotipo descargado que está en uso (null con los logotipos propios). */
    val custom: StateFlow<ImageBitmap?> = _custom

    fun isCommunity(brand: String) = brand.startsWith(COMMUNITY)
    fun communityId(brand: String) = brand.removePrefix(COMMUNITY)

    private fun file(context: Context, id: String) = File(File(context.filesDir, "marcas"), "$id.png")

    /** Al abrir la app: recupera el logotipo descargado, si lo hay. */
    fun init(context: Context, settings: Settings) {
        if (!isCommunity(settings.brand)) return
        _custom.value = runCatching {
            BitmapFactory.decodeFile(file(context, communityId(settings.brand)).path)?.asImageBitmap()
        }.getOrNull()
    }

    private fun bytes(url: String, max: Int): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 12000
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            return c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > max) error("Archivo demasiado grande")
                }
                out.toByteArray()
            }
        } finally { c.disconnect() }
    }

    /** Lista de marcas compartidas. */
    suspend fun catalog(): List<CommunityBrand> = withContext(Dispatchers.IO) {
        var last: Throwable? = null
        for (src in SOURCES) {
            try {
                val json = JSONObject(String(bytes(src + "marcas.json", 200_000)))
                val arr = json.getJSONArray("marcas")
                val list = (0 until arr.length()).mapNotNull { i ->
                    val o = arr.getJSONObject(i)
                    val id = o.optString("id")
                    val archivo = o.optString("archivo")
                    // solo nombres sencillos: nada de rutas ni direcciones externas
                    if (!id.matches(Regex("[a-z0-9_-]{1,40}")) || !archivo.matches(Regex("[A-Za-z0-9._-]{1,80}"))) null
                    else CommunityBrand(id, o.optString("nombre", id).take(40), archivo, o.optBoolean("monocromo", true))
                }
                base = src
                return@withContext list
            } catch (t: Throwable) { last = t }
        }
        throw last ?: IllegalStateException("Sin catálogo")
    }

    /** Descarga el logotipo de una marca (para verlo antes de elegirlo). */
    suspend fun download(brand: CommunityBrand): Pair<ByteArray, ImageBitmap> = withContext(Dispatchers.IO) {
        val data = bytes(base + brand.file, 1_500_000)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096) error("Imagen no válida")
        val bmp = BitmapFactory.decodeByteArray(data, 0, data.size) ?: error("Imagen no válida")
        data to bmp.asImageBitmap()
    }

    /** Guarda la marca en el móvil y la pone como marca de arranque. */
    suspend fun use(context: Context, brand: CommunityBrand, data: ByteArray, image: ImageBitmap) {
        withContext(Dispatchers.IO) {
            val f = file(context, brand.id)
            f.parentFile?.mkdirs()
            f.parentFile?.listFiles()?.forEach { it.delete() }
            f.writeBytes(data)
        }
        _custom.value = image
        AmbientApp.instance.prefs.update { it.copy(brand = COMMUNITY + brand.id, brandName = brand.name, brandMono = brand.mono) }
    }

    /** Vuelve a uno de los logotipos propios y borra lo descargado. */
    fun useBuiltIn(context: Context, id: String) {
        AmbientApp.instance.prefs.update { it.copy(brand = id, brandName = "", brandMono = true) }
        _custom.value = null
        runCatching { File(context.filesDir, "marcas").listFiles()?.forEach { it.delete() } }
    }
}
