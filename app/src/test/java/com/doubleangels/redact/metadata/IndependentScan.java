package com.doubleangels.redact.metadata;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.drew.imaging.FileType;
import com.drew.imaging.FileTypeDetector;
import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import com.drew.metadata.exif.ExifDirectoryBase;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifInteropDirectory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.ExifThumbnailDirectory;
import com.drew.metadata.exif.GpsDirectory;
import com.drew.metadata.file.FileTypeDirectory;
import com.drew.metadata.icc.IccDirectory;
import com.drew.metadata.xmp.XmpDirectory;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * A second opinion on what a file still carries, from metadata-extractor, a reader that shares no
 * code with the app's own scanners ({@link ImageContainerMetadata}, {@link VideoContainerMetadata},
 * {@link VideoStreamMetadata}, {@link ExifBlock}). A box or segment those scanners skip is skipped
 * by the cleaner and passed by {@link CleanVerifier} alike; this reader would still report it.
 *
 * <p>It sorts what it finds by what the item says about someone, not by which tags Redact keeps:
 * a location, a date, the camera, something always identifying (an owner, a serial, a comment, a
 * maker note, XMP, IPTC), or structure (size, orientation, colour, codec). A test then allows the
 * kinds a Clean preset keeps. Reading the keep list from the app's own {@code getTagsToPreserve}
 * would share its mistakes, so the kinds are worked out here. Review this list together with
 * {@link CleanVerifier} and {@code ImageCleaner}'s keep list.
 */
final class IndependentScan {

    /** What an item found in a file says about someone. */
    enum Kind {
        /** Where it was taken. Kept only by Keep Location. */
        LOCATION,
        /** When it was taken. Kept only by Keep Date Taken. */
        DATE,
        /** Which camera or lens took it, and its settings. Kept only by Keep Camera Settings. */
        CAMERA,
        /** Never kept by any setting: owner, serials, comments, software, maker notes, XMP, IPTC. */
        IDENTIFYING,
        /** Needed to show the picture or play the video. Always allowed. */
        STRUCTURAL
    }

    /** One item the reader found. */
    static final class Finding {
        final Kind kind;
        final String directory;
        final String tag;
        final String value;

        Finding(Kind kind, String directory, String tag, @Nullable String value) {
            this.kind = kind;
            this.directory = directory;
            this.tag = tag;
            this.value = value == null ? "" : value;
        }

        @Override
        public String toString() {
            String shown = value.length() > 60 ? value.substring(0, 57) + "..." : value;
            return kind + " " + directory + " / " + tag + " = " + shown;
        }
    }

    /** What the reader made of one file. */
    static final class Report {
        /** False when metadata-extractor can't read the format at all (WebM, for one). */
        final boolean readable;
        final FileType fileType;
        final List<Finding> findings;

        Report(boolean readable, FileType fileType, List<Finding> findings) {
            this.readable = readable;
            this.fileType = fileType;
            this.findings = findings;
        }

        /** Every finding of a kind not in {@code allowed}. */
        List<Finding> notAllowed(Set<Kind> allowed) {
            List<Finding> out = new ArrayList<>();
            for (Finding f : findings) {
                if (f.kind != Kind.STRUCTURAL && !allowed.contains(f.kind)) {
                    out.add(f);
                }
            }
            return out;
        }

        boolean has(Kind kind) {
            for (Finding f : findings) {
                if (f.kind == kind) {
                    return true;
                }
            }
            return false;
        }
    }

    // ---- EXIF ---------------------------------------------------------------------------------

    /** EXIF tags that hold a date or time. */
    private static final Set<Integer> EXIF_DATE = Set.of(
            ExifDirectoryBase.TAG_DATETIME, ExifDirectoryBase.TAG_DATETIME_ORIGINAL,
            ExifDirectoryBase.TAG_DATETIME_DIGITIZED, ExifDirectoryBase.TAG_TIME_ZONE,
            ExifDirectoryBase.TAG_TIME_ZONE_ORIGINAL, ExifDirectoryBase.TAG_TIME_ZONE_DIGITIZED,
            ExifDirectoryBase.TAG_SUBSECOND_TIME, ExifDirectoryBase.TAG_SUBSECOND_TIME_ORIGINAL,
            ExifDirectoryBase.TAG_SUBSECOND_TIME_DIGITIZED);

    /** EXIF tags that name the camera or lens. Their exposure settings are below, as CAMERA too. */
    private static final Set<Integer> EXIF_CAMERA = Set.of(
            ExifDirectoryBase.TAG_MAKE, ExifDirectoryBase.TAG_MODEL,
            ExifDirectoryBase.TAG_LENS_MAKE, ExifDirectoryBase.TAG_LENS_MODEL,
            ExifDirectoryBase.TAG_LENS_SPECIFICATION,
            ExifDirectoryBase.TAG_EXPOSURE_TIME, ExifDirectoryBase.TAG_FNUMBER,
            ExifDirectoryBase.TAG_EXPOSURE_PROGRAM, ExifDirectoryBase.TAG_ISO_EQUIVALENT,
            ExifDirectoryBase.TAG_SENSITIVITY_TYPE, ExifDirectoryBase.TAG_SHUTTER_SPEED,
            ExifDirectoryBase.TAG_APERTURE, ExifDirectoryBase.TAG_BRIGHTNESS_VALUE,
            ExifDirectoryBase.TAG_EXPOSURE_BIAS, ExifDirectoryBase.TAG_MAX_APERTURE,
            ExifDirectoryBase.TAG_SUBJECT_DISTANCE, ExifDirectoryBase.TAG_METERING_MODE,
            ExifDirectoryBase.TAG_WHITE_BALANCE, ExifDirectoryBase.TAG_FLASH,
            ExifDirectoryBase.TAG_FOCAL_LENGTH, ExifDirectoryBase.TAG_35MM_FILM_EQUIV_FOCAL_LENGTH,
            ExifDirectoryBase.TAG_EXPOSURE_MODE, ExifDirectoryBase.TAG_WHITE_BALANCE_MODE,
            ExifDirectoryBase.TAG_DIGITAL_ZOOM_RATIO, ExifDirectoryBase.TAG_SCENE_CAPTURE_TYPE,
            ExifDirectoryBase.TAG_GAIN_CONTROL, ExifDirectoryBase.TAG_CONTRAST,
            ExifDirectoryBase.TAG_SATURATION, ExifDirectoryBase.TAG_SHARPNESS,
            ExifDirectoryBase.TAG_SUBJECT_DISTANCE_RANGE, ExifDirectoryBase.TAG_SENSING_METHOD,
            ExifDirectoryBase.TAG_FILE_SOURCE, ExifDirectoryBase.TAG_SCENE_TYPE,
            ExifDirectoryBase.TAG_CUSTOM_RENDERED, ExifDirectoryBase.TAG_EXIF_VERSION,
            ExifDirectoryBase.TAG_FLASHPIX_VERSION, ExifDirectoryBase.TAG_COMPONENTS_CONFIGURATION);

    /**
     * EXIF tags a picture needs to be shown right, or that only describe its pixels. Anything
     * else in EXIF that is not a date, the camera or a location counts as identifying, so a tag
     * nobody listed (XPAuthor, a vendor's own) fails closed.
     */
    private static final Set<Integer> EXIF_STRUCTURAL = Set.of(
            ExifDirectoryBase.TAG_IMAGE_WIDTH, ExifDirectoryBase.TAG_IMAGE_HEIGHT,
            ExifDirectoryBase.TAG_EXIF_IMAGE_WIDTH, ExifDirectoryBase.TAG_EXIF_IMAGE_HEIGHT,
            ExifDirectoryBase.TAG_ORIENTATION, ExifDirectoryBase.TAG_COLOR_SPACE,
            ExifDirectoryBase.TAG_BITS_PER_SAMPLE, ExifDirectoryBase.TAG_COMPRESSION,
            ExifDirectoryBase.TAG_PHOTOMETRIC_INTERPRETATION, ExifDirectoryBase.TAG_SAMPLES_PER_PIXEL,
            ExifDirectoryBase.TAG_PLANAR_CONFIGURATION, ExifDirectoryBase.TAG_YCBCR_SUBSAMPLING,
            ExifDirectoryBase.TAG_YCBCR_POSITIONING, ExifDirectoryBase.TAG_X_RESOLUTION,
            ExifDirectoryBase.TAG_Y_RESOLUTION, ExifDirectoryBase.TAG_RESOLUTION_UNIT,
            ExifDirectoryBase.TAG_LIGHT_SOURCE, ExifDirectoryBase.TAG_INTEROP_INDEX,
            ExifDirectoryBase.TAG_INTEROP_VERSION,
            0x8769 /* ExifIFD pointer */, 0x8825 /* GPS IFD pointer */, 0xA005 /* Interop IFD pointer */);

    // ---- other formats ------------------------------------------------------------------------

    /** XMP properties a kept Motion Photo or Ultra HDR directory is made of. Any other fails. */
    private static final Pattern XMP_PICTURE_ONLY = Pattern.compile(
            "^(GCamera|Container|Item|hdrgm|xmpNote|rdf|x):.*");

    /** Words in a tag's name that mean a date, in formats this class has no tag list for. */
    private static final Pattern NAMED_DATE = Pattern.compile(
            "(?i).*(date|time stamp|creation time|modification time|recorded|year).*");
    /** Words in a tag's name that mean a place. */
    private static final Pattern NAMED_LOCATION = Pattern.compile(
            "(?i).*(gps|location|latitude|longitude|altitude|iso ?6709|xyz).*");
    /** Words in a tag's name that mean the device. */
    private static final Pattern NAMED_CAMERA = Pattern.compile("(?i).*\\b(make|model)\\b.*");
    /** Words in a tag's name that mean something about a person or the file's history. */
    private static final Pattern NAMED_IDENTIFYING = Pattern.compile(
            "(?i).*(author|artist|owner|comment|copyright|title|description|keyword|serial|"
                    + "software|encoder|encoded|handler name|writer|creator|user|text|url|uuid|"
                    + "unique|name|album|genre|composer|publisher|rating).*");

    // ---- colour profiles ----------------------------------------------------------------------

    /**
     * ICC header fields a clean always zeroes ({@link ImageContainerMetadata#clearIccHeader}): the
     * CMM, creation date, device maker, model and attributes, the creator and the profile ID. A
     * header field is keyed by its byte offset.
     */
    private static final Set<Integer> ICC_HEADER_CLEARED = Set.of(4, 24, 48, 52, 56, 80, 84);
    private static final int ICC_HEADER_DATE = 24;
    private static final int ICC_DESC = signature("desc");
    private static final int ICC_CPRT = signature("cprt");
    /** Profile tags that name a display or the day and target it was calibrated against. */
    private static final Set<Integer> ICC_PERSONAL = Set.of(
            signature("dmnd"), signature("dmdd"), signature("calt"), signature("targ"), signature("pseq"));
    /** A localized ICC text as the reader shows it: {@code 1 enUS(sRGB built-in)}. */
    private static final Pattern ICC_LOCALIZED = Pattern.compile("^\\d+ [A-Za-z]{4}\\((.*)\\)$");

    /** Directories whose whole content is text, history or vendor data, whatever its tags. */
    private static final Pattern IDENTIFYING_DIRECTORY = Pattern.compile(
            "(?i).*(makernote|iptc|photoshop|comment|ducky|printim|icc profile description|"
                    + "apple run time|adobe xmp|metadata header|user data).*");

    private IndependentScan() {
    }

    /** Reads {@code file} with metadata-extractor and sorts everything it finds. */
    @NonNull
    static Report scan(@NonNull File file) throws IOException {
        FileType type;
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            type = FileTypeDetector.detectFileType(in);
        }
        Metadata metadata;
        // Read from a stream, not the File: given a File, the reader adds the temp file's own name
        // and dates, which were never in the content.
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            metadata = ImageMetadataReader.readMetadata(in, file.length());
        } catch (ImageProcessingException e) {
            return new Report(false, type, List.of());
        }
        List<Finding> findings = new ArrayList<>();
        for (Directory directory : metadata.getDirectories()) {
            sort(directory, findings);
        }
        return new Report(true, type, findings);
    }

    private static void sort(Directory directory, List<Finding> out) {
        String dirName = directory.getName();
        if (directory instanceof FileTypeDirectory) {
            return; // the reader's own guess at the format and extension, not read from the file
        }
        if (directory instanceof IccDirectory) {
            sortIcc(directory, out);
            return;
        }
        if (directory instanceof XmpDirectory) {
            Map<String, String> properties = ((XmpDirectory) directory).getXmpProperties();
            for (Map.Entry<String, String> p : properties.entrySet()) {
                Kind kind = XMP_PICTURE_ONLY.matcher(p.getKey()).matches() ? Kind.STRUCTURAL : Kind.IDENTIFYING;
                out.add(new Finding(kind, dirName, p.getKey(), p.getValue()));
            }
            return;
        }
        if (directory instanceof GpsDirectory) {
            for (Tag tag : directory.getTags()) {
                out.add(new Finding(Kind.LOCATION, dirName, tag.getTagName(), tag.getDescription()));
            }
            return;
        }
        boolean exif = directory instanceof ExifIFD0Directory || directory instanceof ExifSubIFDDirectory
                || directory instanceof ExifInteropDirectory;
        if (directory instanceof ExifThumbnailDirectory) {
            // A preview kept from the original can show what was cropped or censored out.
            for (Tag tag : directory.getTags()) {
                out.add(new Finding(Kind.IDENTIFYING, dirName, tag.getTagName(), tag.getDescription()));
            }
            return;
        }
        boolean wholeDirectory = IDENTIFYING_DIRECTORY.matcher(dirName).matches()
                || directory.getClass().getSimpleName().contains("Makernote");
        for (Tag tag : directory.getTags()) {
            int type = tag.getTagType();
            String name = tag.getTagName();
            String value = tag.getDescription();
            Kind kind;
            if (wholeDirectory) {
                kind = Kind.IDENTIFYING;
            } else if (exif) {
                kind = exifKind(directory, type);
            } else {
                kind = namedKind(directory, type, name);
            }
            out.add(new Finding(kind, dirName, name, value));
        }
    }

    /**
     * A colour profile is kept because dropping it changes the colours. Its colour numbers are
     * structural. The header fields a clean zeroes, the display and calibration tags and any tag the
     * reader can't name are not. The description and copyright pass only for a well-known profile
     * ({@link IccProfiles#isWellKnown}) or when both carry the neutral name Strict and shares give
     * any other profile.
     */
    private static void sortIcc(Directory directory, List<Finding> out) {
        String description = iccText(directory.getDescription(ICC_DESC));
        String copyright = iccText(directory.getDescription(ICC_CPRT));
        boolean impersonalName = IccProfiles.isWellKnown(description)
                || IccProfiles.NEUTRAL_NAME.equals(description) && IccProfiles.NEUTRAL_NAME.equals(copyright);
        for (Tag tag : directory.getTags()) {
            int type = tag.getTagType();
            Kind kind;
            if (type < 128) {
                boolean set = ICC_HEADER_CLEARED.contains(type) && !isBlank(directory.getObject(type));
                kind = !set ? Kind.STRUCTURAL : type == ICC_HEADER_DATE ? Kind.DATE : Kind.IDENTIFYING;
            } else if (type == ICC_DESC || type == ICC_CPRT) {
                kind = impersonalName ? Kind.STRUCTURAL : Kind.IDENTIFYING;
            } else if (ICC_PERSONAL.contains(type) || !directory.hasTagName(type)) {
                kind = Kind.IDENTIFYING;
            } else {
                kind = Kind.STRUCTURAL;
            }
            out.add(new Finding(kind, directory.getName(), tag.getTagName(), tag.getDescription()));
        }
    }

    @Nullable
    private static String iccText(@Nullable String shown) {
        if (shown == null) {
            return null;
        }
        Matcher m = ICC_LOCALIZED.matcher(shown.trim());
        return m.matches() ? m.group(1) : shown.trim();
    }

    private static boolean isBlank(@Nullable Object raw) {
        if (raw == null) {
            return true;
        }
        if (raw instanceof Number) {
            return ((Number) raw).longValue() == 0;
        }
        if (raw instanceof byte[]) {
            for (byte b : (byte[]) raw) {
                if (b != 0) {
                    return false;
                }
            }
            return true;
        }
        String text = raw.toString().replace("\0", "").trim();
        return text.isEmpty() || text.equals("0");
    }

    private static int signature(String fourChars) {
        int v = 0;
        for (int i = 0; i < 4; i++) {
            v = v << 8 | fourChars.charAt(i) & 0xFF;
        }
        return v;
    }

    private static Kind exifKind(Directory directory, int type) {
        if (EXIF_DATE.contains(type)) {
            return Kind.DATE;
        }
        if (EXIF_CAMERA.contains(type)) {
            return Kind.CAMERA;
        }
        if (EXIF_STRUCTURAL.contains(type) && directory.hasTagName(type)) {
            return Kind.STRUCTURAL;
        }
        return Kind.IDENTIFYING;
    }

    /**
     * For formats with no tag list here (PNG, WebP, HEIF, GIF, MP4, QuickTime): sorted by the
     * words in the tag's name. A date that is unset (1904 or 1970, as a clean writes it) is
     * structural; a tag whose name says nothing is structural, as the dimensions and codecs are.
     */
    private static Kind namedKind(Directory directory, int type, String name) {
        if (NAMED_LOCATION.matcher(name).matches()) {
            return Kind.LOCATION;
        }
        if (NAMED_DATE.matcher(name).matches()) {
            return isUnsetDate(directory, type) ? Kind.STRUCTURAL : Kind.DATE;
        }
        if (NAMED_CAMERA.matcher(name).matches()) {
            return Kind.CAMERA;
        }
        if (NAMED_IDENTIFYING.matcher(name).matches()) {
            return Kind.IDENTIFYING;
        }
        if (!directory.hasTagName(type)) {
            return Kind.IDENTIFYING; // a tag the reader has no name for: fail closed
        }
        return Kind.STRUCTURAL;
    }

    private static boolean isUnsetDate(Directory directory, int type) {
        Object raw = directory.getObject(type);
        if (raw instanceof Number && ((Number) raw).longValue() == 0) {
            return true;
        }
        Date date = raw instanceof Date ? (Date) raw : null;
        if (date == null) {
            try {
                date = directory.getDate(type);
            } catch (RuntimeException ignored) {
                date = null;
            }
        }
        if (date == null) {
            return false;
        }
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);
        c.setTime(date);
        int year = c.get(Calendar.YEAR);
        return year <= 1904 || year == 1970 && date.getTime() < 86_400_000L;
    }
}
