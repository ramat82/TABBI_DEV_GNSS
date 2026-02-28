package dz.ogefgef322.gnss
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

private const val PREFS_NAME = "surveyogef_prefs"
private const val TOP_FOLDER = "TOPOGRAPHIE"

fun resolveChantierDir(context: Context): DocumentFile? {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val docsUri = prefs.getString(ChantierHomeActivity.KEY_DOCS_TREE_URI, null)
        ?.let { runCatching { Uri.parse(it) }.getOrNull() }
        ?: return null
    val projectName = prefs.getString(ChantierHomeActivity.KEY_PROJECT_NAME, null)?.trim().orEmpty()
    val chantierName = prefs.getString(ChantierHomeActivity.KEY_CHANTIER_NAME, null)?.trim().orEmpty()
    if (projectName.isBlank() || chantierName.isBlank()) return null

    val root = DocumentFile.fromTreeUri(context, docsUri) ?: return null
    val topDir = root.findFile(TOP_FOLDER) ?: root.createDirectory(TOP_FOLDER) ?: return null
    val projectDir = topDir.findFile(projectName) ?: topDir.createDirectory(projectName) ?: return null
    if (!projectDir.isDirectory) return null
    val chantierDir = projectDir.findFile(chantierName) ?: projectDir.createDirectory(chantierName) ?: return null
    return if (chantierDir.isDirectory) chantierDir else null
}

fun resolveChantierDirFromUriOrPrefs(context: Context, uri: Uri?): DocumentFile? {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val chantierName = prefs.getString(ChantierHomeActivity.KEY_CHANTIER_NAME, null)?.trim().orEmpty()
    if (chantierName.isBlank()) return null

    val fromUri = uri?.let {
        DocumentFile.fromTreeUri(context, it) ?: DocumentFile.fromSingleUri(context, it)
    }
    if (fromUri != null && fromUri.isDirectory && fromUri.name == chantierName) {
        return fromUri
    }

    return resolveChantierDir(context)
}
