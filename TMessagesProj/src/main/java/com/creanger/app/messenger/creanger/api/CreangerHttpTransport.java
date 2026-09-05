package com.creanger.app.messenger.creanger.api;

import java.io.IOException;

/**
 * Low-level HTTPS transport. Separated behind an interface so unit tests can
 * inject a stub transport without Android network stack or a running server.
 */
public interface CreangerHttpTransport {

    /**
     * Executes the request and returns the raw response (status + body +
     * retry-after). Must run on a background thread.
     */
    TransportResponse execute(ApiRequest request) throws IOException;
}