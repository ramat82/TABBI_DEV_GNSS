package dz.ogefgef322.gnss
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject

/**
 * Settings stored per project inside:
 * Documents/TOPOGRAPHIE/<project>_proj/project_settings.json
 */
data class ProjectSettings(
    val crsId: String = ProjectCrs.INCT_UTM_ALGERIA.id
)

data class ProjectCrs(
    val id: String,
    val label: String,
    val epsg: Int? = null,
    /** If the EPSG definition uses kilometres (e.g. EPSG:22300), we convert to meters for storage/display. */
    val unitToMeterScale: Double = 1.0,
    val supportedNow: Boolean = true
) {
    companion object {
        // Always available
        val WGS84_LATLON = ProjectCrs("wgs84_latlon", "WGS84 (Latitude/Longitude)", 4326, 1.0, true)
        val WGS84_UTM_AUTO = ProjectCrs("wgs84_utm_auto", "WGS84 / UTM (zone auto)", null, 1.0, true)

        // INCT (kept as the existing Algeria INCT behavior)
        val INCT_UTM_ALGERIA = ProjectCrs("inct_utm_dz", "INCT", null, 1.0, true)

        fun allForUi(): List<ProjectCrs> = listOf(
            INCT_UTM_ALGERIA,
            WGS84_UTM_AUTO,
            WGS84_LATLON
        )

        fun byId(id: String?): ProjectCrs {
            return allForUi().firstOrNull { it.id == id } ?: INCT_UTM_ALGERIA
        }

        /** Maps a CSV CRS signature (e.g. "INCT", "EPSG:4326", "WGS84_UTM_AUTO") to a supported CRS. */
        fun fromSignature(signature: String?): ProjectCrs {
            val sig = signature?.trim()?.uppercase().orEmpty()
            return when {
                sig == "INCT" || sig == "INCT_UTM" -> INCT_UTM_ALGERIA
                sig == "WGS84_UTM" || sig == "WGS84_UTM_AUTO" -> WGS84_UTM_AUTO
                sig == "WGS84_GEOG" || sig == "EPSG:4326" -> WGS84_LATLON
                // accept explicit UTM zones (north/south)
                sig.startsWith("EPSG:326") || sig.startsWith("EPSG:327") -> {
                    val epsg = sig.removePrefix("EPSG:").toIntOrNull()
                    if (epsg != null) {
                        val zone = when {
                            epsg in 32601..32660 -> epsg - 32600
                            epsg in 32701..32760 -> epsg - 32700
                            else -> null
                        }
                        if (zone != null) {
                            val north = epsg in 32601..32660
                            ProjectCrs(
                                id = "wgs84_utm_${zone}${if (north) "N" else "S"}",
                                label = "WGS84 / UTM zone $zone${if (north) "N" else "S"} (EPSG:$epsg)",
                                epsg = epsg,
                                unitToMeterScale = 1.0,
                                supportedNow = true
                            )
                        } else {
                            WGS84_UTM_AUTO
                        }
                    } else {
                        WGS84_UTM_AUTO
                    }
                }
                else -> INCT_UTM_ALGERIA
            }
        }
    }
}

object ProjectSettingsStore {
    private const val PREFS_NAME = "surveyogef_prefs"
    private const val TOP_FOLDER = "TOPOGRAPHIE"
    private const val SETTINGS_FILE = "project_settings.json"

    fun load(context: Context, docsTreeUri: Uri, projectName: String): ProjectSettings? {
        val root = DocumentFile.fromTreeUri(context, docsTreeUri) ?: return null
        val topDir = root.findFile(TOP_FOLDER) ?: return null
        val projectDir = topDir.findFile(projectName) ?: return null
        val settingsFile = projectDir.findFile(SETTINGS_FILE) ?: return null
        return runCatching {
            val jsonText = context.contentResolver.openInputStream(settingsFile.uri)?.bufferedReader()
                ?.use { it.readText() } ?: return null
            val obj = JSONObject(jsonText)
            ProjectSettings(
                crsId = obj.optString("crsId", ProjectCrs.INCT_UTM_ALGERIA.id)
            )
        }.getOrNull()
    }

    fun save(context: Context, docsTreeUri: Uri, projectName: String, settings: ProjectSettings): Boolean {
        val root = DocumentFile.fromTreeUri(context, docsTreeUri) ?: return false
        val topDir = root.findFile(TOP_FOLDER) ?: root.createDirectory(TOP_FOLDER) ?: return false
        val projectDir = topDir.findFile(projectName) ?: return false

        val file = projectDir.findFile(SETTINGS_FILE) ?: projectDir.createFile("application/json", SETTINGS_FILE)
            ?: return false

        return runCatching {
            val obj = JSONObject()
            obj.put("crsId", settings.crsId)
            context.contentResolver.openOutputStream(file.uri, "wt")?.bufferedWriter()
                ?.use { it.write(obj.toString(2)) } ?: return false
            true
        }.getOrDefault(false)
    }

    fun setSelectedCrsInPrefs(context: Context, crsId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(ChantierHomeActivity.KEY_PROJECT_CRS_ID, crsId).apply()
    }

    fun getSelectedCrsFromPrefs(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(ChantierHomeActivity.KEY_PROJECT_CRS_ID, ProjectCrs.INCT_UTM_ALGERIA.id)
            ?: ProjectCrs.INCT_UTM_ALGERIA.id
    }
}