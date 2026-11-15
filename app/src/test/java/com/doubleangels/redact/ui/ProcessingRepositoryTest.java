package com.doubleangels.redact.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import android.app.Application;
import android.net.Uri;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.SavedStateHandle;

import com.doubleangels.redact.media.AppProcessingScope;
import com.doubleangels.redact.media.MediaItem;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.List;

/**
 * The jobs' state belongs to one repository per processing scope. Each screen lends it its saved
 * state while it shows, so results land where a recreated screen will look.
 */
@RunWith(RobolectricTestRunner.class)
public class ProcessingRepositoryTest {

    @Rule
    public InstantTaskExecutorRule instantTasks = new InstantTaskExecutorRule();

    private Application app;

    @Before
    public void setUp() {
        AppProcessingScope.resetForTests();
        app = RuntimeEnvironment.getApplication();
    }

    @After
    public void tearDown() {
        AppProcessingScope.resetForTests();
    }

    private static List<MediaItem> oneItem() {
        List<MediaItem> items = new ArrayList<>();
        items.add(new MediaItem(Uri.parse("content://com.example/1"), false, "a.jpg"));
        return items;
    }

    @Test
    public void oneRepositoryPerScope_sharedByEveryScreen() {
        ProcessingRepository repository = AppProcessingScope.get(app).repository();
        assertSame(repository, AppProcessingScope.get(app).repository());

        MainViewModel first = new MainViewModel(app);
        MainViewModel second = new MainViewModel(app);
        first.setSelectedItems(oneItem());
        assertEquals(1, second.getSelectedItems().getValue().size());
    }

    @Test
    public void aClearedScreen_stopsReceivingResults_aNewerOneKeepsThem() {
        ProcessingRepository repository = AppProcessingScope.get(app).repository();
        SavedStateHandle old = new SavedStateHandle();
        SavedStateHandle current = new SavedStateHandle();
        repository.attach(old);
        repository.attach(current);

        // The old screen is cleared after the new one appeared: the new one keeps saving.
        repository.detach(old);
        repository.setSelectedItems(oneItem());
        assertEquals(1, ((List<?>) current.get(ProcessingRepository.KEY_CLEAN_URIS)).size());
        assertNull(old.get(ProcessingRepository.KEY_CLEAN_URIS));

        // Once no screen shows, results are kept in memory but saved nowhere.
        repository.detach(current);
        repository.setSelectedItems(new ArrayList<>());
        assertEquals(1, ((List<?>) current.get(ProcessingRepository.KEY_CLEAN_URIS)).size());
        assertEquals(0, repository.selectedItems().getValue().size());
    }

    @Test
    public void onlyTheFirstScreen_restoresSavedState() {
        SavedStateHandle saved = new SavedStateHandle();
        ArrayList<String> uris = new ArrayList<>();
        uris.add("content://com.example/1");
        ArrayList<String> names = new ArrayList<>();
        names.add("a.jpg");
        ArrayList<Boolean> videos = new ArrayList<>();
        videos.add(false);
        saved.set(ProcessingRepository.KEY_CLEAN_URIS, uris);
        saved.set(ProcessingRepository.KEY_CLEAN_NAMES, names);
        saved.set(ProcessingRepository.KEY_CLEAN_VIDEOS, videos);

        MainViewModel first = new MainViewModel(app, saved);
        assertEquals(1, first.getSelectedItems().getValue().size());
        first.setSelectedItems(new ArrayList<>());

        // A later screen in the same process must not bring back older saved items.
        MainViewModel later = new MainViewModel(app, saved);
        assertEquals(0, later.getSelectedItems().getValue().size());
    }
}
