#!/usr/bin/env python3
import re
from pathlib import Path

res = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "res"
pat1 = re.compile(r"(</string>)\s+(<string name=\"settings_section_share_subtitle\">)")
pat2 = re.compile(r"^        (<string name=\"settings_)", re.MULTILINE)

for path in res.glob("values-*/strings.xml"):
    text = path.read_text(encoding="utf-8")
    orig = text
    text = pat1.sub(r"\1\n    \2", text)
    text = pat2.sub(r"    \1", text)
    if text != orig:
        path.write_text(text, encoding="utf-8")
        print("fixed", path.parent.name)
