package com.rubicon.analytics.internal.storage

import com.rubicon.analytics.RubiconInternal
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * [KeyValueStore] as small files in [root], `noBackupFilesDir/rubicon/` on Android (Disk 2a, 7).
 *
 * - A write replaces the whole file atomically: `{name}.tmp` is written, synced and renamed over
 *   `{name}`, so a reader sees the old or the new content, never a mix.
 * - A `{name}.tmp` found when [name][read] is first read is a write whose rename never happened; it
 *   is deleted and the old file is intact. Reads and writes run on the writer only (Queue 1), so a
 *   `.tmp` is never one in progress.
 * - A file that exists but cannot be read, or is not valid UTF-8, is [KeyValueStore.Read.Unreadable].
 *   In Direct Boot the directory may not be visible at all; that reads as missing and the following
 *   write fails, so the file is not overwritten either way.
 *
 * Never throws; a failed write or delete returns `false` and the caller logs it.
 */
@RubiconInternal
public class FileKeyValueStore(
    private val root: File,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : KeyValueStore {
    override fun read(name: String): KeyValueStore.Read {
        val file = File(root, name)
        return try {
            discardLeftover(name)
            if (!fileSystem.exists(file)) {
                KeyValueStore.Read.Missing
            } else {
                val bytes = fileSystem.openRead(file).use { it.readBytes() }
                KeyValueStore.Read.Present(decodeUtf8(bytes))
            }
        } catch (e: IOException) {
            KeyValueStore.Read.Unreadable(e)
        } catch (e: SecurityException) {
            KeyValueStore.Read.Unreadable(e)
        }
    }

    override fun write(
        name: String,
        content: String,
    ): Boolean {
        val file = File(root, name)
        val tmp = tmpOf(name)
        return try {
            fileSystem.mkdirs(root)
            fileSystem.openWrite(tmp).use { sink ->
                sink.write(content.toByteArray(Charsets.UTF_8))
                sink.sync()
            }
            if (!fileSystem.rename(tmp, file)) throw IOException("Could not rename $name.tmp")
            true
        } catch (e: IOException) {
            deleteQuietly(tmp)
            false
        } catch (e: SecurityException) {
            deleteQuietly(tmp)
            false
        }
    }

    override fun delete(name: String): Boolean {
        val file = File(root, name)
        return try {
            !fileSystem.exists(file) || fileSystem.delete(file)
        } catch (e: IOException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    private fun tmpOf(name: String) = File(root, "$name.tmp")

    private fun discardLeftover(name: String) {
        val tmp = tmpOf(name)
        if (fileSystem.exists(tmp)) fileSystem.delete(tmp)
    }

    private fun deleteQuietly(file: File) {
        try {
            fileSystem.delete(file)
        } catch (e: IOException) {
            // The next read of the store deletes the leftover.
        } catch (e: SecurityException) {
            // Same.
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String =
        try {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            throw IOException("Not valid UTF-8", e)
        }
}
