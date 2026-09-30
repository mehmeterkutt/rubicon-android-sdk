package com.rubicon.analytics.internal.storage

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

class FileSystemTest {
    @TempDir
    lateinit var dir: File

    private val fs = FileSystem.SYSTEM

    @Test
    fun `append adds to the end and creates the file`() {
        val file = File(dir, "0000000000.tmp")

        fs.openAppend(file).use { it.write("a\n".toByteArray()) }
        fs.openAppend(file).use {
            it.write("b\n".toByteArray())
            it.sync()
        }

        assertEquals("a\nb\n", file.readText())
        assertEquals(4L, fs.length(file))
    }

    @Test
    fun `write replaces the content`() {
        val file = File(dir, "device.json.tmp").apply { writeText("old content") }

        fs.openWrite(file).use {
            it.write("new".toByteArray())
            it.sync()
        }

        assertEquals("new", file.readText())
    }

    @Test
    fun `read returns the bytes`() {
        val file = File(dir, "person.json").apply { writeText("{\"v\":1}") }

        assertArrayEquals("{\"v\":1}".toByteArray(), fs.openRead(file).use { it.readBytes() })
    }

    @Test
    fun `reading a missing file throws IOException`() {
        assertThrows<IOException> { fs.openRead(File(dir, "missing")) }
    }

    @Test
    fun `rename replaces the target`() {
        val tmp = File(dir, "device.json.tmp").apply { writeText("new") }
        val target = File(dir, "device.json").apply { writeText("old") }

        assertTrue(fs.rename(tmp, target))

        assertEquals("new", target.readText())
        assertFalse(fs.exists(tmp))
    }

    @Test
    fun `list, mkdirs and delete`() {
        val events = File(dir, "events/v1/abc")

        assertEquals(emptyList<File>(), fs.list(events))
        assertTrue(fs.mkdirs(events))
        assertTrue(fs.mkdirs(events))
        File(events, "0000000001").writeText("x")

        assertEquals(listOf("0000000001"), fs.list(events).map { it.name })
        assertTrue(fs.delete(File(events, "0000000001")))
        assertFalse(fs.delete(File(events, "0000000001")))
    }
}
