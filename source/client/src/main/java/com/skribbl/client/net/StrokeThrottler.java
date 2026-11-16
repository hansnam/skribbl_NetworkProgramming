package com.skribbl.client.net;

/**
 * Bộ lọc tối ưu băng thông: chỉ gửi tọa độ mới khi khoảng cách Euclid > 2px.
 */
public class StrokeThrottler {
    private static final double MIN_DISTANCE = 2.0;
    private double lastX = -1;
    private double lastY = -1;
    private boolean hasLastPoint = false;

    public boolean shouldSend(double x, double y) {
        if (!hasLastPoint) {
            update(x, y);
            return true;
        }
        double distance = Math.hypot(x - lastX, y - lastY);
        if (distance > MIN_DISTANCE) {
            update(x, y);
            return true;
        }
        return false;
    }

    public void reset() {
        hasLastPoint = false;
        lastX = -1;
        lastY = -1;
    }

    private void update(double x, double y) {
        this.lastX = x;
        this.lastY = y;
        this.hasLastPoint = true;
    }
}