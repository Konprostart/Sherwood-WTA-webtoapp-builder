package com.sherwood.wta.core.workspace

import com.sherwood.wta.core.i18n.AppLanguage
import com.sherwood.wta.core.i18n.Strings

/**
 * Workspace-bundle-only strings.
 *
 * They live with the feature rather than in `core/i18n` so the portable-workspace work
 * stays inside its own package, the same way the restore messages do. The ten-language
 * `when (Strings.lang)` shape is the shared one, including the rule that a block never
 * falls back to `else` for a real language.
 */
internal object WorkspaceText {

    val menuTitle: String get() = when (Strings.lang) {
        AppLanguage.CHINESE -> "工作区迁移包"
        AppLanguage.ENGLISH -> "Workspace bundle"
        AppLanguage.ARABIC -> "حزمة مساحة العمل"
        AppLanguage.PORTUGUESE -> "Pacote de área de trabalho"
        AppLanguage.SPANISH -> "Paquete de espacio de trabajo"
        AppLanguage.FRENCH -> "Paquet d'espace de travail"
        AppLanguage.GERMAN -> "Arbeitsbereich-Paket"
        AppLanguage.RUSSIAN -> "Пакет рабочей области"
        AppLanguage.JAPANESE -> "ワークスペースパッケージ"
        AppLanguage.KOREAN -> "워크스페이스 번들"
    }

    val dialogTitle: String get() = when (Strings.lang) {
        AppLanguage.CHINESE -> "迁移会话"
        AppLanguage.ENGLISH -> "Move a session"
        AppLanguage.ARABIC -> "نقل جلسة"
        AppLanguage.PORTUGUESE -> "Mover uma sessão"
        AppLanguage.SPANISH -> "Mover una sesión"
        AppLanguage.FRENCH -> "Déplacer une session"
        AppLanguage.GERMAN -> "Sitzung übertragen"
        AppLanguage.RUSSIAN -> "Перенос сессии"
        AppLanguage.JAPANESE -> "セッションを移行"
        AppLanguage.KOREAN -> "세션 이동"
    }

    val dialogDesc: String get() = when (Strings.lang) {
        AppLanguage.CHINESE ->
            "把一个会话和它的项目文件打包成 .sherwoodworkspace，在另一台设备上导入。密钥、令牌、签名密钥库和私有配置永远不会被包含。"
        AppLanguage.ENGLISH ->
            "Package one session and its project files as a .sherwoodworkspace archive and import it on another device. Keys, tokens, signing keystores and private settings are never included."
        AppLanguage.ARABIC ->
            "حزّم جلسة واحدة وملفات مشروعها في أرشيف .sherwoodworkspace ثم استوردها على جهاز آخر. لا تُضمَّن المفاتيح أو الرموز أو مخازن مفاتيح التوقيع أو الإعدادات الخاصة أبدًا."
        AppLanguage.PORTUGUESE ->
            "Empacota uma sessão e os ficheiros do projeto num arquivo .sherwoodworkspace para importar noutro dispositivo. Chaves, tokens, keystores de assinatura e definições privadas nunca são incluídos."
        AppLanguage.SPANISH ->
            "Empaqueta una sesión y sus archivos de proyecto en un archivo .sherwoodworkspace para importarlo en otro dispositivo. Las claves, los tokens, los keystores de firma y los ajustes privados nunca se incluyen."
        AppLanguage.FRENCH ->
            "Empaquetez une session et ses fichiers de projet dans une archive .sherwoodworkspace à importer sur un autre appareil. Les clés, jetons, keystores de signature et réglages privés ne sont jamais inclus."
        AppLanguage.GERMAN ->
            "Packt eine Sitzung und ihre Projektdateien als .sherwoodworkspace-Archiv für ein anderes Gerät. Schlüssel, Tokens, Signatur-Keystores und private Einstellungen sind nie enthalten."
        AppLanguage.RUSSIAN ->
            "Упакуйте одну сессию и её файлы проекта в архив .sherwoodworkspace и импортируйте на другом устройстве. Ключи, токены, хранилища подписи и приватные настройки в него не попадают."
        AppLanguage.JAPANESE ->
            "セッションとそのプロジェクトファイルを .sherwoodworkspace としてまとめ、別の端末で読み込みます。鍵・トークン・署名キーストア・非公開設定は決して含まれません。"
        AppLanguage.KOREAN ->
            "세션과 프로젝트 파일을 .sherwoodworkspace로 묶어 다른 기기에서 가져옵니다. 키, 토큰, 서명 키스토어, 비공개 설정은 절대 포함되지 않습니다."
    }

    val exportCurrent: String get() = when (Strings.lang) {
        AppLanguage.CHINESE -> "导出当前会话"
        AppLanguage.ENGLISH -> "Export current session"
        AppLanguage.ARABIC -> "تصدير الجلسة الحالية"
        AppLanguage.PORTUGUESE -> "Exportar sessão atual"
        AppLanguage.SPANISH -> "Exportar la sesión actual"
        AppLanguage.FRENCH -> "Exporter la session actuelle"
        AppLanguage.GERMAN -> "Aktuelle Sitzung exportieren"
        AppLanguage.RUSSIAN -> "Экспортировать текущую сессию"
        AppLanguage.JAPANESE -> "現在のセッションを書き出す"
        AppLanguage.KOREAN -> "현재 세션 내보내기"
    }

    val importBundle: String get() = when (Strings.lang) {
        AppLanguage.CHINESE -> "导入会话包"
        AppLanguage.ENGLISH -> "Import bundle"
        AppLanguage.ARABIC -> "استيراد حزمة"
        AppLanguage.PORTUGUESE -> "Importar pacote"
        AppLanguage.SPANISH -> "Importar paquete"
        AppLanguage.FRENCH -> "Importer un paquet"
        AppLanguage.GERMAN -> "Paket importieren"
        AppLanguage.RUSSIAN -> "Импортировать пакет"
        AppLanguage.JAPANESE -> "パッケージを読み込む"
        AppLanguage.KOREAN -> "번들 가져오기"
    }

    val noCurrentSession: String get() = when (Strings.lang) {
        AppLanguage.CHINESE -> "还没有打开的会话。请先打开一个会话再导出。"
        AppLanguage.ENGLISH -> "No session is open. Open a session first, then export it."
        AppLanguage.ARABIC -> "لا توجد جلسة مفتوحة. افتح جلسة أولًا ثم صدّرها."
        AppLanguage.PORTUGUESE -> "Nenhuma sessão está aberta. Abre uma sessão primeiro e depois exporta-a."
        AppLanguage.SPANISH -> "No hay ninguna sesión abierta. Abre una sesión y luego expórtala."
        AppLanguage.FRENCH -> "Aucune session n'est ouverte. Ouvrez une session, puis exportez-la."
        AppLanguage.GERMAN -> "Es ist keine Sitzung geöffnet. Öffne zuerst eine Sitzung und exportiere sie dann."
        AppLanguage.RUSSIAN -> "Ни одна сессия не открыта. Сначала откройте сессию, затем экспортируйте её."
        AppLanguage.JAPANESE -> "開いているセッションがありません。先にセッションを開いてから書き出してください。"
        AppLanguage.KOREAN -> "열려 있는 세션이 없습니다. 먼저 세션을 연 다음 내보내세요."
    }

    /** What the bundle is guaranteed never to contain, stated on the dialog itself. */
    val keptOutNote: String get() = when (Strings.lang) {
        AppLanguage.CHINESE -> "不会包含：API 密钥与令牌、签名密钥库、Git 与 Telegram 凭据、私钥、.env 文件、.git 目录。"
        AppLanguage.ENGLISH -> "Never included: API keys and tokens, signing keystores, git and Telegram credentials, private keys, .env files, .git directories."
        AppLanguage.ARABIC -> "لا يُضمَّن أبدًا: مفاتيح API والرموز، ومخازن مفاتيح التوقيع، وبيانات اعتماد Git وTelegram، والمفاتيح الخاصة، وملفات .env، ومجلدات .git."
        AppLanguage.PORTUGUESE -> "Nunca incluído: chaves de API e tokens, keystores de assinatura, credenciais de git e Telegram, chaves privadas, ficheiros .env, pastas .git."
        AppLanguage.SPANISH -> "Nunca se incluye: claves de API y tokens, keystores de firma, credenciales de git y Telegram, claves privadas, archivos .env, carpetas .git."
        AppLanguage.FRENCH -> "Jamais inclus : clés d'API et jetons, keystores de signature, identifiants git et Telegram, clés privées, fichiers .env, dossiers .git."
        AppLanguage.GERMAN -> "Nie enthalten: API-Schlüssel und Tokens, Signatur-Keystores, Git- und Telegram-Zugangsdaten, private Schlüssel, .env-Dateien, .git-Ordner."
        AppLanguage.RUSSIAN -> "Не включается никогда: ключи API и токены, хранилища ключей подписи, учётные данные git и Telegram, приватные ключи, файлы .env, каталоги .git."
        AppLanguage.JAPANESE -> "含まれないもの: API キーとトークン、署名キーストア、git と Telegram の認証情報、秘密鍵、.env ファイル、.git フォルダー。"
        AppLanguage.KOREAN -> "포함되지 않음: API 키와 토큰, 서명 키스토어, git 및 Telegram 자격 증명, 개인 키, .env 파일, .git 폴더."
    }

    // ---------------------------------------------------------------- progress

    val readingSession: String get() = when (Strings.lang) {
        AppLanguage.CHINESE -> "正在读取会话…"
        AppLanguage.ENGLISH -> "Reading the session…"
        AppLanguage.ARABIC -> "جارٍ قراءة الجلسة…"
        AppLanguage.PORTUGUESE -> "A ler a sessão…"
        AppLanguage.SPANISH -> "Leyendo la sesión…"
        AppLanguage.FRENCH -> "Lecture de la session…"
        AppLanguage.GERMAN -> "Sitzung wird gelesen…"
        AppLanguage.RUSSIAN -> "Чтение сессии…"
        AppLanguage.JAPANESE -> "セッションを読み込んでいます…"
        AppLanguage.KOREAN -> "세션을 읽는 중…"
    }

    val collectingFiles: String get() = when (Strings.lang) {
        AppLanguage.CHINESE -> "正在收集项目文件…"
        AppLanguage.ENGLISH -> "Collecting project files…"
        AppLanguage.ARABIC -> "جارٍ جمع ملفات المشروع…"
        AppLanguage.PORTUGUESE -> "A recolher os ficheiros do projeto…"
        AppLanguage.SPANISH -> "Recopilando los archivos del proyecto…"
        AppLanguage.FRENCH -> "Collecte des fichiers du projet…"
        AppLanguage.GERMAN -> "Projektdateien werden gesammelt…"
        AppLanguage.RUSSIAN -> "Сбор файлов проекта…"
        AppLanguage.JAPANESE -> "プロジェクトファイルを集めています…"
        AppLanguage.KOREAN -> "프로젝트 파일을 모으는 중…"
    }

    fun packingFile(name: String): String = when (Strings.lang) {
        AppLanguage.CHINESE -> "正在打包 $name…"
        AppLanguage.ENGLISH -> "Packing $name…"
        AppLanguage.ARABIC -> "جارٍ حزم $name…"
        AppLanguage.PORTUGUESE -> "A empacotar $name…"
        AppLanguage.SPANISH -> "Empaquetando $name…"
        AppLanguage.FRENCH -> "Empaquetage de $name…"
        AppLanguage.GERMAN -> "$name wird gepackt…"
        AppLanguage.RUSSIAN -> "Упаковка $name…"
        AppLanguage.JAPANESE -> "$name をまとめています…"
        AppLanguage.KOREAN -> "$name 패키징 중…"
    }

    val checkingBundle: String get() = when (Strings.lang) {
        AppLanguage.CHINESE -> "正在检查会话包…"
        AppLanguage.ENGLISH -> "Checking the bundle…"
        AppLanguage.ARABIC -> "جارٍ فحص الحزمة…"
        AppLanguage.PORTUGUESE -> "A verificar o pacote…"
        AppLanguage.SPANISH -> "Comprobando el paquete…"
        AppLanguage.FRENCH -> "Vérification du paquet…"
        AppLanguage.GERMAN -> "Paket wird geprüft…"
        AppLanguage.RUSSIAN -> "Проверка пакета…"
        AppLanguage.JAPANESE -> "パッケージを確認しています…"
        AppLanguage.KOREAN -> "번들을 확인하는 중…"
    }

    val readingBundle: String get() = when (Strings.lang) {
        AppLanguage.CHINESE -> "正在读取会话包…"
        AppLanguage.ENGLISH -> "Reading the bundle…"
        AppLanguage.ARABIC -> "جارٍ قراءة الحزمة…"
        AppLanguage.PORTUGUESE -> "A ler o pacote…"
        AppLanguage.SPANISH -> "Leyendo el paquete…"
        AppLanguage.FRENCH -> "Lecture du paquet…"
        AppLanguage.GERMAN -> "Paket wird gelesen…"
        AppLanguage.RUSSIAN -> "Чтение пакета…"
        AppLanguage.JAPANESE -> "パッケージを読み込んでいます…"
        AppLanguage.KOREAN -> "번들을 읽는 중…"
    }

    val verifyingBundle: String get() = when (Strings.lang) {
        AppLanguage.CHINESE -> "正在校验会话包完整性…"
        AppLanguage.ENGLISH -> "Checking bundle integrity…"
        AppLanguage.ARABIC -> "جارٍ التحقق من سلامة الحزمة…"
        AppLanguage.PORTUGUESE -> "A verificar a integridade do pacote…"
        AppLanguage.SPANISH -> "Comprobando la integridad del paquete…"
        AppLanguage.FRENCH -> "Vérification de l'intégrité du paquet…"
        AppLanguage.GERMAN -> "Paketintegrität wird geprüft…"
        AppLanguage.RUSSIAN -> "Проверка целостности пакета…"
        AppLanguage.JAPANESE -> "パッケージの整合性を確認しています…"
        AppLanguage.KOREAN -> "번들 무결성을 확인하는 중…"
    }

    val creatingSession: String get() = when (Strings.lang) {
        AppLanguage.CHINESE -> "正在创建会话…"
        AppLanguage.ENGLISH -> "Creating the session…"
        AppLanguage.ARABIC -> "جارٍ إنشاء الجلسة…"
        AppLanguage.PORTUGUESE -> "A criar a sessão…"
        AppLanguage.SPANISH -> "Creando la sesión…"
        AppLanguage.FRENCH -> "Création de la session…"
        AppLanguage.GERMAN -> "Sitzung wird erstellt…"
        AppLanguage.RUSSIAN -> "Создание сессии…"
        AppLanguage.JAPANESE -> "セッションを作成しています…"
        AppLanguage.KOREAN -> "세션을 만드는 중…"
    }

    fun restoringFile(name: String): String = when (Strings.lang) {
        AppLanguage.CHINESE -> "正在恢复 $name…"
        AppLanguage.ENGLISH -> "Restoring $name…"
        AppLanguage.ARABIC -> "جارٍ استعادة $name…"
        AppLanguage.PORTUGUESE -> "A restaurar $name…"
        AppLanguage.SPANISH -> "Restaurando $name…"
        AppLanguage.FRENCH -> "Restauration de $name…"
        AppLanguage.GERMAN -> "$name wird wiederhergestellt…"
        AppLanguage.RUSSIAN -> "Восстановление $name…"
        AppLanguage.JAPANESE -> "$name を復元しています…"
        AppLanguage.KOREAN -> "$name 복원 중…"
    }

    val exportComplete: String get() = when (Strings.lang) {
        AppLanguage.CHINESE -> "会话包已导出。"
        AppLanguage.ENGLISH -> "Workspace bundle exported."
        AppLanguage.ARABIC -> "تم تصدير حزمة مساحة العمل."
        AppLanguage.PORTUGUESE -> "Pacote exportado."
        AppLanguage.SPANISH -> "Paquete exportado."
        AppLanguage.FRENCH -> "Paquet exporté."
        AppLanguage.GERMAN -> "Paket exportiert."
        AppLanguage.RUSSIAN -> "Пакет экспортирован."
        AppLanguage.JAPANESE -> "パッケージを書き出しました。"
        AppLanguage.KOREAN -> "번들을 내보냈습니다."
    }

    val importComplete: String get() = when (Strings.lang) {
        AppLanguage.CHINESE -> "会话包已导入。"
        AppLanguage.ENGLISH -> "Workspace bundle imported."
        AppLanguage.ARABIC -> "تم استيراد حزمة مساحة العمل."
        AppLanguage.PORTUGUESE -> "Pacote importado."
        AppLanguage.SPANISH -> "Paquete importado."
        AppLanguage.FRENCH -> "Paquet importé."
        AppLanguage.GERMAN -> "Paket importiert."
        AppLanguage.RUSSIAN -> "Пакет импортирован."
        AppLanguage.JAPANESE -> "パッケージを読み込みました。"
        AppLanguage.KOREAN -> "번들을 가져왔습니다."
    }

    /** Says exactly how many files were exported and what was left out, and why. */
    fun exportSummary(sessionTitle: String, files: Int, omitted: Int): String = when (Strings.lang) {
        AppLanguage.CHINESE -> if (omitted > 0) {
            "已导出「$sessionTitle」：$files 个文件（另有 $omitted 个因安全规则被排除）。"
        } else {
            "已导出「$sessionTitle」：$files 个文件。"
        }
        AppLanguage.ENGLISH -> if (omitted > 0) {
            "Exported \"$sessionTitle\": $files files ($omitted left out by the safety rules)."
        } else {
            "Exported \"$sessionTitle\": $files files."
        }
        AppLanguage.ARABIC -> if (omitted > 0) {
            "تم تصدير «$sessionTitle»: $files ملفًا (استُبعد $omitted بموجب قواعد الأمان)."
        } else {
            "تم تصدير «$sessionTitle»: $files ملفًا."
        }
        AppLanguage.PORTUGUESE -> if (omitted > 0) {
            "Exportado \"$sessionTitle\": $files ficheiros ($omitted excluídos pelas regras de segurança)."
        } else {
            "Exportado \"$sessionTitle\": $files ficheiros."
        }
        AppLanguage.SPANISH -> if (omitted > 0) {
            "Exportado \"$sessionTitle\": $files archivos ($omitted excluidos por las reglas de seguridad)."
        } else {
            "Exportado \"$sessionTitle\": $files archivos."
        }
        AppLanguage.FRENCH -> if (omitted > 0) {
            "Exporté « $sessionTitle » : $files fichiers ($omitted exclus par les règles de sécurité)."
        } else {
            "Exporté « $sessionTitle » : $files fichiers."
        }
        AppLanguage.GERMAN -> if (omitted > 0) {
            "\"$sessionTitle\" exportiert: $files Dateien ($omitted durch die Sicherheitsregeln ausgelassen)."
        } else {
            "\"$sessionTitle\" exportiert: $files Dateien."
        }
        AppLanguage.RUSSIAN -> if (omitted > 0) {
            "Экспортировано «$sessionTitle»: $files файлов ($omitted исключено правилами безопасности)."
        } else {
            "Экспортировано «$sessionTitle»: $files файлов."
        }
        AppLanguage.JAPANESE -> if (omitted > 0) {
            "「$sessionTitle」を書き出しました: $files ファイル（$omitted 件は安全規則により除外）。"
        } else {
            "「$sessionTitle」を書き出しました: $files ファイル。"
        }
        AppLanguage.KOREAN -> if (omitted > 0) {
            "\"$sessionTitle\" 내보냄: ${files}개 파일 (안전 규칙으로 ${omitted}개 제외)."
        } else {
            "\"$sessionTitle\" 내보냄: ${files}개 파일."
        }
    }

    fun importSummary(sessionTitle: String, files: Int, messages: Int): String = when (Strings.lang) {
        AppLanguage.CHINESE -> "已导入「$sessionTitle」：$files 个文件、$messages 条消息。"
        AppLanguage.ENGLISH -> "Imported \"$sessionTitle\": $files files, $messages messages."
        AppLanguage.ARABIC -> "تم استيراد «$sessionTitle»: $files ملفًا و$messages رسالة."
        AppLanguage.PORTUGUESE -> "Importado \"$sessionTitle\": $files ficheiros, $messages mensagens."
        AppLanguage.SPANISH -> "Importado \"$sessionTitle\": $files archivos, $messages mensajes."
        AppLanguage.FRENCH -> "Importé « $sessionTitle » : $files fichiers, $messages messages."
        AppLanguage.GERMAN -> "\"$sessionTitle\" importiert: $files Dateien, $messages Nachrichten."
        AppLanguage.RUSSIAN -> "Импортировано «$sessionTitle»: $files файлов, сообщений: $messages."
        AppLanguage.JAPANESE -> "「$sessionTitle」を読み込みました: $files ファイル、$messages メッセージ。"
        AppLanguage.KOREAN -> "\"$sessionTitle\" 가져옴: 파일 ${files}개, 메시지 ${messages}개."
    }

    /** Explains why an entry was refused, so nothing is dropped without a reason. */
    fun refusedEntry(reason: WorkspaceAllowlist.Refusal): String {
        val label = when (reason) {
            WorkspaceAllowlist.Refusal.EXCLUDED_DIRECTORY -> when (Strings.lang) {
                AppLanguage.CHINESE -> "目录不随包迁移（版本库、缓存或会话历史）"
                AppLanguage.ENGLISH -> "directory is never carried (repository, cache or session history)"
                AppLanguage.ARABIC -> "لا يُنقل هذا المجلد أبدًا (مستودع أو ذاكرة مؤقتة أو سجل جلسة)"
                AppLanguage.PORTUGUESE -> "a pasta nunca é transportada (repositório, cache ou histórico da sessão)"
                AppLanguage.SPANISH -> "la carpeta nunca se transporta (repositorio, caché o historial de la sesión)"
                AppLanguage.FRENCH -> "le dossier n'est jamais transporté (dépôt, cache ou historique de session)"
                AppLanguage.GERMAN -> "Ordner wird nie mitgenommen (Repository, Cache oder Sitzungsverlauf)"
                AppLanguage.RUSSIAN -> "каталог не переносится (репозиторий, кеш или история сессии)"
                AppLanguage.JAPANESE -> "このフォルダーは持ち出されません（リポジトリ・キャッシュ・セッション履歴）"
                AppLanguage.KOREAN -> "이 폴더는 포함되지 않습니다 (저장소, 캐시, 세션 기록)"
            }
            WorkspaceAllowlist.Refusal.SECRET_NAME -> when (Strings.lang) {
                AppLanguage.CHINESE -> "文件名看起来像密钥或凭据"
                AppLanguage.ENGLISH -> "file name looks like a key or credential"
                AppLanguage.ARABIC -> "اسم الملف يبدو كمفتاح أو بيانات اعتماد"
                AppLanguage.PORTUGUESE -> "o nome do ficheiro parece ser uma chave ou credencial"
                AppLanguage.SPANISH -> "el nombre del archivo parece una clave o credencial"
                AppLanguage.FRENCH -> "le nom du fichier ressemble à une clé ou un identifiant"
                AppLanguage.GERMAN -> "Dateiname sieht nach einem Schlüssel oder Geheimnis aus"
                AppLanguage.RUSSIAN -> "имя файла похоже на ключ или учётные данные"
                AppLanguage.JAPANESE -> "ファイル名が鍵や認証情報に見えます"
                AppLanguage.KOREAN -> "파일 이름이 키나 자격 증명으로 보입니다"
            }
            WorkspaceAllowlist.Refusal.UNSAFE_PATH -> when (Strings.lang) {
                AppLanguage.CHINESE -> "路径无法安全地写入包内"
                AppLanguage.ENGLISH -> "path cannot be represented safely in the bundle"
                AppLanguage.ARABIC -> "لا يمكن تمثيل المسار بأمان داخل الحزمة"
                AppLanguage.PORTUGUESE -> "o caminho não pode ser representado com segurança no pacote"
                AppLanguage.SPANISH -> "la ruta no se puede representar de forma segura en el paquete"
                AppLanguage.FRENCH -> "le chemin ne peut pas être représenté en toute sécurité dans le paquet"
                AppLanguage.GERMAN -> "Pfad lässt sich nicht sicher im Paket abbilden"
                AppLanguage.RUSSIAN -> "путь нельзя безопасно представить в пакете"
                AppLanguage.JAPANESE -> "このパスは安全にパッケージへ格納できません"
                AppLanguage.KOREAN -> "이 경로는 번들에 안전하게 담을 수 없습니다"
            }
            WorkspaceAllowlist.Refusal.NOT_REGULAR_FILE -> when (Strings.lang) {
                AppLanguage.CHINESE -> "不是常规文件（例如符号链接）"
                AppLanguage.ENGLISH -> "not a regular file (for example a symbolic link)"
                AppLanguage.ARABIC -> "ليس ملفًا عاديًا (مثل ارتباط رمزي)"
                AppLanguage.PORTUGUESE -> "não é um ficheiro normal (por exemplo, uma ligação simbólica)"
                AppLanguage.SPANISH -> "no es un archivo normal (por ejemplo, un enlace simbólico)"
                AppLanguage.FRENCH -> "ce n'est pas un fichier ordinaire (par exemple un lien symbolique)"
                AppLanguage.GERMAN -> "keine reguläre Datei (zum Beispiel ein symbolischer Link)"
                AppLanguage.RUSSIAN -> "это не обычный файл (например, символическая ссылка)"
                AppLanguage.JAPANESE -> "通常のファイルではありません（シンボリックリンクなど）"
                AppLanguage.KOREAN -> "일반 파일이 아닙니다 (예: 심볼릭 링크)"
            }
            WorkspaceAllowlist.Refusal.TOO_LARGE -> when (Strings.lang) {
                AppLanguage.CHINESE -> "文件或包体超过体积上限"
                AppLanguage.ENGLISH -> "file or bundle exceeds the size limit"
                AppLanguage.ARABIC -> "الملف أو الحزمة يتجاوز حد الحجم"
                AppLanguage.PORTUGUESE -> "o ficheiro ou o pacote excede o limite de tamanho"
                AppLanguage.SPANISH -> "el archivo o el paquete supera el límite de tamaño"
                AppLanguage.FRENCH -> "le fichier ou le paquet dépasse la limite de taille"
                AppLanguage.GERMAN -> "Datei oder Paket überschreitet die Größengrenze"
                AppLanguage.RUSSIAN -> "файл или пакет превышает ограничение по размеру"
                AppLanguage.JAPANESE -> "ファイルまたはパッケージがサイズ上限を超えています"
                AppLanguage.KOREAN -> "파일이나 번들이 크기 한도를 초과했습니다"
            }
        }
        return label
    }
}
