package com.limelight.library;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Text normalization shared by search, sorting and name matching.
 */
public final class LibraryText {
    private LibraryText() {}

    private static boolean isZeroWidth(char c) {
        switch (c) {
            case '​': // zero width space
            case '‌': // zero width non-joiner
            case '‍': // zero width joiner
            case '⁠': // word joiner
            case '﻿': // zero width no-break space
            case '­': // soft hyphen
                return true;
            default:
                return false;
        }
    }

    // Library rebuilds normalize the same names again and again; a renamed app
    // simply misses the cache because its text is the key
    private static final int CACHE_SIZE = 4096;
    private static final Map<String, String> CACHE = new LinkedHashMap<String, String>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    /**
     * Lower case, accent free, zero-width free text with runs of whitespace
     * collapsed to one space and no leading or trailing whitespace.
     * Compatibility forms (full width letters, ligatures) fold to plain ones.
     */
    public static String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        synchronized (CACHE) {
            String cached = CACHE.get(text);
            if (cached != null) {
                return cached;
            }
        }
        String normalized = computeNormalized(text);
        synchronized (CACHE) {
            CACHE.put(text, normalized);
        }
        return normalized;
    }

    private static String computeNormalized(String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD);
        StringBuilder sb = new StringBuilder(decomposed.length());
        boolean pendingSpace = false;
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            if (isZeroWidth(c)) {
                continue;
            }
            int type = Character.getType(c);
            if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
                    || type == Character.COMBINING_SPACING_MARK) {
                continue;
            }
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                pendingSpace = sb.length() > 0;
                continue;
            }
            if (pendingSpace) {
                sb.append(' ');
                pendingSpace = false;
            }
            sb.append(c);
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    /** Splits a query into normalized, non-empty tokens. */
    public static List<String> tokens(String query) {
        List<String> tokens = new ArrayList<>();
        String normalized = normalize(query);
        if (normalized.isEmpty()) {
            return tokens;
        }
        for (String token : normalized.split(" ")) {
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    /** Trims whitespace and zero-width characters from both ends, keeping case and accents. */
    public static String clean(String text) {
        if (text == null) {
            return "";
        }
        int start = 0;
        int end = text.length();
        while (start < end && (Character.isWhitespace(text.charAt(start))
                || Character.isSpaceChar(text.charAt(start)) || isZeroWidth(text.charAt(start)))) {
            start++;
        }
        while (end > start && (Character.isWhitespace(text.charAt(end - 1))
                || Character.isSpaceChar(text.charAt(end - 1)) || isZeroWidth(text.charAt(end - 1)))) {
            end--;
        }
        return text.substring(start, end);
    }
}
