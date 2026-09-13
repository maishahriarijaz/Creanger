package com.creanger.app.messenger.creanger;

import com.creanger.app.messenger.creanger.auth.GoogleSignInErrorMapper;
import com.creanger.app.messenger.creanger.auth.GoogleSignInErrorMapper.Outcome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class GoogleSignInErrorMapperTest {

    @Test
    public void cancelledIsSilent() {
        assertEquals(Outcome.CANCELLED, GoogleSignInErrorMapper.mapStatusCode(12501));
    }

    @Test
    public void networkErrorMapsToNetwork() {
        assertEquals(Outcome.NETWORK_ERROR, GoogleSignInErrorMapper.mapStatusCode(7));
    }

    @Test
    public void developerErrorMapsToConfig() {
        assertEquals(Outcome.CONFIG_ERROR, GoogleSignInErrorMapper.mapStatusCode(10));
    }

    @Test
    public void signInFailedMapsToConfig() {
        assertEquals(Outcome.CONFIG_ERROR, GoogleSignInErrorMapper.mapStatusCode(12500));
    }

    @Test
    public void unknownCodeMapsToGenericFailure() {
        assertEquals(Outcome.SIGN_IN_FAILED, GoogleSignInErrorMapper.mapStatusCode(8));
        assertEquals(Outcome.SIGN_IN_FAILED, GoogleSignInErrorMapper.mapStatusCode(0));
    }
}
