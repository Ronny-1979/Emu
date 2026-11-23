// Prüft Run-Ahead und automatische Bildverzögerung mit einem Schein-Kern am PC (ohne Android).
#include <cstdio>
#include <cstring>
#include <vector>
#include "../../libretrodroid/src/main/cpp/runahead.h"
#include "../../libretrodroid/src/main/cpp/framedelay.h"

using namespace libretrodroid;

static int checks = 0, failures = 0;
#define CHECK(cond, msg) do { checks++; if (!(cond)) { failures++; printf("FEHLER: %s (Zeile %d)\n", msg, __LINE__); } } while (0)

// Schein-Kern: Zustand = Summe aller Eingaben (eine Eingabe pro gerechnetem Bild) und Bildzähler.
struct FakeCore {
    long sum = 0;
    long frames = 0;
    long input = 0;
    bool muted = false;
    std::vector<long> shown;     // angezeigte Werte
    long audioChunks = 0;
    bool failSerialize = false;
    bool failUnserialize = false;
    size_t reportedSize = 16;
    int serializeCalls = 0;

    void run() {
        sum += input;
        frames++;
        if (!muted) { shown.push_back(sum); audioChunks++; }
    }
    RunAhead::Hooks hooks() {
        RunAhead::Hooks h;
        h.run = [this]() { run(); };
        h.serializeSize = [this]() { return reportedSize; };
        h.serialize = [this](void* d, size_t n) {
            serializeCalls++;
            if (failSerialize) return false;
            std::memcpy(d, &sum, sizeof(sum));
            std::memcpy((char*) d + sizeof(sum), &frames, sizeof(frames));
            return n >= 16;
        };
        h.unserialize = [this](const void* d, size_t n) {
            if (failUnserialize) return false;
            std::memcpy(&sum, d, sizeof(sum));
            std::memcpy(&frames, (const char*) d + sizeof(sum), sizeof(frames));
            return n >= 16;
        };
        h.setMuted = [this](bool m) { muted = m; };
        return h;
    }
};

static void testAheadByOneFrame() {
    FakeCore normal, ahead;
    RunAhead ra(ahead.hooks());
    long inputs[] = {0, 0, 1, 1, 3, 0, 0, 2, 2, 2};
    long realBefore = 0;
    for (long in : inputs) {
        normal.input = in; normal.run();
        ahead.input = in;
        CHECK(ra.runFrame(), "Run-Ahead soll laufen");
        // Angezeigt: echter Stand vor diesem Durchlauf + zweimal die aktuelle Eingabe.
        CHECK(ahead.shown.back() == realBefore + 2 * in, "angezeigtes Bild ist ein Bild voraus");
        realBefore += in;
        // Der echte Stand muss genau dem normalen Spiel entsprechen (nach Zurückspielen).
        CHECK(ahead.sum == normal.sum, "echter Spielstand stimmt mit normalem Lauf überein");
        CHECK(ahead.frames == normal.frames, "Bildzähler stimmt mit normalem Lauf überein");
    }
    CHECK(ahead.shown.size() == normal.shown.size(), "gleich viele Bilder angezeigt");
    CHECK(ahead.audioChunks == normal.audioChunks, "gleich viele Tonblöcke");
    CHECK(!ra.isBroken(), "nicht ausgefallen");
}

static void testEffectEarlier() {
    FakeCore normal, ahead;
    RunAhead ra(ahead.hooks());
    for (int t = 0; t < 5; t++) {
        long in = (t >= 3) ? 1 : 0;   // Taste ab Bild 3
        normal.input = in; normal.run();
        ahead.input = in; ra.runFrame();
    }
    // Normal: Wirkung im Bild 3 = 1. Run-Ahead: im selben Bild schon 2 (ein Bild weiter).
    CHECK(normal.shown[3] == 1 && ahead.shown[3] == 2, "Tastenwirkung kommt ein Bild früher");
}

static void testNotSupported() {
    FakeCore c; c.reportedSize = 0;
    RunAhead ra(c.hooks());
    CHECK(!ra.runFrame(), "ohne Zustandsgröße: nicht möglich");
    CHECK(c.frames == 0, "dabei wurde nichts gerechnet");
    CHECK(ra.isBroken() && !ra.runFrame(), "bleibt ausgefallen");

    FakeCore d; d.failSerialize = true;
    RunAhead rb(d.hooks());
    CHECK(!rb.runFrame() && d.frames == 0, "Probe scheitert beim Sichern: nichts gerechnet");

    FakeCore e; e.failUnserialize = true;
    RunAhead rc(e.hooks());
    CHECK(!rc.runFrame() && e.frames == 0, "Probe scheitert beim Laden: nichts gerechnet");
}

static void testFailureMidGame() {
    FakeCore c;
    RunAhead ra(c.hooks());
    c.input = 1;
    CHECK(ra.runFrame(), "erstes Bild läuft");
    c.failSerialize = true;
    size_t shownBefore = c.shown.size();
    long framesBefore = c.frames;
    CHECK(ra.runFrame(), "Ausfall mitten im Spiel: Bild wird trotzdem ausgegeben");
    CHECK(c.shown.size() == shownBefore + 1, "genau ein Bild ausgegeben");
    CHECK(c.frames == framesBefore + 2, "zwei Bilder gerechnet (stumm + sichtbar)");
    CHECK(ra.isBroken(), "danach ausgefallen");
    CHECK(!ra.runFrame(), "danach wieder normal laufen lassen");

    FakeCore u;
    RunAhead rb(u.hooks());
    u.input = 1;
    CHECK(rb.runFrame(), "läuft");
    u.failUnserialize = true;
    CHECK(rb.runFrame(), "Bild wird ausgegeben");
    CHECK(rb.isBroken() && !rb.runFrame(), "Zurückspielen scheitert: ausgefallen");
}

static void testFrameDelay() {
    AutoFrameDelay d;
    const long period = 16667;
    for (int i = 0; i < 300; i++) d.report(3000, period);
    long settled = d.delayMicros();
    CHECK(settled > 10000 && settled <= (long) (period * 0.75), "Wartezeit steigt auf etwa 12 ms");
    CHECK(settled + 3000 < period * 0.92, "bei 3 ms Rechenzeit bleibt genug Reserve");

    d.report(9000, period);   // Ausreißer
    CHECK(d.delayMicros() + 9000 < period, "nach einem Ausreißer sofort deutlich weniger Wartezeit");

    // Langsames Wiederansteigen: nicht schneller als 100 µs pro Bild.
    long before = d.delayMicros();
    d.report(3000, period);
    CHECK(d.delayMicros() - before <= 100, "steigt höchstens 100 µs pro Bild");

    // Zufälliges Zittern der Rechenzeit: nach dem Einschwingen darf nie ein Bild zu spät sein.
    AutoFrameDelay j;
    unsigned seed = 12345;
    int late = 0;
    for (int i = 0; i < 3000; i++) {
        seed = seed * 1103515245u + 12345u;
        long work = 2500 + (seed >> 16) % 1800;   // 2,5 bis 4,3 ms
        if (i > 400 && j.delayMicros() + work > period) late++;
        j.report(work, period);
    }
    CHECK(late == 0, "mit Zittern kein verspätetes Bild");

    // Sehr lange Rechenzeit: gar keine Wartezeit.
    AutoFrameDelay s;
    for (int i = 0; i < 200; i++) s.report(16000, period);
    CHECK(s.delayMicros() == 0, "bei fast voller Auslastung keine Wartezeit");

    s.reset();
    CHECK(s.delayMicros() == 0, "reset setzt zurück");
}

int main() {
    testAheadByOneFrame();
    testEffectEarlier();
    testNotSupported();
    testFailureMidGame();
    testFrameDelay();
    printf("%d Pruefungen, %d Fehler\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
