package com.creanger.app.messenger.creanger;

import org.json.JSONObject;
import org.junit.Test;
import com.creanger.app.messenger.creanger.api.JsonEnvelopeParser;
import com.creanger.app.messenger.creanger.api.ApiResponse;
import com.creanger.app.messenger.creanger.model.ApiError;

import static org.junit.Assert.*;

public class JsonEnvelopeParserTest {

    @Test
    public void parsesSuccessEnvelope() throws Exception {
        String body = "{\"success\":true,\"data\":{\"otpSent\":true,\"resendAfterSeconds\":30}}";
        ApiResponse res = JsonEnvelopeParser.parse(202, body, null);
        assertTrue(res.isSuccess());
        assertNull(res.error);
        JSONObject data = JsonEnvelopeParser.dataObject(body);
        assertNotNull(data);
        assertTrue(data.getBoolean("otpSent"));
        assertEquals(30L, data.getLong("resendAfterSeconds"));
    }

    @Test
    public void parsesErrorEnvelopeWithCodeAndMessage() {
        String body = "{\"success\":false,\"error\":{\"code\":\"OTP_EXPIRED\",\"message\":\"code expired\"}}";
        ApiResponse res = JsonEnvelopeParser.parse(400, body, null);
        assertFalse(res.isSuccess());
        assertNotNull(res.error);
        assertTrue(res.error.is(ApiError.OTP_EXPIRED));
    }

    @Test
    public void parsesRetryAfterHeaderForCooldown() {
        String body = "{\"success\":false,\"error\":{\"code\":\"RATE_LIMITED\",\"message\":\"slow down\"}}";
        ApiResponse res = JsonEnvelopeParser.parse(429, body, "12");
        assertNotNull(res.error);
        assertEquals(12L, res.error.retryAfterSeconds);
        assertTrue(res.error.is(ApiError.RATE_LIMITED));
    }

    @Test
    public void missingBodyYieldsInternalError() {
        ApiResponse res = JsonEnvelopeParser.parse(500, "", null);
        assertFalse(res.isSuccess());
        assertNotNull(res.error);
        assertEquals(ApiError.INTERNAL_ERROR, res.error.code);
    }

    @Test
    public void httpErrorWithoutErrorObjectIsGeneric() {
        ApiResponse res = JsonEnvelopeParser.parse(503, "upstream down", null);
        assertFalse(res.isSuccess());
        assertNotNull(res.error);
        assertEquals(ApiError.INTERNAL_ERROR, res.error.code);
    }
}