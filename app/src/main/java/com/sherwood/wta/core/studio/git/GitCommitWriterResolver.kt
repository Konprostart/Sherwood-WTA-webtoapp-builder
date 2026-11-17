package com.sherwood.wta.core.studio.git

import android.content.Context
import com.sherwood.wta.core.frontend.RepoBridgeMetadataStore
import java.io.File

/** Which publish path a working copy gets, decided from its own provenance. */
sealed class GitWriterResolution {
    data class Ok(val writer: GitCommitWriter) : GitWriterResolution()

    /** The copy's provider has no write support in this build; publishing must refuse. */
    data class ReadOnly(val provider: GitProviderId) : GitWriterResolution()

    /**
     * The working copy carries no readable provenance.
     *
     * This is a different failure from a read-only host: the caller reports it as
     * `NO_METADATA`. Building a GitHub writer here would put a provider-specific writer
     * outside the registry, which is the fallback this layer exists to prevent.
     */
    data object MissingProvenance : GitWriterResolution()
}

/**
 * Resolves the publish path for a working copy.
 *
 * The decision comes from `repo_bridge.json`, not from the active provider setting: a
 * project imported from one server must never be pushed to another. Absent or unreadable
 * metadata is reported as [GitWriterResolution.MissingProvenance] rather than assuming
 * GitHub.
 */
object GitCommitWriterResolver {

    fun resolve(context: Context, projectDir: File): GitWriterResolution {
        val metadata = RepoBridgeMetadataStore().read(projectDir)
            ?: return GitWriterResolution.MissingProvenance
        val lookup = GitProviderRegistry.forProvenance(
            context,
            metadata.providerId,
            metadata.host
        )
        // A recorded provider that cannot be built (missing server URL, non-HTTPS server,
        // changed host) must never fall through to the GitHub writer: that would push a
        // copy imported from `git.example.com` to `github.com/<owner>/<repo>`. Refusing is
        // the only safe answer — the user fixes the server URL and publishing works again.
        val provider = (lookup as? GitProviderLookup.Available)?.provider
            ?: return GitWriterResolution.ReadOnly(metadata.providerId)
        val writer = provider.commitWriter()
            ?: return GitWriterResolution.ReadOnly(metadata.providerId)
        return GitWriterResolution.Ok(writer)
    }
}
