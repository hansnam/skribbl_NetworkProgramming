package com.skribbl.server.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public final class WordMask {
    private final String word;
    private final List<Integer> positions = new ArrayList<>();

    public WordMask(String word, Random random) {
        this.word = word;
        
        for (int i = 0; i < word.length(); i++) {
            if (word.charAt(i) != ' ') positions.add(i); 
        }
        Collections.shuffle(positions, random);
    }

    public String display(int elapsedSec, int totalSec) {
        int stage = elapsedSec * 4L >= totalSec * 3L ? 2
                : elapsedSec * 2L >= totalSec ? 1 : 0;
        int count = Math.min(stage, Math.min(2, positions.size() / 2));
        char[] chars = word.toCharArray();

        for (int i = 0; i < chars.length; i++) {
            if (chars[i] != ' ') chars[i] = '_';
        }

        for (int i = 0; i < count; i++) {
            int index = positions.get(i);
            chars[index] = word.charAt(index);
        }

        return new String(chars);
    }
}
