#!/usr/bin/env python3
"""Sync document-picker string updates into locale strings.xml files."""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "res"

UPDATES = {
    "values-es": {
        "settings_permissions_subtitle": "En Android 13+, Redact usa el selector de archivos del sistema: Descargas, Archivos o carpetas de la galería sin acceso amplio a la biblioteca. El acceso completo a fotos/vídeo es opcional. Notificaciones y red son opcionales.",
        "button_select_media": "Seleccionar archivos",
        "clean_status_ready": "Listo para seleccionar archivos para limpiar.",
        "convert_status_ready": "Listo para seleccionar archivos para convertir.",
        "convert_status_hint": "Selecciona archivos para convertir.",
        "scan_status_ready": "Selecciona un archivo para escanear sus metadatos.",
        "media_picker_selection_capped": "Solo se pueden seleccionar %1$d archivos a la vez.",
    },
    "values-fr": {
        "settings_permissions_subtitle": "Sur Android 13+, Redact utilise le sélecteur de fichiers système : Téléchargements, Fichiers ou dossiers galerie sans accès large à la bibliothèque. L'accès photo/vidéo complet est optionnel. Notifications et réseau sont optionnels.",
        "button_select_media": "Sélectionner des fichiers",
        "clean_status_ready": "Prêt à sélectionner des fichiers à nettoyer.",
        "convert_status_ready": "Prêt à sélectionner des fichiers à convertir.",
        "convert_status_hint": "Sélectionnez des fichiers à convertir.",
        "scan_status_ready": "Sélectionnez un fichier pour analyser ses métadonnées.",
        "media_picker_selection_capped": "Seuls %1$d fichiers peuvent être sélectionnés à la fois.",
    },
    "values-it": {
        "settings_permissions_subtitle": "Su Android 13+, Redact usa il selettore file di sistema: Download, File o cartelle galleria senza accesso ampio alla libreria. L'accesso completo a foto/video è opzionale. Notifiche e rete sono opzionali.",
        "button_select_media": "Seleziona file",
        "clean_status_ready": "Pronto per selezionare file da pulire.",
        "convert_status_ready": "Pronto per selezionare file da convertire.",
        "convert_status_hint": "Seleziona file da convertire.",
        "scan_status_ready": "Seleziona un file per analizzarne i metadati.",
        "media_picker_selection_capped": "È possibile selezionare al massimo %1$d file alla volta.",
    },
    "values-pt": {
        "settings_permissions_subtitle": "No Android 13+, o Redact usa o seletor de arquivos do sistema: Downloads, Arquivos ou pastas da galeria sem acesso amplo à biblioteca. Acesso total a fotos/vídeo é opcional. Notificações e rede são opcionais.",
        "button_select_media": "Selecionar arquivos",
        "clean_status_ready": "Pronto para selecionar arquivos para limpar.",
        "convert_status_ready": "Pronto para selecionar arquivos para converter.",
        "convert_status_hint": "Selecione arquivos para converter.",
        "scan_status_ready": "Selecione um arquivo para analisar os metadados.",
        "media_picker_selection_capped": "Somente %1$d arquivos podem ser selecionados por vez.",
    },
    "values-ru": {
        "settings_permissions_subtitle": "На Android 13+ Redact использует системный выбор файлов — «Загрузки», «Файлы» или папки галереи без широкого доступа к медиатеке. Полный доступ к фото/видео необязателен. Уведомления и сеть необязательны.",
        "button_select_media": "Выбрать файлы",
        "clean_status_ready": "Готово к выбору файлов для очистки.",
        "convert_status_ready": "Готово к выбору файлов для конвертации.",
        "convert_status_hint": "Выберите файлы для конвертации.",
        "scan_status_ready": "Выберите файл для просмотра метаданных.",
        "media_picker_selection_capped": "За раз можно выбрать не более %1$d файлов.",
    },
    "values-ja": {
        "settings_permissions_subtitle": "Android 13以降、Redactはシステムのファイル選択を使用します。ダウンロード、ファイル、ギャラリーフォルダを広いライブラリアクセスなしで参照できます。写真/動画へのフルアクセスは任意です。通知とネットワークも任意です。",
        "button_select_media": "ファイルを選択",
        "clean_status_ready": "クリーンするファイルを選択する準備ができました。",
        "convert_status_ready": "変換するファイルを選択する準備ができました。",
        "convert_status_hint": "変換するファイルを選択してください。",
        "scan_status_ready": "メタデータをスキャンするファイルを選択してください。",
        "media_picker_selection_capped": "一度に選択できるのは %1$d ファイルまでです。",
    },
    "values-ko": {
        "settings_permissions_subtitle": "Android 13 이상에서 Redact는 시스템 파일 선택기를 사용합니다. 다운로드, 파일 또는 갤러리 폴더를 넓은 라이브러리 접근 없이 탐색할 수 있습니다. 전체 사진/동영상 접근은 선택 사항입니다. 알림과 네트워크도 선택 사항입니다.",
        "button_select_media": "파일 선택",
        "clean_status_ready": "정리할 파일을 선택할 준비가 되었습니다.",
        "convert_status_ready": "변환할 파일을 선택할 준비가 되었습니다.",
        "convert_status_hint": "변환할 파일을 선택하세요.",
        "scan_status_ready": "메타데이터를 스캔할 파일을 선택하세요.",
        "media_picker_selection_capped": "한 번에 %1$d개 파일만 선택할 수 있습니다.",
    },
    "values-zh-rCN": {
        "settings_permissions_subtitle": "Android 13+ 上，Redact 使用系统文件选择器，可浏览下载、文件或图库文件夹，无需广泛的媒体库权限。完整的照片/视频访问为可选。通知和网络为可选。",
        "button_select_media": "选择文件",
        "clean_status_ready": "准备选择要清理的文件。",
        "convert_status_ready": "准备选择要转换的文件。",
        "convert_status_hint": "选择要转换的文件。",
        "scan_status_ready": "选择文件以扫描其元数据。",
        "media_picker_selection_capped": "一次最多只能选择 %1$d 个文件。",
    },
    "values-zh-rTW": {
        "settings_permissions_subtitle": "Android 13+ 上，Redact 使用系統檔案選擇器，可瀏覽下載、檔案或圖庫資料夾，無需廣泛的媒體庫權限。完整的照片/影片存取為選用。通知與網路為選用。",
        "button_select_media": "選擇檔案",
        "clean_status_ready": "準備選擇要清理的檔案。",
        "convert_status_ready": "準備選擇要轉換的檔案。",
        "convert_status_hint": "選擇要轉換的檔案。",
        "scan_status_ready": "選擇檔案以掃描其中繼資料。",
        "media_picker_selection_capped": "一次最多只能選擇 %1$d 個檔案。",
    },
    "values-hi": {
        "settings_permissions_subtitle": "Android 13+ पर Redact सिस्टम फ़ाइल पिकर का उपयोग करता है — डाउनलोड, फ़ाइलें या गैलरी फ़ोल्डर व्यापक लाइब्रेरी एक्सेस के बिना। पूर्ण फ़ोटो/वीडियो एक्सेस वैकल्पिक है। नोटिफ़िकेशन और नेटवर्क वैकल्पिक हैं।",
        "button_select_media": "फ़ाइलें चुनें",
        "clean_status_ready": "साफ़ करने के लिए फ़ाइलें चुनने के लिए तैयार।",
        "convert_status_ready": "कन्वर्ट करने के लिए फ़ाइलें चुनने के लिए तैयार।",
        "convert_status_hint": "कन्वर्ट करने के लिए फ़ाइलें चुनें।",
        "scan_status_ready": "मेटाडेटा स्कैन करने के लिए एक फ़ाइल चुनें।",
        "media_picker_selection_capped": "एक बार में अधिकतम %1$d फ़ाइलें चुनी जा सकती हैं।",
    },
    "values-ar": {
        "settings_permissions_subtitle": "على Android 13+، يستخدم Redact منتقي ملفات النظام — التنزيلات أو الملفات أو مجلدات المعرض دون وصول واسع للمكتبة. الوصول الكامل للصور/الفيديو اختياري. الإشعارات والشبكة اختيارية.",
        "button_select_media": "تحديد الملفات",
        "clean_status_ready": "جاهز لتحديد الملفات للتنظيف.",
        "convert_status_ready": "جاهز لتحديد الملفات للتحويل.",
        "convert_status_hint": "حدد الملفات للتحويل.",
        "scan_status_ready": "اختر ملفاً لفحص بياناته الوصفية.",
        "media_picker_selection_capped": "يمكن تحديد %1$d ملفات فقط في المرة الواحدة.",
    },
}


def xml_escape(value: str) -> str:
    return (
        value.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("'", "\\'")
        .replace('"', "&quot;")
    )


def upsert_strings(path: Path, strings: dict) -> None:
    text = path.read_text(encoding="utf-8")
    for key, value in strings.items():
        escaped = xml_escape(value)
        pattern = rf'    <string name="{re.escape(key)}">.*?</string>\n'
        replacement = f'    <string name="{key}">{escaped}</string>\n'
        if re.search(pattern, text, flags=re.DOTALL):
            text = re.sub(pattern, replacement, text, count=1)
        else:
            text = text.replace("</resources>", replacement + "</resources>")
    path.write_text(text, encoding="utf-8")
    print(f"Updated {path}")


def main() -> None:
    for folder, strings in UPDATES.items():
        path = ROOT / folder / "strings.xml"
        if path.exists():
            upsert_strings(path, strings)


if __name__ == "__main__":
    main()
