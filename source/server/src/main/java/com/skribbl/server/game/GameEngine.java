package com.skribbl.server.game;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import com.skribbl.common.dto.*;
import com.skribbl.common.model.PlayerDto;
import com.skribbl.common.model.RoomStatus;
import com.skribbl.common.protocol.JsonCodec;
import com.skribbl.common.protocol.MessageType;
import com.skribbl.common.protocol.NetworkMessage;
import com.skribbl.server.net.ClientSession;
import com.skribbl.server.room.GameRoom;

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
        mask = new WordMask(answer, random);
        choices = List.of(); // Không cần giữ 3 lựa chọn sau khi chọn
        room.setStatus(RoomStatus.DRAWING);
        setDeadline(room.getDrawTimeSec());
        room.broadcastRoomUpdate();;
        
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
            default -> { }
        }
    }

    private void sendTurnStart(ClientSession member) {
        PlayerDto drawer = room.getPlayer(drawerId);
        TurnStartDto dto = new TurnStartDto(room.getCurrentRound(), room.getMaxRounds(), 
            drawerId, drawer == null ? "" : drawer.nickname(),
            displayFor(member), room.getDrawTimeSec());
        room.sendTo(member, packet(MessageType.TURN_START, dto));
    }

    private String displayFor(ClientSession member) {
        if (Objects.equals(member.getPlayerId(), drawerId))
            return answer;

        int elapsed = room.getDrawTimeSec() - remainingSeconds();
        return  mask.display(elapsed, room.getDrawTimeSec());
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

        room.broadcastRoomUpdate();
        room.broadcast(packet(MessageType.GAME_OVER, new GameOverDto(winners, players)));
    }

    private void abortGame() {
        room.setStatus(RoomStatus.WAITING);
        room.setCurrentDrawerId(null);
        room.setCurrentRound(0);
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

    /**
     * Xử lý logic ván chơi khi có một thành viên rời khỏi phòng (Leave/Disconnect).
     *
     * @param playerId ID của người chơi vừa rời phòng (String).
     */
    public void onMemberLeft(String playerId) {
        // 1. Dọn dẹp người chơi khỏi danh sách hàng đợi vẽ và danh sách đoán
        drawers.removeIf(playerId::equals);
        eligible.remove(playerId);
        guessed.remove(playerId);

        // 2. Nếu phòng đang trong trận đấu mà số người chơi tụt xuống dưới 2 -> hủy ván đấu về sảnh
        RoomStatus state = room.getStatus();
        boolean playing = state == RoomStatus.WORD_SELECTION || state == RoomStatus.DRAWING
                || state == RoomStatus.TURN_REVEAL;
        if (playing && room.getPlayerCount() < 2) {
            abortGame();
            return;
        }

        // 3. Nếu người rời đi chính là người đang vẽ trong lượt này -> kết thúc lượt ngay lập tức
        if ((state == RoomStatus.WORD_SELECTION || state == RoomStatus.DRAWING)
                && Objects.equals(playerId, drawerId)) {
            finishTurn("DRAWER_LEFT");
        } else if (state == RoomStatus.DRAWING && guessed.containsAll(eligible)) {
            // Nếu những người còn lại đều đã đoán đúng hết -> kết thúc lượt sớm
            finishTurn("ALL_GUESSED");
        }
    }

    /**
     * Xử lý logic ván chơi khi có một thành viên mới tham gia vào phòng (Join).
     * Chạy an toàn luồng dưới khóa roomLock.
     *
     * @param member Phiên mạng của người chơi mới vào (ClientSession).
     */
    public void onMemberJoined(ClientSession member) {
        // Nếu người chơi vào đúng lúc phòng đang trong lượt vẽ (DRAWING)
        if (room.getStatus() == RoomStatus.DRAWING) {
            if (remainingSeconds() == 0) {
                finishTurn("TIME_UP");
                return;
            } else {
                // Gửi thông tin lượt hiện tại, thời gian còn lại và thông báo chỉ được đoán từ lượt sau
                sendTurnStart(member);
                room.sendTo(member, packet(MessageType.TIMER_UPDATE,
                        new TimerUpdateDto(remainingSeconds(), displayFor(member))));
                privateChat(member, "You can guess from the next turn.", ChatKind.SYSTEM);
            }
        }

        // Nếu vào đúng lúc đang công bố đáp án của lượt (TURN_REVEAL)
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

            // Bao gồm spectator: không broadcast câu chứa nguyên đáp án.
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
        // Guard cuối cùng để helper không vô tình thưởng trùng nếu caller thay đổi.
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
