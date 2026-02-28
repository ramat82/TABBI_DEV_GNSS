package dz.ogefgef322.gnss
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile

class ChantierHomeActivity : AppCompatActivity() {

    companion object {
        private const val PREFS = "surveyogef_prefs"
        const val KEY_DOCS_TREE_URI = "docs_tree_uri"
        const val KEY_PROJECT_NAME = "selected_project_name"
        // NOTE: le CRS est désormais géré au niveau du levé (CRS verrouillé par levé).
        // On conserve la clé uniquement pour compatibilité avec d'anciens prefs.
        const val KEY_PROJECT_CRS_ID = "selected_project_crs_id"
        const val KEY_CHANTIER_NAME = "selected_chantier_name"
        private const val TOP_FOLDER = "TOPOGRAPHIE"

        const val EXTRA_CHANTIER_NAME = "extra_chantier_name"
        const val EXTRA_LEVES_URI = "extra_leves_uri"
        const val EXTRA_REPERES_URI = "extra_reperes_uri"
        const val EXTRA_CHANTIER_DIR_URI = "extra_chantier_dir_uri"
    }

    private lateinit var prefs: SharedPreferences

    private lateinit var btnPickRoot: Button
    private lateinit var btnNewProject: Button
    private lateinit var btnNew: Button
    private lateinit var btnOpen: Button
    private lateinit var btnQuit: Button
    private lateinit var listChantiers: ListView
    private lateinit var txtRootStatus: TextView
    private lateinit var txtChantiersTitle: TextView
    private var defaultPickRootBackground: Drawable? = null

    private val chantierNames = mutableListOf<String>()
    private lateinit var adapter: ArrayAdapter<String>

    private val pickTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, flags)
            prefs.edit()
                .putString(KEY_DOCS_TREE_URI, uri.toString())
                .remove(KEY_PROJECT_NAME)
                .apply()
            refreshUiAndList()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        // UI
        setContentView(R.layout.activity_chantier_home)

        btnPickRoot = findViewById(R.id.btnPickRootFolder)
        btnNewProject = findViewById(R.id.btnNewProject)
        btnNew = findViewById(R.id.btnNewChantier)
        btnOpen = findViewById(R.id.btnOpenChantier)
        btnQuit = findViewById(R.id.btnQuitChantierHome)
        listChantiers = findViewById(R.id.listChantiers)
        txtRootStatus = findViewById(R.id.txtRootStatus)
        txtChantiersTitle = findViewById(R.id.txtChantiersTitle)
        defaultPickRootBackground = btnPickRoot.background?.constantState?.newDrawable()

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_single_choice, chantierNames)
        listChantiers.choiceMode = ListView.CHOICE_MODE_SINGLE
        listChantiers.adapter = adapter

        btnPickRoot.setOnClickListener {
            val docsTree = getDocsTreeUri()
            if (docsTree == null || !hasPersistedPermission(docsTree)) {
                pickTreeLauncher.launch(null)
            } else {
                showProjectChooser(docsTree)
            }
        }

        btnNewProject.setOnClickListener {
            val docsTree = getDocsTreeUri()
            if (docsTree == null || !hasPersistedPermission(docsTree)) {
                Toast.makeText(this, "Choisis d'abord le dossier Documents", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            promptNewProject()
        }

        btnNew.setOnClickListener {
            if (getProjectDir() == null) {
                Toast.makeText(this, "Choisis d'abord un projet", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            promptNewChantier()
        }

        btnOpen.setOnClickListener {
            val pos = listChantiers.checkedItemPosition
            if (pos == AdapterView.INVALID_POSITION || pos !in chantierNames.indices) {
                Toast.makeText(this, "Sélectionne un chantier", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            openChantier(chantierNames[pos])
        }

        btnQuit.setOnClickListener { confirmQuit() }

        listChantiers.setOnItemClickListener { _, _, _, _ ->
            btnOpen.isEnabled = true
        }

        refreshUiAndList()
    }

    private fun confirmQuit() {
        AlertDialog.Builder(this)
            .setTitle("Quitter")
            .setMessage("Voulez-vous fermer l'application ?")
            .setPositiveButton("Oui") { _, _ -> finishAffinity() }
            .setNegativeButton("Non", null)
            .show()
    }

    // -------------------- SAF + Projets/Chantiers (inchangé) --------------------

    private fun refreshUiAndList() {
        val docsTree = getDocsTreeUri()
        if (docsTree == null || !hasPersistedPermission(docsTree)) {
            txtRootStatus.text = "Documents : autorisation requise"
            txtChantiersTitle.text = "Chantiers existants dans (autorisation requise)"
            btnNewProject.isEnabled = false
            btnNew.isEnabled = false
            btnOpen.isEnabled = false
            chantierNames.clear()
            adapter.notifyDataSetChanged()
            return
        }

        val topDir = getTopographieDir(docsTree)
        if (topDir == null) {
            txtRootStatus.text = "Documents : accès impossible"
            txtChantiersTitle.text = "Chantiers existants dans (accès impossible)"
            btnNewProject.isEnabled = false
            btnNew.isEnabled = false
            btnOpen.isEnabled = false
            chantierNames.clear()
            adapter.notifyDataSetChanged()
            return
        }

        val projectName = getProjectName()
        val projectDir = if (projectName.isNullOrBlank()) null else topDir.findFile(projectName)
        if (projectDir == null || !projectDir.isDirectory) {
            txtRootStatus.text = "Projet : non défini"
            txtChantiersTitle.text = "Chantiers existants dans (aucun projet)"
            btnNewProject.isEnabled = true
            btnNew.isEnabled = false
            btnOpen.isEnabled = false
            updateProjectSelectionUi(false)
            chantierNames.clear()
            adapter.notifyDataSetChanged()
            return
        }

        val displayName = projectDir.name ?: "Projet"
        txtRootStatus.text = "Projet : $displayName"
        txtChantiersTitle.text = "Chantiers existants dans ($displayName)"
        btnNewProject.isEnabled = true
        btnNew.isEnabled = true
        btnOpen.isEnabled = false
        updateProjectSelectionUi(true)

        chantierNames.clear()
        chantierNames.addAll(listExistingChantiers(projectDir))
        adapter.notifyDataSetChanged()
    }

    private fun getDocsTreeUri(): Uri? {
        val s = prefs.getString(KEY_DOCS_TREE_URI, null) ?: return null
        return runCatching { Uri.parse(s) }.getOrNull()
    }

    private fun hasPersistedPermission(uri: Uri): Boolean {
        val targetId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        return contentResolver.persistedUriPermissions.any { perm ->
            if (!perm.isReadPermission || !perm.isWritePermission) return@any false
            val permId = runCatching { DocumentsContract.getTreeDocumentId(perm.uri) }.getOrNull()
            when {
                perm.uri == uri -> true
                targetId != null && permId != null && targetId.startsWith(permId) -> true
                else -> false
            }
        }
    }

    private fun getTopographieDir(docsTreeUri: Uri): DocumentFile? {
        val root = DocumentFile.fromTreeUri(this, docsTreeUri) ?: return null
        return root.findFile(TOP_FOLDER) ?: root.createDirectory(TOP_FOLDER)
    }

    private fun getProjectName(): String? = prefs.getString(KEY_PROJECT_NAME, null)

    private fun getProjectDir(): DocumentFile? {
        val docsTree = getDocsTreeUri() ?: return null
        if (!hasPersistedPermission(docsTree)) return null
        val topDir = getTopographieDir(docsTree) ?: return null
        val projectName = getProjectName()?.trim().orEmpty()
        if (projectName.isBlank()) return null
        val projectDir = topDir.findFile(projectName) ?: return null
        return if (projectDir.isDirectory) projectDir else null
    }

    private fun listExistingChantiers(projectDir: DocumentFile): List<String> {
        return projectDir.listFiles()
            .filter { it.isDirectory }
            .mapNotNull { it.name }
            .filterNot { it.equals("REPERES", ignoreCase = true) }
            .sortedBy { it.lowercase() }
    }

    private fun promptNewProject() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 0)
        }

        val et = EditText(this).apply {
            hint = "Nom du projet"
            inputType = InputType.TYPE_CLASS_TEXT
        }

        container.addView(et)

        AlertDialog.Builder(this)
            .setTitle("Nouveau projet")
            .setMessage("Nom du projet (suffixe automatique _proj)")
            .setView(container)
            .setPositiveButton("Créer") { _, _ ->
                val raw = et.text?.toString()?.trim().orEmpty()
                val name = sanitizeName(raw)
                if (name.isBlank()) {
                    Toast.makeText(this, "Nom invalide", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // CRS désormais choisi au niveau du levé (et non du projet)
                createProject(name)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun createProject(name: String) {
        val docsTree = getDocsTreeUri()
        if (docsTree == null || !hasPersistedPermission(docsTree)) {
            Toast.makeText(this, "Accès Documents requis", Toast.LENGTH_LONG).show()
            return
        }

        val topDir = getTopographieDir(docsTree)
        if (topDir == null) {
            Toast.makeText(this, "Impossible d'accéder au dossier", Toast.LENGTH_LONG).show()
            return
        }

        val projectName = "${name}_proj"
        val projectDir = topDir.findFile(projectName) ?: topDir.createDirectory(projectName)
        if (projectDir == null) {
            Toast.makeText(this, "Création dossier échouée", Toast.LENGTH_LONG).show()
            return
        }

        val reperesDir = projectDir.findFile("REPERES") ?: projectDir.createDirectory("REPERES")
        if (reperesDir == null) {
            Toast.makeText(this, "Création dossier REPERES échouée", Toast.LENGTH_LONG).show()
            return
        }

        val reperesName = "reperes_${projectName}.csv"
        val reperesFile =
            reperesDir.findFile(reperesName) ?: reperesDir.createFile("text/csv", reperesName)
        if (reperesFile == null) {
            Toast.makeText(this, "Création fichier repères échouée", Toast.LENGTH_LONG).show()
            return
        }

        ensureHeader(reperesFile.uri, "id,x,y,zone,lat,lon\n")

        prefs.edit().putString(KEY_PROJECT_NAME, projectName).apply()
        refreshUiAndList()
    }

    private fun showProjectChooser(docsTreeUri: Uri) {
        val topDir = getTopographieDir(docsTreeUri)
        if (topDir == null) {
            Toast.makeText(this, "Impossible d'accéder au dossier", Toast.LENGTH_LONG).show()
            return
        }

        val projects = topDir.listFiles()
            .filter { it.isDirectory }
            .mapNotNull { it.name }
            .sortedBy { it.lowercase() }

        if (projects.isEmpty()) {
            Toast.makeText(this, "Aucun projet existant.", Toast.LENGTH_SHORT).show()
            return
        }

        val names = projects.toTypedArray()
        var selectedIndex = -1
        val dialog = AlertDialog.Builder(this)
            .setTitle("Choisir un projet")
            .setSingleChoiceItems(names, -1) { _, which -> selectedIndex = which }
            .setNegativeButton("Annuler", null)
            .setPositiveButton("OK", null)
            .create()

        dialog.setOnShowListener {
            val okButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            okButton.setOnClickListener {
                if (selectedIndex == -1) {
                    Toast.makeText(this, "Veuillez choisir un projet.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val selected = names[selectedIndex]
                prefs.edit().putString(KEY_PROJECT_NAME, selected).apply()

                refreshUiAndList()
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun updateProjectSelectionUi(hasSelection: Boolean) {
        if (hasSelection) {
            val green = ContextCompat.getColor(this, android.R.color.holo_green_dark)
            btnPickRoot.setBackgroundColor(green)
        } else {
            val restored = defaultPickRootBackground?.constantState?.newDrawable()
            if (restored != null) btnPickRoot.background = restored
        }
    }

    private fun promptNewChantier() {
        val et = EditText(this).apply {
            hint = "Nom du chantier"
            inputType = InputType.TYPE_CLASS_TEXT
        }

        AlertDialog.Builder(this)
            .setTitle("Nouveau chantier")
            .setMessage("Nom du chantier (sans caractères spéciaux)")
            .setView(et)
            .setPositiveButton("Créer") { _, _ ->
                val raw = et.text?.toString()?.trim().orEmpty()
                val name = sanitizeName(raw)
                if (name.isBlank()) {
                    Toast.makeText(this, "Nom invalide", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                createChantier(name)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    
private fun sanitizeName(raw: String): String {
        return raw.replace(Regex("[^a-zA-Z0-9 _-]"), "")
            .trim()
            .replace(Regex("""\s+"""), "_")
    }

    private fun createChantier(name: String) {
        val projectDir = getProjectDir()
        if (projectDir == null) {
            Toast.makeText(this, "Impossible d'accéder au dossier", Toast.LENGTH_LONG).show()
            return
        }

        val chantierDir = projectDir.findFile(name) ?: projectDir.createDirectory(name)
        if (chantierDir == null) {
            Toast.makeText(this, "Création dossier échouée", Toast.LENGTH_LONG).show()
            return
        }

        refreshUiAndList()
        openChantier(name)
    }

    private fun ensureHeader(fileUri: Uri, header: String) {
        runCatching {
            val ins = contentResolver.openInputStream(fileUri) ?: return@runCatching
            val existing = ins.bufferedReader().use { it.readLine() ?: "" }
            if (existing.isBlank()) {
                contentResolver.openOutputStream(fileUri, "wt")?.use { os ->
                    os.write(header.toByteArray(Charsets.UTF_8))
                }
            }
        }
    }

    private fun openChantier(name: String) {
        val projectDir = getProjectDir() ?: return
        val chantierDir = projectDir.findFile(name)
        if (chantierDir == null) {
            Toast.makeText(this, "Chantier introuvable", Toast.LENGTH_SHORT).show()
            return
        }

        prefs.edit().putString(KEY_CHANTIER_NAME, name).apply()

        val reperesDir = projectDir.findFile("REPERES")
        val reperesName = "reperes_${projectDir.name}.csv"
        val reperesFile = reperesDir?.findFile(reperesName)
        if (reperesFile == null) {
            Toast.makeText(this, "Fichier repères introuvable", Toast.LENGTH_LONG).show()
            return
        }

        val i = Intent(this, MainActivity::class.java).apply {
            putExtra(EXTRA_CHANTIER_NAME, name)
            putExtra(EXTRA_REPERES_URI, reperesFile.uri.toString())
            putExtra(EXTRA_CHANTIER_DIR_URI, chantierDir.uri.toString())
        }
        startActivity(i)
    }

    private fun unlockProFeatures() {
        // TODO: Débloquer Pro (cacher boutons, débloquer écrans, etc.)
    }
}