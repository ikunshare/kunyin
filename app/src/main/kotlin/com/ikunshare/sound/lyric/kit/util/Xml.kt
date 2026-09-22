package com.ikunshare.sound.lyric.kit.util

/**
 * 对应 utils/xml：手写递归下降 XML 解析器 + 生成器。1:1 移植 char-code 扫描逻辑。
 */

const val XML_ROOT_TAG = "#document"

class XmlAttribute(
    val name: String,
    val value: String,
    val prefix: String,
    val local: String,
)

sealed class XmlNode {
    var parent: XmlElement? = null
}

class XmlElement(
    val tag: String,
    val prefix: String,
    val local: String,
) : XmlNode() {
    val attributes: MutableList<XmlAttribute> = mutableListOf()
    val children: MutableList<XmlNode> = mutableListOf()
}

class XmlText(val content: String) : XmlNode()
class XmlCdata(val content: String) : XmlNode()
class XmlComment(val content: String) : XmlNode()

private object CharCode {
    const val LessThan = 0x3c
    const val GreaterThan = 0x3e
    const val Slash = 0x2f
    const val Equal = 0x3d
    const val DoubleQuote = 0x22
    const val SingleQuote = 0x27
    const val Exclamation = 0x21
    const val Dash = 0x2d
    const val QuestionMark = 0x3f
    const val Ampersand = 0x26
    const val Hash = 0x23
    const val LowerX = 0x78
}

class XmlParser {
    private var src: String = ""
    private var pos: Int = 0
    private var len: Int = 0
    private var valid: Boolean = true

    private fun code(i: Int): Int = src[i].code

    fun parse(xml: String): XmlElement? {
        src = xml
        pos = 0
        len = xml.length
        valid = true

        val root = XmlElement(XML_ROOT_TAG, "", XML_ROOT_TAG)

        if (len > 0 && code(0) == 0xfeff) pos++

        parseContent(root)

        if (!valid || !hasElementChild(root)) return null
        return root
    }

    private fun hasElementChild(node: XmlElement): Boolean = node.children.any { it is XmlElement }

    private fun skipWhitespace() {
        while (pos < len && code(pos) <= 32) pos++
    }

    private fun skipDeclaration() {
        pos += 2
        var depth = 1
        while (pos < len && depth > 0) {
            val ch = code(pos)
            if (ch == CharCode.LessThan) depth++
            else if (ch == CharCode.GreaterThan) depth--
            pos++
        }
    }

    private fun skipProcessingInstruction() {
        pos += 2
        val endIdx = src.indexOf("?>", pos)
        pos = if (endIdx == -1) len else endIdx + 2
    }

    private fun parseContent(parent: XmlElement) {
        while (pos < len) {
            val nextIndex = src.indexOf('<', pos)
            if (nextIndex == -1) {
                parseText(parent, len)
                break
            }
            if (nextIndex > pos) parseText(parent, nextIndex)
            pos = nextIndex
            if (pos + 1 >= len) break
            val nextChar = code(pos + 1)
            when (nextChar) {
                CharCode.Slash -> return
                CharCode.Exclamation -> {
                    if (pos + 3 < len && code(pos + 2) == CharCode.Dash && code(pos + 3) == CharCode.Dash) {
                        parseComment()
                    } else if (pos + 9 <= len && src.substring(pos + 2, pos + 9) == "[CDATA[") {
                        parseCdata(parent)
                    } else {
                        skipDeclaration()
                    }
                }
                CharCode.QuestionMark -> skipProcessingInstruction()
                else -> parseElement(parent)
            }
        }
    }

    private fun parseText(parent: XmlElement, end: Int) {
        val raw = src.substring(pos, end)
        pos = end
        if (raw.isNotEmpty()) {
            val content = if (raw.indexOf('&') != -1) decodeEntity(raw) else raw
            val node = XmlText(content)
            node.parent = parent
            parent.children.add(node)
        }
    }

    private fun parseComment() {
        pos += 4
        val end = src.indexOf("-->", pos)
        pos = if (end == -1) len else end + 3
    }

    private fun parseCdata(parent: XmlElement) {
        pos += 9
        val start = pos
        val end = src.indexOf("]]>", pos)
        val contentEnd = if (end != -1) end else len
        pos = if (end == -1) len else end + 3
        val content = src.substring(start, contentEnd)
        if (content.isNotEmpty()) {
            val node = XmlCdata(content)
            node.parent = parent
            parent.children.add(node)
        }
    }

    private fun parseName(): String {
        val start = pos
        while (pos < len) {
            val ch = code(pos)
            if (ch <= 32 || ch == CharCode.GreaterThan || ch == CharCode.Slash || ch == CharCode.Equal) break
            pos++
        }
        return src.substring(start, pos)
    }

    private fun parseAttributeValue(): String {
        val quote = code(pos)
        if (quote != CharCode.DoubleQuote && quote != CharCode.SingleQuote) {
            valid = false
            val start = pos
            while (pos < len) {
                val ch = code(pos)
                if (ch <= 32 || ch == CharCode.GreaterThan) break
                pos++
            }
            val raw = src.substring(start, pos)
            return if (raw.indexOf('&') != -1) decodeEntity(raw) else raw
        }
        pos++
        val quoteChar = if (quote == CharCode.DoubleQuote) '"' else '\''
        val end = src.indexOf(quoteChar, pos)
        val raw: String
        if (end == -1) {
            raw = src.substring(pos)
            pos = len
        } else {
            raw = src.substring(pos, end)
            pos = end + 1
        }
        return if (raw.indexOf('&') != -1) decodeEntity(raw) else raw
    }

    private fun parseElement(parent: XmlElement) {
        pos++ // skip '<'
        val tag = parseName()
        if (tag.isEmpty()) {
            if (pos < len && code(pos) == CharCode.GreaterThan) pos++
            return
        }
        val colonIdx = tag.indexOf(':')
        val prefix: String
        val local: String
        if (colonIdx != -1) {
            prefix = tag.substring(0, colonIdx)
            local = tag.substring(colonIdx + 1)
        } else {
            prefix = ""
            local = tag
        }
        val element = XmlElement(tag, prefix, local)
        element.parent = parent

        while (pos < len) {
            skipWhitespace()
            if (pos >= len) break
            val ch = code(pos)
            if (ch == CharCode.GreaterThan) {
                pos++
                parseContent(element)
                parseCloseTag(tag)
                break
            }
            if (ch == CharCode.Slash) {
                pos++
                if (pos < len && code(pos) == CharCode.GreaterThan) pos++
                break
            }
            val attrName = parseName()
            if (attrName.isEmpty()) {
                pos++
                continue
            }
            skipWhitespace()
            var attrValue = ""
            if (pos < len && code(pos) == CharCode.Equal) {
                pos++
                skipWhitespace()
                if (pos < len) attrValue = parseAttributeValue()
            }
            val attrColon = attrName.indexOf(':')
            val attrPrefix: String
            val attrLocal: String
            if (attrColon != -1) {
                attrPrefix = attrName.substring(0, attrColon)
                attrLocal = attrName.substring(attrColon + 1)
            } else {
                attrPrefix = ""
                attrLocal = attrName
            }
            element.attributes.add(XmlAttribute(attrName, attrValue, attrPrefix, attrLocal))
        }

        parent.children.add(element)
    }

    private fun parseCloseTag(expected: String) {
        if (pos + 1 >= len || code(pos) != CharCode.LessThan || code(pos + 1) != CharCode.Slash) {
            valid = false
            return
        }
        pos += 2
        if (parseName() != expected) valid = false
        val end = src.indexOf('>', pos)
        pos = if (end == -1) len else end + 1
    }

    private fun parseEntity(entity: String): String {
        return when (entity) {
            "lt" -> "<"
            "gt" -> ">"
            "amp" -> "&"
            "quot" -> "\""
            "apos" -> "'"
            else -> {
                if (entity.isNotEmpty() && entity[0].code == CharCode.Hash) {
                    val isHex = entity.length > 1 && entity[1].code == CharCode.LowerX
                    val codeStr = if (isHex) entity.substring(2) else entity.substring(1)
                    val cp = codeStr.toIntOrNull(if (isHex) 16 else 10)
                    if (cp != null) {
                        try {
                            return String(Character.toChars(cp))
                        } catch (_: Exception) {
                            return "&#$entity;"
                        }
                    }
                    "&$entity;"
                } else {
                    "&$entity;"
                }
            }
        }
    }

    private fun decodeEntity(text: String): String {
        val result = StringBuilder()
        var lastIndex = 0
        var current = 0
        val n = text.length
        while (current < n) {
            if (text[current].code == CharCode.Ampersand) {
                result.append(text.substring(lastIndex, current))
                val semiIdx = text.indexOf(';', current + 1)
                if (semiIdx == -1) {
                    result.append('&')
                    lastIndex = current + 1
                    current++
                    continue
                }
                val entity = text.substring(current + 1, semiIdx)
                result.append(parseEntity(entity))
                current = semiIdx + 1
                lastIndex = current
            } else {
                current++
            }
        }
        if (lastIndex < n) result.append(text.substring(lastIndex))
        return result.toString()
    }
}

class XmlGeneratorOptions(
    val declaration: Boolean = false,
    val format: Boolean = false,
    val indentChar: String = "  ",
)

class XmlGenerator {
    private val chunks = StringBuilder()
    private var format = false
    private var indentChar = "  "

    fun generate(root: XmlElement, options: XmlGeneratorOptions = XmlGeneratorOptions(declaration = true)): String {
        chunks.setLength(0)
        format = options.format
        indentChar = options.indentChar

        if (options.declaration) {
            chunks.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            if (format) chunks.append("\n")
        }
        if (root.tag == XML_ROOT_TAG) {
            for (child in root.children) serializeNode(child, 0)
        } else {
            serializeNode(root, 0)
        }
        return chunks.toString()
    }

    private fun serializeNode(node: XmlNode, depth: Int) {
        when (node) {
            is XmlElement -> serializeElement(node, depth)
            is XmlText -> chunks.append(escapeString(node.content, false))
            is XmlCdata -> chunks.append("<![CDATA[").append(node.content).append("]]>")
            is XmlComment -> chunks.append("<!--").append(node.content).append("-->")
        }
    }

    private fun serializeElement(node: XmlElement, depth: Int) {
        if (format && depth > 0) {
            chunks.append("\n").append(indentChar.repeat(depth))
        }
        chunks.append("<").append(node.tag)
        for (attr in node.attributes) {
            chunks.append(" ").append(attr.name).append("=\"").append(escapeString(attr.value, true)).append("\"")
        }
        val childLen = node.children.size
        if (childLen == 0) {
            chunks.append("/>")
        } else {
            chunks.append(">")
            var isOnlyText = true
            for (child in node.children) {
                if (child !is XmlText && child !is XmlCdata) isOnlyText = false
                serializeNode(child, depth + 1)
            }
            if (format && !isOnlyText) {
                chunks.append("\n").append(indentChar.repeat(depth))
            }
            chunks.append("</").append(node.tag).append(">")
        }
    }

    private fun escapeString(str: String?, isAttr: Boolean): String {
        if (str.isNullOrEmpty()) return ""
        val result = StringBuilder()
        var lastIndex = 0
        val n = str.length
        for (i in 0 until n) {
            val esc: String? = when (str[i].code) {
                CharCode.Ampersand -> "&amp;"
                CharCode.LessThan -> "&lt;"
                CharCode.GreaterThan -> "&gt;"
                CharCode.DoubleQuote -> if (isAttr) "&quot;" else null
                CharCode.SingleQuote -> if (isAttr) "&apos;" else null
                else -> null
            }
            if (esc != null) {
                if (lastIndex < i) result.append(str.substring(lastIndex, i))
                result.append(esc)
                lastIndex = i + 1
            }
        }
        if (lastIndex == 0) return str
        if (lastIndex < n) result.append(str.substring(lastIndex))
        return result.toString()
    }
}
