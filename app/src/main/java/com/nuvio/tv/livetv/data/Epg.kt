package com.nuvio.tv.livetv.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

private const val EPG_MAX_XML_BYTES = 160 * 1024 * 1024

data class XmltvGuide(
    val channelIds: Set<String> = emptySet(),
    val programsByTvgId: Map<String, List<LiveTvEpgProgram>> = emptyMap(),
)

data class LiveTvNowNext(
    val now: LiveTvEpgProgram? = null,
    val next: LiveTvEpgProgram? = null,
)

fun parseXmltvGzip(bytes: ByteArray): XmltvGuide =
    parseXmltv(ungzipXmltv(bytes).decodeToString())

/** Gzip when the payload starts with the gzip magic bytes; otherwise UTF-8 XML. */
fun parseEpgPayload(bytes: ByteArray): XmltvGuide {
    val gzip = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
    return if (gzip) parseXmltvGzip(bytes) else parseXmltv(bytes.decodeToString())
}

fun parseXmltv(xml: String): XmltvGuide {
    val channelIds = linkedSetOf<String>()
    scanTags(xml, "channel") { openTag, _ ->
        attrKey(openTag, "id")?.let { channelIds += it }
    }
    val programs = HashMap<String, MutableList<LiveTvEpgProgram>>()
    scanTags(xml, "programme") { openTag, body ->
        if (body == null) return@scanTags
        val channel = attrKey(openTag, "channel") ?: return@scanTags
        val start = xmlAttr(openTag, "start")?.let(::parseXmltvTime) ?: return@scanTags
        val stop = xmlAttr(openTag, "stop")?.let(::parseXmltvTime) ?: return@scanTags
        if (stop <= start) return@scanTags
        val title = xmlChildText(body, "title") ?: return@scanTags
        channelIds += channel
        programs.getOrPut(channel) { mutableListOf() } += LiveTvEpgProgram(
            title = title,
            startMillis = start,
            endMillis = stop,
            description = xmlChildText(body, "desc"),
        )
    }
    return XmltvGuide(
        channelIds = channelIds,
        programsByTvgId = programs.mapValues { (_, list) ->
            list.sortedBy { it.startMillis }.distinctBy { it.startMillis }
        },
    )
}

fun XmltvGuide.programsInWindow(
    tvgId: String,
    windowStartMillis: Long,
    windowEndMillis: Long,
): List<LiveTvEpgProgram> {
    val key = epgKey(tvgId)
    if (key.isEmpty()) return emptyList()
    return programsByTvgId[key].orEmpty().filter { program ->
        program.endMillis > windowStartMillis && program.startMillis < windowEndMillis
    }
}

fun XmltvGuide.nowNext(tvgId: String, nowMillis: Long): LiveTvNowNext =
    nowNextFor(channelId = "", epgId = tvgId, nowMillis = nowMillis)

/** XMLTV id first, then the channel id (Stalker `get_epg_info` is keyed `stalker:<id>`). */
fun XmltvGuide.programsFor(channelId: String, epgId: String?): List<LiveTvEpgProgram> {
    val byEpg = epgId?.let(::epgKey)?.takeIf(String::isNotEmpty)?.let { programsByTvgId[it] }.orEmpty()
    if (byEpg.isNotEmpty()) return byEpg
    return epgKey(channelId).takeIf(String::isNotEmpty)?.let { programsByTvgId[it] }.orEmpty()
}

fun XmltvGuide.nowNextFor(channelId: String, epgId: String?, nowMillis: Long): LiveTvNowNext {
    val list = programsFor(channelId, epgId)
    val now = list.firstOrNull { it.isAiringAt(nowMillis) }
    val next = if (now != null) {
        list.firstOrNull { it.startMillis >= now.endMillis }
    } else {
        list.firstOrNull { it.startMillis >= nowMillis }
    }
    return LiveTvNowNext(now, next)
}

/** XMLTV `YYYYMMDDHHmmss` plus an optional `+HHMM` / `-HHMM` offset. No offset means UTC. */
fun parseXmltvTime(value: String): Long? {
    val trimmed = value.trim()
    val digits = trimmed.takeWhile(Char::isDigit)
    if (digits.length < 12) return null
    val year = digits.substring(0, 4).toIntOrNull() ?: return null
    val month = digits.substring(4, 6).toIntOrNull()?.takeIf { it in 1..12 } ?: return null
    val day = digits.substring(6, 8).toIntOrNull()?.takeIf { it in 1..31 } ?: return null
    val hour = digits.substring(8, 10).toIntOrNull()?.takeIf { it in 0..23 } ?: return null
    val minute = digits.substring(10, 12).toIntOrNull()?.takeIf { it in 0..59 } ?: return null
    val second = if (digits.length >= 14) {
        digits.substring(12, 14).toIntOrNull()?.coerceIn(0, 59) ?: 0
    } else {
        0
    }
    val rest = trimmed.substring(digits.length).trim()
    var offsetMinutes = 0
    if (rest.length >= 5 && (rest[0] == '+' || rest[0] == '-')) {
        val offsetDigits = rest.substring(1).filter(Char::isDigit)
        val offsetHours = offsetDigits.take(2).toIntOrNull() ?: return null
        val offsetMins = offsetDigits.drop(2).take(2).toIntOrNull() ?: 0
        offsetMinutes = (offsetHours * 60 + offsetMins) * if (rest[0] == '-') -1 else 1
    }
    val epochSeconds = epgDaysFromCivil(year, month, day) * 86_400L +
        hour * 3_600L + minute * 60L + second - offsetMinutes * 60L
    return epochSeconds * 1000L
}

private fun ungzipXmltv(bytes: ByteArray): ByteArray {
    GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buf)
            if (read < 0) break
            total += read
            if (total > EPG_MAX_XML_BYTES) error("XMLTV gzip exceeds $EPG_MAX_XML_BYTES bytes")
            out.write(buf, 0, read)
        }
        return out.toByteArray()
    }
}

private fun epgDaysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yearOfEra = y - era * 400
    val dayOfYear = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era.toLong() * 146_097L + dayOfEra - 719_468L
}

private fun epgKey(value: String): String = value.trim().lowercase()

private fun attrKey(tag: String, name: String): String? =
    xmlAttr(tag, name)?.let { epgKey(decodeXmlEntities(it)) }?.takeIf { it.isNotEmpty() }

private inline fun scanTags(
    xml: String,
    name: String,
    block: (openTag: String, body: String?) -> Unit,
) {
    val open = "<$name"
    val close = "</$name>"
    var index = 0
    while (index < xml.length) {
        val start = xml.indexOf(open, index)
        if (start < 0) return
        if (!isTagBoundary(xml.getOrNull(start + open.length))) {
            index = start + open.length
            continue
        }
        val tagEnd = xml.indexOf('>', start)
        if (tagEnd < 0) return
        val openTag = xml.substring(start, tagEnd)
        if (openTag.endsWith('/')) {
            block(openTag, null)
            index = tagEnd + 1
            continue
        }
        val end = xml.indexOf(close, tagEnd)
        if (end < 0) return
        block(openTag, xml.substring(tagEnd + 1, end))
        index = end + close.length
    }
}

private fun isTagBoundary(char: Char?): Boolean =
    char == null || char == '>' || char == '/' || char.isWhitespace()

private fun xmlAttr(tag: String, name: String): String? {
    var from = 0
    while (from < tag.length) {
        val index = tag.indexOf(name, from)
        if (index < 0) return null
        val before = tag.getOrNull(index - 1)
        if (before == null || !before.isWhitespace()) {
            from = index + name.length
            continue
        }
        var cursor = index + name.length
        while (cursor < tag.length && tag[cursor].isWhitespace()) cursor++
        if (tag.getOrNull(cursor) != '=') {
            from = index + name.length
            continue
        }
        cursor++
        while (cursor < tag.length && tag[cursor].isWhitespace()) cursor++
        val quote = tag.getOrNull(cursor)
        if (quote != '"' && quote != '\'') {
            from = cursor
            continue
        }
        val end = tag.indexOf(quote, cursor + 1)
        if (end < 0) return null
        return tag.substring(cursor + 1, end)
    }
    return null
}

private fun xmlChildText(body: String, name: String): String? {
    val open = "<$name"
    var index = 0
    while (index < body.length) {
        val start = body.indexOf(open, index)
        if (start < 0) return null
        if (!isTagBoundary(body.getOrNull(start + open.length))) {
            index = start + open.length
            continue
        }
        val tagEnd = body.indexOf('>', start)
        if (tagEnd < 0) return null
        if (body[tagEnd - 1] == '/') {
            index = tagEnd + 1
            continue
        }
        val end = body.indexOf("</$name>", tagEnd)
        if (end < 0) return null
        val text = decodeXmlText(body.substring(tagEnd + 1, end)).trim()
        if (text.isNotEmpty()) return text
        index = end + name.length + 3
    }
    return null
}

private fun decodeXmlText(raw: String): String {
    val cdataStart = raw.indexOf("<![CDATA[")
    if (cdataStart < 0) return decodeXmlEntities(raw)
    val contentStart = cdataStart + 9
    val cdataEnd = raw.indexOf("]]>", contentStart)
    if (cdataEnd < 0) return decodeXmlEntities(raw.substring(0, cdataStart)) + raw.substring(contentStart)
    return decodeXmlEntities(raw.substring(0, cdataStart)) +
        raw.substring(contentStart, cdataEnd) +
        decodeXmlEntities(raw.substring(cdataEnd + 3))
}

private fun decodeXmlEntities(value: String): String {
    if ('&' !in value) return value
    val builder = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char == '&') {
            val semicolon = value.indexOf(';', index + 1)
            if (semicolon > index && semicolon - index <= 10) {
                val decoded = when (val entity = value.substring(index + 1, semicolon)) {
                    "amp" -> "&"
                    "lt" -> "<"
                    "gt" -> ">"
                    "quot" -> "\""
                    "apos" -> "'"
                    else -> when {
                        entity.startsWith("#x") || entity.startsWith("#X") ->
                            entity.substring(2).toIntOrNull(16)?.let(::xmlCodePoint)
                        entity.startsWith("#") -> entity.substring(1).toIntOrNull()?.let(::xmlCodePoint)
                        else -> null
                    }
                }
                if (decoded != null) {
                    builder.append(decoded)
                    index = semicolon + 1
                    continue
                }
            }
        }
        builder.append(char)
        index++
    }
    return builder.toString()
}

private fun xmlCodePoint(codePoint: Int): String? = when (codePoint) {
    in 0..0xFFFF -> codePoint.toChar().toString()
    in 0x10000..0x10FFFF -> {
        val value = codePoint - 0x10000
        charArrayOf(((value shr 10) + 0xD800).toChar(), ((value and 0x3FF) + 0xDC00).toChar()).concatToString()
    }
    else -> null
}
