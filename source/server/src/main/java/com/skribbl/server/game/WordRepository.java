package com.skribbl.server.game;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public final class WordRepository {
    private final List<String> words;

    public WordRepository(InputStream input) throws IOException {
        if (input == null)
            throw new IOException("Missing /word_en.csv");

        LinkedHashSet<String> unique = new LinkedHashSet<>();

        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(input, StandardCharsets.UTF_8)
        )) {
            String header = reader.readLine();
            if (header == null || !header.replace("\uFEFF", "").strip().equals("word")) {
                throw new IOException("Expected single-column header: word");
            }
            String line;
            while ((line = reader.readLine()) != null) {
                String word = WordRules.normalize(line);
                if (word.isEmpty())
                    continue;
                if (word.length() < 2 || word.length() > 40 || !word.matches("[a-z]+(?: [a-z]+)*")) {
                    throw new IOException("Invalid dataset word: " + word);
                }
                unique.add(word);
            }
        }
        if (unique.size() < 3)
            throw new IOException("Need at least 3 distinct words");
        words = List.copyOf(unique);
    }

    public List<String> pickThree(Random random) {
        List<String> shuffled = new ArrayList<>(words);
        Collections.shuffle(shuffled, random);
        return List.copyOf(shuffled.subList(0, 3));
    }

    /**
     * Picks 3 distinct words prioritizing words not yet used in the current match.
     * Falls back to previously used words if fewer than 3 unused words remain.
     */
    public List<String> pickThreeExcluding(Random random, Set<String> excluded) {
        if (excluded == null || excluded.isEmpty()) {
            return pickThree(random);
        }
        List<String> available = new ArrayList<>();
        for (String w : words) {
            if (!excluded.contains(w)) {
                available.add(w);
            }
        }
        if (available.size() >= 3) {
            Collections.shuffle(available, random);
            return List.copyOf(available.subList(0, 3));
        }
        return pickThree(random);
    }
}
