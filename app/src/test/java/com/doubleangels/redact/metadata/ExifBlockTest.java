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
    public void aTagIsKnownOnlyInTheIfdWhereExifInterfaceLooksForIt() {
        byte[] none = new byte[0];
        // GPSLatitude's number: known in the GPS IFD, never in the EXIF IFD.
        assertTrue(new ExifBlock.Entry(ExifBlock.IFD_GPS, 0x0002, 5, 3, 24, none).knownToExifInterface());
        assertFalse(new ExifBlock.Entry(ExifBlock.IFD_EXIF, 0x0002, 5, 3, 24, none).knownToExifInterface());
        assertFalse(new ExifBlock.Entry(ExifBlock.IFD_GPS, 0x0112, 3, 1, 2, none).knownToExifInterface());
        // InteroperabilityIndex only, in its own IFD.
        assertTrue(new ExifBlock.Entry(ExifBlock.IFD_INTEROPERABILITY, 0x0001, 2, 4, 4, none).knownToExifInterface());
        assertFalse(new ExifBlock.Entry(ExifBlock.IFD_INTEROPERABILITY, 0x0002, 7, 4, 4, none).knownToExifInterface());
        // ColorSpace belongs to the EXIF IFD; the IFD pointers and DNG tags to the TIFF ones.
        assertTrue(new ExifBlock.Entry(ExifBlock.IFD_EXIF, 0xA001, 3, 1, 2, none).knownToExifInterface());
        assertFalse(new ExifBlock.Entry(ExifBlock.IFD_0, 0xA001, 3, 1, 2, none).knownToExifInterface());
        assertTrue(new ExifBlock.Entry(ExifBlock.IFD_0, 0x8769, 4, 1, 4, none).knownToExifInterface());
        assertTrue(new ExifBlock.Entry(ExifBlock.IFD_1, 0x0201, 4, 1, 4, none).knownToExifInterface());
        assertFalse(new ExifBlock.Entry(ExifBlock.IFD_EXIF, 0x8769, 4, 1, 4, none).knownToExifInterface());
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

    /** A big-endian TIFF block: IFD0 with one SHORT entry, then {@code next} as its next-IFD pointer. */
    private static byte[] tiffWithNext(long next, int extraBytes) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(8 + 2 + 12 + 4 + extraBytes);
        b.put((byte) 'M').put((byte) 'M').putShort((short) 42).putInt(8);
        b.putShort((short) 1).putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) 1).putShort((short) 0);
        b.putInt((int) next);
        return b.array();
    }

    @Test
    public void aCompactBlock_isCoveredWhole() throws Exception {
        org.junit.Assert.assertTrue(ExifBlock.coversWholeBlock(tiffWithNext(0, 0)));
    }

    /** Bytes no entry points to could hold anything, so a block with many of them isn't covered. */
    @Test
    public void unreferencedBytes_leaveTheBlockUncovered() throws Exception {
        org.junit.Assert.assertTrue("a little padding is fine", ExifBlock.coversWholeBlock(tiffWithNext(0, 16)));
        org.junit.Assert.assertFalse(ExifBlock.coversWholeBlock(tiffWithNext(0, 500)));
    }

    /** An IFD chained after IFD1 is never read, so the block isn't covered. */
    @Test
    public void anIfdPastIfd1_leavesTheBlockUncovered() throws Exception {
        // IFD0 -> IFD1 at 26 (one entry), whose next pointer is not zero.
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(26 + 18 + 18);
        b.put((byte) 'M').put((byte) 'M').putShort((short) 42).putInt(8);
        b.putShort((short) 1).putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) 1).putShort((short) 0);
        b.putInt(26);
        b.putShort((short) 1).putShort((short) 0x0103).putShort((short) 3).putInt(1).putShort((short) 6).putShort((short) 0);
        b.putInt(44);
        b.putShort((short) 0).putInt(0);
        org.junit.Assert.assertFalse(ExifBlock.coversWholeBlock(b.array()));
    }
}
