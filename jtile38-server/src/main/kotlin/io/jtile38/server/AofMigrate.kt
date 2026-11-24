// Port of tile38 internal/server/aofmigrate.go, Copyright Joshua J Baker, MIT License.
// Kotlin port Copyright jtile38 authors, MIT License.

package io.jtile38.server

import io.jtile38.resp.RespValue
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.logging.Logger

private val migrateLog = Logger.getLogger("io.jtile38.server.AofMigrate")

/**
 * Reads records of the pre-1.0 Tile38 AOF format (Go `LegacyAOFReader`): each record is a
 * little-endian uint32 size, the command line, the size again and a zero byte.
 */
internal class LegacyAofReader(
    private val data: ByteArray,
) {
    private var p = 0

    private fun u32(at: Int): Long =
        (data[at].toLong() and 0xFF) or
            ((data[at + 1].toLong() and 0xFF) shl 8) or
            ((data[at + 2].toLong() and 0xFF) shl 16) or
            ((data[at + 3].toLong() and 0xFF) shl 24)

    /** Returns the next command line, or null at the end; throws on a corrupted or truncated file. */
    fun readCommand(): ByteArray? {
        val l = data.size - p
        if (l >= 4) {
            val sz1 = u32(p)
            if (l >= sz1 + 9) {
                // we have enough data for a record
                val n = sz1.toInt()
                val sz2 = u32(p + 4 + n)
                if (sz2 != sz1 || data[p + 4 + n + 4] != 0.toByte()) throw IOException("corrupted aof file")
                val cmd = data.copyOfRange(p + 4, p + 4 + n)
                p += n + 9
                return cmd
            }
        }
        if (l != 0) throw IOException("unexpected EOF")
        return null
    }
}

/**
 * Converts a legacy `<dir>/aof` file into `<dir>/appendonly.aof` (Go `migrateAOF`). Does nothing
 * when appendonly.aof already exists or there is no legacy file.
 */
internal fun migrateLegacyAof(dir: File) {
    if (File(dir, "appendonly.aof").exists()) return
    val old = File(dir, "aof")
    if (!old.exists()) return
    migrateLog.warning("Migrating aof to new format")
    val migrate = File(dir, "migrate.aof")
    val rd = LegacyAofReader(old.readBytes())
    var count = 0
    BufferedOutputStream(FileOutputStream(migrate), 1 shl 20).use { wr ->
        while (true) {
            val cmd = rd.readCommand() ?: break
            var line = String(cmd, Charsets.ISO_8859_1)
            val values = ArrayList<RespValue>()
            while (line != "") {
                val i = line.indexOf(' ')
                var tok: String
                if (i < 0) {
                    tok = line
                    line = ""
                } else {
                    tok = line.substring(0, i)
                    line = line.substring(i + 1)
                }
                // a JSON object takes the rest of the line
                if (tok.isNotEmpty() && tok[0] == '{' && line != "") {
                    tok = "$tok $line"
                    line = ""
                }
                values.add(RespValue.string(tok))
            }
            wr.write(RespValue.array(values).marshal())
            count++
        }
    }
    migrateLog.fine("$count items migrated")
    if (!migrate.renameTo(File(dir, "appendonly.aof"))) throw IOException("rename ${migrate.path} failed")
}
