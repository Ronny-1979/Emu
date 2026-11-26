/*
 *     PoloRetro-Ergänzung zu LibretroDroid (GPL-3.0)
 *
 *     Zurückspulen: Alle paar Bilder wird der Spielzustand gesichert. Gespeichert wird nur der
 *     Unterschied zum nächsten (neueren) Zustand (XOR, Nullfolgen gekürzt). Das braucht wenig Speicher,
 *     weil sich von einem Bild zum nächsten fast nichts ändert. Beim Zurückspulen werden die Unterschiede
 *     rückwärts angewendet.
 */
#ifndef LIBRETRODROID_REWIND_H
#define LIBRETRODROID_REWIND_H

#include <cstddef>
#include <cstdint>
#include <deque>
#include <vector>

namespace libretrodroid {

class Rewind {
public:
    /** [maxSteps] Schritte höchstens, [maxBytes] Speicher für alle Unterschiede zusammen. */
    Rewind(size_t maxSteps, size_t maxBytes) : maxSteps(maxSteps), maxBytes(maxBytes) {}

    /** Neuen Zustand sichern. Ändert sich die Größe, wird der Verlauf verworfen. */
    void push(const uint8_t* data, size_t size) {
        if (size == 0) return;
        if (current.size() != size) {
            clear();
            current.assign(data, data + size);
            return;
        }
        std::vector<uint8_t> delta;
        encode(current.data(), data, size, delta);
        usedBytes += delta.size();
        steps.push_back(std::move(delta));
        current.assign(data, data + size);
        while (!steps.empty() && (steps.size() > maxSteps || usedBytes > maxBytes)) {
            usedBytes -= steps.front().size();
            steps.pop_front();
        }
    }

    /** Einen Schritt zurück. Gibt false zurück, wenn nichts mehr da ist. [out] ist dann der ältere Zustand. */
    bool pop(std::vector<uint8_t>& out) {
        if (steps.empty()) return false;
        std::vector<uint8_t>& delta = steps.back();
        apply(current.data(), current.size(), delta);
        usedBytes -= delta.size();
        steps.pop_back();
        out = current;
        return true;
    }

    size_t available() const { return steps.size(); }
    size_t bytesUsed() const { return usedBytes + current.size(); }

    void clear() {
        steps.clear();
        current.clear();
        usedBytes = 0;
    }

private:
    // Format: wiederholt [Länge der Nullfolge][Länge der Abweichungen][Abweichungen], Längen als Varint.
    static void putVarint(std::vector<uint8_t>& out, size_t v) {
        while (v >= 0x80) {
            out.push_back((uint8_t) (v | 0x80));
            v >>= 7;
        }
        out.push_back((uint8_t) v);
    }

    static size_t getVarint(const std::vector<uint8_t>& in, size_t& pos) {
        size_t v = 0;
        int shift = 0;
        while (pos < in.size()) {
            uint8_t b = in[pos++];
            v |= (size_t) (b & 0x7F) << shift;
            if (!(b & 0x80)) break;
            shift += 7;
        }
        return v;
    }

    static void encode(const uint8_t* a, const uint8_t* b, size_t size, std::vector<uint8_t>& out) {
        size_t i = 0;
        while (i < size) {
            size_t zeros = 0;
            while (i < size && a[i] == b[i]) { i++; zeros++; }
            if (i >= size) break;
            size_t start = i;
            // Abweichungen sammeln; kurze Lücken (unter 4 gleiche Bytes) bleiben im Block.
            size_t lastDiff = i;
            while (i < size) {
                if (a[i] != b[i]) {
                    lastDiff = i;
                } else if (i - lastDiff > 4) {
                    break;
                }
                i++;
            }
            size_t end = lastDiff + 1;
            putVarint(out, zeros);
            putVarint(out, end - start);
            for (size_t k = start; k < end; k++) out.push_back(a[k] ^ b[k]);
            i = end;
        }
    }

    static void apply(uint8_t* state, size_t size, const std::vector<uint8_t>& delta) {
        size_t pos = 0;
        size_t at = 0;
        while (pos < delta.size()) {
            at += getVarint(delta, pos);
            size_t len = getVarint(delta, pos);
            for (size_t k = 0; k < len && pos < delta.size() && at < size; k++) {
                state[at++] ^= delta[pos++];
            }
        }
    }

    size_t maxSteps;
    size_t maxBytes;
    size_t usedBytes = 0;
    std::vector<uint8_t> current;
    std::deque<std::vector<uint8_t>> steps;
};

} // namespace libretrodroid

#endif // LIBRETRODROID_REWIND_H
