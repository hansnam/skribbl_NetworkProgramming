package com.skribbl.server.game;

public final class ScoreService{
    public static final int DRAWER_BONUS = 50;
    private ScoreService() {}

    public static int guessPoints(int remainingSec, int totalSec) {
        if (totalSec <= 0) throw new IllegalArgumentException("TotalSec must be positive");

        int safe = Math.max(0, Math.min(remainingSec, totalSec));
        return  100 + (int) (400L * safe / totalSec);
    }
}