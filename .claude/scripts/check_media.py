"""Report identifying metadata left in cleaned or converted files.

Usage: python .claude/scripts/check_media.py <file_or_dir> [...]

Prints one line per file and ends with FAIL lines for anything a clean should have removed:
EXIF GPS, camera make/model/software, an XMP packet, a video location (©xyz, loci or a location
tag), or the test fixtures' marker strings ("secret", "SecretSoft", "TestCam"). Exits 1 if any
file fails. Needs Pillow, and ffprobe on PATH for videos.

The EXIF placeholders Android's ExifInterface cannot clear (Orientation, ImageWidth, ImageLength
and LightSource set to 0) are expected and not reported.
"""
import json
import os
import re
import subprocess
import sys

from PIL import Image

Image.MAX_IMAGE_PIXELS = None
IMAGE_EXT = (".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif")
VIDEO_EXT = (".mp4", ".m4v", ".mov", ".webm", ".mkv", ".3gp")
IDENTIFYING_EXIF = {0x010F: "Make", 0x0110: "Model", 0x0131: "Software", 0x013B: "Artist",
                    0x8298: "Copyright", 0xA430: "CameraOwnerName", 0xA431: "BodySerialNumber",
                    0x9003: "DateTimeOriginal"}
MARKERS = (b"secret", b"SecretSoft", b"TestCam", b"\xa9xyz", b"loci")


def check_image(path):
    problems = []
    with Image.open(path) as image:
        info = f"{image.format} {image.size[0]}x{image.size[1]}"
        if image.format == "PNG":
            image.load()  # a PNG's eXIf chunk is only read with the image data
        exif = image.getexif()
        for tag, name in IDENTIFYING_EXIF.items():
            if tag in exif or tag in exif.get_ifd(0x8769):
                problems.append(name)
        if exif.get_ifd(0x8825):
            problems.append("GPS")
    return info, problems


def check_video(path):
    out = subprocess.run(["ffprobe", "-v", "error", "-print_format", "json", "-show_format",
                          "-show_streams", path], capture_output=True, text=True, check=True).stdout
    probe = json.loads(out)
    codecs = "+".join(s.get("codec_name", "?") for s in probe.get("streams", []))
    tags = dict(probe["format"].get("tags", {}))
    for stream in probe.get("streams", []):
        tags.update(stream.get("tags", {}))
    problems = [k for k in tags if "location" in k.lower() or k.lower() in ("comment", "title", "artist")]
    return f"{probe['format']['format_name'].split(',')[0]} {codecs}", problems


def check(path):
    lower = path.lower()
    if lower.endswith(IMAGE_EXT):
        info, problems = check_image(path)
    elif lower.endswith(VIDEO_EXT):
        info, problems = check_video(path)
    else:
        return None
    with open(path, "rb") as f:
        data = f.read()
    problems += [m.decode("latin-1") for m in MARKERS if m in data]
    # An Ultra HDR gain map keeps its own small XMP packet (hdrgm only) for display; anything
    # else in an XMP packet, or an Extended XMP segment, is reported.
    for packet in re.findall(rb"<x:xmpmeta.*?</x:xmpmeta>", data, re.S):
        if set(re.findall(rb"xmlns:(\w+)=", packet)) - {b"x", b"rdf", b"hdrgm"}:
            problems.append("XMP")
    if b"http://ns.adobe.com/xmp/extension/" in data:
        problems.append("Extended XMP")
    return info, sorted(set(problems))


def main(paths):
    failed = 0
    for root in paths:
        files = [root] if os.path.isfile(root) else sorted(
            os.path.join(d, f) for d, _, names in os.walk(root) for f in names)
        for path in files:
            result = check(path)
            if result is None:
                continue
            info, problems = result
            status = "FAIL " + ", ".join(problems) if problems else "ok"
            failed += bool(problems)
            print(f"{status:<30} {info:<28} {path}")
    print(f"\n{failed} file(s) with identifying metadata")
    return 1 if failed else 0


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    sys.exit(main(sys.argv[1:]))
