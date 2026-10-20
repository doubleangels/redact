"""Give the store-screenshot samples in icons/sample the metadata a real phone photo carries.

Usage: python scripts/tag_samples.py

The screenshots should show what Redact is for, so every sample carries a GPS location (public
landmarks, never a real home), a phone make and model, capture times and camera settings, the way a
photo straight off a phone does. Scan then shows the Location badge, and the Clean summary reports
real numbers. Converts any PNG sample to JPEG first (PNG cannot carry most of this) and rewrites
the tags in place on every run, so it is safe to re-run. Needs Pillow, and ffmpeg for the video.
"""
import os
import shutil
import subprocess
import sys
import tempfile

from PIL import Image
from PIL.TiffImagePlugin import IFDRational

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SAMPLES = os.path.join(ROOT, "icons", "sample")

EXIF_IFD = 0x8769
GPS_IFD = 0x8825

# name: (latitude, longitude, altitude m, capture time, UTC offset)
PLACES = {
    "beach": (21.2766, -157.8269, 3, "2026:07:04 14:32:10", "-10:00"),          # Waikiki Beach
    "christmas": (40.7812, -73.9665, 40, "2025:12:25 09:12:44", "-05:00"),      # Central Park
    "city": (41.8826, -87.6233, 181, "2026:03:14 18:05:31", "-05:00"),          # Millennium Park
    "dog": (37.7694, -122.4862, 60, "2026:01:18 11:47:02", "-08:00"),           # Golden Gate Park
    "forest": (47.8021, -123.6044, 520, "2026:02:07 13:20:15", "-08:00"),       # Olympic National Park
    "landscape": (37.7456, -119.5936, 1220, "2026:05:30 19:41:08", "-07:00"),   # Yosemite Valley
    "mountain": (46.8523, -121.7603, 1650, "2026:08:22 07:58:39", "-07:00"),    # Mount Rainier
}
VIDEO_PLACE = (35.6595, 139.7005, "2026-04-02T12:10:00Z")                      # Shibuya Crossing


def dms(value):
    value = abs(value)
    d = int(value)
    m = int((value - d) * 60)
    s = round(((value - d) * 60 - m) * 60 * 100)
    return (IFDRational(d, 1), IFDRational(m, 1), IFDRational(s, 100))


def tag_photo(path, place):
    lat, lon, alt, when, offset = place
    image = Image.open(path)
    image.load()
    image = image.convert("RGB")
    exif = Image.Exif()
    exif[0x010F] = "Google"                 # Make
    exif[0x0110] = "Pixel 10 Pro"           # Model
    exif[0x0131] = "HDR+ 1.0.796315851zd"   # Software
    exif[0x0132] = when                     # DateTime
    exif[0x0112] = 1                        # Orientation
    sub = exif.get_ifd(EXIF_IFD)
    sub[0x9003] = when                      # DateTimeOriginal
    sub[0x9004] = when                      # DateTimeDigitized
    sub[0x9010] = offset                    # OffsetTime
    sub[0x9011] = offset                    # OffsetTimeOriginal
    sub[0x829A] = IFDRational(1, 640)          # ExposureTime
    sub[0x829D] = IFDRational(168, 100)        # FNumber
    sub[0x8827] = 50                        # ISO
    sub[0x920A] = IFDRational(690, 100)        # FocalLength
    sub[0xA433] = "Google"                  # LensMake
    sub[0xA434] = "Pixel 10 Pro back camera 6.9mm f/1.68"  # LensModel
    gps = exif.get_ifd(GPS_IFD)
    gps[0x0001] = "N" if lat >= 0 else "S"
    gps[0x0002] = dms(lat)
    gps[0x0003] = "E" if lon >= 0 else "W"
    gps[0x0004] = dms(lon)
    gps[0x0005] = 0
    gps[0x0006] = IFDRational(alt, 1)
    gps[0x001D] = when.split(" ")[0]        # GPSDateStamp
    out = os.path.splitext(path)[0] + ".jpg"
    if out == path:
        # Already a JPEG: swap only the EXIF segment, so re-runs never re-encode (and soften) the photo.
        with open(path, "rb") as f:
            data = f.read()
        with open(path, "wb") as f:
            f.write(replace_exif(data, exif.tobytes()))
        return out
    image.save(out, "JPEG", quality=92, exif=exif)
    os.remove(path)
    return out


def replace_exif(jpeg, exif_bytes):
    """Returns the JPEG with its EXIF APP1 segments replaced by one holding exif_bytes."""
    if jpeg[:2] != b"\xff\xd8":
        sys.exit("Not a JPEG")
    payload = b"Exif\x00\x00" + exif_bytes
    if len(payload) + 2 > 0xFFFF:
        sys.exit("EXIF too large for one APP1 segment")
    kept = []
    i = 2
    while i + 4 <= len(jpeg) and jpeg[i] == 0xFF:
        marker = jpeg[i + 1]
        if marker == 0xDA:   # start of scan: the rest is image data
            break
        length = int.from_bytes(jpeg[i + 2:i + 4], "big")
        segment = jpeg[i:i + 2 + length]
        if not (marker == 0xE1 and segment[4:10] == b"Exif\x00\x00"):
            kept.append(segment)
        i += 2 + length
    app1 = b"\xff\xe1" + (len(payload) + 2).to_bytes(2, "big") + payload
    # EXIF goes right after JFIF (APP0) when there is one, otherwise first.
    head = [s for s in kept if s[1] == 0xE0]
    rest = [s for s in kept if s[1] != 0xE0]
    return b"\xff\xd8" + b"".join(head) + app1 + b"".join(rest) + jpeg[i:]


def tag_video(path):
    lat, lon, when = VIDEO_PLACE
    location = "%+08.4f%+09.4f/" % (lat, lon)
    fd, tmp = tempfile.mkstemp(suffix=".mp4")
    os.close(fd)
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", path, "-map", "0", "-c", "copy",
                    "-metadata", "location=" + location, "-metadata", "location-eng=" + location,
                    "-metadata", "creation_time=" + when,
                    "-metadata", "com.android.manufacturer=Google",
                    "-metadata", "com.android.model=Pixel 10 Pro",
                    "-movflags", "use_metadata_tags", tmp], check=True)
    shutil.move(tmp, path)


def main():
    for name in sorted(os.listdir(SAMPLES)):
        base, ext = os.path.splitext(name)
        path = os.path.join(SAMPLES, name)
        if ext.lower() in (".png", ".jpg", ".jpeg") and base in PLACES:
            print("tagged", os.path.basename(tag_photo(path, PLACES[base])))
        elif ext.lower() == ".mp4":
            tag_video(path)
            print("tagged", name)
        else:
            print("skipped", name, file=sys.stderr)


if __name__ == "__main__":
    main()
