package com.fbvd.downloader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.util.regex.Pattern

data class ExtractedVideoData(
    val videoDownloadUrl: String,
    val title: String,
    val description: String,
    val hashtags: String
)

object VideoExtractor {

    suspend fun extract(webUrl: String): ExtractedVideoData? = withContext(Dispatchers.IO) {
        try {
            val cleanUrl = cleanFacebookUrl(webUrl)
            
            val doc = Jsoup.connect(cleanUrl)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .timeout(15000)
                .followRedirects(true)
                .get()

            val ogVideo = doc.select("meta[property=og:video]").attr("content").ifEmpty {
                doc.select("meta[property=og:video:url]").attr("content")
            }.ifEmpty {
                doc.select("meta[property=og:video:secure_url]").attr("content")
            }

            var title = doc.select("meta[property=og:title]").attr("content")
            var description = doc.select("meta[property=og:description]").attr("content")

            if (title.isBlank()) {
                title = doc.title()
            }

            val combinedText = "$title $description"
            val hashtags = extractHashtags(combinedText)

            var finalDownloadUrl = ogVideo
            if (finalDownloadUrl.isBlank()) {
                val html = doc.html()
                val regex = "(hd_src_no_ratelimit|sd_src_no_ratelimit|hd_src|sd_src):\"(https:[^\"]+)\""
                val pattern = Pattern.compile(regex)
                val matcher = pattern.matcher(html)
                if (matcher.find()) {
                    finalDownloadUrl = matcher.group(2)?.replace("\\/", "/") ?: ""
                }
            }

            if (finalDownloadUrl.isNotBlank()) {
                ExtractedVideoData(
                    videoDownloadUrl = URLDecoder.decode(finalDownloadUrl, "UTF-8"),
                    title = title.ifBlank { "FBVD Reel" },
                    description = description,
                    hashtags = hashtags
                )
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun extractHashtags(text: String): String {
        val pattern = Pattern.compile("#(\\w+)")
        val matcher = pattern.matcher(text)
        val tags = mutableListOf<String>()
        while (matcher.find()) {
            tags.add("#${matcher.group(1)}")
        }
        return tags.distinct().joinToString(" ")
    }

    private fun cleanFacebookUrl(input: String): String {
        val pattern = Pattern.compile("https?://(www\\.|m\\.|fb\\.)?(facebook\\.com|fb\\.watch)/[^\\s]+")
        val matcher = pattern.matcher(input)
        return if (matcher.find()) {
            matcher.group(0)
        } else {
            input
        }
    }
}
