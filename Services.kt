package com.abhynex.jarvis

import com.abhynex.jarvis.ApiException.Kind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** AI provider: Anthropic Messages API (https://docs.anthropic.com). Swap this object to change provider. */
object AiClient {
    suspend fun chat(
        key: String, model: String, system: String, history: List<Msg>, maxTokens: Int = 2048
    ): String = withContext(Dispatchers.IO) {
        val msgs = JSONArray()
        history.forEach { msgs.put(JSONObject().put("role", it.role).put("content", it.text)) }
        val body = JSONObject().put("model", model).put("max_tokens", maxTokens)
            .put("system", system).put("messages", msgs).toString()
        val (code, bytes) = Http.request(
            "POST", "https://api.anthropic.com/v1/messages",
            mapOf(
                "x-api-key" to key,
                "anthropic-version" to "2023-06-01",
                "content-type" to "application/json"
            ), body
        )
        val text = String(bytes, Charsets.UTF_8)
        if (code == 401 || code == 403) throw ApiException(Kind.AUTH, "AI authentication failed.")
        if (code !in 200..299) throw ApiException(Kind.HTTP, Http.errorMessage(text) ?: "AI request failed ($code).")
        try {
            val arr = JSONObject(text).getJSONArray("content")
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val b = arr.getJSONObject(i)
                if (b.optString("type") == "text") sb.append(b.optString("text"))
            }
            sb.toString().ifBlank { throw ApiException(Kind.PARSE, "AI returned an empty response.") }
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ApiException(Kind.PARSE, "Unexpected AI response.")
        }
    }
}

/** Live search: Brave Search API (https://api.search.brave.com). */
object SearchClient {
    suspend fun search(key: String, query: String, count: Int = 5): List<Src> = withContext(Dispatchers.IO) {
        val url = "https://api.search.brave.com/res/v1/web/search?q=" +
            URLEncoder.encode(query.take(300), "UTF-8") + "&count=$count"
        val (code, bytes) = Http.request(
            "GET", url, mapOf("Accept" to "application/json", "X-Subscription-Token" to key), null
        )
        when {
            code == 401 || code == 403 || code == 422 -> throw ApiException(Kind.AUTH, "Search authentication failed.")
            code == 429 -> throw ApiException(Kind.HTTP, "Live Search rate limit reached.")
            code !in 200..299 -> throw ApiException(Kind.HTTP, "Live Search is currently unavailable.")
        }
        try {
            val arr = JSONObject(String(bytes, Charsets.UTF_8)).optJSONObject("web")?.optJSONArray("results")
                ?: return@withContext emptyList()
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Src(
                    o.optString("title"), o.optString("url"),
                    o.optString("description").replace(Regex("<[^>]+>"), "")
                )
            }.filter { it.url.startsWith("http") }
        } catch (e: Exception) {
            throw ApiException(Kind.PARSE, "Live Search is currently unavailable.")
        }
    }
}
