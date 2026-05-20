package com.nexora.event;

@FunctionalInterface
public interface EventHandler<E extends ExecutionEvent> {
    void onEvent(E event);
}
