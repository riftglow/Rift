package light.rift.ui

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import light.rift.ktx.Logs
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Minimal WebDAV backup: one fixed file (rift_backup.json) plus
 * timestamped history copies. Manual upload/download and the
 * auto-backup worker (AutoBackup, 5 min after a config change)
 * both go through here.
 * Server / username are stored in DataStore; the password is never stored in
 * plain text -- it is AES/GCM encrypted with a key held in AndroidKeyStore
 * and only the ciphertext is stored in DataStore.
 */
object WebDavBackup {

    const val FILE_NAME = "rift_backup.json"
    // Like Legado and other WebDAV clients: the server field takes the
    // bare root (e.g. https://dav.jianguoyun.com/dav/), and the client
    // itself creates and uses a subfolder under it. The user never has
    // to type the subfolder into the server address. Older builds stored
    // the full .../dav/Rift/ address; folderUrl() treats that as already
    // being the subfolder, so it is not doubled.
    const val SUBFOLDER = "Rift"
    const val DEFAULT_SERVER = "https://dav.jianguoyun.com/dav/"
    const val LEGACY_FOLDER_SERVER = "https://dav.jianguoyun.com/dav/Rift/"

    private const val KEY_ALIAS = "rift_webdav"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun folderUrl(server: String): String {
        val base = server.trim().trimEnd('/')
        require(base.isNotEmpty()) { "WebDAV server is empty" }
        return if (base.endsWith("/$SUBFOLDER")) base else "$base/$SUBFOLDER"
    }

    fun fileUrl(server: String): String {
        return "${folderUrl(server)}/$FILE_NAME"
    }

    // Uploads before the subfolder change went straight to the server
    // root (e.g. .../dav/rift_backup.json). Downloads still check there
    // as a fallback so an old backup is not silently lost.
    private fun legacyFileUrl(server: String): String {
        val base = server.trim().trimEnd('/')
        return "$base/$FILE_NAME"
    }

    fun upload(server: String, username: String, password: String, content: String) {
        uploadTo(server, username, password, FILE_NAME, content)
    }

    /**
     * PUT to an arbitrary file name inside the Rift folder. The fixed
     * file uses FILE_NAME; auto backup also writes timestamped history
     * copies (rift_backup_yyyyMMdd_HHmmss.json) through this.
     */
    fun uploadTo(
        server: String, username: String, password: String,
        fileName: String, content: String
    ) {
        require(username.isNotBlank()) { "WebDAV username is empty" }
        require(password.isNotBlank()) { "WebDAV password is empty" }
        ensureFolder(server, username, password)
        val request = Request.Builder()
            .url("${folderUrl(server)}/$fileName")
            .header("Authorization", Credentials.basic(username, password))
            .put(content.toByteArray(Charsets.UTF_8).toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("WebDAV upload failed: HTTP ${response.code}")
            }
        }
    }

    /** Lightweight connectivity probe: PROPFIND the Rift folder. 2xx = reachable. */
    fun probe(server: String, username: String, password: String): Boolean {
        require(username.isNotBlank()) { "WebDAV username is empty" }
        require(password.isNotBlank()) { "WebDAV password is empty" }
        val request = Request.Builder()
            .url(folderUrl(server) + "/")
            .header("Authorization", Credentials.basic(username, password))
            .header("Depth", "0")
            .method("PROPFIND", "".toRequestBody(null))
            .build()
        client.newCall(request).execute().use { response ->
            return response.isSuccessful
        }
    }

    /**
     * List timestamped history copies in the Rift folder, newest first
     * is the caller's sort. The PROPFIND body is parsed with a regex
     * over <href> entries: it only ever has to match our own file
     * names, and a full XML parse is not worth the dependency.
     */
    fun listHistoryFiles(server: String, username: String, password: String): List<String> {
        val request = Request.Builder()
            .url(folderUrl(server) + "/")
            .header("Authorization", Credentials.basic(username, password))
            .header("Depth", "1")
            .method("PROPFIND", "".toRequestBody(null))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("WebDAV list failed: HTTP ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            val pattern = Regex("rift_backup_\\d{8}_\\d{6}\\.json")
            return pattern.findAll(body).map { it.value }.distinct().toList()
        }
    }

    fun deleteFile(server: String, username: String, password: String, fileName: String) {
        val request = Request.Builder()
            .url("${folderUrl(server)}/$fileName")
            .header("Authorization", Credentials.basic(username, password))
            .delete()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 404) {
                throw IllegalStateException("WebDAV delete failed: HTTP ${response.code}")
            }
        }
    }

    /**
     * MKCOL the folder the backup file lives in. Nutstore (and several
     * other providers) only accept PUT inside an existing folder; creating
     * it first is what other WebDAV clients do. 201 = created, 405/409/403 =
     * already exists or root not creatable, 301/302 = redirect: ignore and
     * let PUT report the real result. Only auth failures are surfaced here,
     * so a wrong account/password shows as 401 instead of a misleading PUT 404.
     */
    private fun ensureFolder(server: String, username: String, password: String) {
        val folderUrl = folderUrl(server) + "/"
        val request = Request.Builder()
            .url(folderUrl)
            .header("Authorization", Credentials.basic(username, password))
            .method("MKCOL", "".toRequestBody(null))
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 401) {
                throw IllegalStateException("WebDAV auth failed: HTTP ${response.code}")
            }
            // Any other result (created, already exists, redirect, root
            // not creatable) is not fatal: PUT below gives the real answer.
        }
    }

    fun download(server: String, username: String, password: String): String {
        require(username.isNotBlank()) { "WebDAV username is empty" }
        require(password.isNotBlank()) { "WebDAV password is empty" }
        val urls = listOf(fileUrl(server), legacyFileUrl(server)).distinct()
        for (url in urls) {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", Credentials.basic(username, password))
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code == 404) {
                    return@use
                }
                if (!response.isSuccessful) {
                    throw IllegalStateException("WebDAV download failed: HTTP ${response.code}")
                }
                return response.body?.string()
                    ?: throw IllegalStateException("WebDAV download returned an empty body")
            }
        }
        throw IllegalStateException("No backup found on WebDAV server")
    }

    // ---- AndroidKeyStore AES/GCM password encryption ----

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        fun deleteDeadKey() {
            try {
                keyStore.deleteEntry(KEY_ALIAS)
            } catch (_: Exception) {
            }
        }
        try {
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        } catch (e: KeyPermanentlyInvalidatedException) {
            // The key is genuinely dead (e.g. a lock-screen change
            // invalidated it): drop it so a fresh key can be made
            // below. The stored ciphertext cannot be decrypted with
            // the new key, but keeping the dead entry would fail
            // every encrypt/decrypt call forever.
            Logs.w(e)
            deleteDeadKey()
        } catch (e: InvalidKeyException) {
            // Unusable (corrupt) key entry: same treatment.
            Logs.w(e)
            deleteDeadKey()
        } catch (e: UnrecoverableKeyException) {
            // Key present but cannot be recovered: same treatment.
            Logs.w(e)
            deleteDeadKey()
        } catch (e: Exception) {
            // S-B6: a transient failure (e.g. the keystore is
            // locked while the device is locked) must NOT delete
            // the key — deleting it would permanently destroy the
            // stored WebDAV password ciphertext. Fail loudly and
            // let the caller report it; the key is still there on
            // the next try. (decryptPassword turns this into null,
            // never into an overwrite of the ciphertext.)
            Logs.w(e)
            throw e
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun encryptPassword(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /**
     * Returns the plaintext, "" only when nothing was stored, and null
     * when decryption fails. Callers must NOT treat null as "" : a null
     * here means "stored ciphertext exists but cannot be read", and
     * overwriting it with an empty encryption would destroy it forever.
     */
    fun decryptPassword(encoded: String): String? {
        if (encoded.isEmpty()) return ""
        return try {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            require(combined.size > 12) { "Invalid stored WebDAV password" }
            val iv = combined.copyOfRange(0, 12)
            val ciphertext = combined.copyOfRange(12, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE, secretKey(),
                GCMParameterSpec(128, iv)
            )
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            Logs.w(e)
            null
        }
    }
}
