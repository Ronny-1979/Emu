/*
 *     PoloRetro-Ergänzung zu LibretroDroid (GPL-3.0)
 *
 *     Run-Ahead: Pro angezeigtem Bild wird der Kern zweimal gerechnet. Der erste Durchlauf bringt den
 *     echten Spielstand um ein Bild voran (stumm). Dann wird der Zustand gesichert, ein zweites Bild
 *     gerechnet und angezeigt, und der Zustand zurückgespielt. Das angezeigte Bild ist so immer ein Bild
 *     weiter, als es ohne Run-Ahead wäre: Eingaben wirken ein Bild früher.
 */
#ifndef LIBRETRODROID_RUNAHEAD_H
#define LIBRETRODROID_RUNAHEAD_H

#include <cstddef>
#include <cstdint>
#include <functional>
#include <utility>
#include <vector>

namespace libretrodroid {

class RunAhead {
public:
    struct Hooks {
        std::function<void()> run;                           // retro_run()
        std::function<size_t()> serializeSize;               // retro_serialize_size()
        std::function<bool(void*, size_t)> serialize;        // retro_serialize()
        std::function<bool(const void*, size_t)> unserialize;// retro_unserialize()
        std::function<void(bool)> setMuted;                  // Bild und Ton ein-/ausschalten
    };

    explicit RunAhead(Hooks hooks) : hooks(std::move(hooks)) {}

    /**
     * Rechnet genau ein angezeigtes Bild.
     * Gibt false zurück, wenn Run-Ahead mit diesem Kern nicht geht. Dann wurde NICHTS gerechnet und der
     * Aufrufer muss das Bild normal rechnen. Danach gibt die Funktion immer false zurück.
     */
    bool runFrame() {
        if (broken) return false;

        if (!probed) {
            probed = true;
            if (!probe()) {
                broken = true;
                return false;
            }
        }

        // 1. Echten Spielstand um ein Bild voranbringen, ohne Bild und Ton auszugeben.
        hooks.setMuted(true);
        hooks.run();
        hooks.setMuted(false);

        // 2. Zustand sichern.
        size_t size = hooks.serializeSize();
        if (size == 0) {
            giveUp();
            return true;
        }
        if (buffer.size() < size) buffer.resize(size);
        if (!hooks.serialize(buffer.data(), size)) {
            giveUp();
            return true;
        }

        // 3. Das angezeigte Bild rechnen, ein Bild weiter als der echte Stand.
        hooks.run();

        // 4. Zurück auf den echten Stand.
        if (!hooks.unserialize(buffer.data(), size)) {
            broken = true;
        }
        return true;
    }

    bool isBroken() const { return broken; }

    void reset() {
        broken = false;
        probed = false;
    }

private:
    // Gleich beim ersten Mal prüfen, ob der Kern Zustände sichern und laden kann.
    bool probe() {
        size_t size = hooks.serializeSize();
        if (size == 0) return false;
        buffer.resize(size);
        if (!hooks.serialize(buffer.data(), size)) return false;
        return hooks.unserialize(buffer.data(), size);
    }

    // Fällt mitten im Spiel aus: das Bild wird trotzdem noch ausgegeben, danach läuft alles normal.
    void giveUp() {
        broken = true;
        hooks.run();
    }

    Hooks hooks;
    std::vector<uint8_t> buffer;
    bool probed = false;
    bool broken = false;
};

} // namespace libretrodroid

#endif // LIBRETRODROID_RUNAHEAD_H
