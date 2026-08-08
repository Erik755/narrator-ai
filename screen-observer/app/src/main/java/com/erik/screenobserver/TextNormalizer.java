package com.erik.screenobserver;

import java.text.Normalizer;
import java.util.Locale;

/** Shared text normalization for voice, accessibility labels, skills and app names. */
public final class TextNormalizer {
    private TextNormalizer() { }

    public static String normalize(String value) {
        if (value == null) return "";
        String n = Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return n.replaceAll("[^a-z0-9ñ ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
