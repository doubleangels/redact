package com.doubleangels.redact.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/** Reading an EXIF block's tags by number, with every offset bounded. */
public class ExifBlockTest {

    private static final byte[] PLAIN_JPEG = {
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xDA, 0, 2, (byte) 0xFF, (byte) 0xD9};

    @Test
    public void readsAnXpTag_thatExifInterfaceHasNoNameFor() throws IOException {
        byte[] tiff = ExifFixtures.tiffWith(ExifBlock.TAG_XP_AUTHOR, ExifFixtures.xpText("Jane Doe"));
        byte[] block = ExifBlock.find(new ByteArrayInputStream(ExifFixtures.withExif(PLAIN_JPEG, tiff)));

        List<ExifBlock.Entry> entries = ExifBlock.read(block);

        assertEquals(1, entries.size());
        ExifBlock.Entry author = entries.get(0);
        assertEquals(ExifBlock.IFD_0, author.ifd);
        assertFalse(author.knownToExifInterface());
        assertEquals("Jane Doe", author.xpText());
    }

    @Test
    public void aTagExifInterfaceKnows_isMarkedKnown() throws IOException {
        // Orientation, 1.
        ExifBlock.Entry orientation = ExifBlock.read(ExifFixtures.tiffWith(0x0112, new byte[] {1})).get(0);
        assertTrue(orientation.knownToExifInterface());
        assertNull(orientation.xpText());
    }

    @Test
    public void filesWithoutABlock_orInAnotherFormat_giveNull() throws IOException {
        assertNull(ExifBlock.find(new ByteArrayInputStream(PLAIN_JPEG)));
        assertNull(ExifBlock.find(new ByteArrayInputStream(new byte[0])));
        assertNull(ExifBlock.find(new ByteArrayInputStream("....ftypheic".getBytes())));
    }

    @Test
    public void offsetsOutsideTheBlock_orLoopingIfds_areRefused() {
        byte[] tiff = ExifFixtures.tiffWith(ExifBlock.TAG_XP_AUTHOR, ExifFixtures.xpText("Jane Doe"));
        // The value's offset pointed past the end.
        ByteBuffer.wrap(tiff).order(ByteOrder.LITTLE_ENDIAN).putInt(8 + 2 + 8, 1 << 20);
        assertThrows(IOException.class, () -> ExifBlock.read(tiff));

        byte[] looping = ExifFixtures.tiffWith(0x0112, new byte[] {1});
        // IFD0's next-IFD offset points back at IFD0.
        ByteBuffer.wrap(looping).order(ByteOrder.LITTLE_ENDIAN).putInt(8 + 2 + 12, 8);
        assertThrows(IOException.class, () -> ExifBlock.read(looping));

        assertThrows(IOException.class, () -> ExifBlock.read(new byte[] {'X', 'X', 42, 0, 8, 0, 0, 0}));
        assertThrows(IOException.class, () -> ExifBlock.read(new byte[4]));
    }

    /** The list matches the tag table of androidx.exifinterface 1.4.2, which it was read from. */
    @Test
    public void theKnownTagList_isTheOneReadFromExifInterface() {
        assertEquals(147, ExifBlock.exifInterfaceTagCount());
    }
}
