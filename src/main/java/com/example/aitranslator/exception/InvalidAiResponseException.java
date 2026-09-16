package com.example.aitranslator.exception;

/** Indicates that DeepSeek returned a response that does not satisfy the contract. */
public class InvalidAiResponseException extends RuntimeException {

    public enum Reason {
        JSON_SYNTAX, RESPONSE_STRUCTURE, SEGMENT_MAPPING, EMPTY_TRANSLATION, OTHER
    }

    private final Reason reason;

    public InvalidAiResponseException(String message) {
        this(message, Reason.OTHER, null);
    }

    public InvalidAiResponseException(String message, Throwable cause) {
        this(message, Reason.OTHER, cause);
    }

    public InvalidAiResponseException(String message, Reason reason) {
        this(message, reason, null);
    }

    public InvalidAiResponseException(String message, Reason reason, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
