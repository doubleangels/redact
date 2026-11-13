#!/usr/bin/env python3
"""Fails when the release build's merged manifest asks for anything app/release-manifest.txt does not list.

A library update can add a permission or an exported component to the app without any change to
app/src/main/AndroidManifest.xml. This compares what the bundle actually ships (permissions,
exported components and a few privacy-relevant application flags) with the committed list, and
fails on any difference in either direction, so the list always says exactly what ships.

Run after ./gradlew bundleRelease:
    python3 .github/scripts/check_release_manifest.py           # check
    python3 .github/scripts/check_release_manifest.py --update  # rewrite the list after an intended change
"""
import argparse
import glob
import os
import sys
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ALLOWLIST = os.path.join(ROOT, "app", "release-manifest.txt")
# The manifest AGP packages into the release bundle.
MANIFEST_GLOB = os.path.join(ROOT, "app", "build", "intermediates", "bundle_manifest", "release", "**", "AndroidManifest.xml")
COMPONENTS = ("activity", "activity-alias", "service", "receiver", "provider")
APP_FLAGS = ("allowBackup", "usesCleartextTraffic", "debuggable")
HEADER = """\
# What the release bundle's merged manifest grants, checked by .github/scripts/check_release_manifest.py
# after every bundleRelease in CI. A library that adds a permission or an exported component fails
# the build until the line is added here on purpose. Rewrite with --update after an intended change.
"""


def entries(manifest_xml):
    root = ET.fromstring(manifest_xml)
    out = set()
    for tag in ("uses-permission", "uses-permission-sdk-23"):
        for node in root.iter(tag):
            line = f"{tag} {node.get(ANDROID + 'name')}"
            if node.get(ANDROID + "maxSdkVersion"):
                line += f" maxSdkVersion={node.get(ANDROID + 'maxSdkVersion')}"
            out.add(line)
    app = root.find("application")
    if app is None:
        raise ValueError("the manifest has no <application>")
    for flag in APP_FLAGS:
        out.add(f"application {flag}={app.get(ANDROID + flag, 'unset')}")
    for tag in COMPONENTS:
        for node in app.iter(tag):
            exported = node.get(ANDROID + "exported")
            # Fails closed: a component with an intent filter and no exported attribute counts as exported.
            if exported == "true" or (exported is None and node.find("intent-filter") is not None):
                line = f"exported {tag} {node.get(ANDROID + 'name')}"
                if node.get(ANDROID + "permission"):
                    line += f" permission={node.get(ANDROID + 'permission')}"
                out.add(line)
    if not any(e.startswith("uses-permission") for e in out):
        raise ValueError("found no permissions at all, which means the manifest was not read correctly")
    return out


def read_allowlist(path):
    with open(path, encoding="utf-8") as f:
        return {l.strip() for l in f if l.strip() and not l.lstrip().startswith("#")}


def find_manifest():
    found = sorted(glob.glob(MANIFEST_GLOB, recursive=True))
    if len(found) != 1:
        listing = "\n  ".join(found) or "(none)"
        sys.exit(f"::error::Expected one release bundle manifest, found {len(found)}. Run ./gradlew bundleRelease "
                 f"first; if it ran, AGP moved the file and MANIFEST_GLOB needs updating.\n  {listing}")
    return found[0]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--update", action="store_true", help="rewrite the list from the current build")
    parser.add_argument("--manifest", help="read this manifest instead of the release build's")
    args = parser.parse_args()
    path = args.manifest or find_manifest()
    with open(path, encoding="utf-8") as f:
        try:
            shipped = entries(f.read())
        except (ET.ParseError, ValueError) as e:
            sys.exit(f"::error::Could not read {path}: {e}")
    if args.update:
        with open(ALLOWLIST, "w", encoding="utf-8", newline="\n") as f:
            f.write(HEADER + "\n" + "\n".join(sorted(shipped)) + "\n")
        print(f"Wrote {len(shipped)} entries to {os.path.relpath(ALLOWLIST, ROOT)}.")
        return
    allowed = read_allowlist(ALLOWLIST)
    added, removed = sorted(shipped - allowed), sorted(allowed - shipped)
    if not added and not removed:
        print(f"The release manifest matches app/release-manifest.txt ({len(shipped)} entries).")
        return
    for line in added:
        print(f"::error::New in the release manifest: {line}")
    for line in removed:
        print(f"::error::No longer in the release manifest: {line}")
    sys.exit("The release manifest changed. Find which dependency or manifest change did it. If it is intended, "
             "run this script with --update after ./gradlew bundleRelease and commit app/release-manifest.txt.")


if __name__ == "__main__":
    main()
