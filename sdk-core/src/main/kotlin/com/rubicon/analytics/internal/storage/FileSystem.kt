package com.rubicon.analytics.internal.storage

import com.rubicon.analytics.RubiconInternal
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * The file operations the file-based stores use. It exists so that tests can inject failures;
 * production is plain `java.io` ([SYSTEM]). Every operation may throw [IOException].
 */
@RubiconInternal
public interface FileSystem {
    public fun exists(file: File): Boolean

    /** The entries of [dir]; empty when it does not exist. */
    public fun list(dir: File): List<File>

    public fun mkdirs(dir: File): Boolean

    public fun length(file: File): Long

    public fun openRead(file: File): InputStream

    /** Opens [file] for appending, creating it if needed; only the app may read or write it. */
    public fun openAppend(file: File): FileSink

    /** Opens [file] for writing from the start, replacing its content; only the app may read or write it. */
    public fun openWrite(file: File): FileSink

    /** Renames [from] over [to], replacing it. */
    public fun rename(
        from: File,
        to: File,
    ): Boolean

    public fun delete(file: File): Boolean

    public companion object {
        public val SYSTEM: FileSystem = JavaIoFileSystem
    }
}

/** An open file being written. */
@RubiconInternal
public interface FileSink : Closeable {
    public fun write(bytes: ByteArray)

    /** Forces written bytes to the storage device (`fsync`, Disk 4). */
    public fun sync()
}

private object JavaIoFileSystem : FileSystem {
    override fun exists(file: File): Boolean = file.exists()

    override fun list(dir: File): List<File> = dir.listFiles()?.toList().orEmpty()

    override fun mkdirs(dir: File): Boolean = dir.mkdirs() || dir.isDirectory

    override fun length(file: File): Long = file.length()

    override fun openRead(file: File): InputStream = FileInputStream(file)

    override fun openAppend(file: File): FileSink = StreamSink(FileOutputStream(file, true)).also { restrictToOwner(file) }

    override fun openWrite(file: File): FileSink = StreamSink(FileOutputStream(file, false)).also { restrictToOwner(file) }

    // Android's app directories are private already; this keeps a file private on any file system.
    private fun restrictToOwner(file: File) {
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
        file.setExecutable(false, false)
    }

    override fun rename(
        from: File,
        to: File,
    ): Boolean = from.renameTo(to)

    override fun delete(file: File): Boolean = file.delete()
}

private class StreamSink(
    private val stream: FileOutputStream,
) : FileSink {
    override fun write(bytes: ByteArray) = stream.write(bytes)

    override fun sync() {
        stream.flush()
        stream.fd.sync()
    }

    override fun close() = stream.close()
}
