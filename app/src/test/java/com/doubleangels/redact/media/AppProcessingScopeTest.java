package com.doubleangels.redact.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class AppProcessingScopeTest {

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        AppProcessingScope.resetForTests();
    }

    @After
    public void tearDown() {
        shutdown(AppProcessingScope.get(context));
        AppProcessingScope.resetForTests();
    }

    private static void shutdown(AppProcessingScope scope) {
        scope.mediaProcessor().shutdown();
        scope.convertExecutor().shutdownNow();
    }

    @Test
    public void get_returnsSingleton() {
        AppProcessingScope first = AppProcessingScope.get(context);
        assertSame(first, AppProcessingScope.get(context));
        assertSame(first.mediaProcessor(), AppProcessingScope.get(context).mediaProcessor());
    }

    @Test
    public void resetForTests_producesFreshInstance() {
        AppProcessingScope first = AppProcessingScope.get(context);
        shutdown(first);
        AppProcessingScope.resetForTests();
        assertNotSame(first, AppProcessingScope.get(context));
    }

    @Test
    public void freshScope_isIdle() {
        AppProcessingScope scope = AppProcessingScope.get(context);
        assertFalse(scope.isCleanBusy());
        assertFalse(scope.isConvertBusy());
        assertEquals(0, scope.cleanGeneration().get());
        assertEquals(0, scope.convertGeneration().get());
    }

    @Test
    public void isConvertBusy_tracksInProgressFlag() {
        AppProcessingScope scope = AppProcessingScope.get(context);
        scope.convertInProgress().set(true);
        assertTrue(scope.isConvertBusy());
        scope.convertInProgress().set(false);
        assertFalse(scope.isConvertBusy());
    }

    @Test
    public void convertExecutor_runsOnNamedThread() throws Exception {
        String name = AppProcessingScope.get(context).convertExecutor()
                .submit(() -> Thread.currentThread().getName())
                .get();
        assertEquals("redact-convert", name);
    }
}
