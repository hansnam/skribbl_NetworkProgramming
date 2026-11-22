package com.skribbl.server.game;

import com.skribbl.common.dto.*;
import com.skribbl.common.model.PlayerDto;
import com.skribbl.common.model.RoomStatus;
import com.skribbl.common.model.RoomType;
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
    private static final int PUBLIC_COUNTDOWN_SECONDS = 5;
    private static final int PUBLIC_GAMEOVER_WAIT_SECONDS = 5;
    private static final int RATE_LIMIT_MAX_MSGS = 5;
    private static final long RATE_LIMIT_WINDOW_NANO = 5_000_000_000L;
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
    private final Set<String> usedWords = new HashSet<>();
    private final Set<String> reactedThisTurn = new HashSet<>();
    private final Map<String, ArrayDeque<Long>> chatTimestamps = new HashMap<>();
    private final Map<String, Long> lastRateWarnNano = new HashMap<>();

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

        if (room.getRoomType() == RoomType.PUBLIC) {
            room.sendErrorTo(sender, "PERMISSION_DENIED", "Public rooms start automatically when ready");
            return;
        }

        if (!Objects.equals(room.getHostId(), sender.getPlayerId())) {
            room.sendErrorTo(sender, "PERMISSION_DENIED", "Only host can start");
            return;
        }

        if (room.getStatus() != RoomStatus.WAITING && room.getStatus() != RoomStatus.GAME_OVER) {
            room.sendErrorTo(sender, "GAME_ALREADY_RUNNING", "Game is already running");
            return;
        }
        if (room.getPlayerCount() < 2) {
            room.sendErrorTo(sender, "NOT_ENOUGH_PLAYERS", "Need at least 2 players");
            return;
        }

        startMatch();
    }

    private void startMatch() {
        if (room.getPlayerCount() < 2) {
            return;
        }
        room.setStartInSec(null);
        usedWords.clear();
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
        choices = words.pickThreeExcluding(random, usedWords);
        eligible.clear();
        guessed.clear();
        turnScores.clear();
        reactedThisTurn.clear();

        for (PlayerDto p : room.getPlayers()) {
            room.updatePlayer(p.withGuessed(false).withDrawer(false));
            if (!p.id().equals(drawerId))
                eligible.add(p.id());
        }

        room.setCurrentDrawerId(drawerId);
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
            room.sendErrorTo(sender, "INVALID_WORD_CHOICE", "Wrong player, state or turn token");
            return;
        }

        if (remainingSeconds() == 0) {
            beginDrawing(0);
            room.sendErrorTo(sender, "CHOISE_EXPIRED", "Word was selected automatically");
            return;
        }

        if (dto.choiceIndex() < 0 || dto.choiceIndex() >= choices.size()) {
            room.sendErrorTo(sender, "INVALID_WORD_CHOICE", "Choice index must be 0..2");
            return;
        }
        beginDrawing(dto.choiceIndex());
    }

    private void beginDrawing(int index) {
        answer = choices.get(index);
        usedWords.add(answer);
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
            case WAITING -> {
                if (room.getRoomType() == RoomType.PUBLIC) {
                    if (room.getPlayerCount() >= 2) {
                        if (room.getStartInSec() == null) {
                            setDeadline(PUBLIC_COUNTDOWN_SECONDS);
                            room.setStartInSec(PUBLIC_COUNTDOWN_SECONDS);
                            room.broadcastRoomUpdate();
                        } else {
                            int rem = remainingSeconds();
                            if (rem == 0) {
                                startMatch();
                            } else if (!Objects.equals(room.getStartInSec(), rem)) {
                                room.setStartInSec(rem);
                                room.broadcastRoomUpdate();
                            }
                        }
                    } else if (room.getStartInSec() != null) {
                        room.setStartInSec(null);
                        room.broadcastRoomUpdate();
                    }
                }
            }
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
            case GAME_OVER -> {
                if (room.getRoomType() == RoomType.PUBLIC && remainingSeconds() == 0) {
                    room.setStatus(RoomStatus.WAITING);
                    if (room.getPlayerCount() >= 2) {
                        setDeadline(PUBLIC_COUNTDOWN_SECONDS);
                        room.setStartInSec(PUBLIC_COUNTDOWN_SECONDS);
                    } else {
                        room.setStartInSec(null);
                    }
                    room.broadcastRoomUpdate();
                }
            }
        }
    }

    private void sendTurnStart(ClientSession member) {
        PlayerDto drawer = room.getPlayer(drawerId);
        boolean canGuess = Objects.equals(member.getPlayerId(), drawerId) || eligible.contains(member.getPlayerId());
        TurnStartDto dto = new TurnStartDto(room.getCurrentRound(), room.getMaxRounds(),
                drawerId, drawer == null ? "" : drawer.nickname(),
                displayFor(member), room.getDrawTimeSec(), canGuess);
        room.sendTo(member, packet(MessageType.TURN_START, dto));
    }

    private String displayFor(ClientSession member) {
        if (Objects.equals(member.getPlayerId(), drawerId))
            return answer;

        int elapsed = room.getDrawTimeSec() - remainingSeconds();
        return mask.display(elapsed, room.getDrawTimeSec());
    }

    private void sendTimerToAll() {
        for (ClientSession member : memberSnapshot()) {
            room.sendTo(member, packet(MessageType.TIMER_UPDATE,
                    new TimerUpdateDto(remainingSeconds(), displayFor(member))));
        }
    }

    private void finishTurn(String reason) {
        RoomStatus state = room.getStatus();
        if (state != RoomStatus.DRAWING && state != RoomStatus.WORD_SELECTION)
            return;

        room.setStatus(RoomStatus.TURN_REVEAL);
        room.setCurrentDrawerId(null);
        setDeadline(REVEAL_SECONDS);
        lastEnd = new TurnEndDto(answer, reason, Map.copyOf(turnScores), playersSnapshot());
        room.broadcast(packet(MessageType.TURN_END, lastEnd));
        room.broadcastRoomUpdate();
    }

    private void finishGame() {
        room.setStatus(RoomStatus.GAME_OVER);
        room.setCurrentDrawerId(null);

        for (PlayerDto p : room.getPlayers())
            room.updatePlayer(p.withGuessed(false));

        List<PlayerDto> players = playersSnapshot();
        int best = players.stream().mapToInt(PlayerDto::score).max().orElse(0);
        List<PlayerDto> winners = players.stream().filter(p -> p.score() == best).toList();

        if (room.getRoomType() == RoomType.PUBLIC) {
            setDeadline(PUBLIC_GAMEOVER_WAIT_SECONDS);
        }

        room.broadcastRoomUpdate();
        room.broadcast(packet(MessageType.GAME_OVER, new GameOverDto(winners, players)));
    }

    private void abortGame() {
        room.setStatus(RoomStatus.WAITING);
        room.setCurrentDrawerId(null);
        room.setCurrentRound(0);
        room.setStartInSec(null);
        drawers.clear();
        eligible.clear();
        guessed.clear();
        turnScores.clear();
        reactedThisTurn.clear();
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
        reactedThisTurn.remove(playerId);
        chatTimestamps.remove(playerId);
        lastRateWarnNano.remove(playerId);

        RoomStatus state = room.getStatus();
        boolean playing = state == RoomStatus.WORD_SELECTION || state == RoomStatus.DRAWING
                || state == RoomStatus.TURN_REVEAL;
        if (playing && room.getPlayerCount() < 2) {
            abortGame();
            return;
        }

        if (state == RoomStatus.WAITING && room.getRoomType() == RoomType.PUBLIC && room.getPlayerCount() < 2) {
            room.setStartInSec(null);
        }

        if ((state == RoomStatus.WORD_SELECTION || state == RoomStatus.DRAWING)
                && Objects.equals(playerId, drawerId)) {
            finishTurn("DRAWER_LEFT");
        } else if (state == RoomStatus.DRAWING && guessed.containsAll(eligible)) {
            finishTurn("ALL_GUESSED");
        }
    }

    public void onMemberJoined(ClientSession member) {
        if (room.getStatus() == RoomStatus.WAITING && room.getRoomType() == RoomType.PUBLIC) {
            if (room.getPlayerCount() >= 2 && room.getStartInSec() == null) {
                setDeadline(PUBLIC_COUNTDOWN_SECONDS);
                room.setStartInSec(PUBLIC_COUNTDOWN_SECONDS);
                room.broadcastRoomUpdate();
            }
        }

        if (room.getStatus() == RoomStatus.DRAWING) {
            if (remainingSeconds() == 0) {
                finishTurn("TIME_UP");
                return;
            } else {
                sendTurnStart(member);
                room.sendTo(member, packet(MessageType.TIMER_UPDATE,
                        new TimerUpdateDto(remainingSeconds(), displayFor(member))));
                privateChat(member, "You can guess from the next turn.", ChatKind.SYSTEM);
            }
        }

        if (room.getStatus() == RoomStatus.TURN_REVEAL && lastEnd != null) {
            TurnEndDto result = new TurnEndDto(lastEnd.word(), lastEnd.reason(),
                    lastEnd.turnScores(), playersSnapshot());
            room.sendTo(member, packet(MessageType.TURN_END, result));
        }
    }

    public boolean canDraw(ClientSession sender) {
        return isMember(sender) && room.getStatus() == RoomStatus.DRAWING
                && remainingSeconds() > 0 && Objects.equals(drawerId, sender.getPlayerId());
    }

    public void onReaction(ClientSession sender, DrawReactionDto dto) {
        if (!isMember(sender) || dto == null || room.getStatus() != RoomStatus.DRAWING)
            return;
        String id = sender.getPlayerId();
        if (Objects.equals(id, drawerId) || !reactedThisTurn.add(id))
            return;

        DrawReactionDto outbound = new DrawReactionDto(sender.getNickname(), dto.like());
        room.broadcast(packet(MessageType.DRAW_REACTION, outbound));
    }

    private boolean isRateLimited(ClientSession sender) {
        String id = sender.getPlayerId();
        long currentNano = now.getAsLong();
        ArrayDeque<Long> q = chatTimestamps.computeIfAbsent(id, k -> new ArrayDeque<>());
        while (!q.isEmpty() && currentNano - q.peekFirst() >= RATE_LIMIT_WINDOW_NANO) {
            q.pollFirst();
        }
        if (q.size() >= RATE_LIMIT_MAX_MSGS) {
            Long lastWarn = lastRateWarnNano.get(id);
            if (lastWarn == null || currentNano - lastWarn >= RATE_LIMIT_WINDOW_NANO) {
                lastRateWarnNano.put(id, currentNano);
                room.sendErrorTo(sender, "CHAT_RATE_LIMITED", "Slow down! Max 5 messages per 5 seconds.");
            }
            return true;
        }
        q.addLast(currentNano);
        return false;
    }

    public void onChat(ClientSession sender, ChatSendDto dto) {
        if (!isMember(sender))
            return;
        if (dto == null || dto.text() == null || dto.text().isBlank())
            return;
        String text = dto.text().strip();
        if (text.length() > 200) {
            room.sendErrorTo(sender, "CHAT_TOO_LONG", "Maximum 200 characters");
            return;
        }
        if (isRateLimited(sender)) {
            return;
        }

        String normalized = WordRules.normalize(text);
        RoomStatus state = room.getStatus();

        if (state == RoomStatus.DRAWING) {
            int remaining = remainingSeconds();
            if (remaining == 0) {
                finishTurn("TIME_UP");
                privateChat(sender, "Time is up.", ChatKind.SYSTEM);
                return;
            }

            String id = sender.getPlayerId();
            boolean canGuess = eligible.contains(id);
            if (Objects.equals(id, drawerId) || guessed.contains(id)) {
                privateChat(sender, "Wait until the next turn to chat.", ChatKind.SYSTEM);
                return;
            }

            if (canGuess) {
                if (normalized.equals(answer)) {
                    awardCorrectGuess(sender, remaining);
                    return;
                }
                if (WordRules.distance(normalized, answer) == 1) {
                    privateChat(sender, "Your guess is close!", ChatKind.CLOSE_GUESS);
                    return;
                }
            }

            if (WordRules.containsAnswer(text, answer)) {
                if (canGuess) {
                    privateChat(sender, "Type only the answer to submit a guess.", ChatKind.SYSTEM);
                } else {
                    privateChat(sender, "You can guess from the next turn.", ChatKind.SYSTEM);
                }
                return;
            }
        }

        if (state == RoomStatus.TURN_REVEAL && WordRules.containsAnswer(text, answer)) {
            privateChat(sender, "The answer is already shown in the turn result.", ChatKind.SYSTEM);
            return;
        }

        room.broadcast(packet(MessageType.CHAT_MESSAGE,
                new ChatMessageDto(sender.getNickname(), text, ChatKind.NORMAL)
        ));
    }

    private void privateChat(ClientSession sender, String text, ChatKind kind) {
        room.sendTo(sender, packet(MessageType.CHAT_MESSAGE,
                new ChatMessageDto("System", text, kind)));
    }

    private void awardCorrectGuess(ClientSession sender, int remaining) {
        String id = sender.getPlayerId();
        if (!guessed.add(id)) {
            return;
        }

        int points = ScoreService.guessPoints(remaining, room.getDrawTimeSec());
        int bonus = ScoreService.DRAWER_BONUS;

        PlayerDto player = room.getPlayer(id);
        room.updatePlayer(player.withScore(player.score() + points).withGuessed(true));
        room.updatePlayerScore(drawerId, bonus);

        int previousPoints = turnScores.getOrDefault(id, 0);
        turnScores.put(id, previousPoints + points);
        int previousBonus = turnScores.getOrDefault(drawerId, 0);
        turnScores.put(drawerId, previousBonus + bonus);

        PlayerGuessedDto event = new PlayerGuessedDto(id, player.nickname(),
                points, bonus, playersSnapshot());
        room.broadcast(packet(MessageType.PLAYER_GUESSED, event));

        if (guessed.containsAll(eligible))
            finishTurn("ALL_GUESSED");
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
