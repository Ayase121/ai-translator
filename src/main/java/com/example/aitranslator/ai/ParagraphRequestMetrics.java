package com.example.aitranslator.ai;

import java.util.concurrent.atomic.AtomicInteger;

/** Counts model calls belonging to one document translation. */
public final class ParagraphRequestMetrics {

    private final AtomicInteger mainRequests = new AtomicInteger();
    private final AtomicInteger fallbackRequests = new AtomicInteger();

    public void mainRequest() {
        mainRequests.incrementAndGet();
    }

    public void fallbackRequest() {
        fallbackRequests.incrementAndGet();
    }

    public int mainRequests() {
        return mainRequests.get();
    }

    public int fallbackRequests() {
        return fallbackRequests.get();
    }
}
