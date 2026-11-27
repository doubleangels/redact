"""Create the small fixtures the instrumented clean test ships in app/src/androidTest/assets/media.

Usage: python3 .claude/scripts/make_instrumented_media.py

The same kinds of metadata as make_test_media.py, at sizes small enough to commit: EXIF GPS and
camera tags, an XMP location, a PNG eXIf chunk, an Apple-style video location atom with a comment,
and a WebM title. Also the videos Censor is checked against: a fine checkerboard over the whole
frame, which no covered area can still show, upright and with a 90 degree rotation flag. Needs Pillow
and ffmpeg on PATH. Rerun only to change the fixtures.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from make_test_media import exif_with_location, ffmpeg, photo  # noqa: E402

from PIL import Image  # noqa: E402

OUT = os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "androidTest", "assets", "media")


CHECKERBOARD = ("color=c=gray:s=320x240:r=30:d=2,format=yuv420p,"
                "geq=lum='if(mod(floor(X/3)+floor(Y/3)\\,2)\\,235\\,16)':cb=128:cr=128")


def censor_fixtures(j):
    """Censor's videos: 2 s of a 3 px checkerboard, plain and filmed on its side (rotated 90)."""
    ffmpeg("-f", "lavfi", "-i", CHECKERBOARD, "-c:v", "libx264", "-crf", "18", "-pix_fmt", "yuv420p",
           "-f", "mp4", j("detail.mp4"))
    ffmpeg("-display_rotation", "90", "-i", j("detail.mp4"), "-c", "copy", "-f", "mp4",
           j("detail_rotated.mp4"))


def main():
    os.makedirs(OUT, exist_ok=True)
    for name in os.listdir(OUT):
        os.remove(os.path.join(OUT, name))
    j = lambda name: os.path.join(OUT, name)
    photo(j("gps_camera.jpg"), (640, 480), "teal")
    photo(j("xmp_location.jpg"), (480, 360), "olive", xmp=(
        '<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">'
        '<rdf:Description xmlns:exif="http://ns.adobe.com/exif/1.0/" exif:GPSLatitude="48,51.5N"/>'
        '</rdf:RDF></x:xmpmeta>'))
    png = Image.new("RGB", (320, 240), "purple")
    png.save(j("gps.png"), exif=exif_with_location(png, 51.5, -0.12).tobytes())
    ffmpeg("-f", "lavfi", "-i", "testsrc=size=320x240:rate=30", "-f", "lavfi", "-i", "sine",
           "-t", "2", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac",
           "-metadata", "location=+40.6892-074.0445/", "-metadata", "comment=secret comment",
           "-f", "mov", j("location.mp4"))
    ffmpeg("-f", "lavfi", "-i", "testsrc=size=320x240:rate=30", "-t", "2",
           "-c:v", "libvpx-vp9", "-b:v", "200k", "-metadata", "title=secret title", j("title.webm"))
    censor_fixtures(j)
    for name in sorted(os.listdir(OUT)):
        print(f"{os.path.getsize(j(name)):>10}  {name}")


if __name__ == "__main__":
    main()
