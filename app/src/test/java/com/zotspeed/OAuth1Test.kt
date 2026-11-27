package com.zotspeed

import com.zotspeed.data.OAuth1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OAuth1Test {

    /** The classic example from the OAuth 1.0 specification (photos.example.net). */
    @Test
    fun specExampleSignature() {
        val base = OAuth1.signatureBaseString(
            "GET",
            "http://photos.example.net/photos",
            listOf(
                "oauth_consumer_key" to "dpf43f3p2l4k3l03",
                "oauth_token" to "nnch734d00sl2jdk",
                "oauth_signature_method" to "HMAC-SHA1",
                "oauth_timestamp" to "1191242096",
                "oauth_nonce" to "kllo9940pd9333jh",
                "oauth_version" to "1.0",
                "file" to "vacation.jpg",
                "size" to "original",
            ),
        )
        assertEquals(
            "GET&http%3A%2F%2Fphotos.example.net%2Fphotos&file%3Dvacation.jpg%26oauth_consumer_key%3Ddpf43f3p2l4k3l03" +
                "%26oauth_nonce%3Dkllo9940pd9333jh%26oauth_signature_method%3DHMAC-SHA1%26oauth_timestamp%3D1191242096" +
                "%26oauth_token%3Dnnch734d00sl2jdk%26oauth_version%3D1.0%26size%3Doriginal",
            base,
        )
        assertEquals("tR3+Ty81lMeYAr/Fid0kMTYa/WM=", OAuth1.sign(base, "kd94hf93k423kf44", "pfkkdhi9sl3r4s00"))
    }

    /** Twitter's documented signing example, which exercises percent-encoding of spaces, '+' and '!'. */
    @Test
    fun twitterExampleSignature() {
        val header = OAuth1.authorizationHeader(
            method = "POST",
            baseUrl = "https://api.twitter.com/1.1/statuses/update.json",
            consumerKey = "xvz1evFS4wEEPTGEFPHBog",
            consumerSecret = "kAcSOqF21Fu85e7zjz7ZN2U4ZRhfV3WpwPAoE3Z7kBw",
            token = "370773112-GmHxMAgYyLbNEtIKZeRNFsMKPR9EyMZeS9weJAEb",
            tokenSecret = "LswwdoUaIvS8ltyTt5jkRh4J50vUPVVHtR2YPi5kE",
            requestParams = listOf(
                "include_entities" to "true",
                "status" to "Hello Ladies + Gentlemen, a signed OAuth request!",
            ),
            nonce = "kYjzVBB8Y0ZFabxSWbWovY3uYSQ2pTgmZeNu2VS4cg",
            timestamp = "1318622958",
        )
        assertTrue(header, header.contains("oauth_signature=\"${OAuth1.encode("hCtSmYh+iHYCEqBWrE7C7hYmtUk=")}\""))
    }

    @Test
    fun encodesAndParses() {
        assertEquals("a%20b%2Bc~%2A%C3%B8", OAuth1.encode("a b+c~*ø"))
        assertEquals(
            mapOf("oauth_token" to "abc", "userID" to "123", "username" to "a b"),
            OAuth1.parseForm("oauth_token=abc&userID=123&username=a+b\n"),
        )
    }
}
