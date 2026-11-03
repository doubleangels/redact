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


PICTURE_NAMESPACES = {
    b"adobe:ns:meta/", b"http://www.w3.org/1999/02/22-rdf-syntax-ns#",
    b"http://ns.adobe.com/hdr-gain-map/1.0/", b"http://ns.google.com/photos/1.0/camera/",
    b"http://ns.google.com/photos/1.0/container/", b"http://ns.google.com/photos/1.0/container/item/",
}
NUMBER = re.compile(rb"^[-+]?(\d+(\.\d*)?|\.\d+)([eE][-+]?\d+)?$|^(True|False|true|false)$")


def picture_only_xmp(packet):
    """Whether an XMP packet holds only Ultra HDR numbers and the Motion Photo / gain map directory."""
    ns = dict(re.findall(rb'xmlns:([\w.-]+)\s*=\s*"([^"]*)"', packet))
    if any(uri not in PICTURE_NAMESPACES for uri in ns.values()):
        return False
    prefix_of = {uri: p for p, uri in ns.items()}
    item = prefix_of.get(b"http://ns.google.com/photos/1.0/container/item/")
    camera = prefix_of.get(b"http://ns.google.com/photos/1.0/camera/")
    hdrgm = prefix_of.get(b"http://ns.adobe.com/hdr-gain-map/1.0/")
    for prefix, name, value in re.findall(rb'\s([\w.-]+):([\w.-]+)\s*=\s*"([^"]*)"', packet):
        if prefix == b"xmlns":
            continue
        if prefix == item:
            ok = (name == b"Semantic" and value in (b"Primary", b"GainMap", b"MotionPhoto")) or \
                 (name == b"Mime" and value in (b"image/jpeg", b"image/heic", b"image/avif", b"video/mp4")) or \
                 (name in (b"Length", b"Padding") and value.isdigit())
        elif prefix == camera:
            ok = name in (b"MotionPhoto", b"MotionPhotoVersion", b"MotionPhotoPresentationTimestampUs") \
                 and re.fullmatch(rb"-?\d{1,19}", value) is not None
        elif prefix == hdrgm:
            ok = NUMBER.match(value) is not None
        elif ns.get(prefix) == b"http://www.w3.org/1999/02/22-rdf-syntax-ns#":
            ok = (name == b"about" and value == b"") or (name == b"parseType" and value == b"Resource")
        elif ns.get(prefix) == b"adobe:ns:meta/":
            ok = name == b"xmptk"
        else:
            ok = False
        if not ok:
            return False
    # No free text between tags: only numbers and flags (hdrgm sequences) may appear.
    for text in re.findall(rb">([^<]+)<", packet):
        if text.strip() and not NUMBER.match(text.strip()):
            return False
    return True


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
    # A clean keeps XMP that only describes the picture: an Ultra HDR gain map's numbers and the
    # Google container directory naming the primary picture, gain map and Motion Photo video
    # (what UltraHdr.isPictureOnlyXmp accepts). Anything else in an XMP packet, or an Extended
    # XMP segment, is reported.
    for packet in re.findall(rb"<x:xmpmeta.*?</x:xmpmeta>", data, re.S):
        if not picture_only_xmp(packet):
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
