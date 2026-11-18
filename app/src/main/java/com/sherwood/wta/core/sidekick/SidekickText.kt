package com.sherwood.wta.core.sidekick

import com.sherwood.wta.core.i18n.AppLanguage
import com.sherwood.wta.core.i18n.Strings

/**
 * Sidekick-only strings.
 *
 * They live with the feature rather than in `core/i18n` so the assistant can grow without
 * touching the shared string objects, and so the whole overlay stays inside its own
 * package. The ten-language `when (Strings.lang)` shape is the same one the shared strings
 * use — including the rule that a block never falls back to `else` for a real language.
 */
object SidekickText {

    val webResearch: String get() = when (Strings.lang) {
        AppLanguage.CHINESE -> "联网检索"
        AppLanguage.ENGLISH -> "Web research"
        AppLanguage.ARABIC -> "البحث في الويب"
        AppLanguage.PORTUGUESE -> "Pesquisa na web"
        AppLanguage.SPANISH -> "Búsqueda web"
        AppLanguage.FRENCH -> "Recherche web"
        AppLanguage.GERMAN -> "Websuche"
        AppLanguage.RUSSIAN -> "Поиск в интернете"
        AppLanguage.JAPANESE -> "ウェブ検索"
        AppLanguage.KOREAN -> "웹 검색"
    }

    val webResearchDesc: String get() = when (Strings.lang) {
        AppLanguage.CHINESE -> "允许助手搜索网络并打开你粘贴的链接。只读取公开的 HTTPS 页面；本地和私有地址会被拒绝。"
        AppLanguage.ENGLISH -> "Let the assistant search the web and open links you paste. Only public HTTPS pages are read; local and private addresses are refused."
        AppLanguage.ARABIC -> "اسمح للمساعد بالبحث في الويب وفتح الروابط التي تلصقها. يقرأ صفحات HTTPS العامة فقط، وتُرفض العناوين المحلية والخاصة."
        AppLanguage.PORTUGUESE -> "Permite que o assistente pesquise na web e abra as ligações que colares. Só lê páginas HTTPS públicas; endereços locais e privados são recusados."
        AppLanguage.SPANISH -> "Permite que el asistente busque en la web y abra los enlaces que pegues. Solo lee páginas HTTPS públicas; las direcciones locales y privadas se rechazan."
        AppLanguage.FRENCH -> "Autorise l'assistant à chercher sur le web et à ouvrir les liens que vous collez. Seules les pages HTTPS publiques sont lues ; les adresses locales et privées sont refusées."
        AppLanguage.GERMAN -> "Erlaubt dem Assistenten, im Web zu suchen und eingefügte Links zu öffnen. Es werden nur öffentliche HTTPS-Seiten gelesen; lokale und private Adressen werden abgelehnt."
        AppLanguage.RUSSIAN -> "Разрешает ассистенту искать в интернете и открывать вставленные ссылки. Читаются только публичные HTTPS-страницы; локальные и частные адреса отклоняются."
        AppLanguage.JAPANESE -> "アシスタントがウェブを検索し、貼り付けたリンクを開けるようにします。読み取るのは公開HTTPSページのみで、ローカルやプライベートなアドレスは拒否されます。"
        AppLanguage.KOREAN -> "어시스턴트가 웹을 검색하고 붙여넣은 링크를 열 수 있게 합니다. 공개 HTTPS 페이지만 읽으며, 로컬 및 사설 주소는 거부됩니다."
    }

    val webResearchServer: String get() = when (Strings.lang) {
        AppLanguage.CHINESE -> "搜索服务器（可选）"
        AppLanguage.ENGLISH -> "Search server (optional)"
        AppLanguage.ARABIC -> "خادم البحث (اختياري)"
        AppLanguage.PORTUGUESE -> "Servidor de pesquisa (opcional)"
        AppLanguage.SPANISH -> "Servidor de búsqueda (opcional)"
        AppLanguage.FRENCH -> "Serveur de recherche (facultatif)"
        AppLanguage.GERMAN -> "Suchserver (optional)"
        AppLanguage.RUSSIAN -> "Сервер поиска (необязательно)"
        AppLanguage.JAPANESE -> "検索サーバー（任意）"
        AppLanguage.KOREAN -> "검색 서버(선택 사항)"
    }

    val webResearchServerHint: String get() = when (Strings.lang) {
        AppLanguage.CHINESE -> "https://searx.example.com — 留空则使用 DuckDuckGo"
        AppLanguage.ENGLISH -> "https://searx.example.com — leave empty to use DuckDuckGo"
        AppLanguage.ARABIC -> "https://searx.example.com — اتركه فارغًا لاستخدام DuckDuckGo"
        AppLanguage.PORTUGUESE -> "https://searx.example.com — deixa vazio para usar o DuckDuckGo"
        AppLanguage.SPANISH -> "https://searx.example.com — déjalo vacío para usar DuckDuckGo"
        AppLanguage.FRENCH -> "https://searx.example.com — laissez vide pour utiliser DuckDuckGo"
        AppLanguage.GERMAN -> "https://searx.example.com — leer lassen, um DuckDuckGo zu nutzen"
        AppLanguage.RUSSIAN -> "https://searx.example.com — оставьте пустым для DuckDuckGo"
        AppLanguage.JAPANESE -> "https://searx.example.com — 空欄ならDuckDuckGoを使用"
        AppLanguage.KOREAN -> "https://searx.example.com — 비워 두면 DuckDuckGo를 사용합니다"
    }

    val webResearchInvalidServer: String get() = when (Strings.lang) {
        AppLanguage.CHINESE -> "请填写完整的 https:// 地址，或留空。"
        AppLanguage.ENGLISH -> "Use a full https:// address, or leave the field empty."
        AppLanguage.ARABIC -> "استخدم عنوان https:// كاملًا، أو اترك الحقل فارغًا."
        AppLanguage.PORTUGUESE -> "Usa um endereço https:// completo, ou deixa o campo vazio."
        AppLanguage.SPANISH -> "Usa una dirección https:// completa, o deja el campo vacío."
        AppLanguage.FRENCH -> "Utilisez une adresse https:// complète, ou laissez le champ vide."
        AppLanguage.GERMAN -> "Verwende eine vollständige https://-Adresse oder lasse das Feld leer."
        AppLanguage.RUSSIAN -> "Укажите полный адрес https:// или оставьте поле пустым."
        AppLanguage.JAPANESE -> "完全なhttps://アドレスを入力するか、空欄のままにしてください。"
        AppLanguage.KOREAN -> "전체 https:// 주소를 입력하거나 필드를 비워 두세요."
    }

    val statusSearching: String get() = when (Strings.lang) {
        AppLanguage.CHINESE -> "正在搜索网络…"
        AppLanguage.ENGLISH -> "Searching the web…"
        AppLanguage.ARABIC -> "جارٍ البحث في الويب…"
        AppLanguage.PORTUGUESE -> "A pesquisar na web…"
        AppLanguage.SPANISH -> "Buscando en la web…"
        AppLanguage.FRENCH -> "Recherche sur le web…"
        AppLanguage.GERMAN -> "Websuche läuft…"
        AppLanguage.RUSSIAN -> "Идёт поиск в интернете…"
        AppLanguage.JAPANESE -> "ウェブを検索しています…"
        AppLanguage.KOREAN -> "웹을 검색하는 중…"
    }

    val statusReadingPage: String get() = when (Strings.lang) {
        AppLanguage.CHINESE -> "正在读取页面…"
        AppLanguage.ENGLISH -> "Reading the page…"
        AppLanguage.ARABIC -> "جارٍ قراءة الصفحة…"
        AppLanguage.PORTUGUESE -> "A ler a página…"
        AppLanguage.SPANISH -> "Leyendo la página…"
        AppLanguage.FRENCH -> "Lecture de la page…"
        AppLanguage.GERMAN -> "Seite wird gelesen…"
        AppLanguage.RUSSIAN -> "Чтение страницы…"
        AppLanguage.JAPANESE -> "ページを読み込んでいます…"
        AppLanguage.KOREAN -> "페이지를 읽는 중…"
    }

    val statusRetryTextOnly: String get() = when (Strings.lang) {
        AppLanguage.CHINESE -> "模型拒绝了该图片，正在不带图片重试…"
        AppLanguage.ENGLISH -> "The model rejected the image. Retrying without it…"
        AppLanguage.ARABIC -> "رفض النموذج الصورة. إعادة المحاولة بدونها…"
        AppLanguage.PORTUGUESE -> "O modelo recusou a imagem. A tentar sem ela…"
        AppLanguage.SPANISH -> "El modelo rechazó la imagen. Reintentando sin ella…"
        AppLanguage.FRENCH -> "Le modèle a refusé l'image. Nouvelle tentative sans elle…"
        AppLanguage.GERMAN -> "Das Modell hat das Bild abgelehnt. Neuer Versuch ohne Bild…"
        AppLanguage.RUSSIAN -> "Модель отклонила изображение. Повтор без него…"
        AppLanguage.JAPANESE -> "モデルが画像を拒否しました。画像なしで再試行しています…"
        AppLanguage.KOREAN -> "모델이 이미지를 거부했습니다. 이미지 없이 다시 시도하는 중…"
    }

    val imagesTextOnlyHint: String get() = when (Strings.lang) {
        AppLanguage.CHINESE -> "该模型标记为纯文本。图片会保留在附件中但不会发送——请选择视觉模型来读取它。"
        AppLanguage.ENGLISH -> "This model is marked text-only. The image stays attached but is not sent — choose a vision model to have it read."
        AppLanguage.ARABIC -> "هذا النموذج مخصص للنص فقط. ستبقى الصورة مرفقة لكنها لن تُرسل — اختر نموذجًا بصريًا لقراءتها."
        AppLanguage.PORTUGUESE -> "Este modelo está marcado como só texto. A imagem fica anexada mas não é enviada — escolhe um modelo com visão para a ler."
        AppLanguage.SPANISH -> "Este modelo está marcado como solo texto. La imagen queda adjunta pero no se envía: elige un modelo con visión para leerla."
        AppLanguage.FRENCH -> "Ce modèle est marqué texte uniquement. L'image reste jointe mais n'est pas envoyée — choisissez un modèle visuel pour la lire."
        AppLanguage.GERMAN -> "Dieses Modell ist als reines Textmodell markiert. Das Bild bleibt angehängt, wird aber nicht gesendet — wähle ein Vision-Modell, um es lesen zu lassen."
        AppLanguage.RUSSIAN -> "Эта модель помечена как текстовая. Изображение остаётся во вложении, но не отправляется — выберите модель со зрением."
        AppLanguage.JAPANESE -> "このモデルはテキスト専用です。画像は添付されたままですが送信されません。読み取るには視覚対応モデルを選んでください。"
        AppLanguage.KOREAN -> "이 모델은 텍스트 전용으로 표시되어 있습니다. 이미지는 첨부된 상태로 남지만 전송되지 않습니다. 읽게 하려면 비전 모델을 선택하세요."
    }

    val photoNoCamera: String get() = when (Strings.lang) {
        AppLanguage.CHINESE -> "此设备上没有可用的相机应用。"
        AppLanguage.ENGLISH -> "No camera app is available on this device."
        AppLanguage.ARABIC -> "لا يوجد تطبيق كاميرا متاح على هذا الجهاز."
        AppLanguage.PORTUGUESE -> "Não há nenhuma aplicação de câmara neste dispositivo."
        AppLanguage.SPANISH -> "No hay ninguna aplicación de cámara en este dispositivo."
        AppLanguage.FRENCH -> "Aucune application appareil photo n'est disponible sur cet appareil."
        AppLanguage.GERMAN -> "Auf diesem Gerät ist keine Kamera-App verfügbar."
        AppLanguage.RUSSIAN -> "На этом устройстве нет приложения камеры."
        AppLanguage.JAPANESE -> "この端末に利用できるカメラアプリがありません。"
        AppLanguage.KOREAN -> "이 기기에서 사용할 수 있는 카메라 앱이 없습니다."
    }

    val photoLaunchFailed: String get() = when (Strings.lang) {
        AppLanguage.CHINESE -> "无法打开相机。"
        AppLanguage.ENGLISH -> "The camera could not be opened."
        AppLanguage.ARABIC -> "تعذّر فتح الكاميرا."
        AppLanguage.PORTUGUESE -> "Não foi possível abrir a câmara."
        AppLanguage.SPANISH -> "No se pudo abrir la cámara."
        AppLanguage.FRENCH -> "Impossible d'ouvrir l'appareil photo."
        AppLanguage.GERMAN -> "Die Kamera konnte nicht geöffnet werden."
        AppLanguage.RUSSIAN -> "Не удалось открыть камеру."
        AppLanguage.JAPANESE -> "カメラを開けませんでした。"
        AppLanguage.KOREAN -> "카메라를 열 수 없습니다."
    }
}
