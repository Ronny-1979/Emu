package de.ronny.poloretro

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** Startseite: Spieleliste, USB-Import, Zugang zu Controller und Einstellungen. */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var paths: AppPaths
    private lateinit var library: RomLibrary

    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private lateinit var search: EditText
    private val tabButtons = ArrayList<Button>()
    private val adapter = GameAdapter()

    private var allGames: List<Game> = emptyList()
    private var shown: List<Game> = emptyList()
    private var tab = 0
    private var systemFilter: RetroSystem? = null
    private val coverCache = android.util.LruCache<String, android.graphics.Bitmap>(40)
    private var query = ""

    @Volatile private var cancelImport = false
    private var progressDialog: AlertDialog? = null
    private var progressText: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagLog.init(this)
        prefs = Prefs(this)
        paths = AppPaths(this)
        library = RomLibrary(this)
        setContentView(buildUi())
        window.statusBarColor = Ui.BG
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ---------------------------------------------------------------- Oberfläche

    private fun buildUi(): View {
        val pad = Ui.dp(this, 16)
        val root = Ui.vertical(this).apply {
            setBackgroundColor(Ui.BG)
            setPadding(pad, Ui.dp(this@MainActivity, 10), pad, pad)
        }

        val header = Ui.horizontal(this)
        header.addView(Ui.label(this, "PoloRetro", 28f, Ui.ACCENT, true), Ui.weight())
        header.addView(Ui.button(this, "USB-Import", onClick = { startImport() }))
        header.addView(Ui.spacer(this, 8))
        header.addView(Ui.button(this, "Steuerung", onClick = { openController() }))
        header.addView(Ui.spacer(this, 8))
        header.addView(Ui.button(this, "Einstellungen", onClick = {
            startActivity(Intent(this, SettingsActivity::class.java))
        }))
        root.addView(header)

        val tabs = Ui.horizontal(this)
        val names = listOf("Alle", "Zuletzt", "Favoriten", "Konsole")
        for ((i, n) in names.withIndex()) {
            val b = Ui.button(this, n, sizeSp = 15f, onClick = { selectTab(i) })
            tabButtons.add(b)
            val lp = Ui.weight()
            lp.topMargin = Ui.dp(this, 10)
            if (i > 0) lp.leftMargin = Ui.dp(this, 6)
            tabs.addView(b, lp)
        }
        root.addView(tabs)

        search = EditText(this).apply {
            hint = "Suchen ..."
            setHintTextColor(Ui.MUTED)
            setTextColor(Ui.TEXT)
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            background = Ui.focusBackground(this@MainActivity, Ui.SURFACE, 10)
            setPadding(Ui.dp(this@MainActivity, 14), Ui.dp(this@MainActivity, 8), Ui.dp(this@MainActivity, 14), Ui.dp(this@MainActivity, 8))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString()?.trim().orEmpty()
                    applyFilter()
                }
            })
        }
        val searchLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        searchLp.topMargin = Ui.dp(this, 10)
        root.addView(search, searchLp)

        listView = ListView(this).apply {
            divider = null
            dividerHeight = Ui.dp(this@MainActivity, 8)
            selector = android.graphics.drawable.ColorDrawable(0)
            adapter = this@MainActivity.adapter
            isFocusable = true
            // Die Zeilen selbst bekommen den Fokus, damit man sie mit dem Controller anwählen kann.
            itemsCanFocus = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        emptyView = Ui.label(this, "", 17f, Ui.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(Ui.dp(this@MainActivity, 24), Ui.dp(this@MainActivity, 24), Ui.dp(this@MainActivity, 24), 0)
        }
        val listLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        listLp.topMargin = Ui.dp(this, 10)
        root.addView(listView, listLp)
        root.addView(emptyView, listLp)
        updateTabs()
        return root
    }

    private fun updateTabs() {
        for ((i, b) in tabButtons.withIndex()) {
            val active = i == tab
            b.background = Ui.focusBackground(this, if (active) Ui.ACCENT else Ui.SURFACE)
            b.setTextColor(if (active) 0xFF12161B.toInt() else Ui.TEXT)
            if (i == 3) b.text = "Konsole" + (systemFilter?.let { ": " + it.shortLabel } ?: "")
        }
    }

    private fun selectTab(index: Int) {
        if (index == 3) {
            pickSystem()
            return
        }
        tab = index
        updateTabs()
        applyFilter()
    }

    private fun pickSystem() {
        val all = RetroSystem.values()
        val items = arrayOf("Alle Konsolen") + all.map { s ->
            val n = allGames.count { it.system == s }
            s.label + (if (n > 0) "  ($n)" else "")
        }
        Ui.dialog(this).setTitle("Konsole wählen").setItems(items) { _, which ->
            systemFilter = if (which == 0) null else all[which - 1]
            tab = 3
            updateTabs()
            applyFilter()
        }.setNegativeButton("Abbrechen", null).show()
    }

    // ---------------------------------------------------------------- Liste

    private fun refresh() {
        Thread {
            val games = library.scan()
            runOnUiThread {
                allGames = games
                applyFilter()
                autoCovers(games)
            }
        }.start()
    }

    private val coversBusy = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun isOnline(): Boolean = try {
        val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (_: Throwable) {
        false
    }

    /** Lädt im Hintergrund fehlende Titelbilder, höchstens eine Handvoll Spiele je Start. */
    private fun autoCovers(games: List<Game>) {
        if (!prefs.autoCovers || games.isEmpty() || !isOnline()) return
        if (!coversBusy.compareAndSet(false, true)) return
        Thread {
            try {
                val result = CoverDownloader(paths).run(games, false, 40, { _, _, _ -> }, { isFinishing })
                if (result.found > 0) runOnUiThread { if (!isFinishing) { coverCache.evictAll(); adapter.notifyDataSetChanged() } }
            } catch (t: Throwable) {
                DiagLog.add("Titelbilder: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                coversBusy.set(false)
            }
        }.start()
    }

    private fun applyFilter() {
        val q = query.lowercase()
        var list: List<Game> = when (tab) {
            1 -> {
                val order = prefs.recents()
                allGames.filter { it.key in order }.sortedBy { order.indexOf(it.key) }
            }
            2 -> {
                val fav = prefs.favorites()
                allGames.filter { it.key in fav }
            }
            3 -> allGames.filter { systemFilter == null || it.system == systemFilter }
            else -> allGames
        }
        if (q.isNotEmpty()) list = list.filter { it.title.lowercase().contains(q) }
        shown = list
        adapter.notifyDataSetChanged()
        val empty = list.isEmpty()
        listView.visibility = if (empty) View.GONE else View.VISIBLE
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        if (empty) {
            emptyView.text = if (allGames.isEmpty()) {
                "Noch keine Spiele.\n\nLege deine ROM-Dateien in den Ordner \"${UsbImport.STICK_FOLDER}\" oder \"Roms\" auf einem USB-Stick (Unterordner sind erlaubt, die Konsole erkennt die App selbst), " +
                    "stecke ihn ins Radio und tippe oben auf \"USB-Import\".\n\n" +
                    "Alternativ: Dateien direkt in diesen Ordner kopieren:\n${paths.externalRomDir?.absolutePath ?: paths.romDir.absolutePath}"
            } else {
                "Keine Treffer in dieser Ansicht."
            }
        }
    }

    private fun gameMenu(game: Game) {
        val fav = game.key in prefs.favorites()
        val items = arrayOf(
            if (fav) "Aus Favoriten entfernen" else "Zu Favoriten hinzufügen",
            "Spielstände löschen (Neustart des Spiels)",
            "Spiel von diesem Gerät löschen",
        )
        Ui.dialog(this).setTitle(game.title).setItems(items) { _, which ->
            when (which) {
                0 -> { prefs.toggleFavorite(game.key); applyFilter() }
                1 -> confirm("Alle Spielstände von \"${game.title}\" löschen?") {
                    paths.deleteSaves(game)
                    applyFilter()
                }
                2 -> confirm("\"${game.title}\" und seine Spielstände von diesem Gerät löschen?") {
                    paths.deleteSaves(game)
                    game.file.delete()
                    refresh()
                }
            }
        }.setNegativeButton("Abbrechen", null).show()
    }

    private fun confirm(message: String, action: () -> Unit) {
        Ui.dialog(this).setMessage(message)
            .setPositiveButton("Ja") { _, _ -> action() }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun startGame(game: Game) {
        if (game.available && !game.file.exists()) {
            Toast.makeText(this, "Datei nicht mehr vorhanden", Toast.LENGTH_SHORT).show()
            refresh()
            return
        }
        if (!game.available) {
            Toast.makeText(this, "Der USB-Stick mit diesem Spiel ist nicht eingesteckt", Toast.LENGTH_LONG).show()
            return
        }
        prefs.addRecent(game.key)
        startActivity(
            Intent(this, GameActivity::class.java)
                .putExtra(GameActivity.EXTRA_PATH, game.file.absolutePath)
                .putExtra(GameActivity.EXTRA_SYSTEM, game.system.id)
                .putExtra(GameActivity.EXTRA_TITLE, game.title),
        )
    }

    /**
     * Controller-Bedienung der Startseite: A klickt das markierte Feld an, X öffnet bei einem Spiel das
     * Spielmenü (wie langes Drücken). Das Kreuz bewegt den Fokus; das erledigt Android selbst.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (PadInput.isPadKey(event)) {
            PadInput.logFirst(event)
            val focus = currentFocus
            if (focus != null && focus !is EditText) {
                if (event.keyCode == KeyEvent.KEYCODE_BUTTON_A) {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) focus.performClick()
                    return true
                }
                if (event.keyCode == KeyEvent.KEYCODE_BUTTON_X) {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) focus.performLongClick()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun openController() {
        startActivity(Intent(this, ControllerActivity::class.java))
    }

    private inner class GameAdapter : BaseAdapter() {
        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val game = shown[position]
            val ctx = this@MainActivity
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Ui.focusBackground(ctx, Ui.SURFACE)
                minimumHeight = Ui.dp(ctx, 72)
                setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 10), Ui.dp(ctx, 14), Ui.dp(ctx, 10))
                isFocusable = true
                isClickable = true
                setOnClickListener { startGame(game) }
                setOnLongClickListener {
                    gameMenu(game)
                    true
                }
            }
            val badge = Ui.label(ctx, game.system.shortLabel, 14f, 0xFF12161B.toInt(), true).apply {
                gravity = Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(badgeColor(game.system))
                    cornerRadius = Ui.dp(ctx, 8).toFloat()
                }
                setPadding(Ui.dp(ctx, 6), Ui.dp(ctx, 6), Ui.dp(ctx, 6), Ui.dp(ctx, 6))
            }
            row.addView(badge, LinearLayout.LayoutParams(Ui.dp(ctx, 96), ViewGroup.LayoutParams.WRAP_CONTENT))
            coverFor(game)?.let { bmp ->
                val img = android.widget.ImageView(ctx).apply {
                    setImageBitmap(bmp)
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                }
                val lp = LinearLayout.LayoutParams(Ui.dp(ctx, 96), Ui.dp(ctx, 72))
                lp.leftMargin = Ui.dp(ctx, 12)
                row.addView(img, lp)
            }

            val col = Ui.vertical(ctx)
            col.addView(Ui.label(ctx, game.title, 20f, Ui.TEXT, true).apply {
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            val notes = ArrayList<String>()
            notes.add(game.system.label)
            if (!game.available) notes.add("Stick nicht eingesteckt")
            else if (game.external) notes.add("auf USB-Stick")
            if (paths.autoStateFile(game).exists()) notes.add("Fortsetzen möglich")
            if (game.key in prefs.favorites()) notes.add("Favorit")
            col.addView(Ui.label(ctx, notes.joinToString("  ·  "), 13f, Ui.MUTED))
            val colLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            colLp.leftMargin = Ui.dp(ctx, 14)
            row.addView(col, colLp)
            return row
        }
    }

    private fun badgeColor(system: RetroSystem): Int = when (system) {
        RetroSystem.SNES -> 0xFFB9A7E8.toInt()
        RetroSystem.MEGADRIVE -> 0xFF6FC3E8.toInt()
        RetroSystem.N64 -> 0xFF8FD18A.toInt()
        RetroSystem.NES -> 0xFFE8A0A0.toInt()
        RetroSystem.GB, RetroSystem.GBC -> 0xFFD6D88A.toInt()
        RetroSystem.GBA -> 0xFF9CB4F0.toInt()
        RetroSystem.MASTERSYSTEM, RetroSystem.GAMEGEAR -> 0xFFF0B98A.toInt()
    }

    /** Titelbild (klein, zwischengespeichert) oder null. */
    private fun coverFor(game: Game): android.graphics.Bitmap? {
        val extra = listOfNotNull(paths.coverDirFor(game.system), paths.coverDir, paths.externalCoverDir)
        val file = Covers.find(game.file, game.saveKey, extra) ?: return null
        val key = file.absolutePath + file.lastModified()
        coverCache.get(key)?.let { return it }
        return try {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 200 && bounds.outHeight / (sample * 2) >= 200) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)?.also { coverCache.put(key, it) }
        } catch (_: Throwable) {
            null
        }
    }

    // ---------------------------------------------------------------- USB-Import

    private fun startImport() {
        if (!UsbImport.hasAllFilesAccess()) {
            Ui.dialog(this)
                .setTitle("Zugriff auf den USB-Stick")
                .setMessage(
                    "Damit PoloRetro Dateien vom Stick lesen kann, braucht die App die Berechtigung " +
                        "\"Zugriff auf alle Dateien\". Du kannst sie in den Android-Einstellungen erlauben " +
                        "oder stattdessen den Ordner auf dem Stick selbst auswählen.",
                )
                .setPositiveButton("Einstellungen öffnen") { _, _ -> openAllFilesSettings() }
                .setNeutralButton("Ordner wählen") { _, _ -> pickFolder() }
                .setNegativeButton("Abbrechen", null)
                .show()
            return
        }
        Toast.makeText(this, "Suche nach Spielen auf dem Stick ...", Toast.LENGTH_SHORT).show()
        Thread {
            val found = ArrayList<RomSource>()
            val folders = ArrayList<File>()
            val seen = HashSet<String>()
            try {
                for (root in UsbImport.candidateRoots(this)) {
                    val sources = UsbImport.scanFolder(root)
                    if (sources.isNotEmpty()) folders.add(UsbImport.libraryFolder(root))
                    for (s in sources) if (seen.add(s.name.lowercase())) found.add(s)
                }
            } catch (e: Exception) {
                DiagLog.add("USB-Suche: ${e.javaClass.simpleName}: ${e.message}")
            }
            runOnUiThread { offerImport(found, folders) }
        }.start()
    }

    private fun openAllFilesSettings() {
        val uri = Uri.parse("package:$packageName")
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri))
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (_: ActivityNotFoundException) {
                Ui.dialog(this)
                    .setMessage("Dieses Radio hat keine Einstellungsseite dafür. Wähle stattdessen \"Ordner wählen\", oder kopiere die Dateien per Dateimanager in den App-Ordner.")
                    .setPositiveButton("Ordner wählen") { _, _ -> pickFolder() }
                    .setNegativeButton("Abbrechen", null)
                    .show()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun pickFolder() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE)
        } catch (_: ActivityNotFoundException) {
            Ui.dialog(this)
                .setMessage(
                    "Dieses Radio hat keine Dateiauswahl. Kopiere die ROMs mit einem Dateimanager in diesen Ordner:\n" +
                        (paths.externalRomDir?.absolutePath ?: paths.romDir.absolutePath),
                )
                .setPositiveButton("OK", null)
                .show()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_TREE || resultCode != RESULT_OK) return
        val tree = data?.data ?: return
        Thread {
            val found = try {
                UsbImport.scanTree(this, tree)
            } catch (e: Exception) {
                DiagLog.add("Ordner lesen: ${e.javaClass.simpleName}: ${e.message}")
                emptyList()
            }
            runOnUiThread { offerImport(found, emptyList()) }
        }.start()
    }

    /** [folders] sind direkt lesbare Ordner auf dem Stick. Nur dann ist "Vom Stick spielen" möglich. */
    private fun offerImport(found: List<RomSource>, folders: List<File>) {
        if (found.isEmpty()) {
            Ui.dialog(this)
                .setTitle("Nichts gefunden")
                .setMessage(
                    "Es wurden keine Spiele gefunden. Lege die Dateien (.sfc .smc .md .gen .smd .bin .z64 .n64 .v64 .nes .gb .gbc .gba .sms .gg oder ZIP) " +
                        "in einen Ordner \"${UsbImport.STICK_FOLDER}\" oder \"Roms\" auf dem Stick und prüfe, ob der Stick eingesteckt ist.",
                )
                .setPositiveButton("Ordner wählen") { _, _ -> pickFolder() }
                .setNegativeButton("Schließen", null)
                .show()
            return
        }
        val mb = UsbImport.totalBytes(found) / (1024 * 1024)
        val dest = paths.romDir
        val builder = Ui.dialog(this)
            .setTitle("${found.size} Spiele gefunden")
            .setMessage(
                "Gesamtgröße etwa $mb MB.\n\n" +
                    "Kopieren: Die Spiele liegen danach auf dem Radio, der Stick wird nicht mehr gebraucht.\n" +
                    if (folders.isNotEmpty()) "Vom Stick spielen: Der Ordner wird gemerkt, die Spiele bleiben auf dem Stick und " +
                        "erscheinen immer in der Liste. Spielstände liegen immer auf dem Radio."
                    else "",
            )
            .setPositiveButton("Auf Radio kopieren") { _, _ -> runImport(found, dest) }
            .setNegativeButton("Abbrechen", null)
        if (folders.isNotEmpty()) {
            builder.setNeutralButton("Vom Stick spielen") { _, _ ->
                for (f in folders) prefs.addLibraryFolder(f.absolutePath)
                DiagLog.add("Bibliotheksordner gemerkt: ${folders.joinToString { it.absolutePath }}")
                Toast.makeText(this, "Ordner gemerkt", Toast.LENGTH_SHORT).show()
                refresh()
            }
        }
        builder.show()
    }

    private fun runImport(sources: List<RomSource>, dest: File) {
        val need = UsbImport.totalBytes(sources) + 200L * 1024 * 1024
        if (dest.usableSpace < need) {
            Ui.dialog(this).setMessage("Nicht genug freier Speicher auf dem Radio.").setPositiveButton("OK", null).show()
            return
        }
        cancelImport = false
        val text = Ui.label(this, "Starte ...", 16f)
        text.setPadding(Ui.dp(this, 24), Ui.dp(this, 16), Ui.dp(this, 24), Ui.dp(this, 8))
        progressText = text
        progressDialog = Ui.dialog(this)
            .setTitle("Import läuft")
            .setView(text)
            .setCancelable(false)
            .setNegativeButton("Abbrechen") { _, _ -> cancelImport = true }
            .show()
        Thread {
            val summary = try {
                UsbImport.copy(
                    sources, dest,
                    progress = { done, total, name, bytes ->
                        runOnUiThread {
                            progressText?.text = if (name.isEmpty()) "Fertig" else
                                "${done + 1} von $total\n$name\n${bytes / (1024 * 1024)} MB kopiert"
                        }
                    },
                    cancelled = { cancelImport },
                )
            } catch (e: Exception) {
                DiagLog.add("Import: ${e.javaClass.simpleName}: ${e.message}")
                UsbImport.Summary(0, 0, sources.size, false)
            }
            runOnUiThread {
                progressDialog?.dismiss()
                progressDialog = null
                val msg = buildString {
                    append("${summary.copied} kopiert")
                    if (summary.skipped > 0) append(", ${summary.skipped} schon vorhanden")
                    if (summary.failed > 0) append(", ${summary.failed} fehlgeschlagen")
                    if (summary.cancelled) append(" (abgebrochen)")
                }
                DiagLog.add("Import: $msg")
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                refresh()
            }
        }.start()
    }

    companion object {
        private const val REQ_TREE = 4711
    }
}
