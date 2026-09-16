package com.example.aitranslator.exception;

public class InvalidTranslationRequestException extends RuntimeException {

    public InvalidTranslationRequestException(String message) {
        super(message);
    }
}
