package com.joao.extratorapk

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.graphics.drawable.toBitmap
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ExtractedFile(val name: String, val uri: Uri, val isApks: Boolean)

class Extractor(private val context: Context, treeUri: Uri) {

    private val dir: DocumentFile = DocumentFile.fromTreeUri(context, treeUri)
        ?: throw IllegalStateException("Pasta de destino inválida")

    /** Copia o app para a pasta. [onBytes] recebe o total de bytes já copiados. */
    suspend fun extract(app: AppEntry, onBytes: (Long) -> Unit): ExtractedFile {
        if (!dir.canWrite()) throw IllegalStateException("Sem permissão para gravar na pasta escolhida")
        val base = sanitize(app.label) + (if (app.versionName.isNotBlank()) "_" + sanitize(app.versionName) else "")
        val ext = if (app.isSplit) "apks" else "apk"
        val name = uniqueName(base, ext)
        val mime = if (app.isSplit) "application/octet-stream" else "application/vnd.android.package-archive"
        val doc = dir.createFile(mime, name) ?: throw IllegalStateException("Não foi possível criar $name")
        try {
            val out = context.contentResolver.openOutputStream(doc.uri, "w")
                ?: throw IllegalStateException("Não foi possível abrir $name")
            out.use {
                if (app.isSplit) writeApks(app, it, onBytes) else {
                    var done = 0L
                    File(app.sourceDir).inputStream().use { input ->
                        copy(input, it) { n -> done += n; onBytes(done) }
                    }
                }
            }
        } catch (t: Throwable) {
            runCatching { doc.delete() }
            throw t
        }
        return ExtractedFile(doc.name ?: name, doc.uri, app.isSplit)
    }

    /** Formato .apks (zip com base.apk + splits), instalável pelo SAI. */
    private suspend fun writeApks(app: AppEntry, out: OutputStream, onBytes: (Long) -> Unit) {
        val zip = ZipOutputStream(out)
        zip.setLevel(Deflater.BEST_SPEED)
        var done = 0L
        val parts = listOf("base.apk" to app.sourceDir) +
            app.splitDirs.map { File(it).name to it }
        for ((entryName, path) in parts) {
            zip.putNextEntry(ZipEntry(entryName))
            File(path).inputStream().use { input ->
                copy(input, zip) { n -> done += n; onBytes(done) }
            }
            zip.closeEntry()
        }
        iconPng(app)?.let { png ->
            zip.putNextEntry(ZipEntry("icon.png"))
            zip.write(png)
            zip.closeEntry()
        }
        val meta = JSONObject()
            .put("meta_version", 1)
            .put("package", app.packageName)
            .put("label", app.label)
            .put("version_name", app.versionName)
            .put("version_code", app.versionCode)
            .put("splits", JSONArray(parts.map { it.first }))
            .put("exported_by", "Extrator de APKs")
        zip.putNextEntry(ZipEntry("info.json"))
        zip.write(meta.toString(2).toByteArray())
        zip.closeEntry()
        zip.finish()
    }

    private fun iconPng(app: AppEntry): ByteArray? = runCatching {
        val drawable = context.packageManager.getApplicationIcon(app.packageName)
        val bmp = drawable.toBitmap(192, 192, Bitmap.Config.ARGB_8888)
        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }.getOrNull()

    private suspend fun copy(input: InputStream, out: OutputStream, onChunk: (Int) -> Unit) {
        val buf = ByteArray(256 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            onChunk(n)
        }
    }

    private fun uniqueName(base: String, ext: String): String {
        val existing = dir.listFiles().mapNotNull { it.name }.toHashSet()
        var candidate = "$base.$ext"
        var i = 2
        while (candidate in existing) {
            candidate = "$base ($i).$ext"
            i++
        }
        return candidate
    }

    companion object {
        fun sanitize(s: String): String =
            s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.').take(80).ifBlank { "app" }
    }
}
