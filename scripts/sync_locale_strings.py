#!/usr/bin/env python3
"""Sync new/updated string keys into locale strings.xml files."""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "res"

LOCALES = {
    "values-de": {
        "settings_permissions_subtitle": "Ab Android 13+ nutzt Redact die System-Dateiauswahl — Downloads, Dateien oder Galerieordner ohne breiten Medienzugriff. Vollständiger Foto-/Videozugriff ist optional. Benachrichtigungen und Netzwerk sind optional.",
        "button_select_media": "Dateien auswählen",
        "clean_status_ready": "Bereit, Dateien zum Bereinigen auszuwählen.",
        "convert_status_ready": "Bereit, Dateien zum Konvertieren auszuwählen.",
        "convert_status_hint": "Dateien zum Konvertieren auswählen.",
        "scan_status_ready": "Wählen Sie eine Datei, um deren Metadaten zu scannen.",
        "media_picker_selection_capped": "Es können höchstens %1$d Dateien gleichzeitig ausgewählt werden.",
        "share_error_partial_success": "%1$d Datei(en) bereinigt; %2$d fehlgeschlagen.",
        "settings_auto_clear_temp_subtitle": "Entfernt Verarbeitungs-Cache-Dateien älter als 24 Stunden beim App-Start.",
        "settings_progress_notifications_subtitle": "Dauerhafte Fortschrittsbenachrichtigung bei langen Aufträgen. Standard aus, bis Sie Benachrichtigungen erlauben aktivieren.",
        "settings_secure_delete_subtitle": "Wie oft temporäre Dateien vor dem Löschen überschrieben werden. Mehr Durchläufe sind langsamer; auf Flash-Speicher ist Wiederherstellung ggf. trotzdem möglich.",
        "settings_permission_partial": "Teilweise",
        "animated_image_warning": "Animierte Bilder werden als einzelnes Standbild gespeichert.",
        "clean_item_failed": "Fehlgeschlagen: %1$s",
        "settings_preserve_location_confirm_title": "GPS in bereinigten Fotos behalten?",
        "settings_preserve_location_confirm_message": "Bereinigte Dateien auf Bereinigen können weiterhin GPS-Koordinaten enthalten. Teilen aus anderen Apps entfernt den Standort immer.\n\nNur aktivieren, wenn Sie den Standort absichtlich behalten möchten.",
        "settings_location_permission_granted": "Foto-Standortberechtigung erteilt.",
        "settings_location_permission_denied": "Foto-Standortberechtigung verweigert.",
        "settings_crash_reporting_unconfigured": "Absturzberichte sind aktiv, aber in diesem Build nicht konfiguriert.",
        "settings_crash_reporting_init_failed": "Absturzberichte konnten nicht gestartet werden. Versuchen Sie es später erneut.",
    },
    "values-es": {
        "settings_permissions_subtitle": "En Android 13+, Redact usa el selector de archivos del sistema: Descargas, Archivos o carpetas de la galería sin acceso amplio a la biblioteca. El acceso completo a fotos/vídeo es opcional. Notificaciones y red son opcionales.",
        "button_select_media": "Seleccionar archivos",
        "clean_status_ready": "Listo para seleccionar archivos para limpiar.",
        "convert_status_ready": "Listo para seleccionar archivos para convertir.",
        "convert_status_hint": "Selecciona archivos para convertir.",
        "scan_status_ready": "Selecciona un archivo para escanear sus metadatos.",
        "media_picker_selection_capped": "Solo se pueden seleccionar %1$d archivos a la vez.",
        "share_error_partial_success": "%1$d archivo(s) limpiado(s); %2$d con error.",
        "settings_auto_clear_temp_subtitle": "Elimina archivos de caché de procesamiento de más de 24 horas al abrir la app.",
        "settings_progress_notifications_subtitle": "Notificación persistente con progreso en tareas largas. Desactivada por defecto hasta que actives Permitir notificaciones.",
        "settings_secure_delete_subtitle": "Cuántas veces se sobrescriben los archivos temporales antes de borrarlos. Más pasadas son más lentas; en almacenamiento flash la recuperación puede seguir siendo posible.",
        "settings_permission_partial": "Parcial",
        "animated_image_warning": "Las imágenes animadas se guardan como un solo fotograma.",
        "clean_item_failed": "Error: %1$s",
        "settings_preserve_location_confirm_title": "¿Conservar GPS en fotos limpiadas?",
        "settings_preserve_location_confirm_message": "Los archivos limpiados en Limpiar pueden seguir conteniendo coordenadas GPS. Compartir desde otras apps siempre elimina la ubicación.\n\nActiva esto solo si quieres conservar la ubicación a propósito.",
        "settings_location_permission_granted": "Permiso de ubicación de fotos concedido.",
        "settings_location_permission_denied": "Permiso de ubicación de fotos denegado.",
        "settings_crash_reporting_unconfigured": "Los informes de fallos están activados pero no configurados en esta compilación.",
        "settings_crash_reporting_init_failed": "No se pudieron iniciar los informes de fallos. Inténtalo más tarde.",
    },
    "values-fr": {
        "settings_permissions_subtitle": "Sur Android 13+, le sélecteur système fonctionne sans accès large à la bibliothèque. L'accès complet aide Nettoyer et Convertir à lire les fichiers existants. Notifications et réseau sont optionnels.",
        "share_error_partial_success": "%1$d fichier(s) nettoyé(s) ; %2$d en échec.",
        "settings_auto_clear_temp_subtitle": "Supprime les fichiers cache de traitement de plus de 24 heures à l'ouverture de l'app.",
        "settings_progress_notifications_subtitle": "Notification persistante avec progression pour les longues tâches. Désactivée par défaut jusqu'à activation des notifications.",
        "settings_permission_partial": "Partiel",
        "scan_status_ready": "Sélectionnez une photo ou une vidéo pour analyser ses métadonnées.",
        "animated_image_warning": "Les images animées sont enregistrées comme une seule image fixe.",
        "clean_item_failed": "Échec : %1$s",
        "settings_preserve_location_confirm_title": "Conserver le GPS sur les photos nettoyées ?",
        "settings_preserve_location_confirm_message": "Les fichiers nettoyés dans Nettoyer peuvent encore contenir des coordonnées GPS. Le partage depuis d'autres apps supprime toujours la position.\n\nActivez ceci uniquement si vous souhaitez conserver la position volontairement.",
        "settings_location_permission_granted": "Autorisation de localisation photo accordée.",
        "settings_location_permission_denied": "Autorisation de localisation photo refusée.",
        "settings_crash_reporting_unconfigured": "Les rapports de plantage sont activés mais non configurés sur cette version.",
        "settings_crash_reporting_init_failed": "Impossible de démarrer les rapports de plantage. Réessayez plus tard.",
        "settings_secure_delete_subtitle": "Nombre de passes d'écrasement des fichiers temporaires avant suppression. Plus de passes = plus lent ; sur stockage flash, une récupération reste possible.",
    },
    "values-it": {
        "settings_permissions_subtitle": "Su Android 13+, il selettore di sistema funziona senza accesso ampio alla libreria. L'accesso completo aiuta Pulisci e Converti a leggere i file esistenti. Notifiche e rete sono opzionali.",
        "share_error_partial_success": "%1$d file puliti; %2$d non riusciti.",
        "settings_auto_clear_temp_subtitle": "Rimuove file cache di elaborazione più vecchi di 24 ore all'apertura dell'app.",
        "settings_progress_notifications_subtitle": "Notifica persistente con avanzamento per lavori lunghi. Disattivata di default finché non abiliti le notifiche.",
        "settings_permission_partial": "Parziale",
        "scan_status_ready": "Seleziona una foto o un video per analizzarne i metadati.",
        "animated_image_warning": "Le immagini animate vengono salvate come un singolo fotogramma.",
        "clean_item_failed": "Non riuscito: %1$s",
        "settings_preserve_location_confirm_title": "Mantenere il GPS sulle foto pulite?",
        "settings_preserve_location_confirm_message": "I file puliti in Pulisci possono ancora contenere coordinate GPS. La condivisione da altre app rimuove sempre la posizione.\n\nAttiva solo se vuoi mantenere la posizione intenzionalmente.",
        "settings_location_permission_granted": "Autorizzazione posizione foto concessa.",
        "settings_location_permission_denied": "Autorizzazione posizione foto negata.",
        "settings_crash_reporting_unconfigured": "Segnalazione crash attiva ma non configurata in questa build.",
        "settings_crash_reporting_init_failed": "Impossibile avviare la segnalazione crash. Riprova più tardi.",
        "settings_secure_delete_subtitle": "Quante volte sovrascrivere i file temporanei prima dell'eliminazione. Più passaggi = più lento; su memoria flash il recupero può restare possibile.",
    },
    "values-pt": {
        "settings_permissions_subtitle": "No Android 13+, o seletor do sistema funciona sem acesso amplo à biblioteca. Acesso total ajuda Limpar e Converter a ler arquivos existentes. Notificações e rede são opcionais.",
        "share_error_partial_success": "%1$d arquivo(s) limpo(s); %2$d falharam.",
        "settings_auto_clear_temp_subtitle": "Remove arquivos de cache de processamento com mais de 24 horas ao abrir o app.",
        "settings_progress_notifications_subtitle": "Notificação persistente com progresso em tarefas longas. Desativada por padrão até você ativar Permitir notificações.",
        "settings_permission_partial": "Parcial",
        "scan_status_ready": "Selecione uma foto ou vídeo para analisar os metadados.",
        "animated_image_warning": "Imagens animadas são salvas como um único quadro.",
        "clean_item_failed": "Falhou: %1$s",
        "settings_preserve_location_confirm_title": "Manter GPS em fotos limpas?",
        "settings_preserve_location_confirm_message": "Arquivos limpos em Limpar podem ainda conter coordenadas GPS. Compartilhar de outros apps sempre remove a localização.\n\nAtive apenas se quiser manter a localização de propósito.",
        "settings_location_permission_granted": "Permissão de localização de fotos concedida.",
        "settings_location_permission_denied": "Permissão de localização de fotos negada.",
        "settings_crash_reporting_unconfigured": "Relatórios de falha ativados, mas não configurados nesta compilação.",
        "settings_crash_reporting_init_failed": "Não foi possível iniciar relatórios de falha. Tente novamente mais tarde.",
        "settings_secure_delete_subtitle": "Quantas vezes sobrescrever arquivos temporários antes de excluir. Mais passagens = mais lento; em armazenamento flash a recuperação ainda pode ser possível.",
    },
    "values-ru": {
        "settings_permissions_subtitle": "На Android 13+ системный выбор фото работает без широкого доступа к медиатеке. Полный доступ помогает Очистке и Конвертации читать существующие файлы. Уведомления и сеть необязательны.",
        "share_error_partial_success": "Очищено %1$d файл(ов); ошибка: %2$d.",
        "settings_auto_clear_temp_subtitle": "Удаляет файлы кэша обработки старше 24 часов при запуске приложения.",
        "settings_progress_notifications_subtitle": "Постоянное уведомление о прогрессе длительных задач. По умолчанию выкл., пока не включите уведомления.",
        "settings_permission_partial": "Частично",
        "scan_status_ready": "Выберите фото или видео для просмотра метаданных.",
        "animated_image_warning": "Анимированные изображения сохраняются как один кадр.",
        "clean_item_failed": "Ошибка: %1$s",
        "settings_preserve_location_confirm_title": "Сохранять GPS на очищенных фото?",
        "settings_preserve_location_confirm_message": "Очищенные файлы могут содержать GPS-координаты. При отправке из других приложений местоположение всегда удаляется.\n\nВключайте только если намеренно хотите сохранить местоположение.",
        "settings_location_permission_granted": "Разрешение на геоданные фото предоставлено.",
        "settings_location_permission_denied": "Разрешение на геоданные фото отклонено.",
        "settings_crash_reporting_unconfigured": "Отчёты о сбоях включены, но не настроены в этой сборке.",
        "settings_crash_reporting_init_failed": "Не удалось запустить отчёты о сбоях. Попробуйте позже.",
        "settings_secure_delete_subtitle": "Сколько раз перезаписывать временные файлы перед удалением. Больше проходов — медленнее; на flash-памяти восстановление всё равно возможно.",
    },
    "values-ja": {
        "settings_permissions_subtitle": "Android 13以降は、システムの写真ピッカーでライブラリへの広いアクセスなしに選択できます。フルアクセスはクリーンと変換で既存ファイルを読むのに役立ちます。通知とネットワークは任意です。",
        "share_error_partial_success": "%1$d 件をクリーン化、%2$d 件が失敗しました。",
        "settings_auto_clear_temp_subtitle": "アプリ起動時に24時間より古い処理キャッシュを削除します。",
        "settings_progress_notifications_subtitle": "長時間ジョブの進行を表示する常駐通知。通知を許可するまでデフォルトでオフです。",
        "settings_permission_partial": "一部",
        "scan_status_ready": "写真または動画を選択してメタデータをスキャンします。",
        "animated_image_warning": "アニメーション画像は静止画1枚として保存されます。",
        "clean_item_failed": "失敗: %1$s",
        "settings_preserve_location_confirm_title": "クリーン後の写真にGPSを残しますか？",
        "settings_preserve_location_confirm_message": "クリーンタブの出力にGPS座標が残る場合があります。他アプリからの共有では常に位置情報を削除します。\n\n意図的に残す場合のみオンにしてください。",
        "settings_location_permission_granted": "写真の位置情報の権限が許可されました。",
        "settings_location_permission_denied": "写真の位置情報の権限が拒否されました。",
        "settings_crash_reporting_unconfigured": "クラッシュ報告は有効ですが、このビルドでは未設定です。",
        "settings_crash_reporting_init_failed": "クラッシュ報告を開始できませんでした。後でもう一度お試しください。",
        "settings_secure_delete_subtitle": "削除前に一時ファイルを上書きする回数。回数が多いほど遅くなります。フラッシュストレージでは復元が可能な場合があります。",
    },
    "values-ko": {
        "settings_permissions_subtitle": "Android 13 이상에서는 시스템 사진 선택기로 넓은 미디어 접근 없이 선택할 수 있습니다. 전체 접근은 정리 및 변환에서 기존 갤러리 파일 읽기에 도움이 됩니다. 알림과 네트워크는 선택 사항입니다.",
        "share_error_partial_success": "%1$d개 정리됨, %2$d개 실패.",
        "settings_auto_clear_temp_subtitle": "앱을 열 때 24시간보다 오래된 처리 캐시 파일을 제거합니다.",
        "settings_progress_notifications_subtitle": "긴 작업 중 진행 상황을 보여주는 지속 알림. 알림 허용을 켤 때까지 기본적으로 꺼져 있습니다.",
        "settings_permission_partial": "부분",
        "scan_status_ready": "사진 또는 동영상을 선택하여 메타데이터를 스캔하세요.",
        "animated_image_warning": "애니메이션 이미지는 단일 정지 프레임으로 저장됩니다.",
        "clean_item_failed": "실패: %1$s",
        "settings_preserve_location_confirm_title": "정리된 사진에 GPS를 유지할까요?",
        "settings_preserve_location_confirm_message": "정리 탭의 파일에 GPS 좌표가 남을 수 있습니다. 다른 앱에서 공유할 때는 항상 위치가 제거됩니다.\n\n의도적으로 유지하려는 경우에만 켜세요.",
        "settings_location_permission_granted": "사진 위치 권한이 허용되었습니다.",
        "settings_location_permission_denied": "사진 위치 권한이 거부되었습니다.",
        "settings_crash_reporting_unconfigured": "충돌 보고가 켜져 있지만 이 빌드에서는 구성되지 않았습니다.",
        "settings_crash_reporting_init_failed": "충돌 보고를 시작할 수 없습니다. 나중에 다시 시도하세요.",
        "settings_secure_delete_subtitle": "삭제 전 임시 파일을 덮어쓰는 횟수. 횟수가 많을수록 느리며, 플래시 저장소에서는 복구가 가능할 수 있습니다.",
    },
    "values-zh-rCN": {
        "settings_permissions_subtitle": "Android 13+ 上，系统照片选择器无需广泛的媒体库权限即可选文件。完整访问有助于清理和转换读取已有图库文件。通知和网络为可选。",
        "share_error_partial_success": "已清理 %1$d 个文件；%2$d 个失败。",
        "settings_auto_clear_temp_subtitle": "打开应用时删除超过 24 小时的处理缓存文件。",
        "settings_progress_notifications_subtitle": "长时间任务期间显示进度的持续通知。默认关闭，需先开启允许通知。",
        "settings_permission_partial": "部分",
        "scan_status_ready": "选择照片或视频以扫描其元数据。",
        "animated_image_warning": "动画图像将保存为单帧静态图。",
        "clean_item_failed": "失败：%1$s",
        "settings_preserve_location_confirm_title": "在清理后的照片中保留 GPS？",
        "settings_preserve_location_confirm_message": "在清理标签页清理的文件可能仍含 GPS 坐标。从其他应用分享时始终会移除位置信息。\n\n仅在您有意保留位置时才开启。",
        "settings_location_permission_granted": "已授予照片位置权限。",
        "settings_location_permission_denied": "已拒绝照片位置权限。",
        "settings_crash_reporting_unconfigured": "已启用崩溃报告，但此版本未配置。",
        "settings_crash_reporting_init_failed": "无法启动崩溃报告，请稍后重试。",
        "settings_secure_delete_subtitle": "删除前覆写临时文件的次数。次数越多越慢；在闪存上仍可能恢复数据。",
    },
    "values-zh-rTW": {
        "settings_permissions_subtitle": "Android 13+ 上，系統照片選擇器無需廣泛的媒體庫權限即可選檔案。完整存取有助於清理與轉換讀取既有圖庫檔案。通知與網路為選用。",
        "share_error_partial_success": "已清理 %1$d 個檔案；%2$d 個失敗。",
        "settings_auto_clear_temp_subtitle": "開啟應用程式時刪除超過 24 小時的處理快取檔案。",
        "settings_progress_notifications_subtitle": "長時間工作期間顯示進度的持續通知。預設關閉，需先開啟允許通知。",
        "settings_permission_partial": "部分",
        "scan_status_ready": "選擇照片或影片以掃描其中繼資料。",
        "animated_image_warning": "動畫圖像將儲存為單一靜止畫面。",
        "clean_item_failed": "失敗：%1$s",
        "settings_preserve_location_confirm_title": "在清理後的照片中保留 GPS？",
        "settings_preserve_location_confirm_message": "在清理分頁清理的檔案可能仍含 GPS 座標。從其他 App 分享時一律會移除位置資訊。\n\n僅在您有意保留位置時才開啟。",
        "settings_location_permission_granted": "已授予照片位置權限。",
        "settings_location_permission_denied": "已拒絕照片位置權限。",
        "settings_crash_reporting_unconfigured": "已啟用當機回報，但此版本未設定。",
        "settings_crash_reporting_init_failed": "無法啟動當機回報，請稍後再試。",
        "settings_secure_delete_subtitle": "刪除前覆寫暫存檔的次數。次數越多越慢；在快閃記憶體上仍可能復原資料。",
    },
    "values-hi": {
        "settings_permissions_subtitle": "Android 13+ पर सिस्टम फ़ोटो पिकर बिना व्यापक लाइब्रेरी एक्सेस के काम करता है। पूर्ण एक्सेस साफ़ करें और कन्वर्ट में मौजूदा गैलरी फ़ाइलें पढ़ने में मदद करता है। नोटिफ़िकेशन और नेटवर्क वैकल्पिक हैं।",
        "share_error_partial_success": "%1$d फ़ाइल(ें) साफ़; %2$d विफल।",
        "settings_auto_clear_temp_subtitle": "ऐप खोलने पर 24 घंटे से पुरानी प्रोसेसिंग कैश फ़ाइलें हटाता है।",
        "settings_progress_notifications_subtitle": "लंबे कार्यों के दौरान प्रगति वाली स्थायी सूचना। नोटिफ़िकेशन अनुमति चालू होने तक डिफ़ॉल्ट बंद।",
        "settings_permission_partial": "आंशिक",
        "scan_status_ready": "मेटाडेटा स्कैन करने के लिए फ़ोटो या वीडियो चुनें।",
        "animated_image_warning": "एनिमेटेड छवियाँ एक स्थिर फ़्रेम के रूप में सहेजी जाती हैं।",
        "clean_item_failed": "विफल: %1$s",
        "settings_preserve_location_confirm_title": "साफ़ की गई फ़ोटो में GPS रखें?",
        "settings_preserve_location_confirm_message": "साफ़ करें टैब की फ़ाइलों में GPS रह सकता है। अन्य ऐप्स से शेयर करने पर स्थान हमेशा हटाया जाता है।\n\nकेवल तभी चालू करें जब आप जानबूझकर स्थान रखना चाहते हों।",
        "settings_location_permission_granted": "फ़ोटो स्थान अनुमति दी गई।",
        "settings_location_permission_denied": "फ़ोटो स्थान अनुमति अस्वीकृत।",
        "settings_crash_reporting_unconfigured": "क्रैश रिपोर्टिंग चालू है लेकिन इस बिल्ड में कॉन्फ़िगर नहीं है।",
        "settings_crash_reporting_init_failed": "क्रैश रिपोर्टिंग शुरू नहीं हो सकी। बाद में पुनः प्रयास करें।",
        "settings_secure_delete_subtitle": "हटाने से पहले अस्थायी फ़ाइलें कितनी बार ओवरराइट हों। अधिक पास = धीमा; फ़्लैश स्टोरेज पर पुनर्प्राप्ति संभव हो सकती है।",
    },
    "values-ar": {
        "settings_permissions_subtitle": "على Android 13+، يعمل منتقي الصور دون وصول واسع للمكتبة. الوصول الكامل يساعد التنظيف والتحويل في قراءة ملفات المعرض. الإشعارات والشبكة اختيارية.",
        "share_error_partial_success": "تم تنظيف %1$d ملف(ات)؛ فشل %2$d.",
        "settings_auto_clear_temp_subtitle": "يزيل ملفات ذاكرة المعالجة الأقدم من 24 ساعة عند فتح التطبيق.",
        "settings_progress_notifications_subtitle": "إشعار دائم يعرض التقدم في المهام الطويلة. معطّل افتراضياً حتى تفعّل السماح بالإشعارات.",
        "settings_permission_partial": "جزئي",
        "scan_status_ready": "اختر صورة أو فيديو لفحص بياناته الوصفية.",
        "animated_image_warning": "تُحفظ الصور المتحركة كإطار ثابت واحد.",
        "clean_item_failed": "فشل: %1$s",
        "settings_preserve_location_confirm_title": "الإبقاء على GPS في الصور المنظفة؟",
        "settings_preserve_location_confirm_message": "قد تحتوي الملفات المنظفة في تبويب التنظيف على إحداثيات GPS. المشاركة من تطبيقات أخرى تزيل الموقع دائماً.\n\nفعّل هذا فقط إذا أردت الإبقاء على الموقع عمداً.",
        "settings_location_permission_granted": "تم منح إذن موقع الصورة.",
        "settings_location_permission_denied": "تم رفض إذن موقع الصورة.",
        "settings_crash_reporting_unconfigured": "تقارير الأعطال مفعّلة لكن غير مهيأة في هذا الإصدار.",
        "settings_crash_reporting_init_failed": "تعذر بدء تقارير الأعطال. حاول لاحقاً.",
        "settings_secure_delete_subtitle": "عدد مرات الكتابة فوق الملفات المؤقتة قبل الحذف. المزيد أبطأ؛ على التخزين الفلاشي قد يبقى الاسترداد ممكناً.",
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
    for folder, strings in LOCALES.items():
        path = ROOT / folder / "strings.xml"
        if path.exists():
            upsert_strings(path, strings)
        else:
            print(f"Missing {path}")


if __name__ == "__main__":
    main()
