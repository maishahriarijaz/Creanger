package com.creanger.app.messenger.creanger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import com.creanger.app.messenger.creanger.model.AuthModels.AuthSession;
import com.creanger.app.messenger.creanger.model.AuthModels.CreangerUser;
import com.creanger.app.messenger.creanger.storage.KeystoreTokenStore;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Account-scoping tests for {@link KeystoreTokenStore}.
 *
 * The store keeps exactly one ACTIVE account slot (the app's single auth
 * session). These tests pin down that the slot is bound to an account
 * identity: account switches must go through logout (clear), a second account
 * can never silently overwrite or reuse the first account's refresh token or
 * cached profile, and the access token is never durably stored at all.
 */
public class KeystoreTokenStoreTest {

    // ---- helpers ----

    private static CreangerUser user(String id, String username, String email) {
        return new CreangerUser(id, username, email, true, null, null, null);
    }

    private static AuthSession session(String accessToken, String refreshToken, CreangerUser user) {
        return new AuthSession(accessToken, refreshToken, user, System.currentTimeMillis());
    }

    private static final class FakeSeal implements KeystoreTokenStore.Seal {
        @Override
        public byte[] encrypt(String plaintext, byte[] iv) {
            byte[] pt = plaintext.getBytes(StandardCharsets.UTF_8);
            byte[] out = new byte[iv.length + pt.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(pt, 0, out, iv.length, pt.length);
            return out;
        }

        @Override
        public byte[] decrypt(byte[] sealed, byte[] iv) {
            return Arrays.copyOfRange(sealed, iv.length, sealed.length);
        }
    }

    private static final class FakeKvs implements KeystoreTokenStore.Kvs {
        private final Map<String, Object> data = new HashMap<>();

        @Override
        public byte[] getBytes(String key) {
            Object value = data.get(key);
            return value instanceof byte[] ? (byte[]) value : null;
        }

        @Override
        public void putBytes(String key, byte[] value) {
            data.put(key, value);
        }

        @Override
        public String getString(String key) {
            Object value = data.get(key);
            return value instanceof String ? (String) value : null;
        }

        @Override
        public void putString(String key, String value) {
            data.put(key, value);
        }

        @Override
        public void remove(String key) {
            data.remove(key);
        }

        @Override
        public void clearAll() {
            data.clear();
        }

        boolean isEmpty() {
            return data.isEmpty();
        }

        boolean containsPlainString(String needle) {
            for (Object value : data.values()) {
                if (value instanceof String && ((String) value).contains(needle)) {
                    return true;
                }
            }
            return false;
        }

        boolean containsAnyBytes(String needle) {
            byte[] n = needle.getBytes(StandardCharsets.UTF_8);
            for (Object value : data.values()) {
                if (value instanceof byte[] && containsBytes((byte[]) value, n)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static boolean containsBytes(byte[] haystack, byte[] needle) {
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            boolean match = true;
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }

    // ---- tests ----

    @Test
    public void firstAccountRestoresRefreshTokenAndProfile() {
        FakeKvs kv = new FakeKvs();
        KeystoreTokenStore store = new KeystoreTokenStore(new FakeSeal(), kv);
        CreangerUser alice = user("uuid-A", "alice", "a@x.com");

        store.store(session("acc-A", "ref-A", alice));

        AuthSession restored = store.load();
        assertNotNull(restored);
        assertEquals("ref-A", restored.refreshToken);
        assertNotNull(restored.user);
        assertEquals("uuid-A", restored.user.id);
        assertEquals("a@x.com", restored.user.email);
        // Access token deliberately absent after load (memory-only restore).
        assertEquals("", restored.accessToken);
    }

    @Test
    public void secondAccountCannotOverwriteOwnedSlot() {
        FakeKvs kv = new FakeKvs();
        KeystoreTokenStore store = new KeystoreTokenStore(new FakeSeal(), kv);
        store.store(session("acc-A", "ref-A", user("uuid-A", "alice", "a@x.com")));

        // Account B attempts a write over an owned (account A) slot.
        store.store(session("acc-B", "ref-B", user("uuid-B", "bob", "b@x.com")));

        // The write is refused: A is still what loads...
        AuthSession restored = store.load();
        assertNotNull(restored);
        assertEquals("ref-A", restored.refreshToken);
        assertEquals("uuid-A", restored.user.id);
        // ...and no trace of B reached the durable store.
        assertFalse(kv.containsPlainString("b@x.com"));
        assertFalse(kv.containsPlainString("bob"));
        assertFalse(kv.containsAnyBytes("ref-B"));
    }

    @Test
    public void accountSwitchRequiresLogoutBeforeLogin() {
        FakeKvs kv = new FakeKvs();
        KeystoreTokenStore store = new KeystoreTokenStore(new FakeSeal(), kv);
        store.store(session("acc-A", "ref-A", user("uuid-A", "alice", "a@x.com")));

        // Logout clears the slot...
        store.clear();
        assertNull(store.load());

        // ...then account B logs in and owns the slot.
        store.store(session("acc-B", "ref-B", user("uuid-B", "bob", "b@x.com")));
        AuthSession restored = store.load();
        assertNotNull(restored);
        assertEquals("ref-B", restored.refreshToken);
        assertEquals("uuid-B", restored.user.id);
    }

    @Test
    public void logoutClearsAllPersistedState() {
        FakeKvs kv = new FakeKvs();
        KeystoreTokenStore store = new KeystoreTokenStore(new FakeSeal(), kv);
        store.store(session("acc-A", "ref-A", user("uuid-A", "alice", "a@x.com")));

        store.clear();

        assertNull(store.load());
        assertTrue("no token/profile/label may survive logout", kv.isEmpty());
    }

    @Test
    public void logoutThenLoginAgainSameAccount() {
        FakeKvs kv = new FakeKvs();
        KeystoreTokenStore store = new KeystoreTokenStore(new FakeSeal(), kv);
        store.store(session("acc-A", "refA1", user("uuid-A", "alice", "a@x.com")));
        store.clear();

        store.store(session("acc-A", "refA2", user("uuid-A", "alice", "a@x.com")));

        AuthSession restored = store.load();
        assertNotNull(restored);
        assertEquals("refA2", restored.refreshToken);
        // The OLD rotated-out token of the same account is not resurrected.
        assertFalse(kv.containsAnyBytes("refA1"));
        assertFalse(kv.containsPlainString("refA2"));
        assertEquals("uuid-A", restored.user.id);
    }

    @Test
    public void appRestartRestoresPersistedSession() {
        FakeKvs kv = new FakeKvs();
        FakeSeal seal = new FakeSeal();
        CreangerUser alice = user("uuid-A", "alice", "a@x.com");

        // Session persisted by the "first" process.
        new KeystoreTokenStore(seal, kv).store(session("acc-A", "ref-A", alice));

        // "App restart": a brand-new store instance over the same durable
        // backend. The persisted refresh token + profile come back.
        AuthSession restored = new KeystoreTokenStore(seal, kv).load();
        assertNotNull(restored);
        assertEquals("ref-A", restored.refreshToken);
        assertNotNull(restored.user);
        assertEquals("uuid-A", restored.user.id);
    }

    @Test
    public void tokenReplacementAfterRefreshKeepsOwner() {
        FakeKvs kv = new FakeKvs();
        KeystoreTokenStore store = new KeystoreTokenStore(new FakeSeal(), kv);
        CreangerUser alice = user("uuid-A", "alice", "a@x.com");

        store.store(session("acc-A", "ref-A", alice));
        // Rotation for the SAME account rewrites the slot at will.
        store.store(session("acc-A", "ref-A-rotated", alice));

        AuthSession restored = store.load();
        assertNotNull(restored);
        assertEquals("ref-A-rotated", restored.refreshToken);
        assertEquals("uuid-A", restored.user.id);
    }

    @Test
    public void refreshAfterRestartReplacesTokenKeepsOwner() {
        FakeKvs kv = new FakeKvs();
        FakeSeal seal = new FakeSeal();
        CreangerUser alice = user("uuid-A", "alice", "a@x.com");

        new KeystoreTokenStore(seal, kv).store(session("acc-A", "ref-A", alice));
        // Restart, then rotate for the same account.
        new KeystoreTokenStore(seal, kv).store(session("acc-A", "ref-A-rotated", alice));

        AuthSession restored = new KeystoreTokenStore(seal, kv).load();
        assertNotNull(restored);
        assertEquals("ref-A-rotated", restored.refreshToken);
        assertEquals("uuid-A", restored.user.id);
        assertFalse(kv.containsAnyBytes("acc-A"));
    }

    @Test
    public void noCrossAccountProfileOrTokenLeakage() {
        FakeKvs kv = new FakeKvs();
        KeystoreTokenStore store = new KeystoreTokenStore(new FakeSeal(), kv);
        store.store(session("acc-A", "ref-A", user("uuid-A", "alice", "a@x.com")));
        store.clear();
        // Account B now owns the slot after a proper logout/login switch.
        store.store(session("acc-B", "ref-B", user("uuid-B", "bob", "b@x.com")));

        // Everything durable belongs to B...
        assertTrue("B's refresh token present", kv.containsAnyBytes("ref-B"));
        assertTrue("B's profile present", kv.containsPlainString("uuid-B"));
        assertTrue("B's email present", kv.containsPlainString("b@x.com"));
        // ...and nothing of A survives in ANY form (plain strings or bytes).
        assertFalse("A token gone", kv.containsAnyBytes("ref-A"));
        assertFalse("A profile gone", kv.containsPlainString("uuid-A"));
        assertFalse("A email gone", kv.containsPlainString("a@x.com"));
        assertFalse("A username gone", kv.containsPlainString("alice"));

        AuthSession restored = store.load();
        assertNotNull(restored);
        assertEquals("ref-B", restored.refreshToken);
        assertEquals("uuid-B", restored.user.id);
    }

    @Test
    public void accessTokenIsNeverPersisted() {
        FakeKvs kv = new FakeKvs();
        KeystoreTokenStore store = new KeystoreTokenStore(new FakeSeal(), kv);
        store.store(session("acc-A-Secret", "ref-A", user("uuid-A", "alice", "a@x.com")));

        // The access token is memory-only: absent from both plain values and
        // the (encrypted/fake-sealed) byte payloads.
        assertFalse(kv.containsPlainString("acc-A-Secret"));
        assertFalse(kv.containsAnyBytes("acc-A-Secret"));
        // The refresh token, by contrast, is durably kept (sealed only).
        assertTrue(kv.containsAnyBytes("ref-A"));
        assertFalse("refresh token never stored in plaintext", kv.containsPlainString("ref-A"));
    }

    @Test
    public void userLessSessionDropsCachedProfile() {
        FakeKvs kv = new FakeKvs();
        KeystoreTokenStore store = new KeystoreTokenStore(new FakeSeal(), kv);
        store.store(session("acc-A", "ref-A", user("uuid-A", "alice", "a@x.com")));

        // A session without a resolvable profile must not keep account A's
        // cached user_json alive.
        store.store(session("acc-B", "ref-B", null));

        AuthSession restored = store.load();
        assertNotNull(restored);
        assertEquals("ref-B", restored.refreshToken);
        assertNull("cached previous profile cleared", restored.user);
        assertFalse(kv.containsPlainString("a@x.com"));
    }
}