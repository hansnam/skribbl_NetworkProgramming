package com.skribbl.server.game;

import com.skribbl.common.dto.ChatSendDto;
import com.skribbl.common.dto.ChooseWordDto;
import com.skribbl.common.dto.DrawReactionDto;
import com.skribbl.common.dto.DrawStrokeDto;
import com.skribbl.common.dto.FillCanvasDto;
import com.skribbl.server.net.ClientSession;
import com.skribbl.server.room.GameRoom;
import com.skribbl.server.room.RoomManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

public class GameService {
    private final RoomManager rooms;
    private final WordRepository words;
    private final ExecutorService tasks;
    private final LongSupplier now;

    public GameService(RoomManager rooms, WordRepository words, ExecutorService tasks, LongSupplier now) {
        this.rooms = rooms;
        this.words = words;
        this.tasks = tasks;
        this.now = now;
    }

    public void onCreated(GameRoom room) {
        RoomRuntime runtime = new RoomRuntime(room, words, now, () -> {
            try {
                tasks.execute(() -> rooms.closeRoom(room));
            } catch (RejectedExecutionException ex) {
                System.err.println("Cleanup executor is stopping: " + room.getRoomCode());
            }
        });
        room.attachRuntime(runtime);
    }

    public void withMember(ClientSession sender, Consumer<RoomRuntime> action) {
        if (sender == null || !sender.isConnected() || sender.getPlayerId() == null)
            return;
        GameRoom room = rooms.getRoom(sender.getCurrentRoomCode());
        if (room == null) {
            sender.sendError("NOT_IN_ROOM", "Join a room first");
            return;
        }

        room.withStateLock(() -> {
            RoomRuntime runtime = room.getRuntime();
            if (!runtime.active() || room.getSession(sender.getPlayerId()) != sender
                || !sender.isConnected())
                return;

            action.accept(runtime);
        });
    }

    public void onStart(ClientSession sender) {
        withMember(sender, room -> room.engine.onStart(sender));
    }

    public void onChoose(ClientSession sender, ChooseWordDto dto) {
        withMember(sender, room -> room.engine.onChoose(sender, dto));
    }

    public void onChat(ClientSession sender, ChatSendDto dto) {
        withMember(sender, room -> room.engine.onChat(sender, dto));
    }

    public void onReaction(ClientSession sender, DrawReactionDto dto) {
        withMember(sender, room -> room.engine.onReaction(sender, dto));
    }

    public void onJoined(GameRoom room, ClientSession member) {
        room.getRuntime().engine.onMemberJoined(member);
        room.getRuntime().canvas.registerClient(member);
    }

    public void onLeft(GameRoom room, String id) {
        room.getRuntime().engine.onMemberLeft(id);
    }

    public void onDrawStroke(ClientSession sender, DrawStrokeDto dto) {
        withMember(sender, room -> {
            if (!room.engine.canDraw(sender)) {
                room.room.sendErrorTo(sender, "DRAW_NOT_ALLOWED", "Only the current drawer may draw");
                return;
            }
            if (!room.canvas.handleDrawStroke(sender, dto)) {
                room.room.sendErrorTo(sender, "INVALID_STROKE", "Invalid stroke or history full");
            }
        });
    }

    public void onFillCanvas(ClientSession sender, FillCanvasDto dto) {
        withMember(sender, room -> {
            if (!room.engine.canDraw(sender)) {
                room.room.sendErrorTo(sender, "DRAW_NOT_ALLOWED", "Only the current drawer may fill");
                return;
            }
            if (!room.canvas.handleFillCanvas(sender, dto)) {
                room.room.sendErrorTo(sender, "INVALID_FILL", "Invalid fill coordinates or history full");
            }
        });
    }

    public void onUndoCanvas(ClientSession sender) {
        withMember(sender, room -> {
            if (!room.engine.canDraw(sender)) {
                room.room.sendErrorTo(sender, "DRAW_NOT_ALLOWED", "Only the current drawer may undo");
                return;
            }
            room.canvas.handleUndoCanvas(sender);
        });
    }

    public void onClearCanvas(ClientSession sender) {
        withMember(sender, room -> {
            if (!room.engine.canDraw(sender)) {
                room.room.sendErrorTo(sender, "DRAW_NOT_ALLOWED", "Only the current drawer may clear");
                return;
            }
            room.canvas.handleClearCanvas(sender);
        });
    }

    public List<RoomRuntime> snapshot() {
        List<RoomRuntime> result = new ArrayList<>();
        for (GameRoom room : rooms.getAllRooms())
            result.add(room.getRuntime());
        return result;
    }

    public void tick(RoomRuntime runtime) {
        runtime.room.withStateLock(() -> {
            if (runtime.active())
                runtime.engine.tick();
        });
    }
}
