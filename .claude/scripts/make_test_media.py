"""Create photos and videos full of identifying metadata for testing Redact on an emulator.

Usage: python .claude/scripts/make_test_media.py <out_dir>

Needs Pillow and ffmpeg on PATH. Every file carries something a clean must remove:

  IMG_20260117_093012.jpg  GPS, camera make/model, software (a phone-style name for Keep Original File Names)
  IMG_beach.jpg            GPS, camera make/model, software
  IMG_xmp.jpg              EXIF plus an XMP packet with a location
  BIG_panorama.jpg         16000x8000 with GPS, to exercise large-image decoding
  convert_me.png           a PNG with GPS in its EXIF chunk
  VID_20260117_100000.mp4  H.264 + AAC with an Apple-style location atom and a comment
  VID_long.mp4             90 s 1080p H.264 with a location, long enough to overlap with other jobs
  VID_vp9.webm             VP9 with a title, for the WebM and VP9 paths
  censor_me.jpg            4000x3000 with GPS and two lines of small text 34 px apart, at photo
                           (1900, 1498)-(2208, 1514) and (1900, 1548)-(2174, 1564), for covering
                           one line but not the other when zoomed in on Censor
"""
import os
import subprocess
import sys
from fractions import Fraction

from PIL import Image, ImageDraw, ImageFont

GPS_IFD = 0x8825


def dms(value):
    d = int(value)
    m = int((value - d) * 60)
    s = round(((value - d) * 60 - m) * 60 * 100)
    return (Fraction(d), Fraction(m), Fraction(s, 100))


def exif_with_location(image, lat, lon, camera=True):
    exif = image.getexif()
    if camera:
        exif[0x010F] = "TestCam"      # Make
        exif[0x0110] = "Model X"      # Model
        exif[0x0131] = "SecretSoft"   # Software
    gps = exif.get_ifd(GPS_IFD)
    gps[1] = "N" if lat >= 0 else "S"
    gps[2] = dms(abs(lat))
    gps[3] = "E" if lon >= 0 else "W"
    gps[4] = dms(abs(lon))
    return exif


def photo(path, size, colour, lat=40.6892, lon=-74.0445, xmp=None):
    image = Image.new("RGB", size, colour)
    ImageDraw.Draw(image).text((50, 50), os.path.basename(path), fill="white")
    extra = {"xmp": xmp.encode()} if xmp else {}
    image.save(path, quality=85, exif=exif_with_location(image, lat, lon), **extra)


def ffmpeg(*args):
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", *args], check=True)


def main(out):
    os.makedirs(out, exist_ok=True)
    j = lambda name: os.path.join(out, name)

    photo(j("IMG_20260117_093012.jpg"), (4000, 3000), "teal")
    photo(j("IMG_beach.jpg"), (3000, 2000), "orange", lat=-33.8568, lon=151.2153)
    photo(j("IMG_xmp.jpg"), (1600, 1200), "olive", xmp=(
        '<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">'
        '<rdf:Description xmlns:exif="http://ns.adobe.com/exif/1.0/" exif:GPSLatitude="48,51.5N"/>'
        '</rdf:RDF></x:xmpmeta>'))
    Image.MAX_IMAGE_PIXELS = None
    photo(j("BIG_panorama.jpg"), (16000, 8000), "navy")

    censor = Image.new("RGB", (4000, 3000), (250, 250, 245))
    draw = ImageDraw.Draw(censor)
    for x in range(0, 4000, 100):
        draw.line([(x, 0), (x, 2999)], fill=(200, 210, 230))
    for y in range(0, 3000, 100):
        draw.line([(0, y), (3999, y)], fill=(200, 210, 230))
    font = ImageFont.load_default(size=24)
    draw.text((1900, 1490), "CARD 4111 1111 1111 1111", fill="black", font=font)
    draw.text((1900, 1540), "ACCOUNT 12-3456-7890", fill="black", font=font)
    censor.save(j("censor_me.jpg"), quality=95, exif=exif_with_location(censor, 51.5, -0.12))

    png = Image.new("RGB", (1200, 900), "purple")
    png.save(j("convert_me.png"), exif=exif_with_location(png, 51.5, -0.12).tobytes())

    # The QuickTime muxer writes the ©xyz location atom phones use; the MP4 muxer would write a
    # 3GPP loci box instead, which Android's metadata reader does not report.
    ffmpeg("-f", "lavfi", "-i", "testsrc=size=1280x720:rate=30", "-f", "lavfi", "-i", "sine",
           "-t", "4", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac",
           "-metadata", "location=+40.6892-074.0445/", "-metadata", "comment=secret comment",
           "-f", "mov", j("VID_20260117_100000.mp4"))
    ffmpeg("-f", "lavfi", "-i", "testsrc=size=1920x1080:rate=30", "-f", "lavfi", "-i", "sine",
           "-t", "90", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac",
           "-metadata", "location=+40.6892-074.0445/", "-f", "mov", j("VID_long.mp4"))
    ffmpeg("-f", "lavfi", "-i", "testsrc=size=640x360:rate=30", "-t", "3",
           "-c:v", "libvpx-vp9", "-b:v", "500k", "-metadata", "title=secret title", j("VID_vp9.webm"))

    for name in sorted(os.listdir(out)):
        print(f"{os.path.getsize(j(name)):>10}  {name}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
