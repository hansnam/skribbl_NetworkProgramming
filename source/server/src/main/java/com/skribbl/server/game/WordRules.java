package com.skribbl.server.game;

import java.util.Locale;

public final class WordRules {
    private WordRules() {}

    public static String normalize(String text) {
        return text == null ? "" : text.strip()
            .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    public static boolean containsAnswer(String text, String answer) {
        if (answer == null || answer.isEmpty()) return false;
        // Dataset chỉ chứa a-z và khoảng trắng: dấu câu thành ranh giới từ.
        String tokens = normalize(text).replaceAll("[^a-z]+", " ").strip();
        return (" " + tokens + " ").contains(" " + answer + " ");
    }

    public static int distance(String a, String b) {
        int[] row = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) row[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int diagonal = row[0];
            row[0] = i;
            
            for (int j = 1; j <= b.length(); j++) {
                int oldAbove = row[j];
                int replace = diagonal + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                row[j] = Math.min(replace, Math.min(row[j] + 1, row[j - 1] + 1));
                diagonal = oldAbove;
            }
        }
        return  row[b.length()];
    }
}
