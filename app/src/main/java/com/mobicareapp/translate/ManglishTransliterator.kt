package com.mobicareapp.translate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder

/**
 * Converts Manglish (Malayalam typed in English/Latin letters, e.g. "sukhamano ninakku") into
 * actual Malayalam script (സുഖമാണോ നിനക്ക്), using the same public transliteration engine behind
 * Google's own Malayalam input keyboard — free, no signup or key. MyMemory's translation API (see
 * [MyMemoryTranslator]) doesn't reliably understand romanized Malayalam typed directly, since it's
 * a general translation memory service, not a transliteration tool — running the text through this
 * first gets a real Malayalam sentence that then translates properly.
 */
object ManglishTransliterator {
    private val client = OkHttpClient()

    suspend fun toMalayalam(text: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val encoded = URLEncoder.encode(text, "UTF-8")
            val url = "https://inputtools.google.com/request?text=$encoded&itc=ml-t-i0-und&num=1&cp=0&cs=1&ie=utf-8&oe=utf-8"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Couldn't convert that text (${response.code})")
                val body = response.body?.string().orEmpty()
                val root = JSONArray(body)
                if (root.optString(0) != "SUCCESS") error("Couldn't convert that text")
                root.getJSONArray(1).getJSONArray(0).getJSONArray(1).getString(0)
            }
        }
    }
}
