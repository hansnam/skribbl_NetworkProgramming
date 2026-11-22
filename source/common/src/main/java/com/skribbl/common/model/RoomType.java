package com.skribbl.common.model;

/**
 * Operating mode of a game room:
 * - PUBLIC: Quick play matchmaking, fixed 6-player capacity, automatic 5s countdown when >= 2 players, no host.
 * - PRIVATE: Created by a player, 5-character invite code, host controlled, configurable rounds/time.
 */
public enum RoomType {
    PUBLIC,
    PRIVATE
}
