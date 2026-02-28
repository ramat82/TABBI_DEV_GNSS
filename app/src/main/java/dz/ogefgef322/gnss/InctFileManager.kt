package dz.ogefgef322.gnss

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* =========================
   FILE MANAGER SAF
   ========================= */

enum class OpenResult { OK, LEGACY_OK, MISMATCH, ERROR }

class InctFileManager(private val context: Context) {

    companion object {
        // NOTE: Keep existing column order and APPEND new columns at the end for backward compatibility.
        // Storage is ALWAYS INCT in the first 3 coordinate columns.
        private const val COLUMN_HEADER = "id,xinct,yinct,zinct,zone,timestamp,lat,lon,alt,type,xwgs84_utm,ywgs84_utm\n"
        private const val TAG = "OGEF_DZ"
    }

    private var csvWriter: OutputStreamWriter? = null
    private var csvOutput: OutputStream? = null
    private var fileName: String = ""
    private var reperesFileName: String = ""
    private var counter: Int = 0

    fun open(uri: Uri, name: String, projectCrs: ProjectCrs) {
        close()
        fileName = name
        val stream = context.contentResolver.openOutputStream(uri, "wa")
        if (stream == null) {
            Log.e(TAG, "CSV write failed: output stream null")
            return
        }
        csvOutput = stream
        csvWriter = OutputStreamWriter(stream, Charsets.UTF_8)
        if (isFileEmpty(uri)) {
            // Metadata line + column header
            csvWriter?.write(CsvCrsUtils.metadataLineForProject(projectCrs))
            csvWriter?.write(COLUMN_HEADER)
            csvWriter?.flush()
        }
        counter = 0
    }

    fun openExisting(uri: Uri, name: String, expectedCrs: ProjectCrs): OpenResult {
        close()
        fileName = name

        val foundSig = CsvCrsUtils.readSignature(context.contentResolver, uri)
        val compatible = CsvCrsUtils.isCompatible(expectedCrs, foundSig)

        if (!compatible) {
            // Don't open writer if CRS mismatch
            close()
            return OpenResult.MISMATCH
        }

        val stream = context.contentResolver.openOutputStream(uri, "wa")
        if (stream == null) {
            Log.e(TAG, "CSV write failed: output stream null")
            close()
            return OpenResult.ERROR
        }
        csvOutput = stream
        csvWriter = OutputStreamWriter(stream, Charsets.UTF_8)
        counter = countExistingPoints(uri)
        return if (foundSig.isNullOrBlank()) OpenResult.LEGACY_OK else OpenResult.OK
    }

    /**
     * Opens an existing CSV **without** CRS validation (used only when user explicitly accepts legacy files).
     */
    fun openExistingForce(uri: Uri, name: String): Boolean {
        close()
        fileName = name
        val stream = context.contentResolver.openOutputStream(uri, "wa")
        if (stream == null) {
            Log.e(TAG, "CSV write failed: output stream null")
            close()
            return false
        }
        csvOutput = stream
        csvWriter = OutputStreamWriter(stream, Charsets.UTF_8)
        counter = countExistingPoints(uri)
        return true
    }


    fun write(
        result: InctResult,
        lat: Double,
        lon: Double,
        alt: Double,
        typeLabel: String
    ): Boolean {
        val writerSafe = csvWriter
        if (writerSafe == null) {
            Log.e(TAG, "CSV write failed: writer not initialized")
            return false
        }
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val safeType = typeLabel.replace(",", " ").trim()
        val utm = if (lat == 0.0 || lon == 0.0) null else wgs84ToUtm(lat, lon)
        val utmEasting = utm?.let { String.format(Locale.US, "%.2f", it.easting) } ?: ""
        val utmNorthing = utm?.let { String.format(Locale.US, "%.2f", it.northing) } ?: ""
        val line = String.format(
            Locale.US,
            "%s,%.2f,%.2f,%.2f,%d,%s,%.8f,%.8f,%.2f,%s,%s,%s\n",
            result.id,
            result.x,
            result.y,
            result.z,
            result.zone,
            df.format(Date(result.timestamp)),
            lat,
            lon,
            alt,
            safeType,
            utmEasting,
            utmNorthing
        )
        try {
            writerSafe.write(line)
            writerSafe.flush()
        } catch (e: Exception) {
            Log.e(TAG, "CSV write failed", e)
            return false
        }
        counter++
        Log.d(TAG, "CSV appended: $fileName")
        return true
    }

    fun close() {
        try {
            csvWriter?.close()
            csvOutput?.close()
        } catch (_: Exception) {
        }
        csvWriter = null
        csvOutput = null
        fileName = ""
        reperesFileName = ""
        counter = 0
    }

    fun isOpen(): Boolean = csvWriter != null
    fun getFileName(): String = fileName
    fun getCount(): Int = counter

    private fun countExistingPoints(uri: Uri): Int {
        var count = 0
        runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                var headerPassed = false
                reader.forEachLine { line ->
                    val t = line.trim()
                    if (t.isEmpty()) return@forEachLine
                    if (!headerPassed) {
                        // Skip optional metadata lines starting with '#'
                        if (t.startsWith("#")) return@forEachLine
                        // Skip column header (old or new)
                        if (t.lowercase().startsWith("id,")) {
                            headerPassed = true
                            return@forEachLine
                        }
                        // If first non-comment line isn't a header, treat it as a point
                        headerPassed = true
                        count++
                        return@forEachLine
                    } else {
                        count++
                    }
                }
            }
        }
        return count
    }

    private fun isFileEmpty(uri: Uri): Boolean {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                val firstLine = reader.readLine()
                firstLine == null || firstLine.isBlank()
            } ?: true
        }.getOrDefault(true)
    }
}

/* =========================
   MAIN ACTIVITY
   ========================= */
