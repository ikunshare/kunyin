package com.ikunshare.music.utils.tag.m4a

import com.ikunshare.music.utils.tag.MusicMeta
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 读写 m4a/mp4 文件里的 iTunes 标签（ilst 盒）。
 *
 * 只处理 MusicMeta 里有的字段：标题、艺术家、专辑、曲号、碟号、歌词、封面。
 * 写入策略：先移除既有 ilst（如有），再在 udta/meta 下插入新 ilst；
 * moov 大小同步更新。这避免了重排 mdat（样本数据在 mdat 里不会被改动）。
 */
object M4aTagHandler {

    fun read(filePath: String): MusicMeta? = runCatching {
        readInternal(File(filePath))
    }.getOrNull()

    fun write(filePath: String, meta: MusicMeta) {
        writeInternal(File(filePath), meta)
    }

    // ── 读 ────────────────────────────────────────────────────────────────

    private fun readInternal(file: File): MusicMeta? {
        val bytes = file.readBytes()
        val root = findChildren(bytes, 0, bytes.size)
        val moov = root.firstOrNull { it.type == "moov" } ?: return null
        val udta = findChildren(bytes, moov.body, moov.end).firstOrNull { it.type == "udta" } ?: return null
        val meta = findChildren(bytes, udta.body, udta.end).firstOrNull { it.type == "meta" } ?: return null
        // meta 有 4 字节 version/flags。
        val metaBody = meta.body + 4
        val ilst = findChildren(bytes, metaBody, meta.end).firstOrNull { it.type == "ilst" } ?: return null

        val result = MusicMeta()
        for (item in findChildren(bytes, ilst.body, ilst.end)) {
            val data = findChildren(bytes, item.body, item.end).firstOrNull { it.type == "data" } ?: continue
            val dataBody = data.body + 8 // 4 type + 4 locale
            val value = bytes.copyOfRange(dataBody, data.end)
            when (item.type) {
                "©nam" -> result.title = String(value, Charsets.UTF_8)
                "©ART" -> result.artist = String(value, Charsets.UTF_8)
                "©alb" -> result.album = String(value, Charsets.UTF_8)
                "trkn" -> if (value.size >= 4) result.trackNumber = u16be(value, 2)
                "disk" -> if (value.size >= 4) result.discNumber = u16be(value, 2)
                "©lyr" -> result.lyrics = String(value, Charsets.UTF_8)
                "covr" -> {
                    result.pictureData = value
                    result.pictureMimeType = if (value.size > 4 &&
                        value.copyOfRange(0, 4).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)))
                        "image/png" else "image/jpeg"
                }
            }
        }
        return result
    }

    // ── 写 ────────────────────────────────────────────────────────────────

    private fun writeInternal(file: File, meta: MusicMeta) {
        val bytes = file.readBytes()
        val root = findChildren(bytes, 0, bytes.size)
        val moov = root.firstOrNull { it.type == "moov" }
            ?: throw IllegalStateException("m4a 文件缺少 moov 盒")

        // 构建 ilst。
        val ilst = buildIlst(meta)

        // 构建 udta/meta/ilst，把原有 udta 里的其他内容（如版权）保留。
        val oldUdta = findChildren(bytes, moov.body, moov.end).firstOrNull { it.type == "udta" }
        val udtaContent = buildUdtaContent(bytes, oldUdta, ilst)

        // 重建 moov：去掉旧 udta，拼入新 udta。
        val before = bytes.copyOfRange(moov.body, if (oldUdta != null) oldUdta.start else moov.end)
        val after = if (oldUdta != null) bytes.copyOfRange(oldUdta.end, moov.end) else byteArrayOf()
        val newMoov = box("moov", before + udtaContent + after)

        // 写回文件：替换 moov，ftyp + 其他顶层盒保持不变。
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0L)
            var pos = 0
            for (top in root) {
                if (top.type == "moov") {
                    raf.write(newMoov)
                } else {
                    raf.write(bytes, top.start, top.end - top.start)
                }
                pos = top.end
            }
        }
    }

    private fun buildUdtaContent(bytes: ByteArray, oldUdta: Box?, ilst: ByteArray): ByteArray {
        // 保留 udta 下除 meta 以外的子盒。
        val existingOther = if (oldUdta != null) {
            findChildren(bytes, oldUdta.body, oldUdta.end)
                .filter { it.type != "meta" }
                .map { bytes.copyOfRange(it.start, it.end) }
        } else emptyList()

        // meta = fullbox(4字节flags) + hdlr + ilst
        val hdlr = box("hdlr",
            u32(0), u32(0), "mdir".toByteArray(Charsets.ISO_8859_1),
            ByteArray(12), byteArrayOf(0)
        )
        val metaBody = ByteArray(4) + hdlr + ilst
        val meta = box("meta", metaBody)

        val udtaChildren = (existingOther + listOf(meta)).fold(byteArrayOf()) { acc, b -> acc + b }
        return box("udta", udtaChildren)
    }

    private fun buildIlst(meta: MusicMeta): ByteArray {
        val items = mutableListOf<ByteArray>()
        meta.title?.let { items += textItem("©nam", it) }
        meta.artist?.let { items += textItem("©ART", it) }
        meta.album?.let { items += textItem("©alb", it) }
        meta.trackNumber?.let { items += numericItem("trkn", it, meta.trackNumber) }
        meta.discNumber?.let { items += numericItem("disk", it, meta.discTotal) }
        meta.lyrics?.let { items += textItem("©lyr", it) }
        val cover = meta.pictureData
        if (cover != null) {
            val isPng = cover.size > 4 &&
                cover.copyOfRange(0, 4).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47))
            val dataType = if (isPng) 14 else 13
            val data = box("data", u32(dataType), u32(0), cover)
            items += box("covr", data)
        }
        if (items.isEmpty()) return box("ilst", byteArrayOf())
        return box("ilst", items.fold(byteArrayOf()) { acc, b -> acc + b })
    }

    private fun textItem(tag: String, text: String): ByteArray {
        val utf8 = text.toByteArray(Charsets.UTF_8)
        val data = box("data", u32(1), u32(0), utf8)
        return box(tag, data)
    }

    private fun numericItem(tag: String, number: Int, total: Int?): ByteArray {
        val payload = ByteArray(8)
        payload[3] = number.toByte()
        total?.let { payload[5] = it.toByte() }
        val data = box("data", u32(0), u32(0), payload)
        return box(tag, data)
    }

    // ── MP4 盒工具 ─────────────────────────────────────────────────────────

    private data class Box(val type: String, val start: Int, val end: Int, val body: Int)

    private fun findChildren(bytes: ByteArray, from: Int, to: Int): List<Box> {
        val list = mutableListOf<Box>()
        var pos = from
        while (pos + 8 <= to) {
            val size = ByteBuffer.wrap(bytes, pos, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffff_ffffL
            if (size < 8L || pos + size > to) break
            val type = String(bytes, pos + 4, 4, Charsets.ISO_8859_1)
            list += Box(type, pos, (pos + size).toInt(), pos + 8)
            pos += size.toInt()
        }
        return list
    }

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = parts.fold(byteArrayOf()) { acc, b -> acc + b }
        val size = 8 + body.size
        val header = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .putInt(size).put(type.toByteArray(Charsets.ISO_8859_1)).array()
        return header + body
    }

    private fun u32(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array()

    private fun u16be(data: ByteArray, at: Int): Int =
        ((data[at].toInt() and 0xff) shl 8) or (data[at + 1].toInt() and 0xff)
}
