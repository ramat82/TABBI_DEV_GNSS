package dz.ogefgef322.gnss

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject

/** Per-levé settings stored next to the levé CSV file. */
data class LeveSettings(
    val version: Int = 3,
    val stationing: StationingSettings = StationingSettings()
) {
    data class StationingSettings(
        val enabled: Boolean = false,
        val maxShiftMeters: Double = 30.0,
        /**
         * Business rule:
         * Manual station entry (user typed coordinates) is allowed only once, for the first station of the levé.
         * After it becomes true, the app only allows stationing "from an existing point" (STA...) thereafter.
         */
        val manualFirstDone: Boolean = false,
        val dx: Double = 0.0,
        val dy: Double = 0.0,
        val dz: Double = 0.0,
        val createdAtEpochMs: Long = 0L,
        val source: String = "ONE_POINT"
    )
}

object LeveSettingsStore {
    private const val SETTINGS_SUFFIX = "_leve_settings.json"

    fun settingsFileNameFor(csvName: String): String {
        val base = csvName.removeSuffix(".csv").removeSuffix(".CSV")
        return base + SETTINGS_SUFFIX
    }

    /** Ensures the settings file exists next to the CSV file and returns it. */
    fun ensureExists(
        context: Context,
        levesDir: DocumentFile,
        csvFile: DocumentFile
    ): DocumentFile? {
        val csvName = csvFile.name ?: return null
        val settingsName = settingsFileNameFor(csvName)
        val existing = levesDir.findFile(settingsName)
        if (existing != null && existing.isFile) return existing

        val created = levesDir.createFile("application/json", settingsName) ?: return null
        val default = LeveSettings(
            version = 3,
            stationing = LeveSettings.StationingSettings(enabled = false, maxShiftMeters = 30.0, manualFirstDone = false)
        )
        save(context.contentResolver, created.uri, default)
        return created
    }

    fun load(contentResolver: ContentResolver, uri: Uri): LeveSettings? {
        return runCatching {
            val txt = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return null
            val obj = JSONObject(txt)
            val version = obj.optInt("version", 1)

            // Stationing (kept). Old files may have other fields: ignore them safely.
            val stationObj = obj.optJSONObject("stationing")
            val station = LeveSettings.StationingSettings(
                enabled = stationObj?.optBoolean("enabled", false) ?: false,
                maxShiftMeters = stationObj?.optDouble("maxShiftMeters", 30.0) ?: 30.0,
                manualFirstDone = stationObj?.optBoolean("manualFirstDone", false) ?: false,
                dx = stationObj?.optDouble("dx", 0.0) ?: 0.0,
                dy = stationObj?.optDouble("dy", 0.0) ?: 0.0,
                dz = stationObj?.optDouble("dz", 0.0) ?: 0.0,
                createdAtEpochMs = stationObj?.optLong("createdAtEpochMs", 0L) ?: 0L,
                source = stationObj?.optString("source", "ONE_POINT") ?: "ONE_POINT"
            )

            LeveSettings(version = if (version < 3) 3 else version, stationing = station)
        }.getOrNull()
    }

    fun save(contentResolver: ContentResolver, uri: Uri, settings: LeveSettings): Boolean {
        return runCatching {
            val obj = JSONObject()
            obj.put("version", settings.version)
            obj.put(
                "stationing",
                JSONObject()
                    .put("enabled", settings.stationing.enabled)
                    .put("maxShiftMeters", settings.stationing.maxShiftMeters)
                    .put("manualFirstDone", settings.stationing.manualFirstDone)
                    .put("dx", settings.stationing.dx)
                    .put("dy", settings.stationing.dy)
                    .put("dz", settings.stationing.dz)
                    .put("createdAtEpochMs", settings.stationing.createdAtEpochMs)
                    .put("source", settings.stationing.source)
            )
            contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(obj.toString(2)) }
                ?: return false
            true
        }.getOrDefault(false)
    }
}
