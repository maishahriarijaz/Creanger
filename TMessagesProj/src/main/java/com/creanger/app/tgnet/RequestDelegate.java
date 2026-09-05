package com.creanger.app.tgnet;

import com.creanger.app.tgnet.TLRPC;
import com.creanger.app.tgnet.TLObject;

/**
 * Request delegate for async network operations.
 * No longer tied to Telegram TLRPC types.
 */
public interface RequestDelegate {
    void run(TLObject response, TLRPC.TL_error error);
}