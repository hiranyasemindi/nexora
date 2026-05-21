package com.nexora.event;

public interface ExecutionEventBus {

    <E extends ExecutionEvent> void publish(E event);

    <E extends ExecutionEvent> Subscription subscribe(Class<E> eventType, EventHandler<E> handler);
}
