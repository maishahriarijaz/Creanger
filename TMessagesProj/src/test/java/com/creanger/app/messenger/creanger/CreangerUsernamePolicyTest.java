package com.creanger.app.messenger.creanger;

import org.junit.Test;
import com.creanger.app.messenger.creanger.CreangerUsernamePolicy.Result;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CreangerUsernamePolicyTest {

    private static int code(String raw) {
        return CreangerUsernamePolicy.validateNormalized(raw).code;
    }

    @Test
    public void acceptsValidUsernames() {
        assertEquals(Result.OK, code("abc"));
        assertEquals(Result.OK, code("user.name"));
        assertEquals(Result.OK, code("user_name"));
        assertEquals(Result.OK, code("u2.x_y"));
        assertEquals(Result.OK, CreangerUsernamePolicy.validate(
                repeat('a', CreangerUsernamePolicy.MAX_LENGTH)).code);
    }

    @Test
    public void rejectsEmptyAndShort() {
        assertEquals(Result.EMPTY, code(null));
        assertEquals(Result.EMPTY, code(""));
        assertEquals(Result.TOO_SHORT, code("ab"));
    }

    @Test
    public void rejectsTooLong() {
        assertEquals(Result.TOO_LONG, CreangerUsernamePolicy.validate(
                repeat('a', CreangerUsernamePolicy.MAX_LENGTH + 1)).code);
    }

    @Test
    public void rejectsSpacesAndUnsupportedCharacters() {
        assertEquals(Result.INVALID_CHARACTERS, CreangerUsernamePolicy.validate("has space").code);
        assertEquals(Result.INVALID_CHARACTERS, CreangerUsernamePolicy.validate("dash-in").code);
        assertEquals(Result.INVALID_CHARACTERS, CreangerUsernamePolicy.validate("café").code);
    }

    @Test
    public void rejectsBadPeriodPlacement() {
        assertEquals(Result.BAD_PERIOD_PLACEMENT, code(".start"));
        assertEquals(Result.BAD_PERIOD_PLACEMENT, code("end."));
        assertEquals(Result.BAD_PERIOD_PLACEMENT, code("two..dots"));
    }

    @Test
    public void normalizesCaseAndStripsUnsupported() {
        assertEquals("john.doe_1", CreangerUsernamePolicy.normalize("John.Doe 1"));
        assertEquals("abc", CreangerUsernamePolicy.normalize("ABC"));
        assertEquals("ab", CreangerUsernamePolicy.normalize("a!b"));
        assertEquals("", CreangerUsernamePolicy.normalize("---"));
        assertEquals("", CreangerUsernamePolicy.normalize(null));
    }

    @Test
    public void normalizationIsIdempotent() {
        String once = CreangerUsernamePolicy.normalize("Mi X..Y ");
        String twice = CreangerUsernamePolicy.normalize(once);
        assertEquals(once, twice);
    }

    @Test
    public void normalizedInputPassesValidationWhenRawWasValid() {
        Result r = CreangerUsernamePolicy.validateNormalized("John.Doe");
        assertTrue(r.ok());
        assertFalse(CreangerUsernamePolicy.validateNormalized("..").ok());
    }

    @Test
    public void caseVariantsNormalizeToOneCanonicalUsername() {
        // Matches the DB contract: check_username_available() LOWER()s before
        // validating and the 038 bridge stores LOWER(username), so Alice ==
        // alice == ALICE everywhere and case can never fork identities.
        assertEquals("alice", CreangerUsernamePolicy.normalize("Alice"));
        assertEquals("alice", CreangerUsernamePolicy.normalize("alice"));
        assertEquals("alice", CreangerUsernamePolicy.normalize("ALICE"));
        assertEquals("alice01", CreangerUsernamePolicy.normalize("Alice01"));
        assertTrue(CreangerUsernamePolicy.validateNormalized("Alice").ok());
        assertTrue(CreangerUsernamePolicy.validateNormalized("ALICE").ok());
    }

    @Test
    public void resultOkFlagMatchesCode() {
        assertTrue(new Result(Result.OK).ok());
        assertFalse(new Result(Result.TOO_SHORT).ok());
    }

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
