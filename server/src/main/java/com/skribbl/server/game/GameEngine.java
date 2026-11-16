package com.skribbl.server.game;

import com.skribbl.common.dto.*;
import com.skribbl.common.model.PlayerDto;
import com.skribbl.common.model.RoomStatus;
import com.skribbl.common.protocol.JsonCodec;
import com.skribbl.common.protocol.MessageType;
import com.skribbl.common.protocol.NetworkMessage;
import com.skribbl.server.net.ClientSession;
import com.skribbl.server.room.GameRoom;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

public class GameEngine {
    private static final int CHOOSE_SECONDS = 10;
    private static final int REVEAL_SECONDS = 3;
    private static final long SECOND = 1_000_000_000L;

    private final GameRoom room;
    private final WordRepository words;
    private final Random random;
    private final LongSupplier now;
    private final Consumer<GameRoom> resetCanvas;
    private final ArrayDeque<String> drawers = new ArrayDeque<>();
    private final Set<String> eligible = new HashSet<>();
    private final Set<String> guessed = new HashSet<>();
    private final Map<String, Integer> turnScores = new HashMap<>();

    private String turnId = "";
    private String drawerId;
    private String answer = "";
    private List<String> choices = List.of();
    private WordMask mask;
    private long deadline;
    private TurnEndDto lastEnd;

    public GameEngine(GameRoom room, WordRepository words, Random random,
                      LongSupplier now, Consumer<GameRoom> resetCanvas) {
        this.room = room;
        this.words = words;
        this.random = random;
        this.now = now;
        this.resetCanvas = resetCanvas;
    }

    public void onStart(ClientSession sender) {
        if (!isMember(sender))
            return;

        if (!Objects.equals(room.getHostId(), sender.getPlayerId())) {
            sender.sendError("PERMISSION_DENIED", "Only host can start");
            return;
        }

        if (room.getStatus() != RoomStatus.WAITING && room.getStatus() != RoomStatus.GAME_OVER) {
            sender.sendError("GAME_ALREADY_RUNNING", "Game is already running");
            return;
        }
        if (room.getPlayerCount() < 2) {
            sender.sendError("NOT_ENOUGH_PLAYERS", "Need at least 2 players");
            return;
        }

        for (PlayerDto p : room.getPlayers()) {
            room.updatePlayer(p.withScore(0).withGuessed(false).withDrawer(false));
        }

        room.setCurrentRound(1);
        fillDrawersForRound();
        beginNextTurn();
    }

    private void fillDrawersForRound() {
        drawers.clear();
        room.getPlayers().stream().map(PlayerDto::id).sorted().forEach(drawers::addLast);
    }

    private void beginNextTurn() {
        if (room.getPlayerCount() < 2) {
            abortGame();
            return;
        }

        String next = null;
        while (next == null) {
            while (!drawers.isEmpty()) {
                String candidate = drawers.removeFirst();
                if (room.getPlayer(candidate) != null) {
                    next = candidate;
                    break;
                }
            }

            if (next != null)
                break;
            if (room.getCurrentRound() >= room.getMaxRounds()) {
                finishGame();
                return;
            }

            room.setCurrentRound(room.getCurrentRound() + 1);
            fillDrawersForRound();
        }

        drawerId = next;
        turnId = UUID.randomUUID().toString();
        answer = "";
        mask = null;
        lastEnd = null;
        choices = words.pickThree(random);
        eligible.clear();
        guessed.clear();
        turnScores.clear();

        for (PlayerDto p : room.getPlayers()) {
            room.updatePlayer(p.withGuessed(false).withDrawer(false));
            if (!p.id().equals(drawerId))
                eligible.add(p.id());
        }

        room.setCurrentDrawerId(drawerId);
        room.getCanvasSync().startNewTurn(drawerId);
        room.setStatus(RoomStatus.WORD_SELECTION);
        setDeadline(CHOOSE_SECONDS);
        resetCanvas.accept(room);
        room.broadcastRoomUpdate();
        room.unicast(drawerId, packet(MessageType.WORD_CHOICES,
                new WordChoicesDto(turnId, choices, CHOOSE_SECONDS)
        ));
    }

    public void onChoose(ClientSession sender, ChooseWordDto dto) {
        if (!isMember(sender))
            return;

        if (room.getStatus() != RoomStatus.WORD_SELECTION
                || !Objects.equals(sender.getPlayerId(), drawerId)
                || dto == null || !Objects.equals(turnId, dto.turnId())) {
            sender.sendError("INVALID_WORD_CHOICE", "Wrong player, state or turn token");
            return;
        }

        if (remainingSeconds() == 0) {
            beginDrawing(0);
            sender.sendError("CHOICE_EXPIRED", "Word was selected automatically");
            return;
        }

        if (dto.choiceIndex() < 0 || dto.choiceIndex() >= choices.size()) {
            sender.sendError("INVALID_WORD_CHOICE", "Choice index must be 0..2");
            return;
        }
        beginDrawing(dto.choiceIndex());
    }

    private void beginDrawing(int index) {
        answer = choices.get(index);
        mask = new WordMask(answer, random);
        choices = List.of();
        room.setStatus(RoomStatus.DRAWING);
        setDeadline(room.getDrawTimeSec());
        room.broadcastRoomUpdate();

        for (ClientSession member : memberSnapshot())
            sendTurnStart(member);

        sendTimerToAll();
    }

    public void tick() {
        switch (room.getStatus()) {
            case WORD_SELECTION -> {
                if (remainingSeconds() == 0)
                    beginDrawing(0);
            }
            case DRAWING -> {
                if (remainingSeconds() == 0) {
                    sendTimerToAll();
                    finishTurn("TIME_UP");
                } else {
                    sendTimerToAll();
                }
            }
            case TURN_REVEAL -> {
                if (remainingSeconds() == 0)
                    beginNextTurn();
            }
            default -> {
            }
        }
    }

    private void sendTurnStart(ClientSession member) {
        PlayerDto drawer = room.getPlayer(drawerId);
        TurnStartDto dto = new TurnStartDto(room.getCurrentRound(), room.getMaxRounds(),
                drawerId, drawer == null ? "" : drawer.nickname(),
                displayFor(member), room.getDrawTimeSec());
        member.send(MessageType.TURN_START, dto);
    }

    private String displayFor(ClientSession member) {
        if (Objects.equals(member.getPlayerId(), drawerId))
            return answer;

        int elapsed = room.getDrawTimeSec() - remainingSeconds();
        return mask.display(elapsed, room.getDrawTimeSec());
    }

    private void sendTimerToAll() {
        for (ClientSession member : memberSnapshot()) {
            member.send(MessageType.TIMER_UPDATE,
                    new TimerUpdateDto(remainingSeconds(), displayFor(member)));
        }
    }

    private void finishTurn(String reason) {
        RoomStatus state = room.getStatus();
        if (state != RoomStatus.DRAWING && state != RoomStatus.WORD_SELECTION)
            return;

        room.setStatus(RoomStatus.TURN_REVEAL);
        room.setCurrentDrawerId(null);
        room.getCanvasSync().endTurn();
        setDeadline(REVEAL_SECONDS);
        lastEnd = new TurnEndDto(answer, reason, Map.copyOf(turnScores), playersSnapshot());
        room.broadcast(packet(MessageType.TURN_END, lastEnd));
        room.broadcastRoomUpdate();
    }

    private void finishGame() {
        room.setStatus(RoomStatus.GAME_OVER);
        room.setCurrentDrawerId(null);
        room.getCanvasSync().endTurn();

        for (PlayerDto p : room.getPlayers())
            room.updatePlayer(p.withGuessed(false));

        List<PlayerDto> players = playersSnapshot();
        int best = players.stream().mapToInt(PlayerDto::score).max().orElse(0);
        List<PlayerDto> winners = players.stream().filter(p -> p.score() == best).toList();

        room.broadcastRoomUpdate();
        room.broadcast(packet(MessageType.GAME_OVER, new GameOverDto(winners, players)));
    }

    private void abortGame() {
        room.setStatus(RoomStatus.WAITING);
        room.setCurrentDrawerId(null);
        room.setCurrentRound(0);
        room.getCanvasSync().endTurn();
        drawers.clear();
        eligible.clear();
        guessed.clear();
        turnScores.clear();
        answer = "";
        choices = List.of();
        mask = null;
        lastEnd = null;
        drawerId = null;
        turnId = "";

        for (PlayerDto p : room.getPlayers())
            room.updatePlayer(p.withGuessed(false));

        resetCanvas.accept(room);
        if (room.getPlayerCount() > 0) {
            room.broadcastSystemChat("Not enough players. Returned to lobby.");
            room.broadcastRoomUpdate();
        }
    }

    public void onMemberLeft(String playerId) {
        drawers.removeIf(playerId::equals);
        eligible.remove(playerId);
        guessed.remove(playerId);

        if (room.getPlayerCount() < 2) {
            abortGame();
            return;
        }

        RoomStatus state = room.getStatus();
        if ((state == RoomStatus.WORD_SELECTION || state == RoomStatus.DRAWING)
                && Objects.equals(playerId, drawerId)) {
            finishTurn("DRAWER_LEFT");
        } else if (state == RoomStatus.DRAWING && !eligible.isEmpty() && guessed.containsAll(eligible)) {
            finishTurn("ALL_GUESSED");
        }
    }

    public void onMemberJoined(ClientSession member) {
        if (room.getStatus() == RoomStatus.DRAWING) {
            if (remainingSeconds() == 0) {
                finishTurn("TIME_UP");
            } else {
                sendTurnStart(member);
                member.send(MessageType.CANVAS_SYNC, room.getCanvasSync().createSyncSnapshot());
                member.send(MessageType.TIMER_UPDATE,
                        new TimerUpdateDto(remainingSeconds(), displayFor(member)));
                member.send(MessageType.CHAT_MESSAGE, new ChatMessageDto("System",
                        "You can guess from the next turn.", ChatKind.SYSTEM));
            }
        } else if (room.getStatus() == RoomStatus.TURN_REVEAL && lastEnd != null) {
            member.send(MessageType.TURN_END, lastEnd);
        }
    }

    public boolean canDraw(ClientSession sender) {
        return isMember(sender) && room.getStatus() == RoomStatus.DRAWING
                && remainingSeconds() > 0 && Objects.equals(drawerId, sender.getPlayerId());
    }

    public void onChat(ClientSession sender, ChatSendDto dto) {
        if (!isMember(sender) || dto == null || dto.text() == null || dto.text().isBlank())
            return;

        String text = dto.text().trim();
        String senderId = sender.getPlayerId();

        if (room.getStatus() == RoomStatus.DRAWING) {
            if (Objects.equals(senderId, drawerId)) {
                if (WordRules.containsAnswer(text, answer)) {
                    sender.sendError("DRAWER_CANNOT_REVEAL", "Người vẽ không được nhắn đáp án!");
                    return;
                }
            } else if (eligible.contains(senderId) && !guessed.contains(senderId)) {
                String normGuess = WordRules.normalize(text);
                if (normGuess.equals(answer)) {
                    guessed.add(senderId);
                    int pts = ScoreService.guessPoints(remainingSeconds(), room.getDrawTimeSec());
                    turnScores.merge(senderId, pts, Integer::sum);
                    turnScores.merge(drawerId, ScoreService.DRAWER_BONUS, Integer::sum);

                    room.updatePlayerScore(senderId, pts);
                    room.updatePlayerScore(drawerId, ScoreService.DRAWER_BONUS);

                    PlayerDto p = room.getPlayer(senderId);
                    if (p != null) {
                        room.updatePlayer(p.withGuessed(true));
                    }

                    room.broadcast(packet(MessageType.PLAYER_GUESSED,
                            new PlayerGuessedDto(senderId, sender.getNickname(), pts, ScoreService.DRAWER_BONUS, playersSnapshot())));

                    if (guessed.containsAll(eligible)) {
                        finishTurn("ALL_GUESSED");
                    }
                    return;
                } else if (WordRules.distance(normGuess, answer) == 1) {
                    sender.send(MessageType.CHAT_MESSAGE,
                            new ChatMessageDto("System", "'" + text + "' gần đúng rồi!", ChatKind.CLOSE_GUESS));
                    return;
                }
            }
        }

        room.broadcast(packet(MessageType.CHAT_MESSAGE,
                new ChatMessageDto(sender.getNickname(), text, ChatKind.NORMAL)));
    }

    private boolean isMember(ClientSession sender) {
        return sender != null && sender.getPlayerId() != null
                && room.getSession(sender.getPlayerId()) == sender
                && Objects.equals(room.getRoomCode(), sender.getCurrentRoomCode());
    }

    private void setDeadline(int seconds) {
        deadline = now.getAsLong() + seconds * SECOND;
    }

    private int remainingSeconds() {
        long left = deadline - now.getAsLong();
        return left <= 0 ? 0 : (int) ((left + SECOND - 1) / SECOND);
    }

    private List<ClientSession> memberSnapshot() {
        return new ArrayList<>(room.getMemberSessions());
    }

    private List<PlayerDto> playersSnapshot() {
        return room.getPlayers().stream()
                .sorted(Comparator.comparingInt(PlayerDto::score).reversed()
                        .thenComparing(PlayerDto::id)).toList();
    }

    private static NetworkMessage packet(MessageType type, Object payload) {
        return NetworkMessage.of(type, JsonCodec.toNode(payload));
    }
}