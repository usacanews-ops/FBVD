package com.fbvd.downloader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.regex.Pattern

data class ExtractedVideoData(
    val videoDownloadUrl: String,
    val title: String,
    val description: String,
    val hashtags: String
)

object VideoExtractor {

    private const val MOBILE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; SM-G981B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

    suspend fun extract(inputUrl: String): ExtractedVideoData? = withContext(Dispatchers.IO) {
        try {
            // Step 1: Follow HTTP redirects to expand /share/r/ shortlinks
            val resolvedUrl = followRedirects(inputUrl.trim())

            // Step 2: Fetch the web page using mobile user agent headers
            val doc = Jsoup.connect(resolvedUrl)
                .userAgent(MOBILE_USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Sec-Fetch-Mode", "navigate")
                .timeout(18000)
                .followRedirects(true)
                .get()

            val rawHtml = doc.html()

            // Step 3: Extract direct MP4 stream URL from JSON payloads & OG tags
            val downloadUrl = findVideoStreamUrl(doc, rawHtml) ?: return@withContext null

            // Step 4: Extract Metadata (Title, Description, and Tags)
            var title = doc.select("meta[property=og:title]").attr("content").ifBlank {
                doc.title()
            }
            title = cleanText(title).ifBlank { "FBVD Reel" }

            val description = cleanText(
                doc.select("meta[property=og:description]").attr("content")
            )

            val hashtags = extractHashtags("$title $description")

            ExtractedVideoData(
                videoDownloadUrl = downloadUrl,
                title = title,
                description = description,
                hashtags = hashtags
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun findVideoStreamUrl(doc: org.jsoup.nodes.Document, rawHtml: String): String? {
        // Priority 1: High-Definition JSON keys
        val hdPatterns = listOf(
            "\"browser_native_hd_url\"\\s*:\\s*\"(https:[^\"]+)\"",
            "\"playable_url_quality_hd\"\\s*:\\s*\"(https:[^\"]+)\"",
            "hd_src:\"(https:[^\"]+)\"",
            "hd_src_no_ratelimit:\"(https:[^\"]+)\""
        )
        for (pat in hdPatterns) {
            val matcher = Pattern.compile(pat).matcher(rawHtml)
            if (matcher.find()) {
                return decodeStreamUrl(matcher.group(1) ?: "")
            }
        }

        // Priority 2: Standard-Definition JSON keys
        val sdPatterns = listOf(
            "\"browser_native_sd_url\"\\s*:\\s*\"(https:[^\"]+)\"",
            "\"playable_url\"\\s*:\\s*\"(https:[^\"]+)\"",
            "sd_src:\"(https:[^\"]+)\"",
            "sd_src_no_ratelimit:\"(https:[^\"]+)\""
        )
        for (pat in sdPatterns) {
            val matcher = Pattern.compile(pat).matcher(rawHtml)
            if (matcher.find()) {
                return decodeStreamUrl(matcher.group(1) ?: "")
            }
        }

        // Priority 3: Fallback to OpenGraph meta tags
        val ogVideo = doc.select("meta[property=og:video]").attr("content").ifEmpty {
            doc.select("meta[property=og:video:secure_url]").attr("content")
        }.ifEmpty {
            doc.select("meta[property=og:video:url]").attr("content")
        }

        if (ogVideo.isNotBlank()) {
            return decodeStreamUrl(ogVideo)
        }

        return null
    }

    private fun followRedirects(targetUrl: String): String {
        var currentUrl = targetUrl
        var redirectsFollowed = 0
        val maxRedirects = 6

        while (redirectsFollowed < maxRedirects) {
            val conn = URL(currentUrl).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", MOBILE_USER_AGENT)
            conn.connectTimeout = 8000
            conn.readTimeout = 8000

            val responseCode = conn.responseCode
            if (responseCode in 300..399) {
                val newUrl = conn.getHeaderField("Location") ?: break
                currentUrl = if (newUrl.startsWith("http")) newUrl else URL(URL(currentUrl), newUrl).toString()
                redirectsFollowed++
                conn.disconnect()
            } else {
                conn.disconnect()
                break
            }
        }
        return currentUrl
    }

    private fun decodeStreamUrl(rawUrl: String): String {
        var url = rawUrl.replace("\\/", "/").replace("\\u0026", "&")
        return try {
            URLDecoder.decode(url, "UTF-8")
        } catch (e: Exception) {
            url
        }
    }

    private fun cleanText(text: String): String {
        return text.replace(Regex("&amp;"), "&")
            .replace(Regex("&quot;"), "\"")
            .replace(Regex("&#039;"), "'")
            .trim()
    }

    private fun extractHashtags(text: String): String {
        val pattern = Pattern.compile("#([a-zA-Z0-9_]+)")
        val matcher = pattern.matcher(text)
        val tags = mutableListOf<String>()
        while (matcher.find()) {
            tags.add("#${matcher.group(1)}")
        }
        return tags.distinct().joinToString(" ")
    }
}
