package com.doubleangels.redact.metadata;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import androidx.exifinterface.media.ExifInterface;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The EXIF tag list cleaning and verification walk. A build that lost the {@code TAG_*} fields
 * must fail loudly, not hand back a short list that removes and checks nothing.
 */
@RunWith(RobolectricTestRunner.class)
public class ExifTagsTest {

    @After
    public void tearDown() {
        ExifTags.testFields = null;
    }

    @Test
    public void all_listsEveryTagOnce_andIsBuiltOnce() {
        List<String> tags = ExifTags.all();
        assertTrue("only " + tags.size() + " tags", tags.size() >= ExifTags.MIN_TAGS);
        assertTrue(tags.contains(ExifInterface.TAG_GPS_LATITUDE));
        assertTrue(tags.contains(ExifInterface.TAG_MAKE));
        assertTrue(tags.contains(ExifInterface.TAG_DATETIME_ORIGINAL));
        assertTrue(tags.contains(ExifInterface.TAG_BODY_SERIAL_NUMBER));
        assertSame(tags, ExifTags.all());
    }

    @Test
    public void noFields_throws() {
        assertThrows(IllegalStateException.class, () -> ExifTags.fromFields(new Field[0]));
    }

    /** Most fields there, but not the ones every version has: still a broken build. */
    @Test
    public void fieldsWithoutTheCoreTags_throw() {
        List<Field> fields = new ArrayList<>();
        for (Field field : ExifInterface.class.getDeclaredFields()) {
            String name = field.getName();
            if (!name.equals("TAG_GPS_LATITUDE") && !name.equals("TAG_MAKE")) {
                fields.add(field);
            }
        }
        assertThrows(IllegalStateException.class,
                () -> ExifTags.fromFields(fields.toArray(new Field[0])));
    }

    /** The core tags, but far fewer than ExifInterface defines. */
    @Test
    public void tooFewFields_throw() {
        List<Field> fields = new ArrayList<>();
        for (Field field : ExifInterface.class.getDeclaredFields()) {
            if (Arrays.asList("TAG_GPS_LATITUDE", "TAG_MAKE", "TAG_DATETIME_ORIGINAL")
                    .contains(field.getName())) {
                fields.add(field);
            }
        }
        assertThrows(IllegalStateException.class,
                () -> ExifTags.fromFields(fields.toArray(new Field[0])));
    }

    @Test
    public void aBrokenSource_makesAllThrow_everyTime() {
        ExifTags.testFields = new Field[0];
        assertThrows(IllegalStateException.class, ExifTags::all);
        assertThrows(IllegalStateException.class, ExifTags::all);
        ExifTags.testFields = null;
        assertTrue(ExifTags.all().size() >= ExifTags.MIN_TAGS);
    }
}
