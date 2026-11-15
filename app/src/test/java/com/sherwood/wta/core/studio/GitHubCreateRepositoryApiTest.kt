package com.sherwood.wta.core.studio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.sherwood.wta.core.studio.git.*

/**
 * `POST /user/repos` is the only repository-creating call the app makes: it must send the
 * chosen visibility and `auto_init`, and it must never turn the result into a fork.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GitHubCreateRepositoryApiTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client() = GitHubApiClient(
        context = ApplicationProvider.getApplicationContext<Context>(),
        endpoints = EndpointConfig(githubApiBaseUrl = server.url("/").toString().trimEnd('/')),
        tokenProvider = { TOKEN },
        httpClient = OkHttpClient(),
        // MockWebServer speaks plain HTTP on localhost; production keeps the strict rule.
        urlGuard = {}
    )

    @Test
    fun `creates an independent repository with auto init and the chosen visibility`() =
        runBlocking {
            server.enqueue(
                MockResponse().setResponseCode(201).setBody(
                    """{"id":9,"name":"rebrand","full_name":"$ACCOUNT/rebrand","private":false,
                       "fork":false,"default_branch":"main","description":"A rebranded utility",
                       "owner":{"login":"$ACCOUNT"}}"""
                )
            )

            val created = client().createRepository(
                name = "rebrand",
                description = "A rebranded utility",
                privateRepository = false,
                autoInit = true
            )

            val repository = when (created) {
                is GitResult.Success -> created.value
                is GitResult.Failure -> throw AssertionError(
                    "unexpected failure: ${created.kind} ${created.message}"
                )
            }
            assertThat(repository.fullName).isEqualTo("$ACCOUNT/rebrand")
            // The create path never calls /forks: the repository is independent.
            assertThat(repository.fork).isFalse()
            assertThat(repository.defaultBranch).isEqualTo("main")

            val request = server.takeRequest()
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.path).isEqualTo("/user/repos")
            assertThat(request.getHeader("Authorization")).isEqualTo("Bearer $TOKEN")
            val body = request.body.readUtf8()
            assertThat(body).contains("\"name\":\"rebrand\"")
            assertThat(body).contains("\"private\":false")
            // auto_init is what makes the default branch exist immediately.
            assertThat(body).contains("\"auto_init\":true")
            assertThat(body).contains("\"description\":\"A rebranded utility\"")
        }

    @Test
    fun `a private repository is requested as private`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"id":9,"name":"secret","full_name":"$ACCOUNT/secret","private":true,
                   "fork":false,"default_branch":"main","owner":{"login":"$ACCOUNT"}}"""
            )
        )

        client().createRepository(name = "secret", privateRepository = true)

        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains("\"private\":true")
        assertThat(body).contains("\"auto_init\":true")
    }

    @Test
    fun `a taken name reports gitHub's own explanation instead of a generic failure`() =
        runBlocking {
            server.enqueue(
                MockResponse().setResponseCode(422).setBody(
                    """{"message":"Repository creation failed.",
                       "errors":[{"resource":"Repository","code":"custom",
                                  "message":"name already exists on this account"}]}"""
                )
            )

            val result = client().createRepository("rebrand")

            val failure = result as? GitResult.Failure
                ?: throw AssertionError("expected a failure")
            assertThat(failure.kind).isEqualTo(GitFailureKind.UNPROCESSABLE)
            // Without the errors[] detail the message is just "creation failed".
            assertThat(failure.message).contains("name already exists on this account")
        }

    private companion object {
        const val ACCOUNT = "acme"
        val TOKEN = "ghp_" + "a".repeat(36)
    }
}
