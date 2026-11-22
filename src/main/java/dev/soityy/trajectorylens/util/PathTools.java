package dev.soityy.trajectorylens.util;

import java.util.ArrayList;
import java.util.List;

/** Pure helpers shared by renderer/tracker (unit-testable without a game runtime). */
public final class PathTools {
    private PathTools() {
    }

    /** Max point count drawn per trajectory. */
    public static final int MAX_SAMPLES = 96;

    /**
     * Reduces a per-tick point list to at most maxSamples points by stride
     * sampling, always keeping the first and the last point.
     */
    public static <T> List<T> decimate(List<T> points, int maxSamples) {
        if (points == null || points.isEmpty()) {
            return List.of();
        }
        if (points.size() <= maxSamples) {
            return new ArrayList<>(points);
        }
        int stride = (int) Math.ceil((double) points.size() / maxSamples);
        List<T> out = new ArrayList<>();
        for (int i = 0; i < points.size(); i += stride) {
            out.add(points.get(i));
        }
        T last = points.get(points.size() - 1);
        if (out.get(out.size() - 1) != last) {
            out.add(last);
        }
        return out;
    }

    public static int[] smallestIndices(double[] values, int k) {
        int m = Math.min(k, values.length);
        if (m <= 0) {
            return new int[0];
        }
        int[] heap = new int[m];
        int size = 0;
        for (int i = 0; i < values.length; i++) {
            if (size < m) {
                heap[size] = i;
                siftUp(heap, size, values);
                size++;
            } else if (before(values, i, heap[0])) {
                heap[0] = i;
                siftDown(heap, 0, size, values);
            }
        }
        for (int end = size - 1; end > 0; end--) {
            int top = heap[0];
            heap[0] = heap[end];
            heap[end] = top;
            siftDown(heap, 0, end, values);
        }
        return heap;
    }

    private static boolean before(double[] values, int a, int b) {
        return values[a] < values[b] || (values[a] == values[b] && a < b);
    }

    private static void siftUp(int[] heap, int i, double[] values) {
        int item = heap[i];
        while (i > 0) {
            int parent = (i - 1) >>> 1;
            if (!before(values, heap[parent], item)) {
                break;
            }
            heap[i] = heap[parent];
            i = parent;
        }
        heap[i] = item;
    }

    private static void siftDown(int[] heap, int i, int size, double[] values) {
        int item = heap[i];
        while (true) {
            int child = 2 * i + 1;
            if (child >= size) {
                break;
            }
            if (child + 1 < size && before(values, heap[child], heap[child + 1])) {
                child++;
            }
            if (!before(values, item, heap[child])) {
                break;
            }
            heap[i] = heap[child];
            i = child;
        }
        heap[i] = item;
    }

    public static int argb(int r, int g, int b, int a) {
        return (a & 0xFF) << 24 | (r & 0xFF) << 16 | (g & 0xFF) << 8 | (b & 0xFF);
    }

    /**
     * Parses "RRGGBB" or "AARRGGBB" (optional '#' / '0x' prefix) into an ARGB int.
     * 6-digit input gets the default strong alpha (0xEA) so lines stay vivid.
     * Returns null when the input is not valid hex color.
     */
    public static @org.jspecify.annotations.Nullable Integer parseColor(String input) {
        if (input == null) {
            return null;
        }
        String t = input.trim();
        if (t.startsWith("#")) {
            t = t.substring(1);
        } else if (t.startsWith("0x") || t.startsWith("0X")) {
            t = t.substring(2);
        }
        if (!t.matches("[0-9a-fA-F]{6}|[0-9a-fA-F]{8}")) {
            return null;
        }
        long v = Long.parseLong(t, 16);
        if (t.length() == 6) {
            return (int) (0xEA000000L | v);
        }
        return (int) v; // caller provided full AARRGGBB
    }

    /** Linear interpolation between two ARGB colors, t in [0,1]. */
    public static int lerpArgb(int from, int to, float t) {
        if (t <= 0.0F) {
            return from;
        }
        if (t >= 1.0F) {
            return to;
        }
        int fa = from >>> 24;
        int fr = from >> 16 & 0xFF;
        int fg = from >> 8 & 0xFF;
        int fb = from & 0xFF;
        int ta = to >>> 24;
        int tr = to >> 16 & 0xFF;
        int tg = to >> 8 & 0xFF;
        int tb = to & 0xFF;
        return argb(
            Math.round(fr + (tr - fr) * t),
            Math.round(fg + (tg - fg) * t),
            Math.round(fb + (tb - fb) * t),
            Math.round(fa + (ta - fa) * t));
    }
}
