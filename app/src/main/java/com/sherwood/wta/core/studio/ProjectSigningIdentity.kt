package com.sherwood.wta.core.studio

import android.content.Context
import com.sherwood.wta.core.apkbuilder.JarSigner
import com.sherwood.wta.core.apkbuilder.PerAppSigningIdentity
import com.sherwood.wta.core.crypto.EnhancedCrypto
import com.sherwood.wta.core.logging.AppLogger
import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Project-scoped signing identities used by the Studio.
 *
 * Two layers, one lookup order:
 *
 *  1. **Imported** — a keystore the user brought for a specific package
 *     (Google Play upload key, a key recovered from a previous release, …).
 *     Stored at `filesDir/project_signing/<hash>.p12`; the store alias and the
 *     passwords cannot live in the same plaintext file, so the secrets are
 *     AES-GCM encrypted with the Android Keystore master key and bound to the
 *     package name via AEAD associated data. An encrypted blob copied from
 *     another project therefore fails to decrypt.
 *  2. **Generated** — the existing [PerAppSigningIdentity] store for the package
 *     (PKCS12 under `filesDir/app_signing/`), reused as-is. This deliberately
 *     reuses the identity the export pipeline already signs with, so an app
 *     exported from the editor and an artifact signed in the Studio end up with
 *     the same certificate and stay updateable.
 *
 * There is intentionally **no** fallback to the host application identity. The
 * previous `SigningStudio` signed every vault artifact with Sherwood's own key,
 * which silently produces an artifact that can never be updated with the real
 * project key — and, for an AAB, permanently poisons the Play upload path.
 *
 * Nothing here writes to the project directory, exports, backups or logs: a
 * keystore and its password are app-private files under `filesDir`, and the
 * password never reaches [AppLogger].
 */
object ProjectSigningIdentity {

    private const val TAG = "ProjectSigningIdentity"

    private const val STORE_DIR = "project_signing"

    data class Summary(
        val packageName: String,
        val origin: Origin,
        val alias: String,
        val subject: String,
        val sha256: String,
        val notAfter: Long
    ) {
        enum class Origin { IMPORTED, GENERATED }

        /** Uppercase, colon-separated — the form `apksigner` prints. */
        val displayFingerprint: String
            get() = sha256.chunked(2).joinToString(":").uppercase(Locale.ROOT)

        val shortFingerprint: String
            get() = sha256.take(16).chunked(2).joinToString(":").uppercase(Locale.ROOT)

        val notAfterDisplay: String
            get() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(notAfter))
    }

    /** The identity to sign with, or null when the project has none. Never creates one. */
    fun identity(context: Context, packageName: String): JarSigner.SigningIdentity? {
        importedIdentity(context, packageName)?.let { return it }
        return PerAppSigningIdentity.existing(context, packageName)?.toSigningIdentity()
    }

    /** Description of the identity to sign with, or null when the project has none. */
    fun summary(context: Context, packageName: String): Summary? {
        importedSummary(context, packageName)?.let { return it }
        val generated = PerAppSigningIdentity.existing(context, packageName) ?: return null
        return Summary(
            packageName = packageName,
            origin = Summary.Origin.GENERATED,
            alias = GENERATED_ALIAS,
            subject = generated.certificate.subjectX500Principal.name,
            sha256 = hashHex(generated.certificate),
            notAfter = generated.certificate.notAfter.time
        )
    }

    /**
     * Create the generated identity for [packageName] if it does not exist yet.
     *
     * Called only after the user explicitly confirms: for an app that already has
     * users, creating a new key means the next release can no longer update them.
     */
    fun create(context: Context, packageName: String): Summary {
        val identity = PerAppSigningIdentity.identityFor(context, packageName)
        AppLogger.i(TAG, "Created generated project signing identity for $packageName")
        return Summary(
            packageName = packageName,
            origin = Summary.Origin.GENERATED,
            alias = GENERATED_ALIAS,
            subject = identity.certificate.subjectX500Principal.name,
            sha256 = hashHex(identity.certificate),
            notAfter = identity.certificate.notAfter.time
        )
    }

    /**
     * Import a keystore (PKCS12 or JKS) as the project identity for [packageName].
     *
     * The store is validated **before** anything is written: the alias must exist,
     * both passwords must open it, and the entry must carry a private key with a
     * certificate chain. A malformed file leaves the previous identity untouched.
     * An existing imported identity is never replaced unless [replace] is true.
     */
    fun importKeystore(
        context: Context,
        packageName: String,
        keystore: File,
        storePassword: CharArray,
        alias: String?,
        keyPassword: CharArray?,
        replace: Boolean = false
    ): Result<Summary> {
        if (packageName.isBlank()) {
            return Result.failure(IllegalArgumentException("Package name is required"))
        }
        if (!keystore.isFile || keystore.length() == 0L) {
            return Result.failure(IllegalArgumentException("Keystore file is missing or empty"))
        }
        if (keystore.length() > MAX_KEYSTORE_BYTES) {
            return Result.failure(IllegalArgumentException("Keystore file is too large"))
        }
        if (storePassword.isEmpty()) {
            return Result.failure(IllegalArgumentException("Keystore password is required"))
        }
        val destination = importedStoreFile(context, packageName)
        if (destination.exists() && !replace) {
            return Result.failure(
                IllegalStateException("A signing identity is already imported for $packageName")
            )
        }

        val loaded = try {
            openKeystore(keystore, storePassword, alias, keyPassword ?: storePassword)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Keystore import rejected: ${e.javaClass.simpleName}")
            return Result.failure(IllegalArgumentException("Keystore could not be opened: ${e.message}"))
        }

        val credential = runCatching {
            encryptCredential(context, packageName, storePassword, keyPassword ?: storePassword)
        }.getOrElse {
            // Without Keystore-backed encryption the passwords would have to be
            // stored in plaintext next to the private key. Refuse instead.
            AppLogger.w(TAG, "Secure credential storage unavailable; import refused")
            return Result.failure(
                IllegalStateException("Secure credential storage is unavailable on this device")
            )
        }

        return runCatching {
            val dir = importedDir(context)
            if (!dir.isDirectory && !dir.mkdirs()) {
                throw IllegalStateException("Could not create the signing store directory")
            }
            val temp = File(dir, "${destination.name}.tmp")
            keystore.copyTo(temp, overwrite = true)
            if (destination.exists() && !destination.delete()) {
                temp.delete()
                throw IllegalStateException("Existing keystore could not be replaced")
            }
            if (!temp.renameTo(destination)) {
                temp.delete()
                throw IllegalStateException("Keystore could not be stored")
            }
            importedAliasFile(context, packageName).writeText(loaded.alias)
            importedCredentialFile(context, packageName).writeBytes(credential)
            AppLogger.i(TAG, "Imported project signing identity for $packageName (alias=${loaded.alias})")
            Summary(
                packageName = packageName,
                origin = Summary.Origin.IMPORTED,
                alias = loaded.alias,
                subject = loaded.certificate.subjectX500Principal.name,
                sha256 = hashHex(loaded.certificate),
                notAfter = loaded.certificate.notAfter.time
            )
        }
    }

    /** Remove only the imported identity; a generated identity is left untouched. */
    fun clearImported(context: Context, packageName: String): Boolean {
        val store = importedStoreFile(context, packageName)
        importedAliasFile(context, packageName).delete()
        importedCredentialFile(context, packageName).delete()
        return !store.exists() || store.delete()
    }

    fun hasImported(context: Context, packageName: String): Boolean =
        importedStoreFile(context, packageName).exists()

    // ---- internals -------------------------------------------------------

    private data class Loaded(
        val alias: String,
        val privateKey: PrivateKey,
        val certificate: X509Certificate
    )

    private fun importedIdentity(context: Context, packageName: String): JarSigner.SigningIdentity? {
        val store = importedStoreFile(context, packageName)
        if (!store.isFile) return null
        val aliasFile = importedAliasFile(context, packageName)
        val credentialFile = importedCredentialFile(context, packageName)
        if (!aliasFile.isFile || !credentialFile.isFile) return null
        return runCatching {
            val alias = aliasFile.readText().trim()
            val (storePassword, keyPassword) =
                decryptCredential(context, packageName, credentialFile.readBytes())
            val loaded = openKeystore(store, storePassword, alias, keyPassword)
            JarSigner.SigningIdentity(loaded.privateKey, loaded.certificate)
        }.onFailure {
            AppLogger.w(TAG, "Imported project keystore could not be opened: ${it.javaClass.simpleName}")
        }.getOrNull()
    }

    private fun importedSummary(context: Context, packageName: String): Summary? {
        val store = importedStoreFile(context, packageName)
        if (!store.isFile) return null
        val aliasFile = importedAliasFile(context, packageName)
        val credentialFile = importedCredentialFile(context, packageName)
        if (!aliasFile.isFile || !credentialFile.isFile) return null
        return runCatching {
            val alias = aliasFile.readText().trim()
            val (storePassword, keyPassword) =
                decryptCredential(context, packageName, credentialFile.readBytes())
            val loaded = openKeystore(store, storePassword, alias, keyPassword)
            Summary(
                packageName = packageName,
                origin = Summary.Origin.IMPORTED,
                alias = loaded.alias,
                subject = loaded.certificate.subjectX500Principal.name,
                sha256 = hashHex(loaded.certificate),
                notAfter = loaded.certificate.notAfter.time
            )
        }.getOrNull()
    }

    private fun openKeystore(
        file: File,
        storePassword: CharArray,
        requestedAlias: String?,
        keyPassword: CharArray
    ): Loaded {
        var lastError: Exception? = null
        for (type in KEYSTORE_TYPES) {
            val keyStore = try {
                KeyStore.getInstance(type)
            } catch (e: Exception) {
                lastError = e
                continue
            }
            try {
                FileInputStream(file).use { keyStore.load(it, storePassword.copyOf()) }
            } catch (e: Exception) {
                lastError = e
                continue
            }
            val alias = when {
                !requestedAlias.isNullOrBlank() && keyStore.containsAlias(requestedAlias) ->
                    requestedAlias
                !requestedAlias.isNullOrBlank() ->
                    throw IllegalArgumentException("Alias \"$requestedAlias\" not found in keystore")
                else -> keyStore.aliases().toList().firstOrNull { keyStore.isKeyEntry(it) }
                    ?: throw IllegalArgumentException("Keystore contains no private key entry")
            }
            val key = keyStore.getKey(alias, keyPassword.copyOf()) as? PrivateKey
                ?: throw IllegalArgumentException("Alias \"$alias\" does not hold a private key")
            val chain = keyStore.getCertificateChain(alias).orEmpty()
            val certificate = chain.firstOrNull() as? X509Certificate
                ?: throw IllegalArgumentException("Alias \"$alias\" has no certificate")
            if (certificate.notAfter.before(Date())) {
                throw IllegalArgumentException("Certificate for \"$alias\" has expired")
            }
            certificate.checkValidity()
            return Loaded(alias, key, certificate)
        }
        throw IllegalStateException(lastError?.message ?: "Unsupported keystore format")
    }

    private fun encryptCredential(
        context: Context,
        packageName: String,
        storePassword: CharArray,
        keyPassword: CharArray
    ): ByteArray {
        val masterKey = EnhancedCrypto.getOrCreateMasterKey(context)
            ?: throw IllegalStateException("Android Keystore master key unavailable")
        val plaintext = buildString {
            append(String(storePassword))
            append('\n')
            append(String(keyPassword))
        }.toByteArray(Charsets.UTF_8)
        return EnhancedCrypto.encrypt(plaintext, masterKey, associatedData(packageName))
    }

    private fun decryptCredential(
        context: Context,
        packageName: String,
        blob: ByteArray
    ): Pair<CharArray, CharArray> {
        val masterKey = EnhancedCrypto.getOrCreateMasterKey(context)
            ?: throw IllegalStateException("Android Keystore master key unavailable")
        val plaintext = EnhancedCrypto.decrypt(blob, masterKey, associatedData(packageName))
        val text = String(plaintext, Charsets.UTF_8)
        val parts = text.split('\n', limit = 2)
        val storePassword = parts.getOrElse(0) { "" }.toCharArray()
        val keyPassword = if (parts.size > 1) parts[1].toCharArray() else storePassword
        return storePassword to keyPassword
    }

    private fun associatedData(packageName: String): ByteArray =
        "sherwood-studio/project-keystore/v1:$packageName".toByteArray(Charsets.UTF_8)

    private fun importedDir(context: Context): File = File(context.filesDir, STORE_DIR)

    private fun baseName(packageName: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(packageName.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "sig_" + digest.take(20)
    }

    private fun importedStoreFile(context: Context, packageName: String): File =
        File(importedDir(context), baseName(packageName) + ".p12")

    private fun importedAliasFile(context: Context, packageName: String): File =
        File(importedDir(context), baseName(packageName) + ".alias")

    private fun importedCredentialFile(context: Context, packageName: String): File =
        File(importedDir(context), baseName(packageName) + ".cred")

    private fun hashHex(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02x".format(it) }

    private const val GENERATED_ALIAS = "app"

    private val KEYSTORE_TYPES = listOf("PKCS12", "JKS")

    private const val MAX_KEYSTORE_BYTES = 8L * 1024L * 1024L
}
