#!/usr/bin/env python3
"""Shows how much the source uses each declared dependency, to find unused ones.

Usage:
    python .claude/scripts/unused_dependencies.py

Needs Python 3.11 or newer (for tomllib). Run it from the repository root after one Gradle build, so the libraries are in
the Gradle cache (~/.gradle, or GRADLE_USER_HOME). For every dependency declared
in app/build.gradle and baselineprofile/build.gradle, it reads the library's own
Java packages from its jar or aar (not those of the libraries it pulls in), and
counts the files under that module's src/ that name one of them, in code or in
XML. It also lists version catalog entries no build file uses.

A count of 0 only means no source file names the library's packages. It may
still be used through a theme or style, an annotation processor, the manifest,
reflection, or only at runtime, so check each one before removing it.
"""

import io
import os
import re
import sys
import tomllib
import zipfile
from pathlib import Path

CATALOG = Path("gradle/libs.versions.toml")
MODULES = {"app": Path("app/build.gradle"), "baselineprofile": Path("baselineprofile/build.gradle")}
DECLARATION = re.compile(
    r"^\s*(\w+)\s*\(?\s*(?:project\(['\"]([^'\"]+)['\"]\)|libs\.([\w.]+))", re.M)
SOURCE_SUFFIXES = (".java", ".kt", ".xml")


def accessor(alias):
    return alias.replace("-", ".").replace("_", ".")


def gradle_cache():
    home = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    return home / "caches" / "modules-2" / "files-2.1"


def packages_of(group, name, version):
    """The Java packages a library's own classes live in, from its jar or aar."""
    folder = gradle_cache() / group / name / version
    if not folder.is_dir():
        return None
    packages = set()
    for archive in folder.rglob("*"):
        if archive.suffix not in (".jar", ".aar") or "sources" in archive.name or "javadoc" in archive.name:
            continue
        with zipfile.ZipFile(archive) as outer:
            jars = [outer]
            if archive.suffix == ".aar":
                jars = [zipfile.ZipFile(io.BytesIO(outer.read(n)))
                        for n in outer.namelist() if n.endswith(".jar")]
            for jar in jars:
                for entry in jar.namelist():
                    if entry.endswith(".class") and "/" in entry and not entry.startswith("META-INF"):
                        packages.add(entry.rsplit("/", 1)[0].replace("/", "."))
    # Keep only the shortest packages: a reference to a subpackage also starts with its parent.
    return sorted(p for p in packages if not any(p.startswith(q + ".") for q in packages))


def source_files(module):
    root = Path(module) / "src"
    return [p for p in root.rglob("*") if p.suffix in SOURCE_SUFFIXES and p.is_file()]


def main():
    if not CATALOG.is_file():
        print("Run this from the repository root.", file=sys.stderr)
        return 2
    catalog = tomllib.loads(CATALOG.read_text(encoding="utf-8"))
    versions = catalog.get("versions", {})
    libraries = catalog.get("libraries", {})
    plugins = catalog.get("plugins", {})

    build_text = "".join(p.read_text(encoding="utf-8") for p in Path(".").glob("**/*.gradle*")
                         if "build" in p.name and ".gradle/" not in str(p))
    build_text += Path("settings.gradle").read_text(encoding="utf-8") if Path("settings.gradle").exists() else ""

    print("Declared dependencies (configuration, library, files in src/ naming its packages):")
    for module, build_file in MODULES.items():
        if not build_file.is_file():
            continue
        texts = {p: p.read_text(encoding="utf-8", errors="ignore") for p in source_files(module)}
        for configuration, project, alias_path in DECLARATION.findall(build_file.read_text(encoding="utf-8")):
            if project:
                print(f"  {module}: {configuration} project({project}) (a module, check by hand)")
                continue
            alias = next((a for a in libraries if accessor(a) == alias_path), None)
            if alias is None:
                continue
            spec = libraries[alias]
            if "module" in spec:
                group, name = spec["module"].split(":")
            else:
                group, name = spec["group"], spec["name"]
            # tomllib reads `version.ref = "x"` as {"version": {"ref": "x"}}.
            version = spec.get("version")
            if isinstance(version, dict):
                version = versions.get(version.get("ref"))
            packages = packages_of(group, name, version)
            if packages is None:
                print(f"  {module}: {configuration} {group}:{name}:{version}  not in the Gradle cache, build once first")
                continue
            if not packages:
                print(f"  {module}: {configuration} {group}:{name}  no classes of its own: it only pulls in "
                      "other libraries, so check whether the source needs what those provide")
                continue
            users = [p for p, text in texts.items() if any(pkg in text for pkg in packages)]
            shown = ", ".join(packages[:3]) + (" …" if len(packages) > 3 else "")
            flag = "  <-- no source file names it" if not users else ""
            print(f"  {module}: {configuration} {group}:{name}  {len(users)} file(s)  [{shown}]{flag}")

    unused = [a for a in libraries if f"libs.{accessor(a)}" not in build_text]
    unused += [f"plugin {a}" for a in plugins if f"libs.plugins.{accessor(a)}" not in build_text]
    used_refs = set(re.findall(r'version\.ref\s*=\s*"([^"]+)"', CATALOG.read_text(encoding="utf-8")))
    unused += [f"version {v}" for v in versions if v not in used_refs and v not in build_text]
    print("\nVersion catalog entries no build file uses:")
    print("\n".join(f"  {u}" for u in unused) if unused else "  none")
    return 0


if __name__ == "__main__":
    sys.exit(main())
