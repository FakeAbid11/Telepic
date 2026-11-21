package com.telepic.data.face

import com.telepic.diagnostics.TelepicLog
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Where the face models live on disk, and how they get there.
 *
 * The models are **not bundled in the APK**. They are downloaded on demand, only after the user opts
 * in, because they add ~4.7 MB that a user who never opens the People tab should not have to pay for.
 * That makes this class responsible for the one thing a bundled asset never needs: proving that what
 * landed on disk is the file [FaceModels] pins.
 *
 * Three properties matter, and each is a deliberate choice rather than an accident:
 *
 * - **Downloads are atomic.** Bytes go to `<name>.tmp` and are renamed only after the checksum passes.
 *   A process death mid-download therefore leaves either the old file or no file, never a half-written
 *   one that would fail to load at exactly the moment the user is waiting for it.
 * - **The checksum is verified before the rename.** A truncated body, a captive-portal HTML page, or a
 *   substituted model all fail the same way: the file never becomes the real name, so the loader can
 *   never open it.
 * - **State is derived from disk, not from a flag.** [isReady] stats the files. There is no
 *   "downloaded=true" preference to drift out of sync with reality after a failed write or a
 *   user-initiated clear-data.
 */
interface FaceModelStore {

    /** True when both model files exist and match their pinned size. Cheap enough to call per frame. */
    fun isReady(): Boolean

    /** Absolute path to a verified model file. Throws if [isReady] is false. */
    fun detectorPath(): String

    /** Absolute path to a verified model file. Throws if [isReady] is false. */
    fun embedderPath(): String

    /**
     * Downloads any missing model.
     *
     * @param onProgress called with `(bytesDownloaded, totalBytes)` across both files. Invoked from
     *   the calling coroutine's context, so a worker passing an IO dispatcher gets it off the main
     *   thread.
     * @throws IOException if a download fails, a checksum mismatches, or the response is not 200.
     */
    suspend fun ensureDownloaded(onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> })

    /**
     * Deletes every model file.
     *
     * The user-facing "remove face data" action calls this alongside clearing the index: a model on
     * disk is only useful with an index, and leaving 4.7 MB behind after the user asked for their
     * face data to go is not what they asked for.
     */
    fun deleteAll()
}

/**
 * [FaceModelStore] over a private directory, fetching from the pinned release assets.
 *
 * @param directory where the files live. Production passes `context.filesDir/face_models`; tests pass
 *   a temporary directory.
 */
class HttpFaceModelStore(
    private val directory: File,
    private val baseUrl: String = FaceModels.DOWNLOAD_BASE_URL,
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 60_000,
) : FaceModelStore {

    override fun isReady(): Boolean = FaceModels.ALL.all { file ->
        val target = File(directory, file.name)
        target.isFile && target.length() == file.sizeBytes
    }

    override fun detectorPath(): String = verifiedPath(FaceModels.DETECTOR)

    override fun embedderPath(): String = verifiedPath(FaceModels.EMBEDDER)

    private fun verifiedPath(model: FaceModels.ModelFile): String {
        val target = File(directory, model.name)
        check(target.isFile) { "${model.name} is not downloaded; check isReady() before requesting a path" }
        return target.absolutePath
    }

    override suspend fun ensureDownloaded(onProgress: (downloaded: Long, total: Long) -> Unit) {
        directory.mkdirs()
        if (!directory.isDirectory) {
            throw IOException("could not create the model directory at ${directory.absolutePath}")
        }

        val total = FaceModels.TOTAL_BYTES
        var alreadyDone = 0L

        for (model in FaceModels.ALL) {
            val target = File(directory, model.name)
            if (target.isFile && target.length() == model.sizeBytes) {
                alreadyDone += model.sizeBytes
                onProgress(alreadyDone, total)
                continue
            }

            download(model, alreadyDone, total, onProgress)
            alreadyDone += model.sizeBytes
            onProgress(alreadyDone, total)
        }
    }

    override fun deleteAll() {
        FaceModels.ALL.forEach { model ->
            File(directory, model.name).delete()
            File(directory, "${model.name}.tmp").delete()
        }
    }

    private fun download(
        model: FaceModels.ModelFile,
        alreadyDone: Long,
        total: Long,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ) {
        val temporary = File(directory, "${model.name}.tmp")
        val url = URL("$baseUrl/${model.name}")

        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            instanceFollowRedirects = true
            requestMethod = "GET"
        }

        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val written = transfer(model, connection, temporary, digest, alreadyDone, total, onProgress)
            install(model, temporary, written, digest)
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            temporary.delete()
            throw cancellation
        } catch (io: IOException) {
            // Already carries the model name and the reason; nothing to add, and the caller turns it
            // into a retry.
            temporary.delete()
            throw io
        } catch (throwable: Throwable) {
            temporary.delete()
            TelepicLog.warn("FaceModels", "download of ${model.name} failed unexpectedly", throwable)
            throw throwable
        } finally {
            connection.disconnect()
        }
    }

    /** Streams the body to [temporary], returning how many bytes landed. */
    private fun transfer(
        model: FaceModels.ModelFile,
        connection: HttpURLConnection,
        temporary: File,
        digest: MessageDigest,
        alreadyDone: Long,
        total: Long,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): Long {
        val status = connection.responseCode
        if (status != HttpURLConnection.HTTP_OK) {
            // GitHub's release downloads answer 302 to an object store; HttpURLConnection follows that
            // only for same-protocol redirects, so a non-200 here means the asset is missing or the
            // redirect went somewhere this client will not follow.
            throw IOException("${model.name}: HTTP $status")
        }

        val expected = connection.contentLengthLong
        if (expected > 0 && expected != model.sizeBytes) {
            // Fail before transferring 4 MB that cannot possibly verify. `contentLengthLong` is -1 when
            // the server uses chunked encoding, which is why this is a guard and not the check itself.
            throw IOException(
                "${model.name}: server reports $expected bytes, expected ${model.sizeBytes}",
            )
        }

        return connection.inputStream.use { input ->
            temporary.outputStream().buffered().use { output ->
                copyBody(input, output, digest, alreadyDone, total, onProgress)
            }
        }
    }

    /**
     * Copies the response body, hashing as it goes.
     *
     * Progress is throttled to [PROGRESS_STEP_BYTES] steps: a per-chunk callback would post thousands
     * of state updates per second into a notification that can only show one progress bar.
     */
    private fun copyBody(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        digest: MessageDigest,
        alreadyDone: Long,
        total: Long,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var written = 0L
        var lastReport = 0L

        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            digest.update(buffer, 0, read)
            written += read
            if (written - lastReport >= PROGRESS_STEP_BYTES) {
                lastReport = written
                onProgress(alreadyDone + written, total)
            }
        }

        return written
    }

    /**
     * Verifies what landed on disk and moves it into place.
     *
     * The rename is the only moment the real filename can appear, and it happens strictly after both
     * checks — so a truncated body, a captive-portal page or a substituted model can never be opened
     * under the name the loader asks for.
     */
    private fun install(model: FaceModels.ModelFile, temporary: File, written: Long, digest: MessageDigest) {
        if (written != model.sizeBytes) {
            throw IOException("${model.name}: got $written bytes, expected ${model.sizeBytes}")
        }

        val actual = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        if (!actual.equals(model.sha256, ignoreCase = true)) {
            throw IOException("${model.name}: sha256 $actual does not match the pinned ${model.sha256}")
        }

        if (!temporary.renameTo(target(model))) {
            throw IOException("${model.name}: could not rename the verified download into place")
        }
    }

    private fun target(model: FaceModels.ModelFile) = File(directory, model.name)

    private companion object {
        const val BUFFER_SIZE = 64 * 1024

        /** ~64 KB of transfer per progress callback: frequent enough to look live, cheap enough to ignore. */
        const val PROGRESS_STEP_BYTES = 64L * 1024
    }
}
