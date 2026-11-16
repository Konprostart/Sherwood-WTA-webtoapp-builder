package com.sherwood.wta.core.sidekick.input

/**
 * Something the user attached to a Sidekick question.
 *
 * Two shapes only, because the model can consume exactly two things:
 *
 * - an **image**, sent as vision input through the provider's existing image path;
 * - a **text file**, whose content is pasted into the turn inside an untrusted block.
 *
 * Everything else (PDF, DOCX, archives, video) is refused at the reader with a reason
 * rather than silently dropped — a model that was told nothing about a file will happily
 * answer as if it had read it.
 *
 * Host-only: lives under `core/sidekick/`, which `syncShellRuntimeSources` does not copy.
 */
sealed interface SidekickAttachment {
    /** Display name: a file basename, never a full path. */
    val name: String

    enum class Kind { IMAGE, TEXT }

    val kind: Kind
}

/**
 * A downscaled, re-encoded image.
 *
 * [bytes] is always JPEG produced by [SidekickImageDecoder], so EXIF (including GPS) has
 * already been stripped and the size is bounded before it reaches a provider.
 */
data class SidekickImageAttachment(
    override val name: String,
    val bytes: ByteArray,
    val mimeType: String = "image/jpeg",
    val width: Int = 0,
    val height: Int = 0
) : SidekickAttachment {
    override val kind: SidekickAttachment.Kind get() = SidekickAttachment.Kind.IMAGE

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SidekickImageAttachment) return false
        return name == other.name && mimeType == other.mimeType &&
            width == other.width && height == other.height &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

/**
 * Text pulled out of a log, JSON, source or config file.
 *
 * [truncated] is true when the file was longer than the per-file cap. It is surfaced in
 * the rendered block so the model knows it read a prefix, and so an answer that mentions
 * "the end of the log" can be judged against what was actually sent.
 */
data class SidekickTextAttachment(
    override val name: String,
    val text: String,
    val truncated: Boolean = false
) : SidekickAttachment {
    override val kind: SidekickAttachment.Kind get() = SidekickAttachment.Kind.TEXT
}

/** Why a pick could not become an attachment. Mapped to UI text by the view model. */
enum class SidekickAttachmentRejection {
    /** The file is bigger than the reader will pull into memory. */
    TOO_LARGE,

    /** Contents are binary (NUL bytes in the head) or the type is not a text type. */
    NOT_TEXT,

    /** No `ContentResolver` stream, or the file could not be decoded as an image. */
    UNREADABLE,

    /** Nothing in it (empty file, whitespace-only). */
    EMPTY,

    /** Attaching it would exceed the per-message count or text budget. */
    BUDGET,

    /** The resolved model has no vision capability, so an image cannot be read by it. */
    VISION_UNSUPPORTED
}

/** A read attempt: either an attachment, or a reason it was refused. */
sealed class SidekickAttachmentResult {
    data class Ok(val attachment: SidekickAttachment) : SidekickAttachmentResult()
    data class Rejected(val reason: SidekickAttachmentRejection) : SidekickAttachmentResult()
}
