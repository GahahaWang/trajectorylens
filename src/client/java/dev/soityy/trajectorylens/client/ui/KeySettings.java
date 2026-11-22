package dev.soityy.trajectorylens.client.ui;

import dev.soityy.trajectorylens.client.Lang;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;

/**
 * Chord-style hotkeys (up to 3 keys held together). Bound by pressing the keys
 * directly; not KeyMappings so nothing can hijack them. Persisted by SettingsIO.
 */
public final class KeySettings {

    public static int[] rangeChord = {InputConstants.KEY_O, InputConstants.KEY_P};
    public static int[] panelChord = {InputConstants.KEY_H, InputConstants.KEY_J};
    public static final int MAX_KEYS = 3;

    private KeySettings() {
    }

    /** True when every key of the chord is currently held. */
    public static boolean allDown(Window win, int[] chord) {
        if (chord == null || chord.length == 0 || win == null) {
            return false;
        }
        for (int k : chord) {
            if (!InputConstants.isKeyDown(k)) {
                return false;
            }
        }
        return true;
    }

    /** "O + P" style label. */
    public static String display(int[] chord) {
        if (chord == null || chord.length == 0) {
            return Lang.tr("trajectorylens.keys.unbound");
        }
        StringBuilder sb = new StringBuilder();
        for (int k : chord) {
            if (sb.length() > 0) {
                sb.append(" + ");
            }
            sb.append(name(k));
        }
        return sb.toString();
    }

    /** Build a chord from pressed keys (first MAX_KEYS kept, Esc/Backspace filtered). */
    public static int[] chordFrom(List<Integer> pressed) {
        List<Integer> out = new ArrayList<>();
        for (int k : pressed) {
            if (k == InputConstants.KEY_ESCAPE || k == InputConstants.KEY_BACKSPACE) {
                continue;
            }
            if (out.size() >= MAX_KEYS) {
                break;
            }
            out.add(k);
        }
        int[] arr = new int[out.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = out.get(i);
        }
        return arr;
    }

    public static String name(int code) {
        return InputConstants.Type.KEYBOARD.getOrCreate(code).getDisplayName().getString();
    }
}
