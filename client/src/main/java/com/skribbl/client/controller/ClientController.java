package com.skribbl.client.controller;

import com.skribbl.client.net.SocketClient;
import com.skribbl.client.ui.game.GameOverDialog;
import com.skribbl.client.ui.game.GameView;
import com.skribbl.client.ui.game.WordChoiceDialog;
import com.skribbl.common.dto.*;
import com.skribbl.common.model.PlayerDto;
import com.skribbl.common.model.RoomStatus;
import com.skribbl.common.protocol.JsonCodec;
import com.skribbl.common.protocol.MessageType;
import com.skribbl.common.protocol.NetworkMessage;
import javafx.application.Platform;
import javafx.scene.control.Alert;

import java.io.IOException;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class ClientController {
    private final SocketClient socketClient;
    private volatile String nickname;
    private volatile String playerId;
    private volatile String currentRoomCode;
    private volatile boolean isHost = false;
    private volatile boolean canDraw = false;

    private GameView gameView;
    private Consumer<RoomUpdateDto> onRoomJoinedCallback;
    private Runnable onRoomLeftCallback;

    public ClientController(String nickname) {
        this.nickname = nickname;
        this.socketClient = new SocketClient();
    }

    public void setGameView(GameView gameView) {
        this.gameView = gameView;
    }

    public void setNickname(String nickname) {
        if (nickname != null && !nickname.isBlank()) {
            this.nickname = nickname.trim();
        }
    }

    public String getNickname() {
        return nickname;
    }

    public String getPlayerId() {
        return playerId;
    }

    public String getCurrentRoomCode() {
        return currentRoomCode;
    }

    public boolean isHost() {
        return isHost;
    }

    public boolean canDraw() {
        return canDraw;
    }

    public void setOnRoomJoinedCallback(Consumer<RoomUpdateDto> callback) {
        this.onRoomJoinedCallback = callback;
    }

    public void setOnRoomLeftCallback(Runnable callback) {
        this.onRoomLeftCallback = callback;
    }

    public void connectToServer(String host, int port) throws IOException {
        socketClient.connect(host, port, this);
        socketClient.send(MessageType.CONNECT_REQ, new ConnectReqDto(nickname));
    }

    public void sendDrawStroke(DrawStrokeDto dto) {
        if (!canDraw) return;
        socketClient.send(MessageType.DRAW_STROKE, dto);
    }

    public void sendClearCanvas() {
        if (!canDraw) return;
        socketClient.send(MessageType.CLEAR_CANVAS, null);
    }

    public void createRoom(int maxRounds, int drawTimeSec) {
        socketClient.send(MessageType.CREATE_ROOM, new CreateRoomReqDto(maxRounds, drawTimeSec));
    }

    public void joinRoom(String roomCode) {
        if (roomCode == null || roomCode.isBlank()) return;
        socketClient.send(MessageType.JOIN_ROOM, new JoinRoomReqDto(roomCode.trim().toUpperCase()));
    }

    public void startGame() {
        socketClient.send(MessageType.START_GAME, null);
    }

    public void leaveRoom() {
        socketClient.send(MessageType.LEAVE_ROOM, null);
        resetRoomState();
        if (onRoomLeftCallback != null) {
            runOnUiThread(onRoomLeftCallback);
        }
    }

    private void resetRoomState() {
        this.currentRoomCode = null;
        this.isHost = false;
        this.canDraw = false;
        if (gameView != null) {
            runOnUiThread(() -> {
                gameView.getDrawingCanvas().setDrawingEnabled(false);
                gameView.getDrawingCanvas().clearCanvasLocal();
                gameView.getPlayerListPanel().updatePlayers(null);
                gameView.getTopStatusBar().updateRoomHeader("", false, true, 0);
                gameView.getTopStatusBar().updateTurnInfo("Đang chờ bắt đầu...", "_ _ _ _ _", 60);
            });
        }
    }

    public void sendChat(String text) {
        if (text == null || text.isBlank()) return;
        String trimmed = text.trim();
        if (trimmed.startsWith("/")) {
            handleSlashCommand(trimmed);
            return;
        }
        socketClient.send(MessageType.CHAT_SEND, new ChatSendDto(trimmed));
    }

    private void handleSlashCommand(String cmd) {
        String[] parts = cmd.split("\\s+");
        switch (parts[0].toLowerCase()) {
            case "/create" -> {
                int rounds = 3;
                int time = 60;
                try {
                    if (parts.length >= 2) rounds = Integer.parseInt(parts[1]);
                    if (parts.length >= 3) time = Integer.parseInt(parts[2]);
                } catch (NumberFormatException ignored) {
                }
                createRoom(rounds, time);
                appendSystemChat("Đang tạo phòng (" + rounds + " vòng, " + time + "s)...");
            }
            case "/join" -> {
                if (parts.length < 2) {
                    appendSystemChat("Cú pháp: /join <MÃ_PHÒNG>");
                } else {
                    joinRoom(parts[1]);
                    appendSystemChat("Đang xin vào phòng " + parts[1].toUpperCase() + "...");
                }
            }
            case "/start" -> {
                startGame();
                appendSystemChat("Đang gửi yêu cầu bắt đầu ván đấu...");
            }
            case "/leave" -> {
                leaveRoom();
                appendSystemChat("Đã rời phòng.");
            }
            default -> appendSystemChat("Lệnh hỗ trợ: /create [vòng] [giây] | /join <MÃ> | /start | /leave");
        }
    }

    public void handleIncomingMessage(NetworkMessage msg) {
        if (msg == null || msg.type() == null) return;

        switch (msg.type()) {
            case CONNECT_ACK -> {
                ConnectAckDto ack = JsonCodec.decodePayload(msg, ConnectAckDto.class);
                this.playerId = (ack != null && ack.playerId() != null) ? ack.playerId() : nickname;
                if (ack != null && ack.nickname() != null && !ack.nickname().isBlank()) {
                    this.nickname = ack.nickname();
                }
                appendSystemChat("Kết nối thành công! Xin chào " + nickname + " (ID: " + playerId + ").");
            }
            case PING -> socketClient.send(MessageType.PONG, null);
            case ROOM_UPDATE -> {
                RoomUpdateDto room = JsonCodec.decodePayload(msg, RoomUpdateDto.class);
                if (room != null) {
                    boolean firstJoin = (this.currentRoomCode == null && room.roomCode() != null);
                    this.currentRoomCode = room.roomCode();
                    this.isHost = (playerId != null && playerId.equals(room.hostId()));

                    if (firstJoin && onRoomJoinedCallback != null) {
                        runOnUiThread(() -> onRoomJoinedCallback.accept(room));
                    }
                    if (gameView != null) {
                        runOnUiThread(() -> {
                            int count = room.players() != null ? room.players().size() : 0;
                            boolean waiting = room.status() == RoomStatus.WAITING;
                            gameView.getTopStatusBar().updateRoomHeader(room.roomCode(), isHost, waiting, count);
                            gameView.getPlayerListPanel().updatePlayers(room.players());
                            if (waiting) {
                                String hostHint = isHost
                                        ? (count >= 2 ? "Bấm 'Bắt đầu game' để chơi!" : "Đang chờ thêm người chơi...")
                                        : "Đang chờ chủ phòng bắt đầu...";
                                gameView.getTopStatusBar().updateTurnInfo(hostHint, "_ _ _ _ _", room.drawTimeSec());
                            }
                        });
                    }

                    String playerList = room.players() == null ? "" : room.players().stream()
                            .map(p -> p.nickname() + " (" + p.score() + "đ)")
                            .collect(Collectors.joining(", "));
                    appendSystemChat("Phòng [" + room.roomCode() + "] (" + room.status() + ") | Người chơi: " + playerList);
                }
            }
            case DRAW_STROKE -> {
                DrawStrokeDto stroke = JsonCodec.decodePayload(msg, DrawStrokeDto.class);
                if (stroke != null && gameView != null) {
                    runOnUiThread(() -> gameView.getDrawingCanvas().renderRemoteStroke(stroke));
                }
            }
            case CANVAS_SYNC -> {
                CanvasSyncDto syncDto = JsonCodec.decodePayload(msg, CanvasSyncDto.class);
                if (syncDto != null && gameView != null) {
                    runOnUiThread(() -> gameView.getDrawingCanvas().renderSyncHistory(syncDto.strokes()));
                }
            }
            case CLEAR_CANVAS -> {
                if (gameView != null) {
                    runOnUiThread(() -> gameView.getDrawingCanvas().clearCanvasLocal());
                }
            }
            case WORD_CHOICES -> {
                WordChoicesDto choices = JsonCodec.decodePayload(msg, WordChoicesDto.class);
                if (choices != null && choices.choices() != null && !choices.choices().isEmpty()) {
                    runOnUiThread(() -> showWordChoicePopup(choices));
                }
            }
            case TURN_START -> {
                TurnStartDto turn = JsonCodec.decodePayload(msg, TurnStartDto.class);
                if (turn != null) {
                    this.canDraw = (playerId != null && playerId.equals(turn.drawerId()));
                    if (gameView != null) {
                        runOnUiThread(() -> {
                            gameView.getTopStatusBar().updateRoomHeader(currentRoomCode, isHost, false, 0);
                            gameView.getDrawingCanvas().clearCanvasLocal();
                            gameView.getDrawingCanvas().setDrawingEnabled(canDraw);
                            String roleText = canDraw
                                    ? "Vòng " + turn.round() + "/" + turn.maxRounds() + " - Lượt bạn vẽ!"
                                    : "Vòng " + turn.round() + "/" + turn.maxRounds() + " - Người vẽ: " + turn.drawerNickname();
                            gameView.getTopStatusBar().updateTurnInfo(roleText, turn.wordDisplay(), turn.drawTimeSec());
                        });
                    }
                }
            }
            case TIMER_UPDATE -> {
                TimerUpdateDto timer = JsonCodec.decodePayload(msg, TimerUpdateDto.class);
                if (timer != null && timer.remainingSec() >= 0 && gameView != null) {
                    runOnUiThread(() -> gameView.getTopStatusBar().updateTimerAndMask(timer.remainingSec(), timer.wordMask(), canDraw));
                }
            }
            case CHAT_MESSAGE -> {
                ChatMessageDto chat = JsonCodec.decodePayload(msg, ChatMessageDto.class);
                if (chat != null && gameView != null) {
                    runOnUiThread(() -> {
                        String prefix = switch (chat.kind()) {
                            case SYSTEM -> "[Hệ thống] ";
                            case CLOSE_GUESS -> "[Gợi ý] ";
                            default -> chat.sender() + ": ";
                        };
                        gameView.appendChatMessage(prefix + chat.text());
                    });
                }
            }
            case PLAYER_GUESSED -> {
                PlayerGuessedDto guessed = JsonCodec.decodePayload(msg, PlayerGuessedDto.class);
                if (guessed != null) {
                    if (gameView != null && guessed.players() != null) {
                        runOnUiThread(() -> gameView.getPlayerListPanel().updatePlayers(guessed.players()));
                    }
                    appendSystemChat(guessed.nickname() + " đã đoán đúng! (+" + guessed.awardedPoints() + " điểm)");
                }
            }
            case TURN_END -> {
                TurnEndDto end = JsonCodec.decodePayload(msg, TurnEndDto.class);
                String answer = end != null ? end.word() : "";
                this.canDraw = false;
                if (gameView != null) {
                    runOnUiThread(() -> {
                        gameView.getDrawingCanvas().setDrawingEnabled(false);
                        if (end != null && end.players() != null) {
                            gameView.getPlayerListPanel().updatePlayers(end.players());
                        }
                        gameView.appendChatMessage("[Hệ thống] Hết lượt! Đáp án là: " + answer);
                    });
                }
            }
            case GAME_OVER -> {
                GameOverDto over = JsonCodec.decodePayload(msg, GameOverDto.class);
                this.canDraw = false;
                String winnerText = (over != null && over.winners() != null && !over.winners().isEmpty())
                        ? over.winners().stream().map(PlayerDto::nickname).collect(Collectors.joining(", "))
                        : "Không xác định";
                if (gameView != null) {
                    runOnUiThread(() -> {
                        gameView.getDrawingCanvas().setDrawingEnabled(false);
                        if (over != null && over.players() != null) {
                            gameView.getPlayerListPanel().updatePlayers(over.players());
                        }
                        gameView.getTopStatusBar().updateTurnInfo("Trò chơi kết thúc!", "CHIẾN THẮNG: " + winnerText, 0);
                        gameView.appendChatMessage("[Hệ thống] Trò chơi kết thúc! Người chiến thắng: " + winnerText);

                        GameOverDialog dialog = new GameOverDialog(over != null ? over.players() : null);
                        boolean stayInRoom = dialog.showAndWait().orElse(true);
                        if (!stayInRoom) {
                            leaveRoom();
                        }
                    });
                }
            }
            case ERROR_MSG -> {
                ErrorMsgDto err = JsonCodec.decodePayload(msg, ErrorMsgDto.class);
                String errText = (err != null && err.message() != null) ? err.message() : "Lỗi từ Server";
                if (gameView != null) {
                    runOnUiThread(() -> gameView.appendChatMessage("[Lỗi] " + errText));
                }
            }
            default -> {
            }
        }
    }

    private void appendSystemChat(String text) {
        if (gameView != null) {
            runOnUiThread(() -> gameView.appendChatMessage("[Hệ thống] " + text));
        }
    }

    private void showWordChoicePopup(WordChoicesDto dto) {
        WordChoiceDialog dialog = new WordChoiceDialog(dto.choices(), dto.chooseSeconds());
        Optional<Integer> result = dialog.showAndWait();
        int chosenIndex = result.orElse(0);
        socketClient.send(MessageType.CHOOSE_WORD, new ChooseWordDto(dto.turnId(), chosenIndex));
    }

    public void onConnectionLost(String reason) {
        resetRoomState();
        runOnUiThread(() -> {
            if (onRoomLeftCallback != null) {
                onRoomLeftCallback.run();
            }
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Mất kết nối mạng");
            alert.setHeaderText("Không thể duy trì kết nối tới Server");
            alert.setContentText(reason);
            alert.showAndWait();
        });
    }

    private void runOnUiThread(Runnable action) {
        try {
            if (Platform.isFxApplicationThread()) {
                action.run();
            } else {
                Platform.runLater(action);
            }
        } catch (IllegalStateException ignored) {
        }
    }

    public void shutdown() {
        socketClient.disconnect();
    }
}