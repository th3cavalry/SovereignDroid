package com.th3cavalry.androidllm.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Provides web search via Brave Search API or SerpAPI, with a DuckDuckGo
 * HTML fallback when no API key is configured.
 */
class WebSearchService(
    private val provider: String,
    private val apiKey: String
) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun search(query: String, numResults: Int = 5): String = withContext(Dispatchers.IO) {
        when {
            provider == "brave" && apiKey.isNotBlank() -> braveSearch(query, numResults)
            provider == "serpapi" && apiKey.isNotBlank() -> serpApiSearch(query, numResults)
            else -> duckDuckGoSearch(query, numResults)
        }
    }

    suspend fun fetchUrl(url: String): String = withContext(Dispatchers.IO) {
        // Use Jina Reader API for clean text extraction from any URL
        val jinaUrl = "https://r.jina.ai/$url"
        try {
            val request = Request.Builder()
                .url(jinaUrl)
                .header("Accept", "text/plain")
                .build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.string()?.take(8000) ?: "No content"
            } else {
                "Error fetching URL: HTTP ${response.code}"
            }
        } catch (e: Exception) {
            "Error fetching URL: ${e.message}"
        }
    }

    // ─── Brave Search ────────────────────────────────────────────────────────

    private fun braveSearch(query: String, numResults: Int): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://api.search.brave.com/res/v1/web/search?q=$encoded&count=$numResults"
        return try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("Accept-Encoding", "gzip")
                .header("X-Subscription-Token", apiKey)
                .build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: return "No response from Brave Search"
            parseBraveResults(body)
        } catch (e: Exception) {
            "Error with Brave Search: ${e.message}"
        }
    }

    private fun parseBraveResults(json: String): String {
        // Simple JSON parsing without full deserialization
        return try {
            val sb = StringBuilder("Search Results:\n\n")
            var count = 0
            val resultPattern = Regex(
                """"title"\s*:\s*"([^"]+)".*?"url"\s*:\s*"([^"]+)".*?"description"\s*:\s*"([^"]*?)"""",
                RegexOption.DOT_MATCHES_ALL
            )
            for (match in resultPattern.findAll(json).take(5)) {
                count++
                val title = match.groupValues[1].unescape()
                val url = match.groupValues[2]
                val desc = match.groupValues[3].unescape()
                sb.append("$count. **$title**\n   $url\n   $desc\n\n")
            }
            if (count == 0) "No results found." else sb.toString()
        } catch (e: Exception) {
            "Error parsing Brave results: ${e.message}"
        }
    }

    // ─── SerpAPI ─────────────────────────────────────────────────────────────

    private fun serpApiSearch(query: String, numResults: Int): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://serpapi.com/search.json?q=$encoded&num=$numResults&api_key=$apiKey"
        return try {
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: return "No response from SerpAPI"
            parseSerpApiResults(body)
        } catch (e: Exception) {
            "Error with SerpAPI: ${e.message}"
        }
    }

    private fun parseSerpApiResults(json: String): String {
        return try {
            val sb = StringBuilder("Search Results:\n\n")
            var count = 0
            val resultPattern = Regex(
                """"title"\s*:\s*"([^"]+)".*?"link"\s*:\s*"([^"]+)".*?"snippet"\s*:\s*"([^"]*?)"""",
                RegexOption.DOT_MATCHES_ALL
            )
            for (match in resultPattern.findAll(json).take(5)) {
                count++
                val title = match.groupValues[1].unescape()
                val url = match.groupValues[2]
                val snippet = match.groupValues[3].unescape()
                sb.append("$count. **$title**\n   $url\n   $snippet\n\n")
            }
            if (count == 0) "No results found." else sb.toString()
        } catch (e: Exception) {
            "Error parsing SerpAPI results: ${e.message}"
        }
    }

    // ─── DuckDuckGo HTML fallback ─────────────────────────────────────────────

    private fun duckDuckGoSearch(query: String, numResults: Int): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://html.duckduckgo.com/html/?q=$encoded"
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android)")
                .build()
            val response = httpClient.newCall(request).execute()
            val html = response.body?.string() ?: return "No response from DuckDuckGo"
            parseDuckDuckGoHtml(html, numResults)
        } catch (e: Exception) {
            "Error with DuckDuckGo search: ${e.message}"
        }
    }

    private fun parseDuckDuckGoHtml(html: String, maxResults: Int): String {
        val sb = StringBuilder("Search Results (DuckDuckGo):\n\n")
        var count = 0

        // Extract result blocks — look for the result__title / result__snippet structure
        val titlePattern = Regex("""class="result__a"[^>]*href="([^"]+)"[^>]*>([^<]+)</a>""")
        val snippetPattern = Regex("""class="result__snippet"[^>]*>([^<]+(?:<[^>]+>[^<]*</[^>]+>[^<]*)*)</""")

        val titles = titlePattern.findAll(html).toList()
        val snippets = snippetPattern.findAll(html).toList()

        for (i in 0 until minOf(maxResults, titles.size)) {
            count++
            val url = titles[i].groupValues[1].let {
                // DDG uses redirect URLs, try to extract the actual URL
                if (it.contains("uddg=")) {
                    it.substringAfter("uddg=").substringBefore("&").let { enc ->
                        try { java.net.URLDecoder.decode(enc, "UTF-8") } catch (e: Exception) { it }
                    }
                } else it
            }
            val title = titles[i].groupValues[2].stripHtml()
            val snippet = snippets.getOrNull(i)?.groupValues?.get(1)?.stripHtml() ?: ""
            sb.append("$count. **$title**\n   $url\n   $snippet\n\n")
        }

        return if (count == 0) "No results found." else sb.toString()
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun String.unescape(): String =
        replace("\\n", "\n").replace("\\t", "\t").replace("\\\"", "\"").replace("\\\\", "\\")

    private fun String.stripHtml(): String =
        replace(Regex("<[^>]+>"), "").replace("&amp;", "&")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&#39;", "'").trim()
}
