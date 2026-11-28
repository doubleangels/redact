"""Report identifying metadata left in cleaned or converted files.

Usage: python .claude/scripts/check_media.py <file_or_dir> [...]

Prints one line per file and ends with FAIL lines for anything a clean should have removed:
EXIF GPS, camera make/model/software, an XMP packet, a video location (©xyz, loci or a location
tag), the test fixtures' marker strings ("secret", "SecretSoft", "TestCam"), a colour profile
whose header still names a device, creator or date or that keeps device or calibration text
(Strict Clean, the default, replaces it), and a HEIF depth map, disparity map or matte,
still or in a sequence. Exits 1 if any file fails. Needs Pillow, and ffprobe on PATH for videos.

When exiftool is on PATH, it gives a second opinion on every file: it reads maker notes, unknown
EXIF tags, XMP in any box and vendor trailers that Pillow and ffprobe skip, and any identifying
tag it finds fails the file. Without it the script says "exiftool not found, second opinion
skipped" once and checks the rest as before. Install it with `winget install exiftool` on
Windows or `apt install libimage-exiftool-perl` on Linux.

The EXIF placeholders Android's ExifInterface cannot clear (Orientation, ImageWidth, ImageLength
and LightSource set to 0) are expected and not reported.
"""
import json
import os
import re
import shutil
import subprocess
import sys

from PIL import Image, UnidentifiedImageError

try:  # HEIC and HEIF need the pillow-heif plugin (pip install pillow-heif)
    import pillow_heif
    pillow_heif.register_heif_opener()
except ImportError:
    pillow_heif = None

Image.MAX_IMAGE_PIXELS = None
IMAGE_EXT = (".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif")
VIDEO_EXT = (".mp4", ".m4v", ".mov", ".webm", ".mkv", ".3gp")
IDENTIFYING_EXIF = {0x010F: "Make", 0x0110: "Model", 0x0131: "Software", 0x013B: "Artist",
                    0x8298: "Copyright", 0xA430: "CameraOwnerName", 0xA431: "BodySerialNumber",
                    0x9003: "DateTimeOriginal"}
MARKERS = (b"secret", b"SecretSoft", b"TestCam", b"\xa9xyz", b"loci")
# An auxiliary picture's kind, in a still image's auxC property or a sequence track's auxi box.
DEPTH_OR_MATTE = re.compile(
    rb"(?:auxC|auxi).{4}urn:[^\x00]{0,80}?(?:depth|disparity|matte|hevc:2015:auxid:2)", re.S)


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


def icc_problems(profile):
    """What a cleaned colour profile still says about a device, its maker or when it was made."""
    if not profile or len(profile) < 132:
        return []
    problems = []
    if any(profile[24:36]) or any(profile[48:56]) or any(profile[80:84]):
        problems.append("ICC header")  # date, device maker and model, creator
    count = int.from_bytes(profile[128:132], "big")
    tags = {profile[132 + 12 * i:136 + 12 * i] for i in range(min(count, (len(profile) - 132) // 12))}
    if tags & {b"dmnd", b"dmdd", b"meta", b"targ", b"ciis"}:
        problems.append("ICC device text")
    return problems


def check_image(path):
    problems = []
    try:
        image = Image.open(path)
    except UnidentifiedImageError:
        # Without a decoder the EXIF can't be read, so the file can't be called clean.
        return "not decoded", ["EXIF not read (pip install pillow-heif)" if pillow_heif is None
                               else "EXIF not read (Pillow can't open it)"]
    with image:
        info = f"{image.format} {image.size[0]}x{image.size[1]}"
        if image.format == "PNG":
            image.load()  # a PNG's eXIf chunk is only read with the image data
        exif = image.getexif()
        for tag, name in IDENTIFYING_EXIF.items():
            if tag in exif or tag in exif.get_ifd(0x8769):
                problems.append(name)
        if exif.get_ifd(0x8825):
            problems.append("GPS")
        problems += icc_problems(image.info.get("icc_profile"))
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


EXIFTOOL = shutil.which("exiftool")
# Groups (exiftool's family 0, or family 1 for XMP) that hold only what a picture or video needs,
# or describe the file rather than its content. ICC profiles are checked by icc_problems above.
EXIFTOOL_IGNORED = {"ExifTool", "File", "System", "Composite", "ICC_Profile", "JFIF", "APP14"}
# XMP namespaces a kept Ultra HDR gain map or Motion Photo directory is made of.
EXIFTOOL_PICTURE_XMP = {"XMP-GCamera", "XMP-Container", "XMP-Item", "XMP-hdrgm", "XMP-x", "XMP-rdf"}
# Whole groups that are identifying whatever the tag: maker notes, IPTC, Photoshop, GPS.
EXIFTOOL_IDENTIFYING_GROUPS = {"MakerNotes", "IPTC", "Photoshop", "GPS", "UserData"}
EXIFTOOL_IDENTIFYING_TAG = re.compile(
    r"(Make|Model|Software|Artist|Copyright|Owner|Serial|Date|Time$|Comment|Description|"
    r"Author|Title|Creator|Location|GPS|Keywords|Encoder|HostComputer|UniqueID|DocumentName|"
    r"^XP|Rights|Credit|Album|Genre|Publisher|Writer|URL|0x[0-9a-fA-F]+$)")
GENERIC_HANDLER = re.compile(r"^((Video|Sound|Audio|Meta|Data)Handle[r]?|)$")
UNSET_DATE = re.compile(r"^[0: ]*(\+00:00|Z)?$|^1904:01:01 00:00:00|^1970:01:01 00:00:00")


def exiftool_problems(path):
    """What exiftool finds that a Strict Clean should have removed, or None without exiftool."""
    if EXIFTOOL is None:
        return None
    out = subprocess.run([EXIFTOOL, "-a", "-u", "-G0:1", "-j", "-n", path],
                         capture_output=True, text=True).stdout
    try:
        tags = json.loads(out)[0]
    except (ValueError, IndexError):
        return ["exiftool could not read it"]
    problems = []
    for key, value in tags.items():
        if ":" not in key:
            continue  # SourceFile
        groups, name = key.rsplit(":", 1)
        family0, _, family1 = groups.partition(":")
        if family0 in EXIFTOOL_IGNORED or name in ("Warning", "Error"):
            continue
        if family0 == "XMP":
            if family1 not in EXIFTOOL_PICTURE_XMP:
                problems.append(f"exiftool {family1}:{name}")
            continue
        if family0 in EXIFTOOL_IDENTIFYING_GROUPS or family1 in EXIFTOOL_IDENTIFYING_GROUPS:
            problems.append(f"exiftool {family1 or family0}:{name}")
            continue
        if name == "HandlerDescription" and GENERIC_HANDLER.match(str(value)):
            continue  # the track type a muxer names its handler after, nothing about the source
        if EXIFTOOL_IDENTIFYING_TAG.search(name):
            if "Date" in name or name.endswith("Time"):
                if UNSET_DATE.match(str(value)) or not re.search(r"\d{4}", str(value)):
                    continue  # a clean writes the date as unset, or it is a duration
            problems.append(f"exiftool {family1 or family0}:{name}")
    return problems


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
    if lower.endswith((".heic", ".heif")) and DEPTH_OR_MATTE.search(data):
        problems.append("Depth or matte")
    # A clean keeps XMP that only describes the picture: an Ultra HDR gain map's numbers and the
    # Google container directory naming the primary picture, gain map and Motion Photo video
    # (what UltraHdr.isPictureOnlyXmp accepts). Anything else in an XMP packet, or an Extended
    # XMP segment, is reported.
    for packet in re.findall(rb"<x:xmpmeta.*?</x:xmpmeta>", data, re.S):
        if not picture_only_xmp(packet):
            problems.append("XMP")
    if b"http://ns.adobe.com/xmp/extension/" in data:
        problems.append("Extended XMP")
    problems += exiftool_problems(path) or []
    return info, sorted(set(problems))


def main(paths):
    failed = 0
    if EXIFTOOL is None:
        print("exiftool not found, second opinion skipped")
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
