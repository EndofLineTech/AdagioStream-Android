package com.adagiostream.android.service.parsing

import com.adagiostream.android.model.EPGEntry
import com.adagiostream.android.util.DateUtils
import com.adagiostream.android.util.UrlSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.Reader
import java.io.StringReader

class EPGParser(private val client: OkHttpClient) {

    suspend fun parse(url: String): Map<String, List<EPGEntry>> = withContext(Dispatchers.IO) {
        UrlSanitizer.requireHttpUrl(url)
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return@withContext emptyMap()
        response.body.charStream().use { reader -> parseContent(reader) }
    }

    fun parseContent(xml: String): Map<String, List<EPGEntry>> = parseContent(StringReader(xml))

    fun parseContent(reader: Reader): Map<String, List<EPGEntry>> {
        val entries = mutableListOf<EPGEntry>()
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(reader)

        var channelId: String? = null
        var startStr: String? = null
        var stopStr: String? = null
        var title: String? = null
        var description: String? = null
        var inTitle = false
        var inDesc = false

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "programme" -> {
                            channelId = parser.getAttributeValue(null, "channel")
                            startStr = parser.getAttributeValue(null, "start")
                            stopStr = parser.getAttributeValue(null, "stop")
                            title = null
                            description = null
                        }
                        "title" -> inTitle = true
                        "desc" -> inDesc = true
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inTitle) title = parser.text
                    if (inDesc) description = parser.text
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "programme" -> {
                            if (channelId != null && startStr != null && stopStr != null && title != null) {
                                val start = DateUtils.parseXmltvDate(startStr)
                                val stop = DateUtils.parseXmltvDate(stopStr)
                                if (start > 0 && stop > 0) {
                                    entries.add(
                                        EPGEntry(
                                            channelID = channelId,
                                            title = title,
                                            description = description,
                                            start = start,
                                            end = stop,
                                        )
                                    )
                                }
                            }
                        }
                        "title" -> inTitle = false
                        "desc" -> inDesc = false
                    }
                }
            }
            eventType = parser.next()
        }

        return entries.groupBy { it.channelID }
    }
}
