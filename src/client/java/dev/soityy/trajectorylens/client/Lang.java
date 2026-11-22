package dev.soityy.trajectorylens.client;

import net.minecraft.locale.Language;

/**
 * Tiny translation helper.
 *
 * <p>Every user-visible string is a translation key ({@code trajectorylens.<area>.<name>}) passed
 * through {@link #tr(String)} and looked up in {@code assets/trajectorylens/lang/<locale>.json}:
 *
 * <ul>
 *   <li>players whose game language has a matching entry see the translation;</li>
 *   <li>missing entries fall back to {@code en_us.json}, which holds every key;</li>
 *   <li>placeholders ({@code %s}, {@code %d}) are filled by {@link #tr(String, Object...)}; the
 *       single-argument form never formats (formatting with zero arguments would trip
 *       {@code String.format}).</li>
 * </ul>
 */
public final class Lang {

    private Lang() {
    }

    /** Translates a key for the player's current game language. */
    public static String tr(String source) {
        if (source == null || source.isEmpty()) {
            return source;
        }
        return Language.getInstance().getOrDefault(source);
    }

    public static String tr(String key, Object... args) {
        return String.format(tr(key), args);
    }

    public static String fixed(double value, int places) {
        return String.format("%." + places + "f", value);
    }

    public static String onOff(boolean on) {
        return tr(on ? "trajectorylens.common.on" : "trajectorylens.common.off");
    }
}
