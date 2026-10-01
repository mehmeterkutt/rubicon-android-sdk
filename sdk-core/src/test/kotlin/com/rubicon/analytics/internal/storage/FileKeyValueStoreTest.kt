package com.rubicon.analytics.internal.storage

import com.rubicon.analytics.testing.FaultyFileSystem
import com.rubicon.analytics.testing.FaultyFileSystem.Operation
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class FileKeyValueStoreTest {
    @TempDir
    lateinit var temp: File

    private val root get() = File(temp, "rubicon")
    private val faulty by lazy { FaultyFileSystem() }
    private val store by lazy { FileKeyValueStore(root, faulty) }

    private fun content(read: KeyValueStore.Read) = (read as KeyValueStore.Read.Present).content

    @Nested
    inner class `reading and writing` {
        @Test
        fun `a store never written is missing`() {
            assertEquals(KeyValueStore.Read.Missing, store.read("device.json"))
        }

        @Test
        fun `a write creates the directory and reads back`() {
            assertTrue(store.write("device.json", """{"v":1}"""))

            assertEquals("""{"v":1}""", content(store.read("device.json")))
            assertTrue(File(root, "device.json").isFile)
        }

        @Test
        fun `a write replaces the whole value and leaves no tmp file`() {
            store.write("person.json", "a much longer first value")
            store.write("person.json", "short")

            assertEquals("short", content(store.read("person.json")))
            assertEquals(listOf("person.json"), root.list()!!.toList())
        }

        @Test
        fun `non-ASCII text survives`() {
            store.write("person.json", "Ödeme şöyle 💳")

            assertEquals("Ödeme şöyle 💳", content(store.read("person.json")))
        }

        @Test
        fun `delete removes the value and is fine when there is none`() {
            store.write("remote-config.json", "{}")

            assertTrue(store.delete("remote-config.json"))
            assertTrue(store.delete("remote-config.json"))
            assertEquals(KeyValueStore.Read.Missing, store.read("remote-config.json"))
        }
    }

    @Nested
    inner class `atomic replacement` {
        @Test
        fun `a failed rename leaves the old file valid`() {
            store.write("device.json", "old")
            faulty.failOnce(Operation.RENAME)

            assertFalse(store.write("device.json", "new"))

            assertEquals("old", content(store.read("device.json")))
            assertFalse(File(root, "device.json.tmp").exists())
        }

        @Test
        fun `a failed sync never reaches the file`() {
            store.write("device.json", "old")
            faulty.failOnce(Operation.SYNC)

            assertFalse(store.write("device.json", "new"))
            assertEquals("old", content(store.read("device.json")))
        }

        @Test
        fun `a crash between write and rename leaves a tmp that the next read deletes`() {
            store.write("device.json", "old")
            File(root, "device.json.tmp").writeText("half writ")

            assertEquals("old", content(store.read("device.json")))
            assertFalse(File(root, "device.json.tmp").exists())
        }

        @Test
        fun `a leftover tmp without a file reads as missing`() {
            root.mkdirs()
            File(root, "person.json.tmp").writeText("{}")

            assertEquals(KeyValueStore.Read.Missing, store.read("person.json"))
            assertFalse(File(root, "person.json.tmp").exists())
        }
    }

    @Nested
    inner class `unreadable files` {
        @Test
        fun `an I-O error is unreadable and the file is left byte-identical`() {
            store.write("device.json", "keep me")
            val before = File(root, "device.json").readBytes()
            faulty.failOnce(Operation.OPEN_READ)

            assertInstanceOf(KeyValueStore.Read.Unreadable::class.java, store.read("device.json"))
            assertArrayEquals(before, File(root, "device.json").readBytes())
        }

        @Test
        fun `bytes that are not UTF-8 are unreadable`() {
            root.mkdirs()
            File(root, "device.json").writeBytes(byteArrayOf(0x7B, 0xC3.toByte(), 0x28, 0x7D))

            assertInstanceOf(KeyValueStore.Read.Unreadable::class.java, store.read("device.json"))
        }

        @Test
        fun `a failed write or delete returns false instead of throwing`() {
            faulty.failAlways(Operation.OPEN_WRITE)
            assertFalse(store.write("device.json", "x"))

            faulty.heal()
            store.write("device.json", "x")
            faulty.failAlways(Operation.DELETE)
            assertFalse(store.delete("device.json"))
        }
    }

    @Test
    fun `files are app-private`() {
        assumeTrue("posix" in FileSystems.getDefault().supportedFileAttributeViews())
        val system = FileKeyValueStore(root)

        system.write("device.json", "{}")

        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(File(root, "device.json").toPath())))
    }
}
