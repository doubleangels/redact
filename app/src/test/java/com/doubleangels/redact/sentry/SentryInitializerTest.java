package com.doubleangels.redact.sentry;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.doubleangels.redact.AppPreferences;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.concurrent.atomic.AtomicReference;

import io.sentry.Breadcrumb;
import io.sentry.Sentry;
import io.sentry.SentryEvent;
import io.sentry.android.core.SentryAndroid;
import io.sentry.android.core.SentryAndroidOptions;

@RunWith(RobolectricTestRunner.class)
public class SentryInitializerTest {

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        SentryManager.init(context);
        AppPreferences.setCrashReportingEnabled(context, false);
    }

    @Test
    public void initialize_configuresOptionsAndCallbacks() {
        AtomicReference<SentryAndroidOptions> optionsRef = new AtomicReference<>();

        try (var threadMock = mockConstruction(Thread.class, (mock, ctx) -> {
                    Runnable runnable = (Runnable) ctx.arguments().get(0);
                    doAnswer(inv -> {
                        runnable.run();
                        return null;
                    }).when(mock).start();
                });
                var mocked = mockStatic(SentryAndroid.class)) {

            mocked.when(() -> SentryAndroid.init(any(Context.class), any(Sentry.OptionsConfiguration.class)))
                    .thenAnswer(invocation -> {
                        @SuppressWarnings("unchecked")
                        Sentry.OptionsConfiguration<SentryAndroidOptions> config = invocation.getArgument(1);
                        SentryAndroidOptions options = new SentryAndroidOptions();
                        config.configure(options);
                        optionsRef.set(options);
                        return null;
                    });

            SentryInitializer.initialize(context);
        }

        SentryAndroidOptions options = optionsRef.get();
        assertNotNull(options);

        Breadcrumb breadcrumb = new Breadcrumb();
        breadcrumb.setMessage("user@example.com");
        assertNotNull(options.getBeforeBreadcrumb().execute(breadcrumb, null));

        SentryEvent httpClientEvent = new SentryEvent();
        httpClientEvent.setThrowable(new SentryHttpClientException());
        assertNull(options.getBeforeSend().execute(httpClientEvent, null));

        AppPreferences.setCrashReportingEnabled(context, false);
        SentryEvent disabledEvent = new SentryEvent();
        disabledEvent.setThrowable(new RuntimeException("x"));
        assertNull(options.getBeforeSend().execute(disabledEvent, null));

        AppPreferences.setCrashReportingEnabled(context, true);
        SentryEvent enabledEvent = new SentryEvent();
        enabledEvent.setThrowable(new RuntimeException("x"));
        assertNotNull(options.getBeforeSend().execute(enabledEvent, null));

        SentryEvent messageOnlyEvent = new SentryEvent();
        messageOnlyEvent.setMessage(new io.sentry.protocol.Message());
        assertNotNull(options.getBeforeSend().execute(messageOnlyEvent, null));

        assertTrue(options.isAttachThreads());
        assertTrue(options.isAnrEnabled());
    }

    @Test
    public void initialize_noDsn_returnsEarly() {
        AtomicReference<SentryAndroidOptions> optionsRef = new AtomicReference<>();
        SentryInitializer.testDsnOverride = "";

        try (var threadMock = mockConstruction(Thread.class, (mock, ctx) -> {
                    Runnable runnable = (Runnable) ctx.arguments().get(0);
                    doAnswer(inv -> {
                        runnable.run();
                        return null;
                    }).when(mock).start();
                });
                var mocked = mockStatic(SentryAndroid.class)) {

            mocked.when(() -> SentryAndroid.init(any(Context.class), any(Sentry.OptionsConfiguration.class)))
                    .thenAnswer(invocation -> {
                        @SuppressWarnings("unchecked")
                        Sentry.OptionsConfiguration<SentryAndroidOptions> config = invocation.getArgument(1);
                        SentryAndroidOptions options = new SentryAndroidOptions();
                        config.configure(options);
                        optionsRef.set(options);
                        return null;
                    });

            SentryInitializer.initialize(context);
        } finally {
            SentryInitializer.testDsnOverride = null;
        }

        SentryAndroidOptions options = optionsRef.get();
        assertNotNull(options);
        assertTrue(options.getDsn() == null || options.getDsn().isEmpty());
    }

    /** Simple name matches the filter in {@link SentryInitializer}. */
    private static final class SentryHttpClientException extends RuntimeException {
    }
}
