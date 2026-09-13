package com.creanger.app.messenger.creanger;

import com.creanger.app.tgnet.ConnectionsManager;
import com.creanger.app.tgnet.RequestTimeDelegate;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class RequestTimeDelegateTest {

    @Test
    public void delegateExposesRunWithLongParam() throws Exception {
        Method run = RequestTimeDelegate.class.getMethod("run", long.class);
        assertEquals(void.class, run.getReturnType());
    }

    @Test
    public void delegateReceivesTimeValue() {
        AtomicLong seen = new AtomicLong(-1);
        RequestTimeDelegate delegate = seen::set;
        delegate.run(123456789L);
        assertEquals(123456789L, seen.get());
    }

    @Test
    public void nativeCheckProxyDeclaresDelegateParam() throws Exception {
        Method checkProxy = ConnectionsManager.class.getDeclaredMethod("native_checkProxy",
                int.class, String.class, int.class, String.class, String.class, String.class,
                RequestTimeDelegate.class);
        assertEquals(long.class, checkProxy.getReturnType());
        assertSame(RequestTimeDelegate.class, checkProxy.getParameterTypes()[6]);
    }
}
