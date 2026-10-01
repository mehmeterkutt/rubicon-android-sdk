package com.rubicon.analytics.testing

import com.rubicon.analytics.internal.storage.FileSink
import com.rubicon.analytics.internal.storage.FileSystem
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * A [FileSystem] over real files ([delegate]) that throws [IOException] from the operations the test
 * chooses, once or always.
 */
public class FaultyFileSystem(
    private val delegate: FileSystem = FileSystem.SYSTEM,
) : FileSystem {
    public enum class Operation { EXISTS, LIST, MKDIRS, LENGTH, OPEN_READ, OPEN_APPEND, OPEN_WRITE, WRITE, SYNC, TRUNCATE, RENAME, DELETE }

    private val lock = Any()
    private val once = mutableSetOf<Operation>()
    private val always = mutableSetOf<Operation>()

    /** The next call of [operation] fails; later ones succeed. */
    public fun failOnce(operation: Operation) {
        synchronized(lock) { once += operation }
    }

    /** Every call of [operation] fails until [heal]. */
    public fun failAlways(operation: Operation) {
        synchronized(lock) { always += operation }
    }

    public fun heal() {
        synchronized(lock) {
            once.clear()
            always.clear()
        }
    }

    private fun check(operation: Operation) {
        val fail = synchronized(lock) { operation in always || once.remove(operation) }
        if (fail) throw IOException("Injected failure: $operation")
    }

    override fun exists(file: File): Boolean {
        check(Operation.EXISTS)
        return delegate.exists(file)
    }

    override fun list(dir: File): List<File> {
        check(Operation.LIST)
        return delegate.list(dir)
    }

    override fun mkdirs(dir: File): Boolean {
        check(Operation.MKDIRS)
        return delegate.mkdirs(dir)
    }

    override fun length(file: File): Long {
        check(Operation.LENGTH)
        return delegate.length(file)
    }

    override fun openRead(file: File): InputStream {
        check(Operation.OPEN_READ)
        return delegate.openRead(file)
    }

    override fun openAppend(file: File): FileSink {
        check(Operation.OPEN_APPEND)
        return FaultySink(delegate.openAppend(file))
    }

    override fun openWrite(file: File): FileSink {
        check(Operation.OPEN_WRITE)
        return FaultySink(delegate.openWrite(file))
    }

    override fun truncate(
        file: File,
        length: Long,
    ) {
        check(Operation.TRUNCATE)
        delegate.truncate(file, length)
    }

    override fun rename(
        from: File,
        to: File,
    ): Boolean {
        check(Operation.RENAME)
        return delegate.rename(from, to)
    }

    override fun delete(file: File): Boolean {
        check(Operation.DELETE)
        return delegate.delete(file)
    }

    private inner class FaultySink(
        private val sink: FileSink,
    ) : FileSink {
        override fun write(bytes: ByteArray) {
            check(Operation.WRITE)
            sink.write(bytes)
        }

        override fun sync() {
            check(Operation.SYNC)
            sink.sync()
        }

        override fun close() {
            sink.close()
        }
    }
}
