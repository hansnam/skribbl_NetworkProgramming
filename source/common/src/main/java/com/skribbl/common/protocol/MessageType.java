package com.skribbl.common.protocol;

/**
 * Enumeration of all network message types exchanged between Client and Server.
 * Defined according to the Skribbl NDJSON protocol specification.
 */
public enum MessageType {
    // Connection Lifecycle
    CONNECT_REQ,
    CONNECT_ACK,
    PING,
    PONG,

    // Room & Lobby Management
    QUICK_JOIN,
    CREATE_ROOM,
    JOIN_ROOM,
    LEAVE_ROOM,
    ROOM_UPDATE,
    START_GAME,

    // Game Flow & Word Selection
    WORD_CHOICES,
    CHOOSE_WORD,
    TURN_START,
    TIMER_UPDATE,
    TURN_END,
    GAME_OVER,

    // Real-time Canvas Drawing
    DRAW_STROKE,
    FILL_CANVAS,
    UNDO_CANVAS,
    CLEAR_CANVAS,
    CANVAS_SYNC,
    DRAW_REACTION,

    // Chat & Guessing
    CHAT_SEND,
    CHAT_MESSAGE,
    PLAYER_GUESSED,

    // System & Errors
    ERROR_MSG
}
