package com.adagiostream.android.service.parsing

import com.adagiostream.android.model.Channel
import com.adagiostream.android.util.UrlSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID

class M3UParser(private val client: OkHttpClient) {

    suspend fun parse(url: String): List<Channel> = withContext(Dispatchers.IO) {
        UrlSanitizer.requireHttpUrl(url)
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code} from ${UrlSanitizer.redact(url)}")
        val body = response.body?.string() ?: throw IllegalStateException("Empty response from ${UrlSanitizer.redact(url)}")
        parseContent(body)
    }

    fun parseContent(content: String): List<Channel> {
        val channels = mutableListOf<Channel>()
        val lines = content.lines()
        var i = 0

        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXTINF:")) {
                val infoLine = line
                val streamUrl = findNextUrl(lines, i + 1)
                if (streamUrl != null) {
                    val channel = parseExtInf(infoLine, streamUrl)
                    if (channel != null) {
                        channels.add(channel)
                    }
                }
            }
            i++
        }

        return channels
    }

    private fun findNextUrl(lines: List<String>, startIndex: Int): String? {
        var i = startIndex
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.isNotEmpty() && !line.startsWith("#")) {
                return line
            }
            if (line.startsWith("#EXTINF:")) {
                return null
            }
            i++
        }
        return null
    }

    private fun parseExtInf(line: String, streamUrl: String): Channel? {
        val name = extractDisplayName(line) ?: return null
        val tvgId = extractAttribute(line, "tvg-id")
        val tvgName = extractAttribute(line, "tvg-name")
        val tvgLogo = extractAttribute(line, "tvg-logo")
        val groupTitle = extractAttribute(line, "group-title") ?: "Uncategorized"

        return Channel(
            id = UUID.randomUUID().toString(),
            name = tvgName?.ifBlank { null } ?: name,
            streamURL = streamUrl,
            logoURL = tvgLogo?.ifBlank { null },
            group = groupTitle.ifBlank { "Uncategorized" },
            epgChannelID = tvgId?.ifBlank { null },
        )
    }

    private fun extractAttribute(line: String, attr: String): String? {
        val pattern = Regex("""$attr="([^"]*)"""")
        return pattern.find(line)?.groupValues?.get(1)
    }

    private fun extractDisplayName(line: String): String? {
        val commaIndex = line.lastIndexOf(',')
        if (commaIndex == -1) return null
        val name = line.substring(commaIndex + 1).trim()
        return name.ifEmpty { null }
    }
}
