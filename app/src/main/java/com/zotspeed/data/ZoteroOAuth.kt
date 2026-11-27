package com.zotspeed.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * OAuth 1.0a signing (RFC 5849, HMAC-SHA1). Pure JVM so it can be unit-tested.
 */
object OAuth1 {
    /** RFC 3986 percent-encoding as required by OAuth 1.0. */
    fun encode(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            if (c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code ||
                c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code
            ) sb.append(c.toChar()) else sb.append('%').append("%02X".format(c))
        }
        return sb.toString()
    }

    fun signatureBaseString(method: String, baseUrl: String, params: List<Pair<String, String>>): String {
        val normalized = params
            .map { encode(it.first) to encode(it.second) }
            .sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
            .joinToString("&") { "${it.first}=${it.second}" }
        return "${method.uppercase()}&${encode(baseUrl)}&${encode(normalized)}"
    }

    fun sign(baseString: String, consumerSecret: String, tokenSecret: String): String {
        val key = "${encode(consumerSecret)}&${encode(tokenSecret)}"
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        return Base64.getEncoder().encodeToString(mac.doFinal(baseString.toByteArray(Charsets.UTF_8)))
    }

    /**
     * Builds the `Authorization: OAuth ...` header for a request.
     * [extraOAuth] are additional oauth_* parameters (oauth_callback, oauth_verifier).
     * [requestParams] are query/form parameters that are part of the signature but not the header.
     */
    fun authorizationHeader(
        method: String,
        baseUrl: String,
        consumerKey: String,
        consumerSecret: String,
        token: String? = null,
        tokenSecret: String = "",
        extraOAuth: Map<String, String> = emptyMap(),
        requestParams: List<Pair<String, String>> = emptyList(),
        nonce: String = newNonce(),
        timestamp: String = (System.currentTimeMillis() / 1000).toString(),
    ): String {
        val oauth = linkedMapOf(
            "oauth_consumer_key" to consumerKey,
            "oauth_nonce" to nonce,
            "oauth_signature_method" to "HMAC-SHA1",
            "oauth_timestamp" to timestamp,
            "oauth_version" to "1.0",
        )
        if (token != null) oauth["oauth_token"] = token
        oauth.putAll(extraOAuth)
        val base = signatureBaseString(method, baseUrl, oauth.toList() + requestParams)
        oauth["oauth_signature"] = sign(base, consumerSecret, tokenSecret)
        return "OAuth " + oauth.entries.joinToString(", ") { "${encode(it.key)}=\"${encode(it.value)}\"" }
    }

    private val random = SecureRandom()

    fun newNonce(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Parses an application/x-www-form-urlencoded body or query string. */
    fun parseForm(body: String): Map<String, String> = body.trim().split('&')
        .filter { it.contains('=') }
        .associate {
            val (k, v) = it.split('=', limit = 2)
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }
}

/**
 * Zotero's OAuth 1.0a flow (https://www.zotero.org/support/dev/web_api/v3/oauth).
 * The access token Zotero returns is a regular API key, so after sign-in the rest of
 * the app works exactly as with a pasted key.
 */
class ZoteroOAuth(
    private val clientKey: String,
    private val clientSecret: String,
    private val baseUrl: String = "https://www.zotero.org",
) {
    private val requestUrl get() = "$baseUrl/oauth/request"
    private val authorizeUrl get() = "$baseUrl/oauth/authorize"
    private val accessUrl get() = "$baseUrl/oauth/access"


    data class RequestToken(val token: String, val secret: String)
    data class AccessToken(val apiKey: String, val userId: Long, val username: String)

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private suspend fun post(url: String, authorization: String): Map<String, String> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", authorization)
            .post(ByteArray(0).toRequestBody(null))
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw ZoteroException(
                    "Zotero sign-in failed (HTTP ${resp.code}). ${body.take(200)}".trim() +
                        if (resp.code == 401) " Check the OAuth client key and secret." else "",
                    resp.code,
                )
            }
            OAuth1.parseForm(body)
        }
    }

    /** Step 1: get a temporary request token. */
    suspend fun requestToken(): RequestToken {
        val auth = OAuth1.authorizationHeader(
            "POST", requestUrl, clientKey, clientSecret,
            extraOAuth = mapOf("oauth_callback" to CALLBACK),
        )
        val r = post(requestUrl, auth)
        val token = r["oauth_token"] ?: throw ZoteroException("Zotero did not return a request token")
        return RequestToken(token, r["oauth_token_secret"].orEmpty())
    }

    /** Step 2: the page where the user approves access (opened in the browser). */
    fun authorizeUrl(requestToken: String): String =
        "$authorizeUrl?oauth_token=${OAuth1.encode(requestToken)}" +
            "&library_access=1&notes_access=0&write_access=0&all_groups=read" +
            "&name=${OAuth1.encode("ZotSpeed")}"

    /** Step 3: exchange the approved request token for the access token (= API key). */
    suspend fun accessToken(request: RequestToken, verifier: String): AccessToken {
        val auth = OAuth1.authorizationHeader(
            "POST", accessUrl, clientKey, clientSecret,
            token = request.token, tokenSecret = request.secret,
            extraOAuth = mapOf("oauth_verifier" to verifier),
        )
        val r = post(accessUrl, auth)
        // Zotero returns the same value for oauth_token and oauth_token_secret; it is the API key.
        val key = r["oauth_token_secret"] ?: r["oauth_token"]
            ?: throw ZoteroException("Zotero did not return an access token")
        return AccessToken(key, r["userID"]?.toLongOrNull() ?: 0L, r["username"].orEmpty())
    }

    companion object {
        const val CALLBACK_SCHEME = "zotspeed"
        const val CALLBACK_HOST = "oauth"
        const val CALLBACK = "$CALLBACK_SCHEME://$CALLBACK_HOST"
    }
}
