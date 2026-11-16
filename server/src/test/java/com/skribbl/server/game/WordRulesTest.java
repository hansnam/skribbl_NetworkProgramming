package com.skribbl.server.game;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WordRulesTest {
    @Test
    void normalizesButDoesNotEraseSpacesInsidePhrase() {
        assertEquals("ice cream", WordRules.normalize("  ICE   Cream  "));
        assertNotEquals(WordRules.normalize("icecream"), WordRules.normalize("ice cream"));
    }

    @Test
    void countsInsertDeleteReplaceAndExact() {
        assertEquals(0, WordRules.distance("cat", "cat"));
        assertEquals(1, WordRules.distance("cat", "cats"));
        assertEquals(1, WordRules.distance("cat", "at"));
        assertEquals(1, WordRules.distance("cat", "cut"));
        assertEquals(2, WordRules.distance("ab", "ba"));
    }

    @Test
    void filtersWholeAnswerInsideSentence() {
        assertTrue(WordRules.containsAnswer("it is APPLE!", "apple"));
        assertFalse(WordRules.containsAnswer("pineapple", "apple"));
    }

    @Test
    void scoreMatchesChosenRules() {
        assertEquals(500, ScoreService.guessPoints(60, 60));
        assertEquals(300, ScoreService.guessPoints(30, 60));
        assertEquals(106, ScoreService.guessPoints(1, 60));
    }
}