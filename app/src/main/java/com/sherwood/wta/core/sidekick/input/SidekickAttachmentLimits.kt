package com.sherwood.wta.core.sidekick.input

/**
 * Size, count and type rules for Sidekick attachments.
 *
 * Kept as pure functions and constants so the limits are unit-testable on a plain JVM —
 * the reader that applies them needs a `ContentResolver`, but the arithmetic that decides
 * "does this fit" must not.
 *
 * The budgets exist because the Sidekick answers in a single request: a 10 MB log pasted
 * into one turn would either be rejected by the provider or silently eat the whole context
 * window, and a 30 MP phone photo decoded at full size would put the process near OOM.
 */
object SidekickAttachmentLimits {

    /** Images per question. Two screenshots of the same error are usually enough. */
    const val MAX_ATTACHMENTS = 4

    /** How many of those may be images (each one is a separate vision block). */
    const val MAX_IMAGES = 2

    /** Longest side after downscaling; enough for a readable screenshot. */
    const val MAX_IMAGE_SIDE = 1568

    /** Encoded JPEG cap per image. Providers reject far bigger payloads anyway. */
    const val MAX_IMAGE_BYTES = 2_500_000

    /** Raw image bytes buffered before decoding; beyond this the pick is refused. */
    const val MAX_IMAGE_INPUT_BYTES = 24L * 1024 * 1024

    /** Text file read cap. Never trust `OpenableColumns.SIZE`; this is measured while reading. */
    const val MAX_TEXT_FILE_BYTES = 2L * 1024 * 1024

    /** Per-file character cap after decoding. */
    const val MAX_TEXT_CHARS_PER_FILE = 12_000

    /** Whole-message character cap across all text attachments. */
    const val MAX_TEXT_CHARS_TOTAL = 20_000

    /** Bytes inspected for NUL when deciding "is this really text". */
    const val BINARY_PROBE_BYTES = 8 * 1024

    /** Longest attachment name shown in the panel and in the prompt. */
    const val MAX_NAME_CHARS = 120

    /**
     * Extensions accepted as text. The MIME type from a provider is often
     * `application/octet-stream`, so the extension is the reliable signal; MIME is used
     * only to decide image-vs-file.
     */
    val TEXT_EXTENSIONS: Set<String> = setOf(
        "txt", "log", "md", "markdown", "json", "jsonl", "yaml", "yml", "xml", "csv", "tsv",
        "ini", "conf", "cfg", "properties", "toml", "env", "gradle", "kts",
        "kt", "java", "js", "mjs", "cjs", "ts", "tsx", "jsx", "html", "htm", "css", "scss",
        "py", "go", "rs", "c", "h", "cpp", "hpp", "cs", "rb", "php", "swift", "dart",
        "sh", "bash", "zsh", "bat", "ps1", "sql", "diff", "patch", "gitignore", "pro"
    )

    /**
     * Extensions accepted as images.
     *
     * Needed because a provider does not always report an image type: the photo picker on
     * some ROMs, and any file manager that hands a picture over as a download, report
     * `null` or `application/octet-stream`. Deciding "is this an image" from the MIME type
     * alone then fell through to the text branch, and a perfectly good screenshot was
     * refused as "not a text file" — an image that was picked and never appeared.
     */
    val IMAGE_EXTENSIONS: Set<String> = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif"
    )

    /** MIME types we accept as text when the provider reports them. */
    private val TEXT_MIME_PREFIXES = listOf("text/")
    private val TEXT_MIME_EXACT = setOf(
        "application/json",
        "application/xml",
        "application/x-yaml",
        "application/yaml",
        "application/javascript",
        "application/x-sh"
    )

    fun isTextMime(mime: String?): Boolean {
        val value = mime?.lowercase()?.substringBefore(';')?.trim().orEmpty()
        if (value.isEmpty()) return false
        return TEXT_MIME_PREFIXES.any { value.startsWith(it) } || value in TEXT_MIME_EXACT
    }

    fun isImageMime(mime: String?): Boolean =
        mime?.lowercase()?.startsWith("image/") == true

    /** True when [mime] carries no usable type, so only the extension can decide. */
    fun isUnhelpfulMime(mime: String?): Boolean {
        val value = mime?.lowercase()?.substringBefore(';')?.trim().orEmpty()
        return value.isEmpty() || value == "application/octet-stream"
    }

    /** Whether [name] carries an image extension (the fallback when MIME is unusable). */
    fun isImageExtension(name: String?): Boolean {
        val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return ext.isNotEmpty() && ext in IMAGE_EXTENSIONS
    }

    /**
     * Decides what a pick is, from the provider's type and the file name.
     *
     * A reported MIME type wins whenever it says something — a provider claiming
     * `application/pdf` is believed even if the name ends in `.png`. Only a type that says
     * nothing (`null`, `application/octet-stream`) lets the extension decide, which is the
     * case the old MIME-only check got wrong: a screenshot delivered that way fell through
     * to the text branch and was refused as "not a text file".
     */
    fun kindFor(mime: String?, name: String?): SidekickAttachmentKind = when {
        isImageMime(mime) -> SidekickAttachmentKind.IMAGE
        isTextMime(mime) -> SidekickAttachmentKind.TEXT
        isTextExtension(name) -> SidekickAttachmentKind.TEXT
        isUnhelpfulMime(mime) && isImageExtension(name) -> SidekickAttachmentKind.IMAGE
        else -> SidekickAttachmentKind.UNSUPPORTED
    }

    /**
     * True for image formats that can carry transparency. A JPEG has no alpha channel,
     * so the decoder can skip the flattening pass for it.
     */
    fun mayHaveAlpha(mime: String?): Boolean {
        val value = mime?.lowercase()?.substringBefore(';')?.trim().orEmpty()
        return value == "image/png" || value == "image/webp" || value == "image/gif"
    }

    /**
     * [mayHaveAlpha] for a pick whose MIME type may be missing.
     *
     * With no usable type the extension decides. Guessing "has alpha" only costs one extra
     * bitmap pass; guessing wrong the other way leaves a transparent PNG's content on a
     * black background, which on a design mockup reads as a broken screenshot.
     */
    fun mayHaveAlpha(mime: String?, name: String?): Boolean {
        if (mayHaveAlpha(mime)) return true
        if (!isUnhelpfulMime(mime)) return false
        val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return ext == "png" || ext == "webp" || ext == "gif" || ext == "avif"
    }

    fun isTextExtension(name: String?): Boolean {
        val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return ext.isNotEmpty() && ext in TEXT_EXTENSIONS
    }

    /**
     * Reduces a provider display name to a safe basename.
     *
     * The full path never reaches the prompt: models repeat paths they were given, and a
     * path can leak the user's directory layout into an answer. Falls back to the
     * extension-derived placeholder when the provider returns nothing usable.
     */
    fun sanitizeName(raw: String?, fallback: String): String {
        val base = raw
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            // Control characters would let a provider-supplied name forge a new line in
            // the prompt header, so they are removed rather than escaped.
            ?.filter { it.code >= 0x20 && it.code != 0x7f }
            ?.trim()
            .orEmpty()
        if (base.isEmpty() || base == "." || base == "..") return fallback
        return if (base.length <= MAX_NAME_CHARS) base else safeTake(base, MAX_NAME_CHARS)
    }
}

/**
 * `take` that never splits a surrogate pair: a lone high surrogate cannot be encoded as
 * UTF-8, so clipping mid-emoji would turn into a provider-side encoding error.
 */
internal fun safeTake(text: String, max: Int): String {
    if (max <= 0) return ""
    if (text.length <= max) return text
    var end = max
    if (end > 0 && Character.isHighSurrogate(text[end - 1])) end--
    return text.substring(0, end)
}

/**
 * Applies the message-level budget: how many attachments a question may carry, and how
 * much text in total.
 *
 * Separate from [SidekickAttachmentLimits] because this one looks at what is already
 * attached; keeping it a pure function means the view model does not have to re-derive it
 * (and the tests can pin the "third file is refused, not silently truncated" rule).
 */
object SidekickAttachmentBudget {

    /** Remaining characters available for another text attachment. */
    fun remainingTextChars(existing: List<SidekickAttachment>): Int {
        val used = existing.sumOf { if (it is SidekickTextAttachment) it.text.length else 0 }
        return (SidekickAttachmentLimits.MAX_TEXT_CHARS_TOTAL - used).coerceAtLeast(0)
    }

    fun imageCount(existing: List<SidekickAttachment>): Int =
        existing.count { it is SidekickImageAttachment }

    /**
     * Whether [candidate] may join [existing], given the count and text budgets.
     *
     * Returns `null` when it fits. An image that would exceed the image count is refused
     * rather than replacing an earlier one — silently swapping attachments mid-question
     * would change what the user believes they are asking about.
     */
    fun rejection(
        existing: List<SidekickAttachment>,
        candidate: SidekickAttachment
    ): SidekickAttachmentRejection? {
        if (existing.size >= SidekickAttachmentLimits.MAX_ATTACHMENTS) {
            return SidekickAttachmentRejection.BUDGET
        }
        return when (candidate) {
            is SidekickImageAttachment ->
                if (imageCount(existing) >= SidekickAttachmentLimits.MAX_IMAGES) {
                    SidekickAttachmentRejection.BUDGET
                } else {
                    null
                }
            is SidekickTextAttachment ->
                if (candidate.text.isBlank()) {
                    SidekickAttachmentRejection.EMPTY
                } else if (remainingTextChars(existing) <= 0) {
                    SidekickAttachmentRejection.BUDGET
                } else {
                    null
                }
        }
    }

    /**
     * Clips a text attachment to what the remaining budget allows, so a later small file
     * still fits after a large one. Returns the attachment unchanged when it fits, and a
     * budget-rejected `null` when nothing is left.
     */
    fun fitText(
        existing: List<SidekickAttachment>,
        candidate: SidekickTextAttachment
    ): SidekickTextAttachment? {
        val remaining = remainingTextChars(existing)
        if (remaining <= 0) return null
        if (candidate.text.length <= remaining) return candidate
        val cut = safeTake(candidate.text, remaining).trimEnd()
        if (cut.isEmpty()) return null
        return candidate.copy(text = cut, truncated = true)
    }
}
