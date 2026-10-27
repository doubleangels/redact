"""Frame the raw store screenshots with captions and draw the Google Play feature graphic.

Usage: python scripts/store_graphics.py [--locales "en-US de-DE ..."|all]

Reads build/screenshots/<locale>/*.png (from scripts/screenshots.sh) and writes, per locale:
  build/store/<locale>/phone/1.png .. 6.png   1440x2560 captioned screenshots, in store order
  build/store/<locale>/featureGraphic.png     1024x500

Each image is an HTML page rendered by headless Chrome (or Edge), so every script the listing uses
(Arabic, Devanagari, CJK) is shaped and laid out properly; set CHROME to the browser's path if it is
not found. A locale without raw screenshots gets only a feature graphic, drawn with the en-US Scan
screenshot. Copy the results into fastlane/metadata/android/<locale>/images/ to publish them.
"""
import argparse
import html
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RAW = ROOT / "build" / "screenshots"
OUT = ROOT / "build" / "store"
ICON_SVG = ROOT / "app" / "src" / "main" / "ic_launcher-playstore.svg"

ALL_LOCALES = ["en-US", "ar", "de-DE", "es-ES", "fr-FR", "hi-IN", "it-IT", "ja-JP", "ko-KR",
               "pt-BR", "ru-RU", "zh-CN", "zh-TW"]

# Store order, alternating light and dark: (raw screenshot, dark background, index into CAPTIONS)
SHOTS = [("scan", False, 0), ("clean-dark", True, 1), ("clean", False, 2),
         ("convert-dark", True, 3), ("clean-done", False, 5), ("scan-dark", True, 4)]

# Per locale: six (headline, subline) pairs, one per screen (Scan, Clean result, Clean selection,
# Convert, Scan, Clean result; SHOTS picks the order), then the feature graphic's pair. Headlines
# and sublines are both written as sentences, ending with the language's full stop.
CAPTIONS = {
    "en-US": [
        ("Your photos can reveal where you live.", "Scan shows the hidden GPS location, phone model and time."),
        ("Remove it all in one tap.", "Redact tells you exactly what it removed."),
        ("Clean 20 photos and videos at once.", "Or share them to Redact from any app."),
        ("Convert without uploading anything.", "JPEG, PNG, WebP, HEIC and MP4, stripped and checked."),
        ("Check any file before you send it.", "Every hidden field, in a clear, searchable list."),
        ("Nothing leaves your phone.", "No ads, no tracking and no account. Open source."),
        ("Strip the hidden location from your photos.", "Scan, clean and convert, all on your phone."),
    ],
    "de-DE": [
        ("Deine Fotos können verraten, wo du wohnst.", "Scannen zeigt den versteckten GPS-Standort, das Handymodell und die Uhrzeit."),
        ("Entferne alles mit einem Tipp.", "Redact zeigt dir genau, was entfernt wurde."),
        ("Bereinige 20 Fotos und Videos auf einmal.", "Oder teile sie aus jeder App mit Redact."),
        ("Konvertiere, ohne etwas hochzuladen.", "JPEG, PNG, WebP, HEIC und MP4, bereinigt und geprüft."),
        ("Prüfe jede Datei vor dem Senden.", "Jedes versteckte Feld in einer klaren, durchsuchbaren Liste."),
        ("Nichts verlässt dein Handy.", "Keine Werbung, kein Tracking, kein Konto. Open Source."),
        ("Entferne den versteckten Standort aus deinen Fotos.", "Scannen, bereinigen und konvertieren, alles auf deinem Handy."),
    ],
    "es-ES": [
        ("Tus fotos pueden revelar dónde vives.", "Escanear muestra la ubicación GPS oculta, el modelo del móvil y la hora."),
        ("Elimínalo todo con un toque.", "Redact te dice exactamente qué ha eliminado."),
        ("Limpia 20 fotos y vídeos a la vez.", "O compártelos con Redact desde cualquier app."),
        ("Convierte sin subir nada.", "JPEG, PNG, WebP, HEIC y MP4, limpios y comprobados."),
        ("Revisa cualquier archivo antes de enviarlo.", "Cada campo oculto, en una lista clara y con búsqueda."),
        ("Nada sale de tu móvil.", "Sin anuncios, sin rastreo y sin cuenta. Código abierto."),
        ("Borra la ubicación oculta de tus fotos.", "Escanea, limpia y convierte, todo en tu móvil."),
    ],
    "fr-FR": [
        ("Vos photos peuvent révéler où vous habitez.", "Scanner affiche la position GPS cachée, le modèle du téléphone et l'heure."),
        ("Supprimez tout d'un seul geste.", "Redact vous indique précisément ce qu'il a supprimé."),
        ("Nettoyez 20 photos et vidéos à la fois.", "Ou partagez-les vers Redact depuis n'importe quelle app."),
        ("Convertissez sans rien envoyer en ligne.", "JPEG, PNG, WebP, HEIC et MP4, nettoyés et vérifiés."),
        ("Vérifiez chaque fichier avant de l'envoyer.", "Chaque champ caché, dans une liste claire avec recherche."),
        ("Rien ne quitte votre téléphone.", "Pas de pub, pas de pistage et pas de compte. Open source."),
        ("Effacez la position cachée de vos photos.", "Scanner, nettoyer et convertir, tout sur votre téléphone."),
    ],
    "it-IT": [
        ("Le tue foto possono rivelare dove abiti.", "Scansiona mostra la posizione GPS nascosta, il modello del telefono e l'ora."),
        ("Rimuovi tutto con un tocco.", "Redact ti dice esattamente cosa ha rimosso."),
        ("Pulisci 20 foto e video alla volta.", "Oppure condividili con Redact da qualsiasi app."),
        ("Converti senza caricare nulla.", "JPEG, PNG, WebP, HEIC e MP4, puliti e verificati."),
        ("Controlla ogni file prima di inviarlo.", "Ogni campo nascosto, in un elenco chiaro e ricercabile."),
        ("Niente lascia il tuo telefono.", "Niente pubblicità, tracciamento o account. Open source."),
        ("Rimuovi la posizione nascosta dalle tue foto.", "Scansiona, pulisci e converti, tutto sul telefono."),
    ],
    "pt-BR": [
        ("Suas fotos podem revelar onde você mora.", "Escanear mostra a localização GPS oculta, o modelo do celular e o horário."),
        ("Remova tudo com um toque.", "O Redact mostra exatamente o que removeu."),
        ("Limpe 20 fotos e vídeos de uma vez.", "Ou compartilhe com o Redact de qualquer app."),
        ("Converta sem enviar nada.", "JPEG, PNG, WebP, HEIC e MP4, limpos e verificados."),
        ("Confira qualquer arquivo antes de enviar.", "Cada campo oculto, em uma lista clara e pesquisável."),
        ("Nada sai do seu celular.", "Sem anúncios, sem rastreamento e sem conta. Código aberto."),
        ("Apague a localização oculta das suas fotos.", "Escaneie, limpe e converta, tudo no seu celular."),
    ],
    "ru-RU": [
        ("Ваши фото могут выдать, где вы живёте.", "Сканирование покажет скрытые GPS-координаты, модель телефона и время."),
        ("Удалите всё одним касанием.", "Redact покажет, что именно удалено."),
        ("Очищайте 20 фото и видео за раз.", "Или отправляйте их в Redact из любого приложения."),
        ("Конвертируйте без загрузки в сеть.", "JPEG, PNG, WebP, HEIC и MP4: очищены и проверены."),
        ("Проверьте файл перед отправкой.", "Все скрытые поля в понятном списке с поиском."),
        ("Ничто не покидает ваш телефон.", "Без рекламы, слежки и аккаунта. Открытый код."),
        ("Удалите скрытое местоположение из своих фото.", "Сканирование, очистка и конвертация: всё на телефоне."),
    ],
    "ja-JP": [
        ("写真から住所がわかってしまうかも。", "スキャンで、隠れた GPS 位置情報・機種・日時を表示します。"),
        ("ワンタップでまとめて削除。", "何を削除したかを正確に表示します。"),
        ("写真と動画を20件まとめてクリーン。", "どのアプリからでも Redact に共有できます。"),
        ("アップロードせずに変換。", "JPEG・PNG・WebP・HEIC・MP4 に対応。クリーンして確認済みです。"),
        ("送る前に、どんなファイルもチェック。", "隠れた項目を、見やすく検索できる一覧で表示します。"),
        ("データはスマホから出ません。", "広告・追跡・アカウントなし。オープンソースです。"),
        ("写真に隠れた位置情報を削除。", "スキャン・クリーン・変換を、すべてスマホの中で。"),
    ],
    "ko-KR": [
        ("사진이 사는 곳을 드러낼 수 있습니다.", "스캔으로 숨은 GPS 위치, 휴대전화 모델, 시간을 확인하세요."),
        ("한 번에 모두 지우세요.", "무엇을 지웠는지 정확히 알려 드립니다."),
        ("사진과 동영상 20개를 한 번에 정리하세요.", "어느 앱에서나 Redact로 공유할 수도 있습니다."),
        ("업로드 없이 변환하세요.", "JPEG, PNG, WebP, HEIC, MP4를 지원하며, 정리 후 검사까지 합니다."),
        ("보내기 전에 모든 파일을 확인하세요.", "숨은 항목을 검색 가능한 깔끔한 목록으로 보여 줍니다."),
        ("아무것도 휴대전화를 떠나지 않습니다.", "광고, 추적, 계정이 없습니다. 오픈소스입니다."),
        ("사진 속 숨은 위치 정보를 지우세요.", "스캔, 정리, 변환을 모두 휴대전화에서 합니다."),
    ],
    "zh-CN": [
        ("你的照片可能暴露你的住址。", "扫描即可看到隐藏的 GPS 位置、手机型号和拍摄时间。"),
        ("一键全部清除。", "Redact 会准确告诉你移除了什么。"),
        ("一次清理 20 张照片和视频。", "也可以从任何应用分享给 Redact。"),
        ("无需上传即可转换。", "支持 JPEG、PNG、WebP、HEIC 和 MP4，清理并检查。"),
        ("发送前先检查文件。", "所有隐藏字段，清晰可搜索。"),
        ("数据从不离开你的手机。", "无广告、无跟踪、无需账号。开源。"),
        ("清除照片中隐藏的位置信息。", "扫描、清理、转换，全部在手机上完成。"),
    ],
    "zh-TW": [
        ("你的照片可能洩露你的住址。", "掃描即可看到隱藏的 GPS 位置、手機型號與拍攝時間。"),
        ("一鍵全部清除。", "Redact 會明確告訴你移除了什麼。"),
        ("一次清理 20 張照片與影片。", "也可以從任何應用程式分享給 Redact。"),
        ("不必上傳就能轉換。", "支援 JPEG、PNG、WebP、HEIC 與 MP4，清理並檢查。"),
        ("傳送前先檢查檔案。", "所有隱藏欄位，清楚又可搜尋。"),
        ("資料從不離開你的手機。", "沒有廣告、追蹤或帳號。開放原始碼。"),
        ("清除照片中隱藏的位置資訊。", "掃描、清理、轉換，全部在手機上完成。"),
    ],
    "hi-IN": [
        ("आपकी फ़ोटो बता सकती हैं कि आप कहाँ रहते हैं।", "स्कैन छिपी GPS लोकेशन, फ़ोन मॉडल और समय दिखाता है।"),
        ("एक टैप में सब हटाएँ।", "Redact बताता है कि उसने ठीक-ठीक क्या हटाया।"),
        ("एक बार में 20 फ़ोटो और वीडियो साफ़ करें।", "या किसी भी ऐप से Redact पर शेयर करें।"),
        ("बिना अपलोड किए फ़ॉर्मैट बदलें।", "JPEG, PNG, WebP, HEIC और MP4, साफ़ और जाँचे हुए।"),
        ("भेजने से पहले हर फ़ाइल जाँचें।", "हर छिपा फ़ील्ड, साफ़ और खोजने योग्य सूची में।"),
        ("कुछ भी आपके फ़ोन से बाहर नहीं जाता।", "कोई विज्ञापन, ट्रैकिंग या खाता नहीं। ओपन सोर्स।"),
        ("अपनी फ़ोटो से छिपी लोकेशन हटाएँ।", "स्कैन करें, साफ़ करें, बदलें, सब आपके फ़ोन पर।"),
    ],
    "ar": [
        ("قد تكشف صورك مكان سكنك.", "يعرض المسح موقع GPS المخفي وطراز الهاتف ووقت الالتقاط."),
        ("أزل كل شيء بلمسة واحدة.", "يخبرك Redact بما أزاله بالضبط."),
        ("نظّف 20 صورة وفيديو دفعة واحدة.", "أو شاركها مع Redact من أي تطبيق."),
        ("حوّل الصيغ دون رفع أي شيء.", "JPEG وPNG وWebP وHEIC وMP4، منظّفة ومفحوصة."),
        ("افحص أي ملف قبل إرساله.", "كل حقل مخفي في قائمة واضحة قابلة للبحث."),
        ("لا شيء يغادر هاتفك.", "بلا إعلانات ولا تتبّع ولا حساب. مفتوح المصدر."),
        ("أزل الموقع المخفي من صورك.", "مسح وتنظيف وتحويل، كل ذلك على هاتفك."),
    ],
}

FONTS = ('"Segoe UI Variable Display", "Segoe UI", Roboto, "Noto Sans", "Yu Gothic UI", '
         '"Malgun Gothic", "Microsoft YaHei UI", "Microsoft JhengHei UI", "Nirmala UI", sans-serif')

# Shrinks every .fit element's font until the element fits its max-height.
FIT_SCRIPT = """<script>
for (const el of document.querySelectorAll('.fit')) {
  let size = parseFloat(getComputedStyle(el).fontSize);
  const max = parseFloat(el.dataset.max);
  while (el.scrollHeight > max && size > 10) { size -= 2; el.style.fontSize = size + 'px'; }
}
</script>"""


def page(lang, body, width, height, css):
    direction = "rtl" if lang == "ar" else "ltr"
    return f"""<!doctype html><html lang="{lang}" dir="{direction}"><head><meta charset="utf-8"><style>
html, body {{ margin: 0; width: {width}px; height: {height}px; overflow: hidden; }}
body {{ font-family: {FONTS}; -webkit-font-smoothing: antialiased; }}
h1 {{ text-wrap: balance; }}
p {{ text-wrap: pretty; }}
/* Korean breaks between words, Japanese between phrases, never mid-word. */
:lang(ko) h1, :lang(ko) p {{ word-break: keep-all; }}
:lang(ja) h1, :lang(ja) p {{ word-break: auto-phrase; }}
{css}
</style></head><body>{body}{FIT_SCRIPT}</body></html>"""


def screenshot_page(lang, shot, dark, headline, sub):
    bg = "linear-gradient(180deg, #2b2b2b 0%, #0f0f0f 100%)" if dark else "linear-gradient(180deg, #f4f4f4 0%, #d4d4d4 100%)"
    fg = "#ffffff" if dark else "#141414"
    css = f"""
body {{ background: {bg}; color: {fg}; }}
.cap {{ position: absolute; top: 140px; left: 110px; right: 110px; text-align: center; }}
h1 {{ font-size: 100px; line-height: 1.08; font-weight: 800; letter-spacing: -1.5px; margin: 0; }}
p {{ font-size: 50px; line-height: 1.3; margin: 34px 0 0; opacity: .7; }}
.phone {{ position: absolute; left: 50%; top: 640px; transform: translateX(-50%); width: 800px; padding: 20px;
  border-radius: 104px; background: #0a0a0a; box-shadow: 0 60px 140px rgba(0,0,0,{.6 if dark else .28}),
  0 0 0 3px rgba(255,255,255,{.12 if dark else 0}); }}
.phone img {{ display: block; width: 100%; border-radius: 86px; }}
"""
    body = f"""<div class="cap fit" data-max="450"><h1>{html.escape(headline)}</h1><p>{html.escape(sub)}</p></div>
<div class="phone"><img src="{shot.as_uri()}"></div>"""
    return page(lang, body, 1440, 2560, css)


def feature_page(lang, shot, headline, sub):
    icon = ICON_SVG.read_text(encoding="utf-8")
    css = """
body { background: radial-gradient(circle at 78% 30%, #3a3a3a 0%, #161616 55%, #0c0c0c 100%); color: #fff; }
.wrap { display: flex; height: 500px; align-items: center; padding: 0 56px; gap: 40px; box-sizing: border-box; }
.text { flex: 1; min-width: 0; }
.icon svg { width: 92px; height: 92px; border-radius: 22px; display: block; margin-bottom: 26px;
  box-shadow: 0 10px 30px rgba(0,0,0,.45); }
h1 { font-size: 50px; line-height: 1.08; font-weight: 800; letter-spacing: -.5px; margin: 0; }
p { font-size: 23px; line-height: 1.35; margin: 18px 0 0; color: #c4c4c4; }
.phone { align-self: flex-start; margin-top: 46px; width: 300px; flex: none; padding: 10px; border-radius: 40px;
  background: #050505; box-shadow: 0 30px 70px rgba(0,0,0,.6), 0 0 0 2px rgba(255,255,255,.12); }
.phone img { display: block; width: 100%; border-radius: 31px; }
"""
    body = f"""<div class="wrap"><div class="text"><div class="icon">{icon}</div>
<div class="fit" data-max="250"><h1>{html.escape(headline)}</h1><p>{html.escape(sub)}</p></div></div>
<div class="phone"><img src="{shot.as_uri()}"></div></div>"""
    return page(lang, body, 1024, 500, css)


def find_browser():
    env = os.environ.get("CHROME")
    candidates = [env] if env else []
    candidates += [
        r"C:\Program Files\Google\Chrome\Application\chrome.exe",
        r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
        r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
        "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    ]
    candidates += [shutil.which(n) for n in ("google-chrome", "chromium", "chromium-browser", "chrome", "msedge")]
    for c in candidates:
        if c and os.path.exists(c):
            return c
    sys.exit("No Chrome or Edge found; set CHROME to the browser's path.")


def render(browser, markup, out, width, height, workdir):
    src = Path(workdir) / (out.stem + ".html")
    src.write_text(markup, encoding="utf-8")
    out.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([browser, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--mute-audio",
                    "--allow-file-access-from-files", "--force-device-scale-factor=1",
                    f"--user-data-dir={Path(workdir) / 'profile'}",
                    f"--window-size={width},{height}", f"--screenshot={out}", src.as_uri()],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=120)
    if not out.exists():
        sys.exit(f"The browser did not write {out}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--locales", default="en-US", help='space-separated store locales, or "all"')
    args = parser.parse_args()
    locales = ALL_LOCALES if args.locales.strip() == "all" else args.locales.split()
    browser = find_browser()
    with tempfile.TemporaryDirectory() as work:
        for loc in locales:
            if loc not in CAPTIONS:
                sys.exit(f"No captions for {loc}")
            captions = CAPTIONS[loc]
            raw = RAW / loc
            have_shots = all((raw / f"{name}.png").exists() for name, _, _ in SHOTS)
            dest = OUT / loc
            if have_shots:
                for i, (name, dark, caption) in enumerate(SHOTS, start=1):
                    headline, sub = captions[caption]
                    render(browser, screenshot_page(loc, raw / f"{name}.png", dark, headline, sub),
                           dest / "phone" / f"{i}.png", 1440, 2560, work)
            scan = raw / "scan.png" if (raw / "scan.png").exists() else RAW / "en-US" / "scan.png"
            if not scan.exists():
                sys.exit(f"No Scan screenshot for the {loc} feature graphic; run scripts/screenshots.sh first.")
            headline, sub = captions[6]
            render(browser, feature_page(loc, scan, headline, sub), dest / "featureGraphic.png", 1024, 500, work)
            print(f"{loc}: {'6 screenshots and ' if have_shots else ''}feature graphic -> {dest}")


if __name__ == "__main__":
    main()
