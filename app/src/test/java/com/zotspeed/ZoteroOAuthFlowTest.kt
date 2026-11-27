package com.zotspeed

import com.zotspeed.data.OAuth1
import com.zotspeed.data.ZoteroException
import com.zotspeed.data.ZoteroOAuth
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Runs the three-legged flow against a mock server that checks signatures like Zotero would. */
class ZoteroOAuthFlowTest {
    private lateinit var server: MockWebServer
    private val base get() = server.url("/").toString().trimEnd('/')

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() = server.shutdown()

    private fun oauthParams(r: RecordedRequest): Map<String, String> {
        val h = r.getHeader("Authorization")!!
        assertTrue(h.startsWith("OAuth "))
        return Regex("""(\w+)="([^"]*)"""").findAll(h).associate {
            java.net.URLDecoder.decode(it.groupValues[1], "UTF-8") to java.net.URLDecoder.decode(it.groupValues[2], "UTF-8")
        }
    }

    /** Recomputes the signature server-side. */
    private fun assertValidSignature(r: RecordedRequest, consumerSecret: String, tokenSecret: String) {
        val p = oauthParams(r)
        val url = server.url(r.path!!).toString()
        val base = OAuth1.signatureBaseString(r.method!!, url, p.filterKeys { it != "oauth_signature" }.toList())
        assertEquals(OAuth1.sign(base, consumerSecret, tokenSecret), p["oauth_signature"])
    }

    @Test
    fun fullFlow() = runBlocking {
        server.enqueue(MockResponse().setBody("oauth_token=req123&oauth_token_secret=reqsecret&oauth_callback_confirmed=true"))
        server.enqueue(
            MockResponse().setBody("oauth_token=APIKEY42&oauth_token_secret=APIKEY42&userID=4711&username=reader+one")
        )
        val o = ZoteroOAuth("ck", "cs", base)

        val rt = o.requestToken()
        assertEquals("req123", rt.token)
        assertEquals("reqsecret", rt.secret)
        val r1 = server.takeRequest()
        assertEquals("POST", r1.method)
        assertEquals("/oauth/request", r1.path)
        assertEquals("zotspeed://oauth", oauthParams(r1)["oauth_callback"])
        assertEquals("ck", oauthParams(r1)["oauth_consumer_key"])
        assertValidSignature(r1, "cs", "")

        val url = o.authorizeUrl(rt.token)
        assertTrue(url, url.startsWith("$base/oauth/authorize?oauth_token=req123&library_access=1"))
        assertTrue(url.contains("all_groups=read"))

        val at = o.accessToken(rt, "verif")
        assertEquals("APIKEY42", at.apiKey)
        assertEquals(4711L, at.userId)
        assertEquals("reader one", at.username)
        val r2 = server.takeRequest()
        assertEquals("/oauth/access", r2.path)
        assertEquals("req123", oauthParams(r2)["oauth_token"])
        assertEquals("verif", oauthParams(r2)["oauth_verifier"])
        assertValidSignature(r2, "cs", "reqsecret")
    }

    @Test
    fun reportsBadClientCredentials() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("Invalid consumer key"))
        try {
            ZoteroOAuth("bad", "bad", base).requestToken()
            fail()
        } catch (e: ZoteroException) {
            assertEquals(401, e.code)
            assertTrue(e.message!!, e.message!!.contains("client key"))
        }
    }
}
