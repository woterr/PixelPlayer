package com.theveloper.pixelplay.data.lyrics

import com.theveloper.pixelplay.data.model.SyncedLine
import com.theveloper.pixelplay.data.model.SyncedWord
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

/**
 * Parses Apple Music / BetterLyrics TTML into PixelPlayer's synced model.
 *
 * The port keeps LastWave's behaviour for the documents that matter in
 * practice: nested `<span>` timing, clock formats, `lyricOffset` correction
 * and background vocals. Background/translation/romanization tracks are
 * skipped because PixelPlayer renders one karaoke row per line.
 */
internal object TtmlLyricsParser {
    private const val BACKGROUND_ROLE = "x-bg"
    private val SKIPPED_ROLES = setOf("x-translation", "x-roman")

    fun parse(ttml: String): List<SyncedLine> {
        if (ttml.isBlank() || "<p" !in ttml.lowercase()) return emptyList()
        return runCatching {
            val factory = newDocumentFactory()
            val document = factory.newDocumentBuilder().parse(InputSource(StringReader(ttml)))
            val offsetMs = readLyricOffset(document)
            val paragraphs = document.getElementsByTagName("p")
            buildList {
                for (i in 0 until paragraphs.length) {
                    val paragraph = paragraphs.item(i) as? Element ?: continue
                    lineFrom(paragraph, offsetMs)?.let(::add)
                }
            }.sortedBy { it.time }
        }.getOrDefault(emptyList())
    }

    fun parseTime(value: String?): Long? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (raw.endsWith("ms", ignoreCase = true)) {
            return raw.dropLast(2).toDoubleOrNull()?.toLong()
        }
        val stripped = if (raw.endsWith("s", ignoreCase = true) && ":" !in raw) raw.dropLast(1) else raw
        val parts = stripped.split(':')
        val seconds = when (parts.size) {
            1 -> parts[0].toDoubleOrNull()
            2 -> parts[0].toDoubleOrNull()?.let { m -> parts[1].toDoubleOrNull()?.let { m * 60 + it } }
            3 -> parts[0].toDoubleOrNull()?.let { h ->
                parts[1].toDoubleOrNull()?.let { m ->
                    parts[2].toDoubleOrNull()?.let { h * 3600 + m * 60 + it }
                }
            }
            else -> null
        } ?: return null
        return (seconds * 1000).toLong()
    }

    private fun newDocumentFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { isExpandEntityReferences = false }
        }

    private fun readLyricOffset(document: org.w3c.dom.Document): Long {
        return runCatching {
            // Apple puts `ttm:lyricOffset` on <tt> (and occasionally on
            // <audio>); check the root first, then any other element.
            val candidates = ArrayList<Element>()
            document.documentElement?.let(candidates::add)
            for (tag in listOf("audio", "tt")) {
                val nodes = document.getElementsByTagName(tag)
                for (i in 0 until nodes.length) {
                    (nodes.item(i) as? Element)?.let(candidates::add)
                }
            }
            for (element in candidates) {
                readLyricOffsetAttribute(element)?.let { return it }
            }
            0L
        }.getOrDefault(0L)
    }

    private fun readLyricOffsetAttribute(element: Element): Long? {
        val attributes = element.attributes ?: return null
        for (i in 0 until attributes.length) {
            val attribute = attributes.item(i) ?: continue
            if (attribute.nodeName.substringAfter(':') == "lyricOffset" ||
                attribute.localName == "lyricOffset"
            ) {
                val seconds = attribute.nodeValue?.toDoubleOrNull()
                if (seconds != null && seconds.isFinite()) {
                    return (seconds * 1000).toLong()
                }
            }
        }
        return null
    }

    private fun lineFrom(paragraph: Element, offsetMs: Long): SyncedLine? {
        val pieces = mutableListOf<Piece>()
        collect(paragraph, pieces)
        val words = mergeIntoWords(pieces)
        val begin = attrTime(paragraph, "begin")
        if (words.isEmpty()) {
            val text = paragraph.textContent?.trim().orEmpty()
            if (begin == null || text.isEmpty()) return null
            return SyncedLine(
                time = (begin + offsetMs).coerceAtLeast(0L).toInt(),
                line = text,
            )
        }
        val lineStart = minOf(begin ?: words.first().timeMs, words.first().timeMs)
        val shifted = words.map { word ->
            SyncedWord(
                time = (word.timeMs + offsetMs).coerceAtLeast(0L).toInt(),
                word = word.text,
                startsNewWord = word.startsNewWord,
                durationMs = word.durationMs.coerceAtLeast(0L).toInt(),
            )
        }.sortedBy { it.time }
        return SyncedLine(
            time = (lineStart + offsetMs).coerceAtLeast(0L).toInt(),
            line = shifted.joinToString(" ") { it.word },
            words = clampWordEdges(shifted),
        )
    }

    /** A word never stays filled past the next word (or its line start), so
     *  overlapping TTML spans cannot keep two syllables lit at once. */
    private fun clampWordEdges(words: List<SyncedWord>): List<SyncedWord> {
        if (words.size < 2) return words
        return words.mapIndexed { index, word ->
            val nextStart = words.getOrNull(index + 1)?.time?.toLong() ?: return@mapIndexed word
            val end = word.time.toLong() + word.durationMs
            if (word.durationMs > 0 && end > nextStart) {
                word.copy(durationMs = (nextStart - word.time).coerceAtLeast(0L).toInt())
            } else {
                word
            }
        }
    }

    private fun collect(node: Node, out: MutableList<Piece>) {
        val children = node.childNodes
        for (i in 0 until children.length) {
            when (val child = children.item(i)) {
                is Element -> {
                    val role = qualified(child, "ttm:role")
                    if (role in SKIPPED_ROLES || role == BACKGROUND_ROLE) continue
                    val begin = attrTime(child, "begin")
                    val end = attrTime(child, "end") ?: (begin?.let { attrDuration(child, it) })
                    if (begin != null && end != null && !hasTimedChild(child)) {
                        out += Piece.Timed(child.textContent.orEmpty(), begin, end)
                    } else {
                        collect(child, out)
                    }
                }
                else -> if (child.nodeType == Node.TEXT_NODE) {
                    val text = child.textContent.orEmpty()
                    if (text.isNotEmpty()) out += Piece.Text(text)
                }
            }
        }
    }

    private fun hasTimedChild(element: Element): Boolean {
        val children = element.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i) as? Element ?: continue
            if (child.getAttribute("begin")?.isNotEmpty() == true || hasTimedChild(child)) return true
        }
        return false
    }

    private fun mergeIntoWords(pieces: List<Piece>): List<TimedWord> {
        val words = mutableListOf<TimedWord>()
        val current = StringBuilder()
        var start = 0L
        var end = 0L
        var timed = false
        var startsNewWord = true

        fun flush() {
            val text = current.toString().trim()
            current.setLength(0)
            if (text.isNotEmpty() && timed) {
                words += TimedWord(
                    timeMs = start,
                    durationMs = (end - start).coerceAtLeast(0L),
                    text = text,
                    startsNewWord = startsNewWord || words.isEmpty(),
                )
                startsNewWord = true
            }
            timed = false
        }

        pieces.forEach { piece ->
            when (piece) {
                is Piece.Text -> when {
                    piece.text.isBlank() -> flush()
                    timed -> current.append(piece.text)
                    else -> Unit
                }
                is Piece.Timed -> {
                    if (piece.text.isBlank()) return@forEach
                    if (piece.text.first().isWhitespace()) flush()
                    if (current.isEmpty()) {
                        start = piece.start
                        startsNewWord = true
                    }
                    current.append(piece.text.trim())
                    end = piece.end
                    timed = true
                    if (piece.text.last().isWhitespace()) flush()
                }
            }
        }
        flush()
        return words
    }

    private fun attrTime(element: Element, name: String): Long? {
        parseTime(element.getAttribute(name))?.let { return it }
        val local = name.substringAfter(':')
        val attributes = element.attributes ?: return null
        for (i in 0 until attributes.length) {
            val attribute = attributes.item(i) ?: continue
            if (attribute.nodeName == name || attribute.nodeName == local || attribute.localName == local) {
                parseTime(attribute.nodeValue)?.let { return it }
            }
        }
        return null
    }

    private fun attrDuration(element: Element, begin: Long): Long? {
        val raw = element.getAttribute("dur")?.takeIf { it.isNotBlank() }
            ?: run {
                val attributes = element.attributes ?: return null
                var found: String? = null
                for (i in 0 until attributes.length) {
                    val attribute = attributes.item(i) ?: continue
                    if (attribute.nodeName == "dur" || attribute.localName == "dur") {
                        found = attribute.nodeValue
                        break
                    }
                }
                found?.takeIf { it.isNotBlank() } ?: return null
            }
        val durationMs = parseTime(raw) ?: raw.trim().toDoubleOrNull()?.let { (it * 1000).toLong() }
        return durationMs?.let { begin + it }
    }

    private fun qualified(element: Element, name: String): String {
        element.getAttribute(name)?.takeIf { it.isNotEmpty() }?.let { return it }
        val local = name.substringAfter(':')
        val attributes = element.attributes ?: return ""
        for (i in 0 until attributes.length) {
            val attribute = attributes.item(i) ?: continue
            if (attribute.nodeName == name || attribute.nodeName == local || attribute.localName == local) {
                return attribute.nodeValue.orEmpty()
            }
        }
        return ""
    }

    private data class TimedWord(
        val timeMs: Long,
        val durationMs: Long,
        val text: String,
        val startsNewWord: Boolean,
    )

    private sealed interface Piece {
        data class Text(val text: String) : Piece
        data class Timed(val text: String, val start: Long, val end: Long) : Piece
    }
}
