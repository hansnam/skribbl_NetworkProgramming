package com.skribbl.client.ui.canvas;

public class StrokeThrottler {
    private final double minDistanceSq;
    private double lastX = Double.NaN;
    private double lastY = Double.NaN;

    public StrokeThrottler() {
        this(2.0);
    }

    public StrokeThrottler(double minDistancePx) {
        this.minDistanceSq = minDistancePx * minDistancePx;
    }

    public boolean shouldSend(double x, double y) {
        if (Double.isNaN(lastX) || Double.isNaN(lastY)) {
            lastX = x;
            lastY = y;
            return true;
        }
        double dx = x - lastX;
        double dy = y - lastY;
        if ((dx * dx + dy * dy) >= minDistanceSq) {
            lastX = x;
            lastY = y;
            return true;
        }
        return false;
    }

    public void reset() {
        lastX = Double.NaN;
        lastY = Double.NaN;
    }
}