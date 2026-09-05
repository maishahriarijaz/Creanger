package com.creanger.app.messenger.creanger;

import org.junit.Test;

import static org.junit.Assert.*;

public class CreangerAuthConfigTest {

    @Test
    public void httpsUrlIsValid() {
        assertTrue(CreangerAuthConfig.isValidBaseUrl("https://auth.example.com"));
        assertTrue(CreangerAuthConfig.isValidBaseUrl("https://127.0.0.1:3000"));
    }

    @Test
    public void httpUrlIsRejected() {
        assertFalse(CreangerAuthConfig.isValidBaseUrl("http://auth.example.com"));
        assertFalse(CreangerAuthConfig.isValidBaseUrl("ws://example.com"));
        assertFalse(CreangerAuthConfig.isValidBaseUrl(null));
        assertFalse(CreangerAuthConfig.isValidBaseUrl(""));
    }
}