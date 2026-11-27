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

/** Startseite: Spieleliste, Suche und Zugang zu den Einstellungen (dort USB-Import und Steuerung). */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var paths: AppPaths
    private lateinit var library: RomLibrary

    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private lateinit var search: EditText
    private val navItems = ArrayList<Pair<Button, () -> Boolean>>()
    private val systemButtons = LinkedHashMap<RetroSystem, Button>()
    private lateinit var resumeCard: LinearLayout
    private lateinit var resumeCover: android.widget.ImageView
    private lateinit var resumeTitle: TextView
    private lateinit var resumeNote: TextView
    private lateinit var listHeader: TextView
    private var resumeGame: Game? = null
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) FullScreen.apply(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagLog.init(this)
        prefs = Prefs(this)
        paths = AppPaths(this)
        library = RomLibrary(this)
        setContentView(buildUi())
        FullScreen.apply(this)
        window.statusBarColor = Ui.BG
    }

    override fun onResume() {
        super.onResume()
        clearSearch()
        refresh()
        startStickWatch()
        when (intent?.getStringExtra(EXTRA_ACTION)) {
            ACTION_IMPORT -> { intent.removeExtra(EXTRA_ACTION); startImport() }
            ACTION_PICK -> { intent.removeExtra(EXTRA_ACTION); pickFolder() }
            ACTION_DELETE -> { intent.removeExtra(EXTRA_ACTION); deleteGames() }
        }
    }

    override fun onPause() {
        super.onPause()
        stopStickWatch()
    }

    // ---------------------------------------------------------------- USB-Stick beobachten

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var stickReceiver: android.content.BroadcastReceiver? = null
    private var pollUntil = 0L
    private var lastUsbState = ""
    private val pollRunnable = Runnable { refresh() }
    private val eventRunnable = Runnable { pollUntil = System.currentTimeMillis() + 60_000; refresh() }

    /**
     * Nach dem Hochfahren meldet das Radio den Stick oft erst spät oder gar nicht. Die App hört auf die Mount-
     * und USB-Meldungen, liest die Liste neu und fragt, solange ein Stick fehlt, drei Minuten lang alle paar
     * Sekunden nach. Den Zustand schreibt sie ins Protokoll (Einstellungen -> Protokoll).
     */
    private fun startStickWatch() {
        pollUntil = System.currentTimeMillis() + 180_000
        logUsbState("Start")
        if (stickReceiver != null) return
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, i: Intent?) {
                DiagLog.add("USB/Speicher-Meldung: ${i?.action}")
                handler.removeCallbacks(eventRunnable)
                handler.postDelayed(eventRunnable, 1500)
            }
        }
        val media = android.content.IntentFilter().apply {
            for (a in listOf(
                Intent.ACTION_MEDIA_MOUNTED, Intent.ACTION_MEDIA_UNMOUNTED, Intent.ACTION_MEDIA_REMOVED,
                Intent.ACTION_MEDIA_BAD_REMOVAL, Intent.ACTION_MEDIA_EJECT, Intent.ACTION_MEDIA_CHECKING,
            )) addAction(a)
            addDataScheme("file")
        }
        val usb = android.content.IntentFilter().apply {
            addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        try {
            registerReceiver(r, media)
            registerReceiver(r, usb)
            stickReceiver = r
        } catch (t: Throwable) {
            DiagLog.add("USB-Beobachtung nicht möglich: ${t.javaClass.simpleName}")
        }
    }

    private fun stopStickWatch() {
        handler.removeCallbacks(pollRunnable)
        handler.removeCallbacks(eventRunnable)
        stickReceiver?.let { try { unregisterReceiver(it) } catch (_: Throwable) {} }
        stickReceiver = null
    }

    private fun logUsbState(reason: String) {
        val sb = StringBuilder()
        try {
            val um = getSystemService(android.content.Context.USB_SERVICE) as android.hardware.usb.UsbManager
            val devs = um.deviceList.values
            sb.append("USB-Geräte: ")
            if (devs.isEmpty()) sb.append("keine")
            for (d in devs) {
                val classes = (0 until d.interfaceCount).joinToString("/") { d.getInterface(it).interfaceClass.toString() }
                sb.append("[%04x:%04x Klassen %s] ".format(d.vendorId, d.productId, classes))
            }
        } catch (t: Throwable) {
            sb.append("USB-Geräte: nicht lesbar (${t.javaClass.simpleName}) ")
        }
        try {
            val sm = getSystemService(android.content.Context.STORAGE_SERVICE) as android.os.storage.StorageManager
            sb.append("| Laufwerke: ")
            for (v in sm.storageVolumes) if (!v.isPrimary) sb.append("[${v.getDescription(this)}: ${v.state}] ")
            sb.append("| lesbar: ").append(UsbImport.candidateRoots(this).joinToString { it.absolutePath }.ifEmpty { "keins" })
        } catch (t: Throwable) {
            sb.append("| Laufwerke: nicht lesbar (${t.javaClass.simpleName})")
        }
        val text = sb.toString()
        if (text != lastUsbState) {
            lastUsbState = text
            DiagLog.add("Stick-Zustand ($reason): $text")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    // ---------------------------------------------------------------- Oberfläche

    private fun buildUi(): View {
        val ctx = this
        val root = Ui.vertical(ctx).apply { setBackgroundColor(Ui.BG) }
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

        // ---- Seitenleiste
        val side = Ui.vertical(ctx).apply {
            setBackgroundColor(0xFF0E1216.toInt())
            setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 10), Ui.dp(ctx, 10), Ui.dp(ctx, 4))
        }
        val logoRow = Ui.horizontal(ctx).apply { setPadding(Ui.dp(ctx, 4), 0, 0, Ui.dp(ctx, 8)) }
        val logo = Ui.label(ctx, "Z", 17f, Ui.BG, true).apply {
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Ui.ACCENT)
                cornerRadius = Ui.dp(ctx, 8).toFloat()
            }
        }
        logoRow.addView(logo, LinearLayout.LayoutParams(Ui.dp(ctx, 30), Ui.dp(ctx, 30)))
        logoRow.addView(Ui.label(ctx, "Zockpit", 19f, Ui.TEXT, true).apply { setPadding(Ui.dp(ctx, 8), 0, 0, 0) })
        side.addView(logoRow)

        side.addView(navButton("Alle Spiele", { tab == 0 }, { selectTab(0) }))
        side.addView(navButton("Zuletzt", { tab == 1 }, { selectTab(1) }))
        side.addView(navButton("Favoriten", { tab == 2 }, { selectTab(2) }))
        side.addView(Ui.label(ctx, "KONSOLEN", 10f, Ui.MUTED, true).apply {
            setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 8), 0, Ui.dp(ctx, 2))
        })
        val consoles = Ui.vertical(ctx)
        for (sys in RetroSystem.values()) {
            val b = navButton(sys.label, { tab == 3 && systemFilter == sys }, { selectSystem(sys) }, small = true)
            b.visibility = View.GONE
            systemButtons[sys] = b
            consoles.addView(b)
        }
        val scroll = android.widget.ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            addView(consoles)
        }
        side.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        body.addView(side, LinearLayout.LayoutParams(Ui.dp(ctx, 165), ViewGroup.LayoutParams.MATCH_PARENT))

        // ---- Hauptbereich
        val main = Ui.vertical(ctx).apply {
            setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 10), Ui.dp(ctx, 20), Ui.dp(ctx, 2))
        }
        val top = Ui.horizontal(ctx)
        search = EditText(ctx).apply {
            hint = "Spiel suchen ..."
            setHintTextColor(Ui.MUTED)
            setTextColor(Ui.TEXT)
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            background = Ui.focusBackground(ctx, Ui.SURFACE, 20)
            setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 4), Ui.dp(ctx, 14), Ui.dp(ctx, 4))
            minHeight = Ui.dp(ctx, 38)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString()?.trim().orEmpty()
                    applyFilter()
                }
            })
        }
        top.addView(search, Ui.weight())
        for ((text, action) in listOf<Pair<String, () -> Unit>>(
            "Einstellungen" to { startActivity(Intent(this, SettingsActivity::class.java)) },
        )) {
            top.addView(Ui.spacer(ctx, 8))
            top.addView(Ui.button(ctx, text, sizeSp = 13f, onClick = action).apply {
                background = Ui.focusBackground(ctx, Ui.SURFACE, 20)
                minHeight = Ui.dp(ctx, 38)
                minimumHeight = Ui.dp(ctx, 38)
                setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 2), Ui.dp(ctx, 14), Ui.dp(ctx, 2))
            })
        }
        main.addView(top)

        // ---- Weiterspielen
        resumeCard = Ui.horizontal(ctx).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Ui.SURFACE)
                cornerRadius = Ui.dp(ctx, 16).toFloat()
            }
            setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 8), Ui.dp(ctx, 14), Ui.dp(ctx, 8))
            visibility = View.GONE
        }
        resumeCover = android.widget.ImageView(ctx).apply {
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF0E1216.toInt())
                cornerRadius = Ui.dp(ctx, 10).toFloat()
            }
            setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 4), Ui.dp(ctx, 4), Ui.dp(ctx, 4))
        }
        resumeCard.addView(resumeCover, LinearLayout.LayoutParams(Ui.dp(ctx, 108), Ui.dp(ctx, 76)))
        val rtext = Ui.vertical(ctx)
        rtext.addView(Ui.label(ctx, "WEITERSPIELEN", 10f, Ui.ACCENT, true))
        resumeTitle = Ui.label(ctx, "", 18f, Ui.TEXT, true).apply {
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        rtext.addView(resumeTitle)
        resumeNote = Ui.label(ctx, "", 12f, Ui.MUTED)
        rtext.addView(resumeNote)
        val resumeBtn = Ui.button(ctx, "Fortsetzen", fill = Ui.ACCENT, textColor = 0xFF12161B.toInt(), sizeSp = 14f, onClick = {
            resumeGame?.let { startGame(it) }
        }).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = Ui.focusBackground(ctx, Ui.ACCENT, 20)
            minHeight = Ui.dp(ctx, 34)
            minimumHeight = Ui.dp(ctx, 34)
            setPadding(Ui.dp(ctx, 18), Ui.dp(ctx, 2), Ui.dp(ctx, 18), Ui.dp(ctx, 2))
        }
        val btnLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        btnLp.topMargin = Ui.dp(ctx, 6)
        rtext.addView(resumeBtn, btnLp)
        val rtLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        rtLp.leftMargin = Ui.dp(ctx, 14)
        resumeCard.addView(rtext, rtLp)
        val cardLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        cardLp.topMargin = Ui.dp(ctx, 8)
        main.addView(resumeCard, cardLp)

        listHeader = Ui.label(ctx, "ALLE SPIELE", 10f, Ui.MUTED, true).apply {
            setPadding(Ui.dp(ctx, 4), 0, 0, 0)
        }
        val hdrLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        hdrLp.topMargin = Ui.dp(ctx, 8)
        hdrLp.bottomMargin = Ui.dp(ctx, 4)
        main.addView(listHeader, hdrLp)

        listView = ListView(ctx).apply {
            divider = null
            dividerHeight = Ui.dp(ctx, 5)
            selector = android.graphics.drawable.ColorDrawable(0)
            adapter = this@MainActivity.adapter
            isFocusable = true
            // Die Zeilen selbst bekommen den Fokus, damit man sie mit dem Controller anwählen kann.
            itemsCanFocus = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        emptyView = Ui.label(ctx, "", 17f, Ui.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(Ui.dp(ctx, 24), Ui.dp(ctx, 24), Ui.dp(ctx, 24), 0)
        }
        val listLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        main.addView(listView, listLp)
        main.addView(emptyView, listLp)
        body.addView(main, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- Tastenleiste unten
        val hints = Ui.horizontal(ctx).apply {
            setBackgroundColor(0xFF0A0D10.toInt())
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(ctx, 3), 0, Ui.dp(ctx, 18))
        }
        hints.addView(hint("A", "Starten"))
        hints.addView(hint("X", "Spielmenü"))
        hints.addView(hint("Y", "Favorit"))
        root.addView(hints, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        updateNav()
        return root
    }

    /** Gespielte Zeit als Text, null unter einer Minute. */
    private fun playTimeText(game: Game): String? {
        val minutes = prefs.playSeconds(game.system, game.saveKey) / 60
        return when {
            minutes <= 0 -> null
            minutes < 60 -> "$minutes Min gespielt"
            else -> "${minutes / 60} Std ${minutes % 60} Min gespielt"
        }
    }

    private fun hint(key: String, text: String): View {
        val ctx = this
        val row = Ui.horizontal(ctx).apply { setPadding(Ui.dp(ctx, 14), 0, Ui.dp(ctx, 14), 0) }
        val k = Ui.label(ctx, key, 11f, Ui.TEXT, true).apply {
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Ui.SURFACE_HI)
                cornerRadius = Ui.dp(ctx, 10).toFloat()
            }
        }
        row.addView(k, LinearLayout.LayoutParams(Ui.dp(ctx, 20), Ui.dp(ctx, 20)))
        row.addView(Ui.label(ctx, text, 12f, 0xFFC5CFD9.toInt()).apply { setPadding(Ui.dp(ctx, 7), 0, 0, 0) })
        return row
    }

    private fun navButton(text: String, active: () -> Boolean, onClick: () -> Unit, small: Boolean = false): Button {
        val ctx = this
        val b = Ui.button(ctx, text, sizeSp = if (small) 12f else 14f, onClick = onClick).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            minHeight = Ui.dp(ctx, if (small) 32 else 38)
            minimumHeight = Ui.dp(ctx, if (small) 32 else 38)
            setPadding(Ui.dp(ctx, 12), 0, Ui.dp(ctx, 8), 0)
        }
        navItems.add(b to active)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = Ui.dp(ctx, 1)
        b.layoutParams = lp
        return b
    }

    private fun updateNav() {
        for ((b, active) in navItems) {
            val on = active()
            b.background = Ui.focusBackground(this, if (on) Ui.SURFACE_HI else 0, 10)
            b.setTextColor(if (on) Ui.TEXT else 0xFFC5CFD9.toInt())
            b.setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        listHeader.text = when (tab) {
            1 -> "ZULETZT GESPIELT"
            2 -> "FAVORITEN"
            3 -> (systemFilter?.label ?: "ALLE KONSOLEN").uppercase()
            else -> "ALLE SPIELE"
        }
    }

    /** Suchfeld leeren: Die Suche gilt nur, solange man sie benutzt. */
    private fun clearSearch() {
        if (!::search.isInitialized) return
        if (query.isEmpty() && search.text.isNullOrEmpty()) return
        query = ""
        search.setText("")
    }

    private fun selectTab(index: Int) {
        clearSearch()
        tab = index
        systemFilter = null
        updateNav()
        applyFilter()
    }

    private fun selectSystem(sys: RetroSystem) {
        clearSearch()
        tab = 3
        systemFilter = sys
        updateNav()
        applyFilter()
    }

    /** Große Karte "Weiterspielen" mit dem zuletzt gespielten Spiel (nur in "Alle Spiele" ohne Suche). */
    private fun updateResume() {
        var g: Game? = null
        if (tab == 0 && query.isEmpty()) {
            for (k in prefs.recents()) {
                g = allGames.firstOrNull { it.key == k && it.available }
                if (g != null) break
            }
        }
        resumeGame = g
        if (g == null) {
            resumeCard.visibility = View.GONE
            return
        }
        resumeCard.visibility = View.VISIBLE
        val cover = coverFor(g)
        resumeCover.setImageBitmap(cover)
        resumeTitle.text = g.title
        resumeNote.text = g.system.label + (if (paths.autoStateFile(g).exists()) "  ·  Fortsetzen möglich" else "") +
            (playTimeText(g)?.let { "  ·  $it" } ?: "")
    }

    // ---------------------------------------------------------------- Liste

    private fun refresh() {
        Thread {
            val games = library.scan()
            runOnUiThread {
                allGames = games
                applyFilter()
                autoCovers(games)
                logUsbState("Liste")
                handler.removeCallbacks(pollRunnable)
                if (!isFinishing && games.any { !it.available } && System.currentTimeMillis() < pollUntil) {
                    handler.postDelayed(pollRunnable, 4000)
                }
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
                if (result.renamed > 0) runOnUiThread { if (!isFinishing) { coverCache.evictAll(); refresh() } }
                else if (result.found > 0) runOnUiThread { if (!isFinishing) { coverCache.evictAll(); adapter.notifyDataSetChanged() } }
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
        for ((sys, b) in systemButtons) {
            val n = allGames.count { it.system == sys }
            b.visibility = if (n > 0 || (tab == 3 && systemFilter == sys)) View.VISIBLE else View.GONE
            b.text = sys.label + if (n > 0) "  ($n)" else ""
        }
        updateNav()
        updateResume()
        adapter.notifyDataSetChanged()
        val empty = list.isEmpty()
        listView.visibility = if (empty) View.GONE else View.VISIBLE
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        if (empty) {
            emptyView.text = if (allGames.isEmpty()) {
                "Noch keine Spiele.\n\nLege deine ROM-Dateien in den Ordner \"${UsbImport.STICK_FOLDER}\" oder \"Roms\" auf einem USB-Stick (Unterordner sind erlaubt, die Konsole erkennt die App selbst), " +
                    "stecke ihn ins Radio und öffne oben \"Einstellungen\" und tippe auf \"USB-Import\".\n\n" +
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

    /** Spiele löschen: erst Konsole wählen, dann einzelne Spiele ankreuzen oder alle auswählen. */
    private fun deleteGames() {
        val local = allGames.filter { !it.external && it.file.exists() }
        val systems = RetroSystem.values().filter { sys -> local.any { it.system == sys } }
        if (systems.isEmpty()) {
            Toast.makeText(this, "Keine Spiele auf dem Radio", Toast.LENGTH_SHORT).show()
            return
        }
        val names = systems.map { sys -> "${sys.label} (${local.count { it.system == sys }})" }.toTypedArray()
        Ui.dialog(this).setTitle("Spiele löschen: Konsole wählen")
            .setItems(names) { _, which -> deleteGamesOf(systems[which], local.filter { it.system == systems[which] }.sortedBy { it.title.lowercase() }) }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun deleteGamesOf(system: RetroSystem, games: List<Game>) {
        val titles = games.map { it.title }.toTypedArray()
        val dialog = Ui.dialog(this).setTitle("${system.label}: Spiele zum Löschen wählen")
            .setMultiChoiceItems(titles, BooleanArray(games.size), null)
            .setPositiveButton("Löschen", null)
            .setNeutralButton("Alle auswählen", null)
            .setNegativeButton("Abbrechen", null)
            .create()
        dialog.show()
        val lv = dialog.listView
        var allOn = false
        dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener { b ->
            allOn = !allOn
            for (i in games.indices) lv.setItemChecked(i, allOn)
            (b as Button).text = if (allOn) "Keine auswählen" else "Alle auswählen"
        }
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val chosen = games.indices.filter { lv.isItemChecked(it) }.map { games[it] }
            if (chosen.isEmpty()) {
                Toast.makeText(this, "Nichts ausgewählt", Toast.LENGTH_SHORT).show()
            } else {
                dialog.dismiss()
                confirm("${chosen.size} Spiel(e) und ihre Spielstände von diesem Gerät löschen?") {
                    for (g in chosen) {
                        paths.deleteSaves(g)
                        g.file.delete()
                    }
                    Toast.makeText(this, "${chosen.size} gelöscht", Toast.LENGTH_SHORT).show()
                    refresh()
                }
            }
        }
    }

    private fun confirm(message: String, action: () -> Unit) {
        Ui.dialog(this).setMessage(message)
            .setPositiveButton("Ja") { _, _ -> action() }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun startGame(game: Game) {
        clearSearch()
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
                if (event.keyCode == KeyEvent.KEYCODE_BUTTON_Y) {
                    val g = focus.tag as? Game
                    if (g != null) {
                        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                            prefs.toggleFavorite(g.key)
                            val pos = listView.getPositionForView(focus)
                            if (tab == 2) applyFilter() else adapter.notifyDataSetChanged()
                            listView.post {
                                val c = listView.getChildAt(pos - listView.firstVisiblePosition)
                                if (c != null) c.requestFocus() else listView.requestFocus()
                            }
                        }
                        return true
                    }
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
                background = Ui.focusBackground(ctx, Ui.SURFACE, 12)
                minimumHeight = Ui.dp(ctx, 54)
                setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 5), Ui.dp(ctx, 12), Ui.dp(ctx, 5))
                isFocusable = true
                isClickable = true
                setOnClickListener { startGame(game) }
                setOnLongClickListener {
                    gameMenu(game)
                    true
                }
            }
            row.tag = game
            val bmp = coverFor(game)
            val img = android.widget.ImageView(ctx).apply {
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF0E1216.toInt())
                    cornerRadius = Ui.dp(ctx, 7).toFloat()
                }
                if (bmp != null) setImageBitmap(bmp)
            }
            row.addView(img, LinearLayout.LayoutParams(Ui.dp(ctx, 68), Ui.dp(ctx, 44)))

            val col = Ui.vertical(ctx)
            col.addView(Ui.label(ctx, game.title, 15f, Ui.TEXT, true).apply {
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            val notes = ArrayList<String>()
            notes.add(game.system.label)
            if (!game.available) notes.add("Stick nicht eingesteckt")
            else if (game.external) notes.add("auf USB-Stick")
            if (paths.autoStateFile(game).exists()) notes.add("Fortsetzen möglich")
            playTimeText(game)?.let { notes.add(it) }
            if (game.key in prefs.favorites()) notes.add("Favorit")
            col.addView(Ui.label(ctx, notes.joinToString("  ·  "), 11f, Ui.MUTED))
            val colLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            colLp.leftMargin = Ui.dp(ctx, 10)
            row.addView(col, colLp)
            val badge = Ui.label(ctx, game.system.shortLabel, 11f, 0xFF12161B.toInt(), true).apply {
                gravity = Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(badgeColor(game.system))
                    cornerRadius = Ui.dp(ctx, 8).toFloat()
                }
                setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 3), Ui.dp(ctx, 8), Ui.dp(ctx, 3))
            }
            row.addView(badge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
                    "Damit Zockpit Dateien vom Stick lesen kann, braucht die App die Berechtigung " +
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
                    DiagLog.add("USB-Suche: Stick ${root.absolutePath}, Ordner: " +
                        (root.listFiles()?.filter { it.isDirectory }?.joinToString { it.name } ?: "nicht lesbar"))
                    for ((folder, sources) in UsbImport.scanFolders(root)) {
                        DiagLog.add("USB-Suche: ${folder.absolutePath}: ${sources.size} Spiele")
                        if (sources.isNotEmpty()) folders.add(folder)
                        for (s in sources) if (seen.add(s.name.lowercase())) found.add(s)
                    }
                    if (UsbImport.skipped.isNotEmpty()) {
                        DiagLog.add("USB-Suche: nicht erkannte Dateien (Endung: Anzahl): " +
                            UsbImport.skipped.entries.sortedByDescending { it.value }.take(12).joinToString { "${it.key}: ${it.value}" })
                    }
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
                    "Die Spiele werden auf das Radio kopiert, danach wird der Stick nicht mehr gebraucht. " +
                    "Spielstände liegen immer auf dem Radio.",
            )
            .setPositiveButton("Auf Radio kopieren") { _, _ -> runImport(found, dest) }
            .setNegativeButton("Abbrechen", null)
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
        const val EXTRA_ACTION = "action"
        const val ACTION_IMPORT = "import"
        const val ACTION_PICK = "pick"
        const val ACTION_DELETE = "delete"
        private const val REQ_TREE = 4711
    }
}
