package com.skribbl.client.ui.avatar;

import javafx.scene.image.Image;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

public class AvatarImages {
    private static final int MAX_PNG_BYTES = 64 * 1024;
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Map<String, CompletableFuture<Image>> CACHE = new HashMap<>();

    private AvatarImages() {}

    // Chỉ gọi trên FX thread. Cache giữ cả ảnh đã tải lẫn lần tải đang chạy.
    static CompletableFuture<Image> load(String imageId) {
        String origin = System.getenv("SKRIBBL_AVATAR_WS");
        if (origin == null || origin.isBlank()) {
            return CompletableFuture.failedFuture(
                    new IOException("Chưa cấu hình dịch vụ avatar"));

        }

        CompletableFuture<Image> previous = CACHE.get(imageId);
        if (previous != null && !previous.isCompletedExceptionally()) {
            return previous;
        }

        CompletableFuture<Image> current = fetch(origin, imageId);
        CACHE.put(imageId, current);
        return current;
    }

    private static CompletableFuture<Image> fetch(String origin, String imageId) {
        CompletableFuture<byte[]> png = new CompletableFuture<>();
        CompletableFuture<WebSocket> connection;

        try {
            URI endpoint = URI.create(origin + "/avatars/" + imageId);
            connection = HTTP.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .buildAsync(endpoint, new PngListener(png));
        } catch (IllegalArgumentException ex) {
            return CompletableFuture.failedFuture(ex);
        }

        connection.whenComplete((socket, error) -> {
            if (error != null) {
                png.completeExceptionally(error);
            }
        });

        png.orTimeout(5, TimeUnit.SECONDS).whenComplete((bytes, error) -> {
            if (error != null) {
                connection.thenAccept(WebSocket::abort);
            }
        });

        return png.thenApplyAsync(bytes -> {
            Image image = new Image(new ByteArrayInputStream(bytes));
            if (image.isError()) {
                throw new IllegalArgumentException("Ảnh avatar không đọc được", image.getException());
            }
            return image;
        });
    }

    private static final class PngListener implements WebSocket.Listener {
        private final CompletableFuture<byte[]> result;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        private PngListener(CompletableFuture<byte[]> result) {
            this.result = result;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
            if (data.remaining() > MAX_PNG_BYTES - bytes.size()) {
                result.completeExceptionally(new IOException("Ảnh avatar quá lớn"));
                socket.abort();
                return CompletableFuture.completedFuture(null);
            }

            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            bytes.writeBytes(chunk);

            if (last) {
                result.complete(bytes.toByteArray());
            }
            socket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int code, String reason) {
            result.completeExceptionally(new IOException("WebSocket đóng trước khi nhận ảnh: " + code));
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            result.completeExceptionally(error);
        }
    }
}
