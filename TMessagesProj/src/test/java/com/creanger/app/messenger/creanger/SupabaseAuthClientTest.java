package com.creanger.app.messenger.creanger;

import org.json.JSONObject;
import org.junit.Test;
import com.creanger.app.messenger.creanger.api.ApiRequest;
import com.creanger.app.messenger.creanger.api.CreangerApiException;
import com.creanger.app.messenger.creanger.api.CreangerHttpTransport;
import com.creanger.app.messenger.creanger.api.SupabaseAuthClient;
import com.creanger.app.messenger.creanger.api.TransportResponse;
import com.creanger.app.messenger.creanger.model.ApiError;
import com.creanger.app.messenger.creanger.model.AuthModels.AuthSession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class SupabaseAuthClientTest {

    private static final class FakeTransport implements CreangerHttpTransport {
        final List<ApiRequest> requests = new ArrayList<>();
        volatile String responseBody;
        volatile int statusCode = 200;
        volatile Map<String, String> headers;

        @Override
        public TransportResponse execute(ApiRequest request) {
            requests.add(request);
            return new TransportResponse(statusCode, responseBody, headers);
        }
    }

    private static final String SESSION_JSON =
            "{\"access_token\":\"acc\",\"refresh_token\":\"ref\",\"user\":{\"id\":\"u1\",\"email\":\"u@x.com\","
                    + "\"email_confirmed_at\":\"2026-01-01T00:00:00Z\",\"created_at\":\"2026-01-01T00:00:00Z\","
                    + "\"user_metadata\":{\"username\":\"user9\",\"display_name\":\"User Nine\"}}}";

    @Test
    public void passwordGrantPostsEmailAndPasswordAndParsesSession() throws Exception {
        FakeTransport t = new FakeTransport();
        t.responseBody = SESSION_JSON;
        SupabaseAuthClient client = new SupabaseAuthClient(t);

        AuthSession session = client.passwordGrant("u@x.com", "secret123");

        assertEquals(1, t.requests.size());
        ApiRequest req = t.requests.get(0);
        assertEquals("POST", req.method);
        assertEquals("/auth/v1/token", req.path);
        assertEquals("password", req.query.get("grant_type"));
        JSONObject body = new JSONObject(req.jsonBody);
        assertEquals("u@x.com", body.getString("email"));
        assertEquals("secret123", body.getString("password"));

        assertEquals("acc", session.accessToken);
        assertEquals("ref", session.refreshToken);
        assertNotNull(session.user);
        assertEquals("u1", session.user.id);
        assertTrue(session.user.emailVerified);
        assertEquals("user9", session.user.username);
        assertEquals("User Nine", session.user.displayName);
    }

    @Test
    public void idTokenGrantSendsGoogleProvider() throws Exception {
        FakeTransport t = new FakeTransport();
        t.responseBody = SESSION_JSON;
        SupabaseAuthClient client = new SupabaseAuthClient(t);

        client.idTokenGrant("google-id-token");

        ApiRequest req = t.requests.get(0);
        assertEquals("id_token", req.query.get("grant_type"));
        JSONObject body = new JSONObject(req.jsonBody);
        assertEquals("google", body.getString("provider"));
        assertEquals("google-id-token", body.getString("id_token"));
    }

    @Test
    public void refreshTokenGrantRotatesTokens() throws Exception {
        FakeTransport t = new FakeTransport();
        t.responseBody = "{\"access_token\":\"acc2\",\"refresh_token\":\"ref2\"}";
        SupabaseAuthClient client = new SupabaseAuthClient(t);

        AuthSession session = client.refreshTokenGrant("ref1");

        ApiRequest req = t.requests.get(0);
        assertEquals("refresh_token", req.query.get("grant_type"));
        assertEquals("ref1", new JSONObject(req.jsonBody).getString("refresh_token"));
        assertEquals("acc2", session.accessToken);
        assertEquals("ref2", session.refreshToken);
    }

    @Test
    public void signUpSendsMetadataAndParsesOptionalSession() throws Exception {
        FakeTransport t = new FakeTransport();
        // Auto-confirm ON: full session.
        t.responseBody = SESSION_JSON;
        SupabaseAuthClient client = new SupabaseAuthClient(t);

        AuthSession session = client.signUp("u@x.com", "secret123", "user9", "User Nine");
        assertNotNull(session);

        ApiRequest req = t.requests.get(0);
        assertEquals("/auth/v1/signup", req.path);
        JSONObject body = new JSONObject(req.jsonBody);
        assertEquals("u@x.com", body.getString("email"));
        assertEquals("secret123", body.getString("password"));
        assertEquals("user9", body.getJSONObject("data").getString("username"));

        // Auto-confirm OFF: bare user object -> null session.
        t.responseBody = "{\"id\":\"u1\",\"email\":\"u@x.com\"}";
        assertNull(client.signUp("u@x.com", "secret123", "user9", null));
    }

    @Test
    public void verifyOtpBuildsTypedBodyAndParsesSession() throws Exception {
        FakeTransport t = new FakeTransport();
        t.responseBody = SESSION_JSON;
        SupabaseAuthClient client = new SupabaseAuthClient(t);

        client.verifyOtp(SupabaseAuthClient.OTP_TYPE_SIGNUP, "u@x.com", "123456");

        ApiRequest req = t.requests.get(0);
        assertEquals("/auth/v1/verify", req.path);
        JSONObject body = new JSONObject(req.jsonBody);
        assertEquals("signup", body.getString("type"));
        assertEquals("123456", body.getString("token"));
    }

    @Test
    public void recoverSendsEmail() throws Exception {
        FakeTransport t = new FakeTransport();
        t.responseBody = "{}";
        SupabaseAuthClient client = new SupabaseAuthClient(t);

        client.recover("u@x.com");

        ApiRequest req = t.requests.get(0);
        assertEquals("/auth/v1/recover", req.path);
        assertEquals("u@x.com", new JSONObject(req.jsonBody).getString("email"));
    }

    @Test
    public void updateUserPasswordUsesPutWithBearer() throws Exception {
        FakeTransport t = new FakeTransport();
        t.responseBody = "{\"id\":\"u1\"}";
        SupabaseAuthClient client = new SupabaseAuthClient(t);

        client.updateUserPassword("access-123", "newSecret99");

        ApiRequest req = t.requests.get(0);
        assertEquals("/auth/v1/user", req.path);
        assertEquals("PUT", req.method);
        assertEquals("access-123", req.accessToken);
        assertEquals("newSecret99", new JSONObject(req.jsonBody).getString("password"));
    }

    @Test
    public void getUserUsesBearerHeader() throws Exception {
        FakeTransport t = new FakeTransport();
        t.responseBody = "{\"id\":\"u1\",\"user_metadata\":{\"username\":\"u\"}}";
        SupabaseAuthClient client = new SupabaseAuthClient(t);

        client.getUser("access-123");

        ApiRequest req = t.requests.get(0);
        assertEquals("/auth/v1/user", req.path);
        assertEquals("GET", req.method);
        assertEquals("access-123", req.accessToken);
    }

    @Test
    public void signOutPostsLogoutWithBearer() throws Exception {
        FakeTransport t = new FakeTransport();
        t.statusCode = 204;
        t.responseBody = "";
        SupabaseAuthClient client = new SupabaseAuthClient(t);

        client.signOut("access-123");

        ApiRequest req = t.requests.get(0);
        assertEquals("/auth/v1/logout", req.path);
        assertEquals("access-123", req.accessToken);
    }

    @Test
    public void checkUsernameSendsGetQueryAndParsesScalar() throws Exception {
        FakeTransport t = new FakeTransport();
        t.responseBody = "true";
        SupabaseAuthClient client = new SupabaseAuthClient(t);

        assertTrue(client.checkUsername("john.doe_1"));

        ApiRequest req = t.requests.get(0);
        assertEquals("GET", req.method);
        assertEquals("/rest/v1/rpc/check_username_available", req.path);
        assertEquals("john.doe_1", req.query.get("p_username"));
        assertNull(req.jsonBody);
        assertNull(req.accessToken);

        t.responseBody = "false";
        assertFalse(client.checkUsername("taken"));
    }

    @Test
    public void resolveLoginEmailParsesScalarStringOrNull() throws Exception {
        FakeTransport t = new FakeTransport();
        t.responseBody = "\"resolved@example.com\"";
        SupabaseAuthClient client = new SupabaseAuthClient(t);

        assertEquals("resolved@example.com", client.resolveLoginEmail("user9"));
        assertEquals("user9", t.requests.get(0).query.get("p_identifier"));

        // Unknown username / email identifier: SQL NULL renders as `null`.
        t.responseBody = "null";
        assertNull(client.resolveLoginEmail("ghost"));
        assertNull(client.resolveLoginEmail("direct@example.com"));
    }

    @Test
    public void getProfileParsesRowAndTreatsUnclaimedUsernameAsMissing() throws Exception {
        FakeTransport t = new FakeTransport();
        t.responseBody = "[{\"user_id\":\"g1\",\"username\":\"newbie\",\"first_name\":\"New\",\"last_name\":null}]";
        SupabaseAuthClient client = new SupabaseAuthClient(t);

        SupabaseAuthClient.ProfileRow row = client.getProfile("access-123", "g1");
        assertNotNull(row);
        assertEquals("newbie", row.username);
        assertEquals("eq.g1", t.requests.get(0).query.get("user_id"));

        // Username unclaimed or no row -> treated as needs-setup.
        t.responseBody = "[{\"user_id\":\"g1\",\"username\":null,\"first_name\":\"N\"}]";
        assertNull(client.getProfile("access-123", "g1"));
        t.responseBody = "[]";
        assertNull(client.getProfile("access-123", "g1"));
    }

    @Test
    public void getProfileReadsDisplayName() throws Exception {
        FakeTransport t = new FakeTransport();
        t.responseBody = "[{\"user_id\":\"u9\",\"username\":\"ijaz\",\"first_name\":\"Ijaz\","
                + "\"last_name\":\"Ahmed\",\"display_name\":\"Ijaz Ahmed\"}]";
        SupabaseAuthClient client = new SupabaseAuthClient(t);

        SupabaseAuthClient.ProfileRow row = client.getProfile("access-123", "u9");

        assertNotNull(row);
        assertEquals("ijaz", row.username);
        assertEquals("Ijaz Ahmed", row.displayName);
        // The projection must keep fetching display_name from profiles.
        assertTrue(t.requests.get(0).query.get("select").contains("display_name"));
    }

    @Test
    public void updateOwnProfilePatchUsernameAndName() throws Exception {
        FakeTransport t = new FakeTransport();
        t.statusCode = 204;
        t.responseBody = "";
        SupabaseAuthClient client = new SupabaseAuthClient(t);

        client.updateOwnProfile("access-123", "g1", "newbie", "New Full",
                "New Bie", "2024-07-03T09:46:40.000Z");

        ApiRequest req = t.requests.get(0);
        assertEquals("PATCH", req.method);
        assertEquals("/rest/v1/profiles", req.path);
        assertEquals("eq.g1", req.query.get("user_id"));
        assertEquals("access-123", req.accessToken);
        JSONObject body = new JSONObject(req.jsonBody);
        assertEquals("newbie", body.getString("username"));
        assertEquals("New Full", body.getString("first_name"));
        assertEquals("New Bie", body.getString("display_name"));
        assertEquals("2024-07-03T09:46:40.000Z", body.getString("terms_accepted_at"));
    }

    @Test
    public void errorMappingCoversGoTrueCodes() {
        assertMapsTo(new FakeTransport(), 400,
                "{\"code\":400,\"error_code\":\"invalid_credentials\",\"msg\":\"Invalid login credentials\"}",
                ApiError.INVALID_CREDENTIALS);
        assertMapsTo(new FakeTransport(), 400,
                "{\"code\":400,\"error_code\":\"invalid_grant\",\"msg\":\"Invalid Refresh Token: Already Used\"}",
                ApiError.SESSION_EXPIRED_OR_REVOKED);
        assertMapsTo(new FakeTransport(), 429,
                "{\"code\":429,\"error_code\":\"over_request_rate_limit\",\"msg\":\"Too many requests\"}",
                ApiError.RATE_LIMITED);
        assertMapsTo(new FakeTransport(), 422,
                "{\"code\":422,\"error_code\":\"user_already_exists\",\"msg\":\"User already registered\"}",
                ApiError.EMAIL_ALREADY_REGISTERED);
        assertMapsTo(new FakeTransport(), 400,
                "{\"code\":400,\"error_code\":\"otp_expired\",\"msg\":\"Email token expired\"}",
                ApiError.OTP_EXPIRED);
        assertMapsTo(new FakeTransport(), 422,
                "{\"code\":422,\"error_code\":\"weak_password\",\"msg\":\"Password should be at least 8 characters.\"}",
                ApiError.VALIDATION_ERROR);
        assertMapsTo(new FakeTransport(), 409,
                "{\"code\":\"23505\",\"msg\":\"duplicate key value violates unique constraint \\\"profiles_username_key\\\"\"}",
                ApiError.USERNAME_TAKEN);
        assertMapsTo(new FakeTransport(), 409, "", ApiError.USERNAME_TAKEN);
    }

    @Test
    public void rateLimitSurfacesRetryAfterHeader() {
        FakeTransport t = new FakeTransport();
        t.statusCode = 429;
        t.responseBody = "{\"code\":429,\"error_code\":\"over_request_rate_limit\",\"msg\":\"Too many requests\"}";
        Map<String, String> headers = new HashMap<>();
        headers.put("Retry-After", "30");
        t.headers = headers;

        try {
            new SupabaseAuthClient(t).checkUsername("abc");
            fail("expected CreangerApiException");
        } catch (CreangerApiException e) {
            assertTrue(e.is(ApiError.RATE_LIMITED));
            assertNotNull(e.error);
            assertEquals(30L, e.error.retryAfterSeconds);
        } catch (Exception e) {
            fail("wrong exception type " + e);
        }
    }

    private static void assertMapsTo(FakeTransport t, int status, String body, String expectedCode) {
        try {
            t.statusCode = status;
            t.responseBody = body;
            new SupabaseAuthClient(t).checkUsername("abc");
            fail("expected CreangerApiException mapping to " + expectedCode);
        } catch (CreangerApiException e) {
            assertTrue("expected " + expectedCode + " but got " + e.error.code,
                    e.is(expectedCode));
        } catch (Exception e) {
            fail("wrong exception type " + e);
        }
    }
}
