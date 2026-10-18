package com.doubleangels.redact.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.doubleangels.redact.media.AppProcessingScope;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class MainViewModelTest {

    @Rule
    public InstantTaskExecutorRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    @After
    public void tearDown() {
        AppProcessingScope.resetForTests();
    }

    @Test
    public void clearingOneTab_keepsTheReadGrantOfAFileTheOtherTabStillHas() {
        Application app = RuntimeEnvironment.getApplication();
        android.net.Uri shared = android.net.Uri.parse("content://com.example.media/photo/1");
        android.net.Uri cleanOnly = android.net.Uri.parse("content://com.example.media/photo/2");
        for (android.net.Uri uri : java.util.Arrays.asList(shared, cleanOnly)) {
            app.getContentResolver().takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        MainViewModel viewModel = new MainViewModel(app);
        viewModel.setSelectedItems(java.util.Arrays.asList(
                new com.doubleangels.redact.media.MediaItem(shared, false, "a.jpg"),
                new com.doubleangels.redact.media.MediaItem(cleanOnly, false, "b.jpg")));
        viewModel.setConvertSelectedItems(java.util.Collections.singletonList(
                new com.doubleangels.redact.media.MediaItem(shared, false, "a.jpg")));

        viewModel.clearSelectedItems();

        java.util.Set<android.net.Uri> granted = new java.util.HashSet<>();
        for (android.content.UriPermission p : app.getContentResolver().getPersistedUriPermissions()) {
            granted.add(p.getUri());
        }
        assertTrue(granted.contains(shared));
        assertTrue(!granted.contains(cleanOnly));
    }

    @Test
    public void cancelCleaning_immediatelySetsCancelledState() {
        Application app = RuntimeEnvironment.getApplication();
        MainViewModel viewModel = new MainViewModel(app);
        viewModel.setCleanProcessingState(MainViewModel.ProcessingState.PROCESSING);

        viewModel.cancelCleaning();

        assertEquals(
                MainViewModel.ProcessingState.CANCELLED,
                viewModel.getCleanProcessingState().getValue());
    }

    @Test
    public void cancelCleaning_invalidatesRunGeneration_soStaleOnCancelledWouldBeIgnored()
            throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        MainViewModel viewModel = new MainViewModel(app);
        viewModel.setCleanProcessingState(MainViewModel.ProcessingState.PROCESSING);

        AtomicInteger cleanGeneration = getCleanGeneration(viewModel);
        int runGeneration = cleanGeneration.incrementAndGet();

        viewModel.cancelCleaning();

        assertNotEquals(runGeneration, cleanGeneration.get());
        assertTrue(runGeneration != cleanGeneration.get());
        assertEquals(
                MainViewModel.ProcessingState.CANCELLED,
                viewModel.getCleanProcessingState().getValue());
    }

    private static AtomicInteger getCleanGeneration(MainViewModel viewModel) throws Exception {
        Field field = MainViewModel.class.getDeclaredField("cleanGeneration");
        field.setAccessible(true);
        return (AtomicInteger) field.get(viewModel);
    }
}
