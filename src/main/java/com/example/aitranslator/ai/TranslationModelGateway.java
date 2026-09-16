package com.example.aitranslator.ai;

@FunctionalInterface
public interface TranslationModelGateway {

    String translate(TranslationModelRequest request);
}
