/*
 *     PoloRetro-Ergänzung zu LibretroDroid (GPL-3.0)
 *
 *     Automatische Bildverzögerung: Der Kern wird nicht gleich nach dem Bildtakt gerechnet, sondern
 *     erst kurz vor dem nächsten. So werden die Tasten später abgefragt und sind aktueller.
 *     Die Wartezeit passt sich an, damit das Bild den Takt nie verpasst: sie steigt langsam und fällt
 *     sofort, wenn die Rechenzeit größer wird.
 */
#ifndef LIBRETRODROID_FRAMEDELAY_H
#define LIBRETRODROID_FRAMEDELAY_H

#include <algorithm>

namespace libretrodroid {

class AutoFrameDelay {
public:
    void reset() {
        delay = 0;
        peak = 0;
    }

    /** Wartezeit vor dem nächsten Bild in Mikrosekunden. */
    long delayMicros() const { return static_cast<long>(delay); }

    /** Rechenzeit des letzten Bildes (ohne Wartezeit) und Länge eines Bildes, beides in Mikrosekunden. */
    void report(long workMicros, long periodMicros) {
        if (periodMicros <= 0) return;
        double work = static_cast<double>(workMicros);
        double period = static_cast<double>(periodMicros);

        // Spitzenwert: steigt sofort, fällt langsam.
        peak = std::max(work, peak * 0.97 + work * 0.03);

        double margin = std::max(1500.0, peak * 0.5);
        double target = period - peak - margin;
        target = std::clamp(target, 0.0, period * 0.75);

        if (delay + work > period * 0.92) {
            // Das Bild wäre fast zu spät fertig geworden: sofort deutlich weniger warten.
            delay = std::min(delay * 0.5, target);
        } else if (target < delay) {
            delay = target;
        } else {
            delay += std::min(target - delay, 100.0);
        }
    }

private:
    double delay = 0;
    double peak = 0;
};

} // namespace libretrodroid

#endif // LIBRETRODROID_FRAMEDELAY_H
