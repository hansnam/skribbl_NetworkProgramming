package com.skribbl.server.game;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WordRepositoryTest {

    @Test
    void loadsSingleAndMultiWordEntriesFromCsv() throws IOException {
        String csv = "word\napple\nice cream\nhot dog\nbanana\n";
        WordRepository repo = new WordRepository(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));

        Set<String> picked = new HashSet<>(repo.pickThreeExcluding(new Random(42), Set.of()));
        assertEquals(3, picked.size());
    }

    @Test
    void pickThreeExcludingAvoidsAlreadyUsedWordsUntilPoolExhausted() throws IOException {
        String csv = "word\napple\nbanana\ncherry\ndate\n";
        WordRepository repo = new WordRepository(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));

        List<String> choice = repo.pickThreeExcluding(new Random(7), Set.of("apple"));
        assertEquals(3, choice.size());
        assertFalse(choice.contains("apple"));
    }
}
