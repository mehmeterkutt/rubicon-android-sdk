package com.rubicon.analytics.testing

import com.rubicon.analytics.testing.FaultyFileSystem.Operation
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File
import java.io.IOException

class FaultyFileSystemTest {
    @TempDir
    lateinit var dir: File

    private val fs = FaultyFileSystem()

    private fun file(name: String = "a") = File(dir, name).apply { writeText("x") }

    // Calls the operation once, on a file that exists, so a healthy call succeeds.
    private fun call(operation: Operation) {
        val f = file()
        when (operation) {
            Operation.EXISTS -> fs.exists(f)
            Operation.LIST -> fs.list(dir)
            Operation.MKDIRS -> fs.mkdirs(File(dir, "sub"))
            Operation.LENGTH -> fs.length(f)
            Operation.OPEN_READ -> fs.openRead(f).close()
            Operation.OPEN_APPEND -> fs.openAppend(f).close()
            Operation.OPEN_WRITE -> fs.openWrite(f).close()
            Operation.WRITE -> fs.openAppend(f).use { it.write(byteArrayOf(1)) }
            Operation.SYNC -> fs.openAppend(f).use { it.sync() }
            Operation.RENAME -> fs.rename(f, File(dir, "b"))
            Operation.DELETE -> fs.delete(f)
        }
    }

    @Nested
    inner class `failing once` {
        @ParameterizedTest
        @EnumSource(Operation::class)
        fun `fails the next call and then succeeds`(operation: Operation) {
            fs.failOnce(operation)

            val error = assertThrows<IOException> { call(operation) }
            call(operation)

            assertEquals("Injected failure: $operation", error.message)
        }
    }

    @Nested
    inner class `failing always` {
        @ParameterizedTest
        @EnumSource(Operation::class)
        fun `fails every call until healed`(operation: Operation) {
            fs.failAlways(operation)

            repeat(3) { assertThrows<IOException> { call(operation) } }
            fs.heal()
            call(operation)
        }

        @Test
        fun `only the chosen operation fails`() {
            fs.failAlways(Operation.RENAME)

            Operation.entries.filter { it != Operation.RENAME }.forEach { call(it) }
        }
    }

    @Nested
    inner class `when healthy` {
        @Test
        fun `passes every operation to the real files`() {
            val a = File(dir, "a")
            fs.openWrite(a).use { it.write("line 1\n".toByteArray()) }
            fs.openAppend(a).use {
                it.write("line 2\n".toByteArray())
                it.sync()
            }

            assertTrue(fs.exists(a))
            assertEquals(14L, fs.length(a))
            assertArrayEquals("line 1\nline 2\n".toByteArray(), fs.openRead(a).use { it.readBytes() })
            assertTrue(fs.mkdirs(File(dir, "sub")))
            assertTrue(fs.rename(a, File(dir, "b")))
            assertEquals(listOf("b", "sub"), fs.list(dir).map { it.name }.sorted())
            assertTrue(fs.delete(File(dir, "b")))
            assertFalse(fs.exists(File(dir, "b")))
        }
    }
}
