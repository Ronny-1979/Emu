package de.ronny.poloretro

import android.view.KeyEvent
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private var failures = 0
private var checks = 0

private fun check(name: String, ok: Boolean) {
    checks++
    if (!ok) { failures++; println("FEHLER: $name") }
}

private fun <T> eq(name: String, actual: T, expected: T) {
    checks++
    if (actual != expected) { failures++; println("FEHLER: $name -> erwartet <$expected>, war <$actual>") }
}

private fun zipOf(file: File, entries: Map<String, ByteArray>) {
    ZipOutputStream(file.outputStream()).use { z ->
        for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
    }
}

fun main() {
    val tmp = File(System.getProperty("java.io.tmpdir"), "poloretro-test-" + System.nanoTime()).apply { mkdirs() }

    // ---- RomNames / RetroSystem
    eq("ext", RomNames.extension("Super Mario World.SFC"), "sfc")
    eq("ext none", RomNames.extension("README"), "")
    eq("system snes", RomNames.systemFor("a.smc"), RetroSystem.SNES)
    eq("system md", RomNames.systemFor("a.md"), RetroSystem.MEGADRIVE)
    eq("system n64", RomNames.systemFor("a.z64"), RetroSystem.N64)
    eq("system unknown", RomNames.systemFor("a.txt"), null)
    check("zip candidate", RomNames.isCandidate("x.zip"))
    check("txt no candidate", !RomNames.isCandidate("x.txt"))
    eq("title", RomNames.title("Sonic_The_Hedgehog (USA, Europe) [!].md"), "Sonic The Hedgehog")
    eq("title only tags", RomNames.title("(USA).md").isNotEmpty(), true)
    eq("saveKey", RomNames.saveKey(File("/x/Super Mario 64 (U).z64")), "Super_Mario_64__U")
    eq("saveKey empty", RomNames.saveKey(File("/x/§§§.z64")), "game")

    // ---- ZipRom
    val z1 = File(tmp, "a.zip")
    zipOf(z1, mapOf("readme.txt" to byteArrayOf(1), "sub/Game.sfc" to ByteArray(10) { 7 }))
    val found = ZipRom.find(z1)
    eq("zip find system", found?.system, RetroSystem.SNES)
    val outDir = File(tmp, "out").apply { mkdirs() }
    val ex = ZipRom.extract(z1, outDir)
    eq("zip extract name", ex?.first?.name, "Game.sfc")
    eq("zip extract size", ex?.first?.length(), 10L)
    val z2 = File(tmp, "evil.zip")
    zipOf(z2, mapOf("../../evil.md" to ByteArray(4)))
    val ex2 = ZipRom.extract(z2, File(tmp, "out2").apply { mkdirs() })
    check("zip slip bleibt im Ordner", ex2 == null || ex2.first.canonicalPath.startsWith(File(tmp, "out2").canonicalPath))
    check("zip slip nichts ausserhalb", !File(tmp.parentFile, "evil.md").exists() && !File(tmp, "../evil.md").exists())
    val z3 = File(tmp, "none.zip")
    zipOf(z3, mapOf("a.txt" to byteArrayOf(1)))
    eq("zip ohne ROM", ZipRom.find(z3), null)
    val z4 = File(tmp, "broken.zip").apply { writeText("kein zip") }
    eq("kaputtes zip find", try { ZipRom.find(z4) } catch (e: Exception) { "exception" }, null)

    // ---- ControllerMapping
    val d = ControllerMapping.default()
    eq("default A->B(retro)", d.retroKey(KeyEvent.KEYCODE_BUTTON_A), KeyEvent.KEYCODE_BUTTON_B)
    eq("default B->A(retro)", d.retroKey(KeyEvent.KEYCODE_BUTTON_B), KeyEvent.KEYCODE_BUTTON_A)
    eq("default X->Y(retro)", d.retroKey(KeyEvent.KEYCODE_BUTTON_X), KeyEvent.KEYCODE_BUTTON_Y)
    eq("default dpad", d.retroKey(KeyEvent.KEYCODE_DPAD_UP), KeyEvent.KEYCODE_DPAD_UP)
    val m = d.withBinding(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_R1)
    eq("bind neu", m.retroKey(KeyEvent.KEYCODE_BUTTON_R1), KeyEvent.KEYCODE_BUTTON_A)
    eq("bind eindeutig (alte Taste von retro A frei)", m.retroKey(KeyEvent.KEYCODE_BUTTON_B), null)
    eq("bind physicalFor", m.physicalFor(KeyEvent.KEYCODE_BUTTON_A), KeyEvent.KEYCODE_BUTTON_R1)
    val dp = d.withBinding(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_BUTTON_L1)
    eq("dpad bleibt belegt", dp.retroKey(KeyEvent.KEYCODE_DPAD_UP), KeyEvent.KEYCODE_DPAD_UP)
    val rt = ControllerMapping.parse(m.serialize())
    eq("serialize roundtrip", rt.serialize(), m.serialize())
    eq("parse null", ControllerMapping.parse(null).serialize(), d.serialize())
    eq("parse kaputt", ControllerMapping.parse("abc").serialize(), d.serialize())
    eq("parse negativ", ControllerMapping.parse("-1:5").serialize(), d.serialize())
    check("parse ergaenzt dpad", ControllerMapping.parse("96:97").isMapped(KeyEvent.KEYCODE_DPAD_LEFT))

    // ---- SystemButtons
    check("SNES hat 12 Eintraege", SystemButtons.forSystem(RetroSystem.SNES).size >= 8)
    check("MD6 mehr als MD3", SystemButtons.forSystem(RetroSystem.MEGADRIVE, true).size > SystemButtons.forSystem(RetroSystem.MEGADRIVE, false).size)
    check("N64 Z vorhanden", SystemButtons.forSystem(RetroSystem.N64).any { it.label.contains("Z") })
    eq("Name A", SystemButtons.physicalName(KeyEvent.KEYCODE_BUTTON_A), "A (unten)")
    check("Name unbekannt", SystemButtons.physicalName(9999).contains("9999"))

    // ---- AxisKeys
    val ak = AxisKeys()
    eq("trigger unten", ak.updateTriggers(0.3f, 0f).size, 0)
    val e1 = ak.updateTriggers(0.7f, 0f)
    check("trigger druecken", e1.size == 1 && e1[0].down && e1[0].keyCode == KeyEvent.KEYCODE_BUTTON_L2)
    eq("trigger hysterese haelt", ak.updateTriggers(0.5f, 0f).size, 0)
    val e2 = ak.updateTriggers(0.3f, 0f)
    check("trigger loslassen", e2.size == 1 && !e2[0].down)
    val e3 = ak.updateTriggers(0f, 0.9f)
    check("R2", e3.size == 1 && e3[0].keyCode == KeyEvent.KEYCODE_BUTTON_R2 && e3[0].down)
    ak.reset()
    eq("reset", ak.updateTriggers(0f, 0f).size, 0)
    eq("deadzone klein", AxisKeys.deadzone(0.1f, 0.05f), 0f to 0f)
    val dz = AxisKeys.deadzone(1f, 0f)
    check("deadzone voll", Math.abs(dz.first - 1f) < 0.001f && dz.second == 0f)
    eq("hat", listOf(AxisKeys.hat(0.9f), AxisKeys.hat(-0.9f), AxisKeys.hat(0.2f)), listOf(1f, -1f, 0f))

    // ---- UsbImport.safeName
    eq("safeName pfad", UsbImport.safeName("../../x/y.sfc"), "y.sfc")
    eq("safeName win", UsbImport.safeName("C:\\a\\b.md"), "b.md")
    eq("safeName leer", UsbImport.safeName("  "), "rom.bin")
    eq("safeName punkte", UsbImport.safeName(".."), "rom.bin")

    // ---- SaveBackup
    val base = File(tmp, "base")
    File(base, "sram/snes").mkdirs(); File(base, "states/md").mkdirs(); File(base, "roms").mkdirs()
    File(base, "sram/snes/a.srm").writeBytes(ByteArray(100) { 1 })
    File(base, "states/md/b.slot1.state").writeBytes(ByteArray(50) { 2 })
    File(base, "roms/big.sfc").writeBytes(ByteArray(10))
    val backup = File(tmp, "usb/PoloRetro/" + SaveBackup.FILE_NAME)
    val n = SaveBackup.export(base, listOf("saves", "sram", "states"), backup)
    eq("backup Anzahl", n, 2)
    check("backup Datei da", backup.isFile && backup.length() > 0)
    check("backup keine .part", !File(backup.parentFile, backup.name + ".part").exists())
    val base2 = File(tmp, "base2").apply { mkdirs() }
    val r = SaveBackup.restore(backup, base2, setOf("saves", "sram", "states"))
    eq("restore Anzahl", r, 2)
    eq("restore Inhalt", File(base2, "sram/snes/a.srm").readBytes().size, 100)
    check("ROMs nicht gesichert", !File(base2, "roms").exists())
    val evil = File(tmp, "evilbackup.zip")
    zipOf(evil, mapOf("sram/ok.srm" to ByteArray(3), "../escape.txt" to ByteArray(3), "roms/x.sfc" to ByteArray(3), "sram/../../escape2.txt" to ByteArray(3)))
    val base3 = File(tmp, "base3").apply { mkdirs() }
    val r3 = SaveBackup.restore(evil, base3, setOf("saves", "sram", "states"))
    eq("restore evil nur gueltige", r3, 1)
    check("kein Ausbruch", !File(tmp, "escape.txt").exists() && !File(tmp, "escape2.txt").exists() && !File(base3, "roms/x.sfc").exists())


    // ---- Neue Konsolen
    eq("nes", RomNames.systemFor("a.nes"), RetroSystem.NES)
    eq("gb", RomNames.systemFor("a.GB"), RetroSystem.GB)
    eq("gbc", RomNames.systemFor("a.gbc"), RetroSystem.GBC)
    eq("gba", RomNames.systemFor("a.gba"), RetroSystem.GBA)
    eq("sms", RomNames.systemFor("a.sms"), RetroSystem.MASTERSYSTEM)
    eq("gg", RomNames.systemFor("a.gg"), RetroSystem.GAMEGEAR)
    eq("md bleibt", RomNames.systemFor("a.bin"), RetroSystem.MEGADRIVE)
    val allExt = RetroSystem.values().flatMap { it.extensions }
    eq("Endungen eindeutig", allExt.size, allExt.toSet().size)
    eq("Ids eindeutig", RetroSystem.values().map { it.id }.toSet().size, RetroSystem.values().size)
    check("Jede Konsole hat Tasten", RetroSystem.values().all { SystemButtons.forSystem(it).isNotEmpty() })
    check("Jede Konsole hat Start", RetroSystem.values().all { s -> SystemButtons.forSystem(s).any { it.label == "Start" } })
    check("Kern endet auf .so", RetroSystem.values().all { it.coreLib.endsWith("_libretro_android.so") })

    // ---- Cheats
    val cf = File(tmp, "cheats/x.txt")
    eq("cheats leer", CheatStore.load(cf), emptyList<Cheat>())
    CheatStore.save(cf, listOf(Cheat("Leben | unendlich", "SXIOPO", true), Cheat("Geld", "7E0DBF:63", false)))
    val loaded = CheatStore.load(cf)
    eq("cheats Anzahl", loaded.size, 2)
    eq("cheats Name bereinigt", loaded[0].name, "Leben / unendlich")
    eq("cheats an", loaded[0].enabled, true)
    eq("cheats aus", loaded[1].enabled, false)
    eq("cheats Code", loaded[1].code, "7E0DBF:63")
    cf.writeText("kaputt\n1|nur zwei\n0||\n1||CODE-OHNE-NAME")
    val tol = CheatStore.load(cf)
    eq("cheats tolerant", tol.size, 1)
    eq("cheats Name aus Code", tol[0].name, "CODE-OHNE-NAME")

    // ---- Titelbilder
    val romDir = File(tmp, "roms").apply { mkdirs() }
    val rom = File(romDir, "Sonic (USA).md").apply { writeBytes(ByteArray(4)) }
    eq("kein Titelbild", Covers.find(rom, "Sonic__USA", emptyList()), null)
    val extra = File(tmp, "extra").apply { mkdirs() }
    File(extra, "Sonic__USA.png").writeBytes(ByteArray(5))
    eq("Titelbild extra per saveKey", Covers.find(rom, "Sonic__USA", listOf(extra))?.name, "Sonic__USA.png")
    File(romDir, "covers").mkdirs()
    File(romDir, "covers/Sonic (USA).jpg").writeBytes(ByteArray(5))
    eq("Titelbild covers-Unterordner", Covers.find(rom, "Sonic__USA", listOf(extra))?.name, "Sonic (USA).jpg")
    File(romDir, "Sonic (USA).png").writeBytes(ByteArray(5))
    eq("Titelbild neben ROM zuerst", Covers.find(rom, "Sonic__USA", listOf(extra))?.parentFile, romDir)
    File(romDir, "leer.png").writeBytes(ByteArray(0))
    eq("leeres Bild ignoriert", Covers.find(File(romDir, "leer.md"), "leer", emptyList()), null)


    // ---- Titelbild-Adressen
    eq("cover Name bereinigt", CoverNames.fileName("Q*bert: A/B <x>?"), "Q_bert_ A_B _x__")
    eq("cover Varianten", CoverNames.variants("Sonic (USA) [!].md"), listOf("Sonic (USA) [!]", "Sonic (USA)"))
    eq("cover Varianten Tags", CoverNames.variants("Game (Europe) (Rev 1).sfc"), listOf("Game (Europe) (Rev 1)", "Game (Europe)"))
    eq("cover Variante einfach", CoverNames.variants("Tetris.gb"), listOf("Tetris"))
    eq(
        "cover URL",
        CoverNames.url(RetroSystem.SNES, CoverNames.BOXART, "Super Mario World (USA)"),
        "https://thumbnails.libretro.com/Nintendo%20-%20Super%20Nintendo%20Entertainment%20System/Named_Boxarts/Super%20Mario%20World%20%28USA%29.png",
    )
    eq("cover Kandidaten", CoverNames.candidates(RetroSystem.GBA, "A (E) [!].gba").size, 10)
    eq(
        "cover URL sparsam",
        CoverNames.urlLight(RetroSystem.SNES, CoverNames.BOXART, "Yoshi's Island (Europe) (En,Fr,De)"),
        "https://thumbnails.libretro.com/Nintendo%20-%20Super%20Nintendo%20Entertainment%20System/Named_Boxarts/Yoshi's%20Island%20(Europe)%20(En,Fr,De).png",
    )
    val gbaCandidates = CoverNames.candidates(RetroSystem.GBA, "A (E).gba")
    check("cover Spielszene ganz zuletzt", gbaCandidates.last().contains("Named_Snaps"))
    check("cover Titelbildschirm vor Spielszene", gbaCandidates.indexOfFirst { it.contains("Named_Titles") } < gbaCandidates.indexOfLast { it.contains("Named_Snaps") })
    check("cover GitHub zuerst", gbaCandidates.first().startsWith("https://raw.githubusercontent.com/libretro-thumbnails/Nintendo_-_Game_Boy_Advance/master/Named_Boxarts/"))
    eq(
        "cover GitHub-Adresse",
        CoverNames.githubUrl(RetroSystem.SNES, CoverNames.BOXART, "Super Mario All-Stars (Europe)"),
        "https://raw.githubusercontent.com/libretro-thumbnails/Nintendo_-_Super_Nintendo_Entertainment_System/master/Named_Boxarts/Super%20Mario%20All-Stars%20%28Europe%29.png",
    )
    check("jede Konsole hat Thumb-Ordner", RetroSystem.values().all { it.thumbFolder.isNotBlank() && !it.thumbFolder.contains('/') })

    // ---- ROM-Ordner auf dem Stick
    val stick = File(tmp, "stick").apply { mkdirs() }
    File(stick, "roms/SNES").mkdirs(); File(stick, "roms/Mega Drive/Sonic").mkdirs(); File(stick, "roms/N64").mkdirs(); File(stick, "roms/GBA").mkdirs()
    File(stick, "roms/SNES/Mario.sfc").writeBytes(ByteArray(3))
    File(stick, "roms/Mega Drive/Sonic/Sonic.md").writeBytes(ByteArray(3))
    File(stick, "roms/N64/Zelda.z64").writeBytes(ByteArray(3))
    File(stick, "roms/GBA/Pokemon.gba").writeBytes(ByteArray(3))
    File(stick, "roms/GBA/notes.txt").writeBytes(ByteArray(3))
    File(stick, "Android/data").mkdirs(); File(stick, "Android/data/Fremd.sfc").writeBytes(ByteArray(3))
    eq("Ordner Roms erkannt", UsbImport.libraryFolder(stick).name, "roms")
    val found2 = UsbImport.scanFolder(stick)
    eq("Roms: Anzahl", found2.size, 4)
    eq(
        "Roms: Konsolen je Datei",
        found2.map { RomNames.systemFor(it.name) }.toSet(),
        setOf(RetroSystem.SNES, RetroSystem.MEGADRIVE, RetroSystem.N64, RetroSystem.GBA),
    )
    val stick2 = File(tmp, "stick2").apply { mkdirs() }
    File(stick2, "PoloRetro").mkdirs(); File(stick2, "Roms").mkdirs()
    eq("PoloRetro vor Roms", UsbImport.libraryFolder(stick2).name, "PoloRetro")
    // PoloRetro (alt, nur SNES) und Roms (alle Konsolen) nebeneinander: beide werden gelesen
    File(stick2, "PoloRetro").mkdirs(); File(stick2, "Roms/Mega Drive").mkdirs()
    File(stick2, "PoloRetro/Alt.sfc").writeBytes(ByteArray(3))
    File(stick2, "Roms/Mega Drive/Sonic.md").writeBytes(ByteArray(3))
    eq("beide Ordner gelistet", UsbImport.libraryFolders(stick2).map { it.name }, listOf("PoloRetro", "Roms"))
    eq("beide Ordner gelesen", UsbImport.scanFolder(stick2).size, 2)
    val stick3 = File(tmp, "stick3").apply { mkdirs() }
    File(stick3, "lose.nes").writeBytes(ByteArray(3))
    eq("ohne Ordner: Stick selbst", UsbImport.libraryFolder(stick3), stick3)
    eq("ohne Ordner: lose Datei", UsbImport.scanFolder(stick3).size, 1)


    // ---- Unscharfe Titelbild-Suche
    val serverList = listOf(
        "Disney's Aladdin (Europe)", "Disney's Aladdin (USA)", "Aladdin II (Unl)", "Aladdin (Europe) (Beta)",
        "Super Mario World (USA)", "Super Mario World (Europe)", "Super Mario World 2 - Yoshi's Island (USA)",
        "Tetris (World)", "Tetris Attack (USA)", "Sonic The Hedgehog (USA, Europe)", "Mario Kart 64 (Europe) (En,Fr,De)",
    )
    // RDB-Datenbank: Aufbau nachgebaut (Kopf, ein Eintrag mit Name und CRC, ein Eintrag ohne CRC)
    run {
        fun str(s: String): ByteArray = byteArrayOf(0xD9.toByte(), s.length.toByte()) + s.toByteArray()
        val entry = byteArrayOf(0x82.toByte()) + byteArrayOf(0xA4.toByte()) + "name".toByteArray() + str("Sonic The Hedgehog (USA, Europe)") +
            byteArrayOf(0xA3.toByte()) + "crc".toByteArray() + byteArrayOf(0xC4.toByte(), 4, 0xF9.toByte(), 0x39, 0x4E, 0x97.toByte())
        val noCrc = byteArrayOf(0x81.toByte()) + byteArrayOf(0xA4.toByte()) + "name".toByteArray() + str("Ohne Pruefsumme")
        val body = entry + noCrc
        val end = 16L + body.size
        val head = "RARCHDB".toByteArray() + byteArrayOf(0) + ByteArray(8) { i -> ((end shr (8 * (7 - i))) and 0xFF).toByte() }
        val db = RomDb.parse(head + body)
        eq("RDB Anzahl", db.size, 1)
        eq("RDB Sonic", db[0xF9394E97L], "Sonic The Hedgehog (USA, Europe)")
        eq("RDB kaputt", RomDb.parse(ByteArray(5)).size, 0)
    }
    // .smd-Entschränkung: erst verschränken, dann muss dieselbe Prüfsumme herauskommen wie von der glatten Datei
    run {
        val plain = ByteArray(2 * 16384) { i -> ((i * 31 + i / 7) and 0xFF).toByte() }
        val raw = ByteArray(512 + plain.size)
        raw[8] = 0xAA.toByte(); raw[9] = 0xBB.toByte()
        var blk = 0
        while (blk < plain.size) {
            for (j in 0 until 8192) {
                raw[512 + blk + j] = plain[blk + 2 * j + 1]
                raw[512 + blk + 8192 + j] = plain[blk + 2 * j]
            }
            blk += 16384
        }
        val c = java.util.zip.CRC32().also { it.update(plain) }.value
        eq("SMD Prüfsumme", RomId.smdCrc(raw), c)
        eq("SMD ohne Kennung", RomId.smdCrc(ByteArray(40000)), null)
    }
    fun hdr(size: Int = RomSniffer.HEADER_BYTES) = ByteArray(size)
    val mdHdr = hdr().also { "SEGA MEGA DRIVE".toByteArray().copyInto(it, 0x100) }
    val smsHdr = hdr().also { "TMR SEGA".toByteArray().copyInto(it, 0x7FF0); it[0x7FFF] = 0x4C }
    val ggHdr = hdr().also { "TMR SEGA".toByteArray().copyInto(it, 0x7FF0); it[0x7FFF] = 0x6C }
    val sms16 = hdr().also { "TMR SEGA".toByteArray().copyInto(it, 0x3FF0); it[0x3FFF] = 0x4C }
    val smdHdr = hdr().also { it[8] = 0xAA.toByte(); it[9] = 0xBB.toByte() }
    eq("Sniffer MD als bin", RomSniffer.refine(RetroSystem.MEGADRIVE, mdHdr), RetroSystem.MEGADRIVE)
    eq("Sniffer SMS als bin", RomSniffer.refine(RetroSystem.MEGADRIVE, smsHdr), RetroSystem.MASTERSYSTEM)
    eq("Sniffer SMS 16KB", RomSniffer.refine(RetroSystem.MEGADRIVE, sms16), RetroSystem.MASTERSYSTEM)
    eq("Sniffer GG als md", RomSniffer.refine(RetroSystem.MEGADRIVE, ggHdr), RetroSystem.GAMEGEAR)
    eq("Sniffer MD als sms", RomSniffer.refine(RetroSystem.MASTERSYSTEM, mdHdr), RetroSystem.MEGADRIVE)
    eq("Sniffer smd verschränkt", RomSniffer.refine(RetroSystem.MEGADRIVE, smdHdr), RetroSystem.MEGADRIVE)
    eq("Sniffer unbekannt bleibt", RomSniffer.refine(RetroSystem.MASTERSYSTEM, hdr()), RetroSystem.MASTERSYSTEM)
    eq("Sniffer SNES bleibt", RomSniffer.refine(RetroSystem.SNES, smsHdr), RetroSystem.SNES)
    eq("Sniffer kurze Datei", RomSniffer.refine(RetroSystem.MEGADRIVE, ByteArray(10)), RetroSystem.MEGADRIVE)
    // Echte Datenbank, falls auf diesem Rechner vorhanden
    java.io.File("/home/claude/libretro/libretro-database/rdb/Sega - Mega Drive - Genesis.rdb").takeIf { it.isFile }?.let {
        val real = RomDb.parse(it.readBytes())
        eq("RDB echt: Sonic 1", real[0xF9394E97L], "Sonic The Hedgehog (USA, Europe)")
        check("RDB echt: viele Einträge", real.size > 6000)
    }
    eq("Match römische Zahl", CoverNames.bestMatch("GOLDEN AXE III (USA).MD", listOf("Golden Axe 3 (USA)", "Golden Axe (USA)")), "Golden Axe 3 (USA)")
    eq("Match Unterstriche", CoverNames.bestMatch("Golden_Axe_2.SMD", listOf("Golden Axe II (World)", "Golden Axe (World)")), "Golden Axe II (World)")
    eq("Match Aladdin Europe", CoverNames.bestMatch("Aladdin (Europe).md", serverList), "Disney's Aladdin (Europe)")
    eq("Match Aladdin USA", CoverNames.bestMatch("aladdin (USA).md", serverList), "Disney's Aladdin (USA)")
    eq("Match Mario Europe", CoverNames.bestMatch("Super Mario World (Europe).sfc", serverList), "Super Mario World (Europe)")
    eq("Match Mario ohne Region nimmt kuerzeste", CoverNames.bestMatch("Super Mario World.sfc", serverList)?.startsWith("Super Mario World ("), true)
    eq("Match Teil 2 nicht verwechseln", CoverNames.bestMatch("Super Mario World (USA).sfc", serverList), "Super Mario World (USA)")
    eq("Match Tetris", CoverNames.bestMatch("Tetris.gb", serverList), "Tetris (World)")
    eq("Match Sonic", CoverNames.bestMatch("Sonic_The_Hedgehog.md".replace('_', ' '), serverList), "Sonic The Hedgehog (USA, Europe)")
    eq("Match Mario Kart", CoverNames.bestMatch("Mario Kart 64.z64", serverList), "Mario Kart 64 (Europe) (En,Fr,De)")
    eq("Kein Treffer", CoverNames.bestMatch("Voellig Unbekannt.sfc", serverList), null)
    eq("Beta nur wenn ROM Beta", CoverNames.bestMatch("Aladdin (Europe) (Beta).md", serverList), "Aladdin (Europe) (Beta)")
    eq("leere Liste", CoverNames.bestMatch("a.md", emptyList()), null)

    // Controller-Erkennung (auch wenn sich der Controller als Tastatur meldet)
    eq("Kreuz hoch ist Controller-Taste", PadInput.isPadKeyCode(android.view.KeyEvent.KEYCODE_DPAD_UP), true)
    eq("BUTTON_A ist Controller-Taste", PadInput.isPadKeyCode(android.view.KeyEvent.KEYCODE_BUTTON_A), true)
    eq("BUTTON_MODE ist Controller-Taste", PadInput.isPadKeyCode(android.view.KeyEvent.KEYCODE_BUTTON_MODE), true)
    eq("BUTTON_L1 ist Controller-Taste", PadInput.isPadKeyCode(android.view.KeyEvent.KEYCODE_BUTTON_L1), true)
    eq("Zurück ist keine Controller-Taste", PadInput.isPadKeyCode(android.view.KeyEvent.KEYCODE_BACK), false)
    eq("Lautstärke ist keine Controller-Taste", PadInput.isPadKeyCode(android.view.KeyEvent.KEYCODE_VOLUME_UP), false)
    eq("Eingabetaste ist keine Controller-Taste", PadInput.isPadKeyCode(android.view.KeyEvent.KEYCODE_ENTER), false)
    eq("Buchstabe ist keine Controller-Taste", PadInput.isPadKeyCode(android.view.KeyEvent.KEYCODE_A), false)
    eq("Gamepad-Quelle erkannt", PadInput.isPadSource(android.view.InputDevice.SOURCE_GAMEPAD or android.view.InputDevice.SOURCE_DPAD), true)
    eq("Joystick-Quelle erkannt", PadInput.isPadSource(android.view.InputDevice.SOURCE_JOYSTICK), true)
    eq("Tastatur-Quelle ist kein Gamepad", PadInput.isPadSource(android.view.InputDevice.SOURCE_KEYBOARD or android.view.InputDevice.SOURCE_DPAD), false)

    tmp.deleteRecursively()
    println("$checks Pruefungen, $failures Fehler")
    if (failures > 0) System.exit(1)
}
