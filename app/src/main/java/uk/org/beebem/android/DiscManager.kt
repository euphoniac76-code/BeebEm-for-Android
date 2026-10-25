package uk.org.beebem.android

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

object DiscManager {

    fun discsDir(context: Context): File =
        File(context.filesDir, "discs").also { it.mkdirs() }

    fun copyUriToPrivate(context: Context, uri: Uri): File? {
        val name = queryDisplayName(context, uri) ?: return null
        val safe = name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val dest = File(discsDir(context), safe)
        return runCatching {
            context.contentResolver.openInputStream(uri)!!.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            dest
        }.getOrNull()
    }

    /** Copies the private image over [uri], truncating it first. Returns false on failure. */
    fun copyPrivateToUri(context: Context, localFile: File, uri: Uri): Boolean = runCatching {
        context.contentResolver.openOutputStream(uri, "wt")!!.use { output ->
            localFile.inputStream().use { input -> input.copyTo(output) }
        }
        true
    }.getOrDefault(false)

    /** CRC-32 of a disc image, used to spot writes by the emulated BBC. Null if unreadable. */
    fun checksum(file: File): Long? = runCatching {
        val crc = java.util.zip.CRC32()
        crc.update(file.readBytes())
        crc.value
    }.getOrNull()

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }
}
