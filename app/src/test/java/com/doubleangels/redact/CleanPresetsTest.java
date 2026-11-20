package com.doubleangels.redact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

@RunWith(RobolectricTestRunner.class)
public class CleanPresetsTest {

    private Application app;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        prefs().edit().clear().commit();
    }

    private SharedPreferences prefs() {
        return app.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE);
    }

    @Test
    public void freshInstall_andResetAll_areStandard() {
        assertEquals(CleanPreset.STANDARD, CleanPresets.current(app));
        CleanPresets.apply(app, CleanPreset.KEEPING_FOR_MYSELF, true);
        AppPreferences.resetAll(app);
        assertEquals(CleanPreset.STANDARD, CleanPresets.current(app));
    }

    @Test
    public void applyingEachPreset_makesItCurrent() {
        for (CleanPreset preset : CleanPreset.choices()) {
            assertTrue(CleanPresets.apply(app, preset, true));
            assertEquals(preset, CleanPresets.current(app));
        }
    }

    @Test
    public void eachPreset_writesTheDocumentedOptions() {
        CleanPresets.apply(app, CleanPreset.STANDARD, false);
        assertTrue(AppPreferences.isStrictClean(app));
        assertKeeps(false, false, false, false);

        CleanPresets.apply(app, CleanPreset.SENDING_TO_PEOPLE, false);
        assertFalse(AppPreferences.isStrictClean(app));
        assertKeeps(false, true, false, false);
        assertTrue(AppPreferences.isDateOnly(app));

        CleanPresets.apply(app, CleanPreset.KEEPING_FOR_MYSELF, true);
        assertFalse(AppPreferences.isStrictClean(app));
        assertKeeps(true, true, true, true);
        assertTrue(AppPreferences.isApproximateLocation(app));
        assertFalse(AppPreferences.isDateOnly(app));

        // Back to Standard: Strict Clean turns every keep option off, as its switch does.
        CleanPresets.apply(app, CleanPreset.STANDARD, false);
        assertKeeps(false, false, false, false);
    }

    private void assertKeeps(boolean location, boolean date, boolean camera, boolean motion) {
        assertEquals(location, AppPreferences.isPreserveLocation(app));
        assertEquals(date, AppPreferences.isPreserveDateTaken(app));
        assertEquals(camera, AppPreferences.isPreserveCameraSettings(app));
        assertEquals(motion, AppPreferences.isPreserveMotionPhoto(app));
    }

    @Test
    public void presets_leaveRemoveAudioAndFileNamesAlone() {
        AppPreferences.setRemoveAudio(app, true);
        AppPreferences.setKeepOriginalFileNames(app, true);
        for (CleanPreset preset : CleanPreset.choices()) {
            CleanPresets.apply(app, preset, true);
            assertTrue(AppPreferences.isRemoveAudio(app));
            assertTrue(AppPreferences.isKeepOriginalFileNames(app));
        }
    }

    @Test
    public void changingAnySingleOption_afterAPreset_isCustom() {
        List<BiConsumer<Context, Boolean>> setters = Arrays.asList(
                AppPreferences::setPreserveLocation,
                AppPreferences::setApproximateLocation,
                AppPreferences::setPreserveDateTaken,
                AppPreferences::setDateOnly,
                AppPreferences::setPreserveCameraSettings,
                AppPreferences::setPreserveMotionPhoto);
        // Keeping for Myself's value for each option above.
        boolean[] keeping = {true, true, true, false, true, true};
        for (int i = 0; i < setters.size(); i++) {
            CleanPresets.apply(app, CleanPreset.KEEPING_FOR_MYSELF, true);
            setters.get(i).accept(app, !keeping[i]);
            assertEquals("option " + i, CleanPreset.CUSTOM, CleanPresets.current(app));
        }
        CleanPresets.apply(app, CleanPreset.SENDING_TO_PEOPLE, false);
        AppPreferences.setDateOnly(app, false);
        assertEquals(CleanPreset.CUSTOM, CleanPresets.current(app));
        CleanPresets.apply(app, CleanPreset.STANDARD, false);
        AppPreferences.setStrictClean(app, false);
        assertEquals("Strict Clean off with nothing kept matches no preset",
                CleanPreset.CUSTOM, CleanPresets.current(app));
    }

    @Test
    public void subOptions_onlyCountWhileTheirOptionIsKept() {
        CleanPresets.apply(app, CleanPreset.SENDING_TO_PEOPLE, false);
        AppPreferences.setApproximateLocation(app, false);
        assertEquals("Approximate Location is moot while location isn't kept",
                CleanPreset.SENDING_TO_PEOPLE, CleanPresets.current(app));
        CleanPresets.apply(app, CleanPreset.STANDARD, false);
        AppPreferences.setDateOnly(app, true);
        assertEquals("Strict Clean ignores every keep option",
                CleanPreset.STANDARD, CleanPresets.current(app));
    }

    @Test
    public void keepingLocation_needsConfirmation() {
        CleanPresets.apply(app, CleanPreset.SENDING_TO_PEOPLE, false);
        assertFalse(CleanPresets.apply(app, CleanPreset.KEEPING_FOR_MYSELF, false));
        assertFalse(AppPreferences.isPreserveLocation(app));
        assertEquals(CleanPreset.SENDING_TO_PEOPLE, CleanPresets.current(app));
        assertTrue(CleanPreset.KEEPING_FOR_MYSELF.keepsLocation());
        assertFalse(CleanPreset.STANDARD.keepsLocation());
        assertFalse(CleanPreset.SENDING_TO_PEOPLE.keepsLocation());
    }

    @Test
    public void custom_cannotBeApplied() {
        assertThrows(IllegalArgumentException.class,
                () -> CleanPresets.apply(app, CleanPreset.CUSTOM, true));
        assertFalse(CleanPresets.matches(app, CleanPreset.CUSTOM));
    }

    @Test
    public void apply_isOneChange() {
        List<String> changed = new ArrayList<>();
        SharedPreferences.OnSharedPreferenceChangeListener listener = (p, key) -> changed.add(key);
        prefs().registerOnSharedPreferenceChangeListener(listener);
        try {
            CleanPresets.apply(app, CleanPreset.KEEPING_FOR_MYSELF, true);
        } finally {
            prefs().unregisterOnSharedPreferenceChangeListener(listener);
        }
        // One edit notifies once per key it wrote; every key it wrote is a Clean option.
        Set<String> keys = new HashSet<>(Arrays.asList("strict_clean", "preserve_location",
                "approximate_location", "preserve_date_taken", "date_only",
                "preserve_camera_settings", "preserve_motion_photo"));
        assertEquals(keys, new HashSet<>(changed));
        assertEquals(keys.size(), changed.size());
    }

    @Test
    public void tagValues_areFixedAndDistinct() {
        Set<String> values = new HashSet<>();
        for (CleanPreset preset : CleanPreset.values()) {
            assertTrue(preset.tagValue.matches("[a-z_]+"));
            assertTrue(values.add(preset.tagValue));
        }
        assertEquals(new HashSet<>(Arrays.asList("standard", "sending_to_people",
                "keeping_for_myself", "custom")), values);
    }

    /**
     * Every Clean option a preset could set is either a field of {@link CleanPreset} or listed
     * here as left alone, so a new option can't be forgotten by the presets.
     */
    @Test
    public void everyCleanOption_isSetByPresetsOrDeliberatelyLeftAlone() throws Exception {
        Set<String> presetFields = new HashSet<>();
        for (Field f : CleanPreset.class.getDeclaredFields()) {
            if (!f.isEnumConstant() && !Modifier.isStatic(f.getModifiers())
                    && (f.getType() == boolean.class || f.getType() == Boolean.class)) {
                presetFields.add(f.getName());
            }
        }
        assertEquals(new HashSet<>(Arrays.asList("strictClean", "keepLocation",
                "approximateLocation", "keepDateTaken", "dateOnly", "keepCameraSettings",
                "keepMotionPhotos")), presetFields);

        Set<String> setByPresets = new HashSet<>(Arrays.asList("KEY_STRICT_CLEAN",
                "KEY_PRESERVE_LOCATION", "KEY_APPROXIMATE_LOCATION", "KEY_PRESERVE_DATE_TAKEN",
                "KEY_DATE_ONLY", "KEY_PRESERVE_CAMERA_SETTINGS", "KEY_PRESERVE_MOTION_PHOTO"));
        Set<String> leftAlone = new HashSet<>(Arrays.asList("KEY_REMOVE_AUDIO",
                "KEY_KEEP_ORIGINAL_NAMES"));
        // The keys of what a clean keeps: strict_clean, preserve_*, approximate_location,
        // date_only, remove_audio, keep_original_file_names.
        for (Field f : AppPreferences.class.getDeclaredFields()) {
            if (!f.getName().startsWith("KEY_")) {
                continue;
            }
            f.setAccessible(true);
            Object value = f.get(null);
            assertNotNull(value);
            String key = value.toString();
            boolean cleanOption = key.equals("strict_clean") || key.startsWith("preserve_")
                    || key.equals("approximate_location") || key.equals("date_only")
                    || key.equals("remove_audio") || key.equals("keep_original_file_names");
            if (cleanOption) {
                assertTrue(f.getName() + " needs a value in every preset or to be left alone",
                        setByPresets.contains(f.getName()) || leftAlone.contains(f.getName()));
            }
        }
    }
}
