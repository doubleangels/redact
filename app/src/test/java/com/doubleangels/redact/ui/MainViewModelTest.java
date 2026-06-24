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
