package uk.org.beebem.android

import android.content.Context
import java.io.File
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object StateManager {
    private const val DIR_NAME = "states"
    private const val EXT = ".uefstate"
    // Snapshots are named "<game>__<date>.uefstate"; older ones are just the date.
    private const val SEP = "__"
    private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    private val SHOW_FMT = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.UK)

    fun statesDir(context: Context): File =
        File(context.filesDir, DIR_NAME).also { it.mkdirs() }

    /** The game name for a disc, as used in snapshot names: its file name without the extension. */
    fun gameName(disc: File?): String? =
        disc?.nameWithoutExtension?.replace(Regex("[^A-Za-z0-9 ._()+-]"), "_")?.trim()?.ifEmpty { null }

    fun generatePath(context: Context, game: String?): String {
        val prefix = game?.let { it + SEP }.orEmpty()
        return File(statesDir(context), prefix + DATE_FMT.format(Date()) + EXT).absolutePath
    }

    fun listStates(context: Context): List<File> =
        statesDir(context)
            .listFiles { f -> f.name.endsWith(EXT) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun delete(file: File) { file.delete() }

    /** The game a snapshot was saved from, or null for snapshots saved before games were recorded. */
    fun gameOf(file: File): String? =
        file.nameWithoutExtension.let { if (SEP in it) it.substringBeforeLast(SEP) else null }

    /** When the snapshot was saved, e.g. "27 Oct 2026, 14:05". */
    fun displayDate(file: File): String {
        val stamp = file.nameWithoutExtension.substringAfterLast(SEP)
        return try {
            SHOW_FMT.format(DATE_FMT.parse(stamp)!!)
        } catch (e: ParseException) {
            stamp.replace('_', ' ')
        }
    }
}
