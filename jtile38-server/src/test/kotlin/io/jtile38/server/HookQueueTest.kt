package io.jtile38.server

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HookQueueTest {
    private fun tmpFile() = File(Files.createTempDirectory("jtile38-queue").toFile(), "queue.db")

    private fun msg(
        hook: String,
        n: Int,
    ) = """{"command":"set","hook":"$hook","n":$n}"""

    @Test
    fun persistsAcrossReopen() {
        val f = tmpFile()
        val q = HookQueue(f).open()
        q.add(listOf(msg("a", 1), msg("b", 2), msg("a", 3)))
        assertEquals(listOf(1L, 3L), q.take("a").map { it.idx })
        q.close()

        val q2 = HookQueue(f).open()
        assertEquals(1, q2.size())
        assertEquals(listOf(msg("b", 2)), q2.take("b").map { it.message })
        // the index continues after a restart
        q2.add(listOf(msg("b", 4)))
        assertEquals(listOf(4L), q2.take("b").map { it.idx })
        q2.close()
        assertEquals(0, HookQueue(f).open().size())
    }

    @Test
    fun reinsertKeepsEntriesAndOrder() {
        val f = tmpFile()
        val q = HookQueue(f).open()
        q.add(listOf(msg("a", 1), msg("a", 2)))
        val taken = q.take("a")
        q.reinsert(taken.subList(1, 2))
        q.close()
        val q2 = HookQueue(f).open()
        assertEquals(listOf(2L), q2.take("a").map { it.idx })
    }

    @Test
    fun loadsBuntdbFilesAndDropsExpiredEntries() {
        val f = tmpFile()
        val now = System.currentTimeMillis() / 1000
        val live = msg("h", 1)
        val expired = msg("h", 2)

        fun rec(vararg a: String) = "*${a.size}\r\n" + a.joinToString("") { "$${it.length}\r\n$it\r\n" }
        f.writeText(
            rec("set", "hook:log:00000000000000000007", live, "ae", (now + 20).toString()) +
                rec("set", "hook:log:00000000000000000008", expired, "ae", (now - 5).toString()) +
                rec("set", "hook:idx", "00000000000000000008") +
                "\u0000" +
                rec("set", "hook:log:00000000000000000009", msg("h", 3), "ae", (now + 20).toString()) +
                rec("del", "hook:log:00000000000000000009") +
                "*3\r\n$3\r\nset\r\n$4\r\nhook", // incomplete tail
            Charsets.ISO_8859_1,
        )
        val q = HookQueue(f).open()
        assertEquals(listOf(live), q.take("h").map { it.message })
        q.add(listOf(msg("h", 4)))
        assertEquals(listOf(9L), q.take("h").map { it.idx })
        q.close()
        assertTrue(!f.readText(Charsets.ISO_8859_1).contains("\$4\r\nhook*"), "the incomplete record is truncated")
    }

    @Test
    fun shrinksLargeFiles() {
        val f = tmpFile()
        val q = HookQueue(f, shrinkMinSize = 4096).open()
        repeat(200) {
            q.add(listOf(msg("a", it)))
            q.take("a")
        }
        q.add(listOf(msg("a", 999)))
        q.close()
        assertTrue(f.length() < 8192, "file was shrunk: ${f.length()} bytes")
        assertEquals(listOf(msg("a", 999)), HookQueue(f).open().take("a").map { it.message })
    }

    @Test
    fun memoryOnlyWithoutFile() {
        val q = HookQueue(null).open()
        q.add(listOf(msg("a", 1)))
        assertEquals(1, q.size())
        q.close()
    }
}
