package com.skribbl.common.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

/**
 * Standard protocol envelope wrapping every NDJSON message exchanged over TCP.
 * Formatted as a single JSON line terminated by newline (\n).
 *
 * @param type Message identifier matching {@link MessageType}
 * @param timestamp Epoch millisecond timestamp when the message was created
 * @param payload JSON data specific to the message type
 */
public record NetworkMessage(
        @JsonProperty("type") MessageType type,
        @JsonProperty("timestamp") long timestamp,
        @JsonProperty("payload") JsonNode payload
) {
    @JsonCreator
    public NetworkMessage {
        if (payload == null) {
            payload = NullNode.getInstance();
        }
    }

    public static NetworkMessage of(MessageType type, JsonNode payload) {
        return new NetworkMessage(type, System.currentTimeMillis(), payload);
    }

    public static NetworkMessage of(MessageType type) {
        return new NetworkMessage(type, System.currentTimeMillis(), NullNode.getInstance());
    }
}

