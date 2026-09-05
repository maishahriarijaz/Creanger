package com.creanger.app.messenger;

public interface GenericProvider<F, T> {
    T provide(F obj);
}
