package com.creanger.app.tgnet;

import com.creanger.app.tgnet.TLRPC;

public interface ResultCallback<T> {
    void onComplete(T result);
    default void onError(TLRPC.TL_error error) {}
}