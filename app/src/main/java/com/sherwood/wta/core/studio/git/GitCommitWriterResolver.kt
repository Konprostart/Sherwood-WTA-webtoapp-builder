package com.sherwood.wta.core.studio.git

import android.content.Context
import com.sherwood.wta.core.frontend.GitDataApiClient
import com.sherwood.wta.core.frontend.RepoBridgeMetadataStore
import java.io.File

/** Which publish path a working copy gets, decided from its own provenance. */
sealed class GitWriterResolution {
    data class Ok(val writer: GitCommitWriter) : GitWriterResolution()

    /** The copy's provider has no write support in this build; publishing must refuse. */
    data class ReadOnly(val provider: GitProviderId) : GitWriterResolution()
}

/**
 * Resolves the publish path for a working copy.
 *
 * The decision comes from `repo_bridge.json`, not from the active provider setting: a
 * project imported from one server must never be pushed to another. When the metadata is
 * missing or unreadable the GitHub writer is returned, because that is the writer whose
 * failure modes the caller already reports (`NO_METADATA`, `NO_BASE`, `NO_TOKEN`) — the
 * absence of provenance is a different problem from a read-only host, and conflating the
 * two would hide a corrupted working copy behind a "read-only" message.
 */
object GitCommitWriterResolver {

    fun resolve(context: Context, projectDir: File): GitWriterResolution {
        val metadata = RepoBridgeMetadataStore().read(projectDir)
            ?: return GitWriterResolution.Ok(GitHubCommitWriter(GitDataApiClient(context)))
        val lookup = GitProviderRegistry.forProvenance(
            context,
            metadata.providerId,
            metadata.host
        )
        // A recorded provider that cannot be built (missing server URL, non-HTTPS server)
        // must never fall through to the GitHub writer: that would push a copy imported
        // from `git.example.com` to `github.com/<owner>/<repo>`. Refusing is the only safe
        // answer — the user fixes the server URL and publishing works again.
        val provider = (lookup as? GitProviderLookup.Available)?.provider
            ?: return GitWriterResolution.ReadOnly(metadata.providerId)
        val writer = provider.commitWriter()
            ?: return GitWriterResolution.ReadOnly(metadata.providerId)
        return GitWriterResolution.Ok(writer)
    }
}
