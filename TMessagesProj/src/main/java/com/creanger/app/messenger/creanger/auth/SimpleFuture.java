package com.creanger.app.messenger.creanger.auth;

/**
 * Minimal single-value future (min SDK 21-safe, no desugaring needed). Used by
 * the single-flight refresh to let concurrent callers await the same result
 * without {@code CompletableFuture} (API 24+).
 */
final class SimpleFuture<T> {

    private final Object lock = new Object();
    private boolean completed;
    private T value;
    private Throwable failure;

    T get() throws Throwable {
        synchronized (lock) {
            while (!completed) {
                lock.wait();
            }
            if (failure != null) {
                throw failure;
            }
            return value;
        }
    }

    void complete(T value) {
        synchronized (lock) {
            this.completed = true;
            this.value = value;
            lock.notifyAll();
        }
    }

    void completeExceptionally(Throwable throwable) {
        synchronized (lock) {
            this.completed = true;
            this.failure = throwable;
            lock.notifyAll();
        }
    }
}