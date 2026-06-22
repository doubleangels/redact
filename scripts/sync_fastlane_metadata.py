#!/usr/bin/env python3
"""Write localized Play Store metadata from en-US template."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "fastlane" / "metadata" / "android"

SHORT = {
    "de-DE": "Metadaten entfernen, EXIF scannen und Medien konvertieren — alles auf dem Gerät.",
    "es-ES": "Elimina metadatos ocultos, escanea EXIF y convierte medios, todo en el dispositivo.",
    "fr-FR": "Supprimez les métadonnées, analysez l'EXIF et convertissez vos médias — sur l'appareil.",
    "it-IT": "Rimuovi metadati nascosti, scansiona EXIF e converti i media — tutto sul dispositivo.",
    "pt-BR": "Remova metadados ocultos, escaneie EXIF e converta mídia — tudo no dispositivo.",
    "ru-RU": "Удаляйте метаданные, просматривайте EXIF и конвертируйте медиа — на устройстве.",
    "ja-JP": "隠れたメタデータを削除し、EXIFを確認し、メディアを変換 — すべて端末内で処理。",
    "ko-KR": "숨은 메타데이터 제거, EXIF 스캔, 미디어 변환 — 모두 기기에서 처리.",
    "zh-CN": "移除隐藏元数据、扫描 EXIF、转换媒体 — 全部在设备本地完成。",
    "zh-TW": "移除隱藏中繼資料、掃描 EXIF、轉換媒體 — 全部在裝置本機完成。",
    "hi-IN": "छिपे मेटाडेटा हटाएँ, EXIF स्कैन करें और मीडिया कन्वर्ट करें — सब डिवाइस पर।",
    "ar-SA": "أزل البيانات الوصفية المخفية، افحص EXIF، وحوّل الوسائط — كل ذلك على الجهاز.",
}

FULL = {
    "de-DE": """<b>Redact: Datenschutz- und Metadaten-Entferner</b>

<p>Schützen Sie Ihre Privatsphäre mit Redact — entfernen Sie versteckte EXIF-Metadaten aus Fotos und Videos, prüfen Sie eingebettete Daten vor dem Teilen und konvertieren Sie Formate lokal auf Ihrem Gerät.</p>

<b>Hauptfunktionen:</b>

<ul>
<li><b>Vollständiger Datenschutz:</b><br>
Entfernen Sie GPS-Standort, Gerätedetails, Zeitstempel, Kameraeinstellungen und andere versteckte Metadaten. Videobereinigung per Remux oder vollständigem Transcode (in den Einstellungen wählbar) bei erhaltener Wiedergabequalität.</li>

<li><b>Metadaten-Viewer:</b><br>
Scannen Sie Fotos oder Videos, um organisierte EXIF- und Container-Metadaten vor der Bereinigung zu sehen. Werte kopieren oder Dateien an Bereinigen oder Konvertieren senden.</li>

<li><b>Bereinigen, Scannen, Konvertieren &amp; Teilen:</b><br>
Metadaten in Stapeln bereinigen (bis zu 20 Dateien). Formate ohne Server-Upload konvertieren — danach Bereinigen, wenn auch konvertierte Dateien ohne Metadaten sein sollen. Teilen in Redact aus jeder App; lange Aufträge abbrechen oder bei Teilerfolg fortfahren.</li>

<li><b>Datenschutz zuerst bei Berechtigungen:</b><br>
Ab Android 13 Dateien mit der System-Fotoauswahl ohne breiten Bibliothekszugriff wählen. Optionale Foto-Standortberechtigung zeigt GPS in Scannen. Absturzberichte und Benachrichtigungen sind standardmäßig aus.</li>

<li><b>100 % lokale Verarbeitung:</b><br>
Bereinigung, Scan und Konvertierung laufen auf Ihrem Gerät. Optionale anonymisierte Absturzdiagnose (Sentry) lädt niemals Ihre Medien hoch. Cache manuell leeren oder beim Start alte Dateien entfernen.</li>

<li><b>Open Source &amp; werbefrei:</b><br>
Keine Werbung oder Verhaltens-Tracking. Quellcode auf GitHub zur Community-Prüfung.</li>

<li><b>13 Sprachen:</b><br>
Englisch, Spanisch, Französisch, Deutsch, Italienisch, Portugiesisch, Russisch, Japanisch, Koreanisch, Chinesisch (vereinfacht &amp; traditionell), Hindi und Arabisch.</li>
</ul>

<p>EXIF-Metadaten können GPS-Koordinaten, Gerätemodell und Aufnahmeeinstellungen preisgeben. Redact hilft Ihnen, nach Ihren Bedingungen zu teilen — mit erhaltener Qualität und Dateien auf Ihrem Telefon.</p>

<p><b>So funktioniert es:</b></p>
<p>Medien in Redact auswählen oder aus einer anderen App teilen, auf Metadaten bereinigen tippen und datenschutzsichere Kopien in der Galerie speichern. Beim Teilen entfernt Redact Metadaten, bevor Sie Dateien weiterleiten. Strenges Bereinigen und Teilen entfernen Standortdaten immer.</p>
""",
    "es-ES": """<b>Redact: Privacidad y eliminador de metadatos</b>

<p>Protege tu privacidad con Redact: elimina metadatos EXIF ocultos de fotos y videos, revisa lo embebido antes de compartir y convierte formatos localmente en tu dispositivo.</p>

<b>Funciones principales:</b>

<ul>
<li><b>Protección de privacidad completa:</b><br>
Elimina ubicación GPS, detalles del dispositivo, marcas de tiempo, ajustes de cámara y otros metadatos ocultos. La limpieza de video usa remux o transcodificación completa (configurable) preservando la calidad de reproducción.</li>

<li><b>Visor de metadatos:</b><br>
Escanea fotos o videos para ver EXIF y metadatos del contenedor organizados antes de limpiar. Copia valores o envía archivos a Limpiar o Convertir.</li>

<li><b>Limpiar, escanear, convertir y compartir:</b><br>
Limpia metadatos por lotes (hasta 20 archivos). Convierte formatos sin subir a un servidor; luego usa Limpiar si también quieres quitar metadatos de los convertidos. Comparte a Redact desde cualquier app; cancela trabajos largos o continúa con éxito parcial.</li>

<li><b>Permisos con privacidad primero:</b><br>
En Android 13+, elige archivos con el selector del sistema sin acceso amplio a la biblioteca. El permiso opcional de ubicación de fotos muestra GPS en Escanear. Informes de fallos y notificaciones desactivados por defecto.</li>

<li><b>100 % procesamiento local:</b><br>
Limpieza, escaneo y conversión en tu dispositivo. Diagnósticos de fallos opcionales (Sentry) nunca suben tus medios. Limpia la caché manualmente o elimina archivos antiguos al iniciar.</li>

<li><b>Código abierto y sin anuncios:</b><br>
Sin anuncios ni seguimiento conductual. Código en GitHub para revisión comunitaria.</li>

<li><b>13 idiomas:</b><br>
Inglés, español, francés, alemán, italiano, portugués, ruso, japonés, coreano, chino (simplificado y tradicional), hindi y árabe.</li>
</ul>

<p>Los metadatos EXIF pueden revelar coordenadas GPS, modelo del dispositivo y ajustes de captura. Redact te ayuda a compartir en tus términos, con calidad preservada y archivos en tu teléfono.</p>

<p><b>Cómo funciona:</b></p>
<p>Selecciona medios en Redact o comparte desde otra app, toca Limpiar metadatos y guarda copias seguras en la galería. Al compartir hacia Redact, elimina metadatos antes de reenviar. Limpieza estricta y compartir siempre quitan la ubicación.</p>
""",
    "fr-FR": """<b>Redact : confidentialité et suppression de métadonnées</b>

<p>Protégez votre vie privée avec Redact — supprimez les métadonnées EXIF cachées des photos et vidéos, inspectez ce qui est embarqué avant de partager et convertissez les formats localement sur votre appareil.</p>

<b>Fonctionnalités clés :</b>

<ul>
<li><b>Protection complète de la vie privée :</b><br>
Supprimez la localisation GPS, les détails de l'appareil, les horodatages, les réglages photo et autres métadonnées cachées. Le nettoyage vidéo utilise remux ou transcodage complet (au choix dans les réglages) tout en préservant la qualité de lecture.</li>

<li><b>Visionneuse de métadonnées :</b><br>
Analysez photos ou vidéos pour voir EXIF et métadonnées du conteneur avant nettoyage. Copiez des valeurs ou envoyez vers Nettoyer ou Convertir.</li>

<li><b>Nettoyer, analyser, convertir et partager :</b><br>
Nettoyez les métadonnées par lots (jusqu'à 20 fichiers). Convertissez les formats sans serveur — puis Nettoyer si vous voulez aussi supprimer les métadonnées des fichiers convertis. Partagez vers Redact depuis toute app ; annulez les longues tâches ou continuez en cas de succès partiel.</li>

<li><b>Autorisations axées sur la confidentialité :</b><br>
Sur Android 13+, choisissez des fichiers avec le sélecteur système sans accès large à la bibliothèque. L'autorisation optionnelle de localisation photo affiche le GPS dans Analyser. Rapports de plantage et notifications désactivés par défaut.</li>

<li><b>Traitement 100 % local :</b><br>
Nettoyage, analyse et conversion sur l'appareil. Diagnostics de plantage optionnels (Sentry) n'envoient jamais vos médias. Videz le cache manuellement ou supprimez les anciens fichiers au démarrage.</li>

<li><b>Open source et sans publicité :</b><br>
Pas de pubs ni de suivi comportemental. Code sur GitHub pour revue communautaire.</li>

<li><b>13 langues :</b><br>
Anglais, espagnol, français, allemand, italien, portugais, russe, japonais, coréen, chinois (simplifié et traditionnel), hindi et arabe.</li>
</ul>

<p>Les métadonnées EXIF peuvent révéler coordonnées GPS, modèle d'appareil et réglages de prise de vue. Redact vous aide à partager selon vos conditions — qualité préservée, fichiers sur votre téléphone.</p>

<p><b>Comment ça marche :</b></p>
<p>Sélectionnez des médias dans Redact ou partagez depuis une autre app, appuyez sur Nettoyer les métadonnées et enregistrez des copies sûres dans la galerie. Lors d'un partage entrant, Redact supprime les métadonnées avant de transférer. Nettoyage strict et partage retirent toujours la localisation.</p>
""",
    "it-IT": """<b>Redact: privacy e rimozione metadati</b>

<p>Proteggi la tua privacy con Redact — rimuovi metadati EXIF nascosti da foto e video, ispeziona ciò che è incorporato prima di condividere e converti i formati localmente sul dispositivo.</p>

<b>Funzionalità principali:</b>

<ul>
<li><b>Protezione completa della privacy:</b><br>
Rimuovi posizione GPS, dettagli dispositivo, timestamp, impostazioni fotocamera e altri metadati nascosti. La pulizia video usa remux o transcodifica completa (scelta nelle impostazioni) preservando la qualità di riproduzione.</li>

<li><b>Visualizzatore metadati:</b><br>
Scansiona foto o video per vedere EXIF e metadati del contenitore organizzati prima della pulizia. Copia valori o invia a Pulisci o Converti.</li>

<li><b>Pulisci, scansiona, converti e condividi:</b><br>
Pulisci metadati in batch (fino a 20 file). Converti formati senza caricare su server — poi Pulisci se vuoi rimuovere metadati anche dai file convertiti. Condividi in Redact da qualsiasi app; annulla lavori lunghi o continua con successo parziale.</li>

<li><b>Autorizzazioni privacy-first:</b><br>
Su Android 13+, scegli file con il selettore di sistema senza accesso ampio alla libreria. Il permesso opzionale posizione foto mostra il GPS in Scansione. Segnalazione crash e notifiche disattivate di default.</li>

<li><b>Elaborazione 100% locale:</b><br>
Pulizia, scansione e conversione sul dispositivo. Diagnostica crash opzionale (Sentry) non carica mai i tuoi media. Svuota la cache manualmente o rimuovi file vecchi all'avvio.</li>

<li><b>Open source e senza pubblicità:</b><br>
Niente annunci o tracciamento comportamentale. Codice su GitHub per revisione della community.</li>

<li><b>13 lingue:</b><br>
Inglese, spagnolo, francese, tedesco, italiano, portoghese, russo, giapponese, coreano, cinese (semplificato e tradizionale), hindi e arabo.</li>
</ul>

<p>I metadati EXIF possono rivelare coordinate GPS, modello dispositivo e impostazioni di scatto. Redact ti aiuta a condividere alle tue condizioni — qualità preservata, file sul telefono.</p>

<p><b>Come funziona:</b></p>
<p>Seleziona media in Redact o condividi da un'altra app, tocca Pulisci metadati e salva copie sicure in galleria. In condivisione in ingresso, Redact rimuove i metadati prima di inoltrare. Pulizia rigorosa e condivisione rimuovono sempre la posizione.</p>
""",
    "pt-BR": """<b>Redact: privacidade e removedor de metadados</b>

<p>Proteja sua privacidade com o Redact — remova metadados EXIF ocultos de fotos e vídeos, inspecione o que está embutido antes de compartilhar e converta formatos localmente no dispositivo.</p>

<b>Recursos principais:</b>

<ul>
<li><b>Proteção completa de privacidade:</b><br>
Remova localização GPS, detalhes do dispositivo, carimbos de data/hora, configurações da câmera e outros metadados ocultos. Limpeza de vídeo por remux ou transcodificação completa (nas configurações), preservando a qualidade de reprodução.</li>

<li><b>Visualizador de metadados:</b><br>
Escaneie fotos ou vídeos para ver EXIF e metadados do contêiner organizados antes de limpar. Copie valores ou envie para Limpar ou Converter.</li>

<li><b>Limpar, escanear, converter e compartilhar:</b><br>
Limpe metadados em lotes (até 20 arquivos). Converta formatos sem enviar a um servidor — depois use Limpar se quiser remover metadados dos convertidos. Compartilhe para o Redact de qualquer app; cancele trabalhos longos ou continue com sucesso parcial.</li>

<li><b>Permissões com privacidade em primeiro lugar:</b><br>
No Android 13+, escolha arquivos com o seletor do sistema sem acesso amplo à biblioteca. Permissão opcional de localização de fotos mostra GPS em Escanear. Relatórios de falha e notificações desativados por padrão.</li>

<li><b>Processamento 100% local:</b><br>
Limpeza, escaneamento e conversão no dispositivo. Diagnósticos de falha opcionais (Sentry) nunca enviam sua mídia. Limpe o cache manualmente ou remova arquivos antigos na inicialização.</li>

<li><b>Código aberto e sem anúncios:</b><br>
Sem anúncios ou rastreamento comportamental. Código no GitHub para revisão da comunidade.</li>

<li><b>13 idiomas:</b><br>
Inglês, espanhol, francês, alemão, italiano, português, russo, japonês, coreano, chinês (simplificado e tradicional), hindi e árabe.</li>
</ul>

<p>Metadados EXIF podem revelar coordenadas GPS, modelo do dispositivo e configurações de captura. O Redact ajuda você a compartilhar nos seus termos — com qualidade preservada e arquivos no telefone.</p>

<p><b>Como funciona:</b></p>
<p>Selecione mídia no Redact ou compartilhe de outro app, toque em Limpar metadados e salve cópias seguras na galeria. Ao compartilhar para o Redact, remove metadados antes de encaminhar. Limpeza rigorosa e compartilhamento sempre removem a localização.</p>
""",
    "ru-RU": """<b>Redact: конфиденциальность и удаление метаданных</b>

<p>Защитите конфиденциальность с Redact — удаляйте скрытые EXIF-метаданные из фото и видео, просматривайте встроенные данные перед отправкой и конвертируйте форматы локально на устройстве.</p>

<b>Ключевые возможности:</b>

<ul>
<li><b>Полная защита конфиденциальности:</b><br>
Удаление GPS, сведений об устройстве, меток времени, настроек камеры и других скрытых метаданных. Очистка видео через remux или полный транскод (на выбор в настройках) с сохранением качества воспроизведения.</li>

<li><b>Просмотр метаданных:</b><br>
Сканируйте фото или видео, чтобы увидеть организованные EXIF и метаданные контейнера до очистки. Копируйте значения или отправляйте в Очистку или Конвертацию.</li>

<li><b>Очистка, сканирование, конвертация и отправка:</b><br>
Очистка метаданных пакетами (до 20 файлов). Конвертация форматов без загрузки на сервер — затем Очистка, если нужно убрать метаданные и с конвертированных файлов. Отправка в Redact из любого приложения; отмена длительных задач или продолжение при частичном успехе.</li>

<li><b>Разрешения с приоритетом конфиденциальности:</b><br>
На Android 13+ выбор файлов через системный фотопикер без широкого доступа к библиотеке. Опциональное разрешение геоданных фото показывает GPS в Сканировании. Отчёты о сбоях и уведомления по умолчанию выключены.</li>

<li><b>100% локальная обработка:</b><br>
Очистка, сканирование и конвертация на устройстве. Опциональная анонимная диагностика (Sentry) никогда не загружает ваши медиа. Очистка кэша вручную или удаление старых файлов при запуске.</li>

<li><b>Открытый код и без рекламы:</b><br>
Без рекламы и поведенческого отслеживания. Исходный код на GitHub для проверки сообществом.</li>

<li><b>13 языков:</b><br>
Английский, испанский, французский, немецкий, итальянский, португальский, русский, японский, корейский, китайский (упрощённый и традиционный), хинди и арабский.</li>
</ul>

<p>EXIF-метаданные могут раскрыть GPS-координаты, модель устройства и параметры съёмки. Redact помогает делиться на ваших условиях — с сохранением качества и файлов на телефоне.</p>

<p><b>Как это работает:</b></p>
<p>Выберите медиа в Redact или поделитесь из другого приложения, нажмите Очистить метаданные и сохраните безопасные копии в галерею. При входящей отправке Redact удаляет метаданные перед пересылкой. Строгая очистка и отправка всегда убирают местоположение.</p>
""",
    "ja-JP": """<b>Redact: プライバシーとメタデータ削除</b>

<p>Redactでプライバシーを守りましょう — 写真や動画の隠れたEXIFメタデータを削除し、共有前に埋め込み情報を確認し、端末内で形式を変換できます。</p>

<b>主な機能:</b>

<ul>
<li><b>完全なプライバシー保護:</b><br>
GPS位置、端末情報、タイムスタンプ、カメラ設定などの隠れたメタデータを削除。動画クリーンはリムックスまたは完全トランスコード（設定で選択）で再生品質を維持。</li>

<li><b>メタデータビューア:</b><br>
写真や動画をスキャンして、クリーン前に整理されたEXIFとコンテナメタデータを表示。値をコピーしたり、クリーンや変換に送れます。</li>

<li><b>クリーン、スキャン、変換、共有:</b><br>
最大20ファイルのバッチでメタデータをクリーン。サーバーにアップロードせず形式変換 — 変換後のファイルからもメタデータを除く場合はクリーンを実行。任意のアプリからRedactへ共有、長時間ジョブのキャンセルや部分成功時の継続に対応。</li>

<li><b>プライバシー優先の権限:</b><br>
Android 13以降、システム写真ピッカーで広いライブラリアクセスなしに選択。任意の写真位置権限でスキャンにGPS表示。クラッシュ報告と通知はデフォルトでオフ。</li>

<li><b>100%ローカル処理:</b><br>
クリーン、スキャン、変換は端末内で実行。任意の匿名クラッシュ診断（Sentry）はメディアをアップロードしません。キャッシュは手動削除または起動時に古いファイルを整理。</li>

<li><b>オープンソース＆広告なし:</b><br>
広告や行動追跡なし。GitHubでソース公開。</li>

<li><b>13言語:</b><br>
英語、スペイン語、フランス語、ドイツ語、イタリア語、ポルトガル語、ロシア語、日本語、韓国語、中国語（簡体字・繁体字）、ヒンディー語、アラビア語。</li>
</ul>

<p>EXIFメタデータはGPS座標、端末モデル、撮影設定を明らかにする可能性があります。Redactは品質を保ち、ファイルを端末に残したまま、あなたの条件で共有するのを助けます。</p>

<p><b>使い方:</b></p>
<p>Redactでメディアを選ぶか他アプリから共有し、「メタデータをクリーン」をタップしてギャラリーに安全なコピーを保存。共有受信時は転送前にメタデータを除去。厳格クリーンと共有は常に位置情報を削除します。</p>
""",
    "ko-KR": """<b>Redact: 개인정보 보호 및 메타데이터 제거</b>

<p>Redact로 개인정보를 보호하세요 — 사진과 동영상의 숨은 EXIF 메타데이터를 제거하고, 공유 전에 포함된 정보를 확인하며, 기기에서 로컬로 형식을 변환합니다.</p>

<b>주요 기능:</b>

<ul>
<li><b>완전한 개인정보 보호:</b><br>
GPS 위치, 기기 정보, 타임스탬프, 카메라 설정 등 숨은 메타데이터 제거. 동영상 정리는 리먹스 또는 전체 트랜스코드(설정에서 선택)로 재생 품질 유지.</li>

<li><b>메타데이터 뷰어:</b><br>
사진 또는 동영상을 스캔하여 정리 전 EXIF 및 컨테이너 메타데이터를 확인. 값 복사 또는 정리/변환으로 보내기.</li>

<li><b>정리, 스캔, 변환 및 공유:</b><br>
최대 20개 파일 배치로 메타데이터 정리. 서버 업로드 없이 형식 변환 — 변환된 파일에서도 메타데이터를 제거하려면 정리 실행. 모든 앱에서 Redact로 공유, 긴 작업 취소 또는 부분 성공 시 계속.</li>

<li><b>개인정보 우선 권한:</b><br>
Android 13 이상에서 시스템 사진 선택기로 넓은 라이브러리 접근 없이 선택. 선택적 사진 위치 권한으로 스캔에서 GPS 표시. 충돌 보고 및 알림은 기본 꺼짐.</li>

<li><b>100% 로컬 처리:</b><br>
정리, 스캔, 변환은 기기에서 실행. 선택적 익명 충돌 진단(Sentry)은 미디어를 업로드하지 않음. 캐시 수동 삭제 또는 시작 시 오래된 파일 정리.</li>

<li><b>오픈 소스 및 광고 없음:</b><br>
광고나 행동 추적 없음. GitHub에서 소스 공개.</li>

<li><b>13개 언어:</b><br>
영어, 스페인어, 프랑스어, 독일어, 이탈리아어, 포르투갈어, 러시아어, 일본어, 한국어, 중국어(간체·번체), 힌디어, 아랍어.</li>
</ul>

<p>EXIF 메타데이터는 GPS 좌표, 기기 모델, 촬영 설정을 노출할 수 있습니다. Redact는 품질을 유지하고 파일을 휴대폰에 둔 채 원하는 조건으로 공유하도록 돕습니다.</p>

<p><b>사용 방법:</b></p>
<p>Redact에서 미디어를 선택하거나 다른 앱에서 공유한 뒤 메타데이터 정리를 탭하여 갤러리에 안전한 복사본을 저장합니다. 공유 수신 시 전달 전에 메타데이터를 제거합니다. 엄격 정리와 공유는 항상 위치를 제거합니다.</p>
""",
    "zh-CN": """<b>Redact：隐私与元数据移除</b>

<p>用 Redact 保护隐私 — 移除照片和视频中隐藏的 EXIF 元数据，分享前查看嵌入信息，并在设备本地转换格式。</p>

<b>主要功能：</b>

<ul>
<li><b>完整隐私保护：</b><br>
移除 GPS 位置、设备信息、时间戳、相机设置等隐藏元数据。视频清理支持重封装或完整转码（可在设置中选择），同时保持播放质量。</li>

<li><b>元数据查看器：</b><br>
扫描照片或视频，在清理前查看有序的 EXIF 和容器元数据。可复制数值或发送到清理/转换。</li>

<li><b>清理、扫描、转换与分享：</b><br>
批量清理元数据（最多 20 个文件）。无需上传服务器即可转换格式 — 若也要移除转换后文件的元数据，请再运行清理。从任意应用分享到 Redact；可取消长时间任务或在部分成功时继续。</li>

<li><b>隐私优先的权限：</b><br>
Android 13+ 可用系统照片选择器，无需广泛的媒体库权限。可选的照片位置权限可在扫描中显示 GPS。崩溃报告和通知默认关闭。</li>

<li><b>100% 本地处理：</b><br>
清理、扫描和转换均在设备上完成。可选的匿名崩溃诊断（Sentry）从不上传您的媒体。可手动清理缓存或在启动时删除过期文件。</li>

<li><b>开源且无广告：</b><br>
无广告或行为追踪。源代码在 GitHub 供社区审查。</li>

<li><b>13 种语言：</b><br>
英语、西班牙语、法语、德语、意大利语、葡萄牙语、俄语、日语、韩语、中文（简体与繁体）、印地语和阿拉伯语。</li>
</ul>

<p>EXIF 元数据可能泄露 GPS 坐标、设备型号和拍摄设置。Redact 帮助您在保留质量、文件留在手机上的前提下按自己的方式分享。</p>

<p><b>使用方法：</b></p>
<p>在 Redact 中选择媒体或从其他应用分享，点击清理元数据，将安全副本保存到图库。从其他应用分享入 Redact 时，会在转发前剥离元数据。严格清理和分享路径始终移除位置信息。</p>
""",
    "zh-TW": """<b>Redact：隱私與中繼資料移除</b>

<p>使用 Redact 保護隱私 — 移除照片與影片中隱藏的 EXIF 中繼資料，分享前檢視嵌入資訊，並在裝置本機轉換格式。</p>

<b>主要功能：</b>

<ul>
<li><b>完整隱私保護：</b><br>
移除 GPS 位置、裝置資訊、時間戳記、相機設定等隱藏中繼資料。影片清理支援重封裝或完整轉碼（可在設定中選擇），同時維持播放品質。</li>

<li><b>中繼資料檢視器：</b><br>
掃描照片或影片，在清理前查看有序的 EXIF 與容器中繼資料。可複製數值或傳送至清理/轉換。</li>

<li><b>清理、掃描、轉換與分享：</b><br>
批次清理中繼資料（最多 20 個檔案）。無需上傳伺服器即可轉換格式 — 若也要移除轉換後檔案的中繼資料，請再執行清理。從任意 App 分享至 Redact；可取消長時間工作或在部分成功時繼續。</li>

<li><b>隱私優先的權限：</b><br>
Android 13+ 可使用系統照片選擇器，無需廣泛的媒體庫權限。可選的照片位置權限可在掃描中顯示 GPS。當機回報與通知預設關閉。</li>

<li><b>100% 本機處理：</b><br>
清理、掃描與轉換均在裝置上完成。可選的匿名當機診斷（Sentry）從不上傳您的媒體。可手動清理快取或在啟動時刪除過期檔案。</li>

<li><b>開源且無廣告：</b><br>
無廣告或行為追蹤。原始碼在 GitHub 供社群審查。</li>

<li><b>13 種語言：</b><br>
英語、西班牙語、法語、德語、義大利語、葡萄牙語、俄語、日語、韓語、中文（簡體與繁體）、印地語與阿拉伯語。</li>
</ul>

<p>EXIF 中繼資料可能洩露 GPS 座標、裝置型號與拍攝設定。Redact 協助您在保留品質、檔案留在手機上的前提下，依自己的方式分享。</p>

<p><b>使用方式：</b></p>
<p>在 Redact 中選擇媒體或從其他 App 分享，點擊清理中繼資料，將安全副本儲存至圖庫。從其他 App 分享至 Redact 時，會在轉送前剝除中繼資料。嚴格清理與分享路徑一律移除位置資訊。</p>
""",
    "hi-IN": """<b>Redact: गोपनीयता और मेटाडेटा हटाने वाला</b>

<p>Redact से अपनी गोपनीयता सुरक्षित रखें — फ़ोटो और वीडियो से छिपे EXIF मेटाडेटा हटाएँ, साझा करने से पहले एम्बेडेड जानकारी देखें, और डिवाइस पर ही फ़ॉर्मैट बदलें।</p>

<b>मुख्य विशेषताएँ:</b>

<ul>
<li><b>पूर्ण गोपनीयता सुरक्षा:</b><br>
GPS स्थान, डिवाइस विवरण, टाइमस्टैम्प, कैमरा सेटिंग्स और अन्य छिपे मेटाडेटा हटाएँ। वीडियो सफ़ाई remux या पूर्ण ट्रांसकोड (सेटिंग्स में चुनाव) से प्लेबैक गुणवत्ता बनाए रखती है।</li>

<li><b>मेटाडेटा व्यूअर:</b><br>
सफ़ाई से पहले व्यवस्थित EXIF और कंटेनर मेटाडेटा देखने के लिए फ़ोटो या वीडियो स्कैन करें। मान कॉपी करें या साफ़ करें/कन्वर्ट में भेजें।</li>

<li><b>साफ़ करें, स्कैन, कन्वर्ट और शेयर:</b><br>
बैच में मेटाडेटा साफ़ करें (अधिकतम 20 फ़ाइलें)। सर्वर पर अपलोड किए बिना फ़ॉर्मैट बदलें — कन्वर्ट की गई फ़ाइलों से भी मेटाडेटा हटाना हो तो साफ़ करें चलाएँ। किसी भी ऐप से Redact में शेयर; लंबे कार्य रद्द या आंशिक सफलता पर जारी रखें।</li>

<li><b>गोपनीयता-प्रथम अनुमतियाँ:</b><br>
Android 13+ पर सिस्टम फ़ोटो पिकर से व्यापक लाइब्रेरी एक्सेस के बिना चुनें। वैकल्पिक फ़ोटो स्थान अनुमति स्कैन में GPS दिखाती है। क्रैश रिपोर्टिंग और नोटिफ़िकेशन डिफ़ॉल्ट बंद।</li>

<li><b>100% स्थानीय प्रोसेसिंग:</b><br>
सफ़ाई, स्कैन और कन्वर्शन आपके डिवाइस पर। वैकल्पिक अनाम क्रैश डायग्नोस्टिक (Sentry) आपका मीडिया कभी अपलोड नहीं करता। कैश मैन्युअल साफ़ या स्टार्टअप पर पुरानी फ़ाइलें हटाएँ।</li>

<li><b>ओपन सोर्स और विज्ञापन-मुक्त:</b><br>
कोई विज्ञापन या व्यवहार ट्रैकिंग नहीं। स्रोत कोड GitHub पर समुदाय समीक्षा के लिए।</li>

<li><b>13 भाषाएँ:</b><br>
अंग्रेज़ी, स्पेनिश, फ़्रेंच, जर्मन, इतालवी, पुर्तगाली, रूसी, जापानी, कोरियाई, चीनी (सरलीकृत और पारंपरिक), हिंदी और अरबी।</li>
</ul>

<p>EXIF मेटाडेटा GPS निर्देशांक, डिवाइस मॉडल और कैप्चर सेटिंग्स उजागर कर सकता है। Redact गुणवत्ता बनाए रखते हुए, फ़ाइलें फ़ोन पर रखकर आपकी शर्तों पर साझा करने में मदद करता है।</p>

<p><b>यह कैसे काम करता है:</b></p>
<p>Redact में मीडिया चुनें या किसी अन्य ऐप से साझा करें, मेटाडेटा साफ़ करें टैप करें और गैलरी में सुरक्षित प्रतियाँ सहेजें। शेयर-इन पर Redact आगे भेजने से पहले मेटाडेटा हटाता है। सख्त सफ़ाई और शेयर हमेशा स्थान हटाते हैं।</p>
""",
    "ar-SA": """<b>Redact: الخصوصية وإزالة البيانات الوصفية</b>

<p>احمِ خصوصيتك مع Redact — أزل بيانات EXIF المخفية من الصور والفيديو، وافحص ما هو مضمّن قبل المشاركة، وحوّل الصيغ محلياً على جهازك.</p>

<b>الميزات الرئيسية:</b>

<ul>
<li><b>حماية خصوصية كاملة:</b><br>
أزل موقع GPS وتفاصيل الجهاز والطوابع الزمنية وإعدادات الكاميرا وبيانات وصفية مخفية أخرى. تنظيف الفيديو عبر إعادة التغليف أو التحويل الكامل (اختيارك في الإعدادات) مع الحفاظ على جودة التشغيل.</li>

<li><b>عارض البيانات الوصفية:</b><br>
افحص أي صورة أو فيديو لرؤية EXIF وبيانات الحاوية منظمة قبل التنظيف. انسخ القيم أو أرسل الملف إلى التنظيف أو التحويل.</li>

<li><b>تنظيف، فحص، تحويل ومشاركة:</b><br>
نظّف البيانات الوصفية دفعات (حتى 20 ملفاً). حوّل الصيغ دون رفع إلى خادم — ثم نظّف إذا أردت إزالة البيانات الوصفية من الملفات المحوّلة أيضاً. شارك إلى Redact من أي تطبيق؛ ألغِ المهام الطويلة أو تابع عند النجاح الجزئي.</li>

<li><b>أذونات تُعطي الخصوصية الأولوية:</b><br>
على Android 13+، اختر الملفات بمنتقي الصور دون وصول واسع للمكتبة. إذن موقع الصورة الاختياري يعرض GPS في الفحص. تقارير الأعطال والإشعارات معطّلة افتراضياً.</li>

<li><b>معالجة محلية 100%:</b><br>
التنظيف والفحص والتحويل على جهازك. التشخيص الاختياري للأعطال (Sentry) لا يرفع وسائطك أبداً. امسح الذاكرة المؤقتة يدوياً أو أزل الملفات القديمة عند البدء.</li>

<li><b>مفتوح المصدر وبدون إعلانات:</b><br>
لا إعلانات ولا تتبع سلوكي. الكود المصدري على GitHub لمراجعة المجتمع.</li>

<li><b>13 لغة:</b><br>
الإنجليزية والإسبانية والفرنسية والألمانية والإيطالية والبرتغالية والروسية واليابانية والكورية والصينية (المبسطة والتقليدية) والهندية والعربية.</li>
</ul>

<p>قد تكشف بيانات EXIF الوصفية إحداثيات GPS وطراز الجهاز وإعدادات الالتقاط. يساعدك Redact على المشاركة بشروطك — مع الحفاظ على الجودة وإبقاء ملفاتك على هاتفك.</p>

<p><b>كيف يعمل:</b></p>
<p>اختر الوسائط في Redact أو شارك من تطبيق آخر، اضغط تنظيف البيانات الوصفية واحفظ نسخاً آمناً في المعرض. عند المشاركة الواردة، يزيل Redact البيانات الوصفية قبل إعادة التوجيه. التنظيف الصارم والمشاركة يزيلان الموقع دائماً.</p>
""",
}


def main() -> None:
    for locale, short in SHORT.items():
        (ROOT / locale / "short_description.txt").write_text(short + "\n", encoding="utf-8")
        print(f"Updated {locale}/short_description.txt")
    for locale, full in FULL.items():
        (ROOT / locale / "full_description.txt").write_text(full, encoding="utf-8")
        print(f"Updated {locale}/full_description.txt")


if __name__ == "__main__":
    main()
