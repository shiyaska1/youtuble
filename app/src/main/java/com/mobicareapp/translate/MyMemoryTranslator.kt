package com.mobicareapp.translate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Free text translation via MyMemory's public API — no signup, no key, no card, unlike every
 * cloud speech/translation service tried elsewhere in this app. Good for plain Malayalam (or other
 * script) text; Manglish (Malayalam typed in Latin letters) isn't reliably understood since it's a
 * general translation memory/MT service, not a dedicated transliteration tool — common phrases
 * that happen to already be in its memory translate fine, but there's no guarantee for an
 * arbitrary romanized sentence.
 */
object MyMemoryTranslator {
    private val client = OkHttpClient()

    suspend fun translate(text: String, sourceLang: String, targetLang: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val encodedText = URLEncoder.encode(text, "UTF-8")
            val url = "https://api.mymemory.translated.net/get?q=$encodedText&langpair=$sourceLang|$targetLang"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Translation request failed (${response.code})")
                val body = response.body?.string().orEmpty()
                val translated = JSONObject(body).getJSONObject("responseData").getString("translatedText")
                translated
            }
        }
    }
}
