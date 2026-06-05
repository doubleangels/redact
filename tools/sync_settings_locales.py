#!/usr/bin/env python3
"""Update settings strings in all locale strings.xml files."""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app" / "src" / "main" / "res"

# All translatable settings keys to sync (values from English refresh)
KEYS = [
    "settings_section_notifications_subtitle",
    "settings_notifications_label",
    "settings_notifications_subtitle",
    "settings_notifications_clean_label",
    "settings_notifications_clean_subtitle",
    "settings_notifications_convert_label",
    "settings_notifications_convert_subtitle",
    "settings_section_diagnostics",
    "settings_section_diagnostics_subtitle",
    "settings_crash_reporting_label",
    "settings_crash_reporting_subtitle",
    "settings_section_conversion",
    "settings_conversion_subtitle",
    "settings_quality_subtitle",
    "settings_section_share",
    "settings_section_share_subtitle",
    "settings_share_confirm_label",
    "settings_share_confirm_subtitle",
    "settings_permissions_subtitle",
    "settings_permissions_media",
    "settings_permissions_notifications",
    "settings_section_storage",
    "settings_storage_size",
    "settings_storage_subtitle",
    "settings_auto_clear_temp",
    "settings_auto_clear_temp_subtitle",
    "settings_progress_notifications_label",
    "settings_progress_notifications_subtitle",
    "settings_crash_reporting_detail_message",
    "settings_section_advanced_privacy",
    "settings_advanced_privacy_subtitle",
    "settings_secure_delete_subtitle",
    "settings_preserve_camera",
    "settings_preserve_camera_subtitle",
    "settings_preserve_location",
    "settings_preserve_location_subtitle",
    "settings_strict_clean_subtitle",
    "settings_video_fallback_subtitle",
    "settings_section_advanced_processing",
    "settings_advanced_processing_subtitle",
    "settings_max_bitmap_size",
    "settings_max_bitmap_size_subtitle",
    "settings_max_file_size",
    "settings_max_file_size_subtitle",
]

LOCALES = {}

def load_data():
    import importlib.util
    data_path = Path(__file__).with_name("settings_locale_data.py")
    spec = importlib.util.spec_from_file_location("settings_locale_data", data_path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod.LOCALES


def xml_escape(s: str) -> str:
    return (
        s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("'", "\\'")
    )


def upsert_string(text: str, key: str, value: str) -> str:
    val = xml_escape(value)
    pat = rf'<string name="{re.escape(key)}">.*?</string>'
    line = f'    <string name="{key}">{val}</string>'
    if re.search(pat, text, re.DOTALL):
        return re.sub(pat, line.strip(), text, count=1, flags=re.DOTALL)

    inserts = {
        "settings_section_notifications_subtitle": (
            r'(<string name="settings_section_notifications">.*?</string>\n)',
            r"\1    " + line + "\n",
        ),
        "settings_section_diagnostics": (
            r'(<string name="settings_notifications_convert_subtitle">.*?</string>\n)',
            r"\1    " + line + "\n",
        ),
        "settings_section_diagnostics_subtitle": (
            r'(<string name="settings_section_diagnostics">.*?</string>\n)',
            r"\1    " + line + "\n",
        ),
        "settings_section_share_subtitle": (
            r'(\s*<string name="settings_section_share">)',
            "    " + line + "\n\\1",
        ),
        "settings_secure_delete_subtitle": (
            r'(<string name="settings_secure_delete">.*?</string>\n)',
            r"\1    " + line + "\n",
        ),
        "settings_max_bitmap_size_subtitle": (
            r'(<string name="settings_max_bitmap_size">.*?</string>\n)',
            r"\1    " + line + "\n",
        ),
        "settings_max_file_size_subtitle": (
            r'(<string name="settings_max_file_size">.*?</string>\n)',
            r"\1    " + line + "\n",
        ),
    }
    if key in inserts:
        pat, repl = inserts[key]
        if re.search(pat, text, re.DOTALL):
            return re.sub(pat, repl, text, count=1, flags=re.DOTALL)
    return text + "\n    " + line + "\n"


def main():
    locales = load_data()
    for folder, strings in locales.items():
        path = RES / folder / "strings.xml"
        if not path.exists():
            print("skip missing", folder)
            continue
        text = path.read_text(encoding="utf-8")
        for key in KEYS:
            if key in strings:
                text = upsert_string(text, key, strings[key])
        path.write_text(text, encoding="utf-8")
        print("updated", folder)


if __name__ == "__main__":
    main()
