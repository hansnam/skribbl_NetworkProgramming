package com.skribbl.common.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;

/**
 * High-performance, thread-safe JSON Codec for NDJSON (Newline-Delimited JSON) protocol.
 * Ensures every serialized message is on a single line without formatting breaks.
 */
public final class JsonCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static {
        MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        MAPPER.configure(SerializationFeature.INDENT_OUTPUT, false);
    }

    private JsonCodec() {
        // Utility class
    }

    /**
     * @return Underlying configured ObjectMapper instance
     */
    public static ObjectMapper getMapper() {
        return MAPPER;
    }

    /**
     * Serializes a NetworkMessage into a single-line JSON string.
     *
     * @param message Message to serialize
     * @return Single line JSON string
     * @throws JsonProcessingException if serialization fails
     */
    public static String serialize(NetworkMessage message) throws JsonProcessingException {
        return MAPPER.writeValueAsString(message);
    }

    /**
     * Convenience method to wrap type and payload into a NetworkMessage and serialize to JSON.
     *
     * @param type MessageType
     * @param payload Object payload (can be null)
     * @return Single line JSON string
     * @throws JsonProcessingException if serialization fails
     */
    public static String serializeMessage(MessageType type, Object payload) throws JsonProcessingException {
        JsonNode node = payload != null ? MAPPER.valueToTree(payload) : null;
        NetworkMessage msg = NetworkMessage.of(type, node);
        return serialize(msg);
    }

    /**
     * Deserializes a single-line JSON string into a NetworkMessage envelope.
     *
     * @param jsonLine Line of JSON received from socket
     * @return Parsed NetworkMessage
     * @throws IOException if parsing fails or invalid JSON
     */
    public static NetworkMessage deserialize(String jsonLine) throws IOException {
        if (jsonLine == null || jsonLine.isBlank()) {
            throw new IllegalArgumentException("JSON line cannot be null or blank");
        }
        return MAPPER.readValue(jsonLine, NetworkMessage.class);
    }

    /**
     * Decodes the payload of a NetworkMessage into a concrete target class (e.g. record/DTO).
     *
     * @param message NetworkMessage containing the payload
     * @param clazz Target class
     * @param <T> Target type
     * @return Decoded payload object
     */
    public static <T> T decodePayload(NetworkMessage message, Class<T> clazz) {
        if (message == null || message.payload() == null || message.payload().isNull()) {
            return null;
        }
        return MAPPER.convertValue(message.payload(), clazz);
    }

    /**
     * Decodes the payload into a generic type via TypeReference.
     */
    public static <T> T decodePayload(NetworkMessage message, TypeReference<T> typeRef) {
        if (message == null || message.payload() == null || message.payload().isNull()) {
            return null;
        }
        return MAPPER.convertValue(message.payload(), typeRef);
    }

    /**
     * Converts any Java object to a Jackson JsonNode tree.
     */
    public static JsonNode toNode(Object object) {
        if (object == null) {
            return null;
        }
        return MAPPER.valueToTree(object);
    }
}

