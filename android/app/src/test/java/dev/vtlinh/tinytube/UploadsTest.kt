package dev.vtlinh.tinytube

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadsTest {

    private val a = "aaaaaaaaaaa"
    private val b = "bbbbbbbbbbb"
    private val c = "ccccccccccc"

    private fun known(vararg videos: Video) = videos.associateBy { it.id }

    private fun reply(vararg entries: String) =
        """{"channel":"UC${"a".repeat(22)}","videos":[${entries.joinToString(",")}]}"""

    private fun bare(id: String) = "\"$id\""

    private fun full(id: String, title: String = "T", published: Long? = 100, thumb: String? = null) =
        JSONObject().apply {
            put("id", id)
            put("title", title)
            published?.let { put("published", it) }
            put("thumb", thumb ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg")
        }.toString()

    @Test fun `a full entry becomes a video`() {
        val v = Uploads.parse(reply(full(a, "First", 500)), emptyMap()).single()
        assertEquals(a, v.id)
        assertEquals("First", v.title)
        assertEquals(500L, v.publishedAt)
        assertEquals("https://i.ytimg.com/vi/$a/hqdefault.jpg", v.thumbnailUrl)
    }

    /* The whole point of the protocol: the Worker leaves out what we told it
       we had, and we fill those back in from the database. */
    @Test fun `a bare id is filled in from what we already had`() {
        val cached = Video(a, "Cached title", 500, "https://i.ytimg.com/vi/$a/hqdefault.jpg")
        val v = Uploads.parse(reply(bare(a)), known(cached)).single()
        assertEquals(cached, v)
    }

    @Test fun `bare and full entries mix, in the order sent`() {
        val cached = Video(b, "Cached", 400)
        val out = Uploads.parse(reply(full(a, "New", 500), bare(b), full(c, "Also new", 300)), known(cached))
        assertEquals(listOf(a, b, c), out.map { it.id })
        assertEquals(listOf("New", "Cached", "Also new"), out.map { it.title })
    }

    /* A bare id we do NOT have is the one case where the two sides disagree —
       a database cleared between the request and the reply, say. Dropping it
       is right: it comes back in full next time, because we will no longer
       claim to know it. A blank tile would not fix itself. */
    @Test fun `a bare id we do not have is dropped rather than shown blank`() {
        val out = Uploads.parse(reply(bare(a), full(b, "Have this")), emptyMap())
        assertEquals(listOf(b), out.map { it.id })
    }

    /* The Worker validated these too. This validates them again on the way in:
       an id ends up in a URL and in a JS string literal, and "our own server
       said so" is not a reason to skip the check that stops the wrong video
       playing. */
    @Test fun `an id that is not a valid video id is refused, however it arrives`() {
        for (bad in listOf("short", "aaaaaaaaaa/", "../../etcpasswd", "aaaaaaaaaaaa", "")) {
            val out = Uploads.parse(reply(full(bad), "\"$bad\"", full(a)), known(Video(bad, "x")))
            assertEquals("should have refused '$bad'", listOf(a), out.map { it.id })
        }
    }

    @Test fun `a repeated id appears once`() {
        val out = Uploads.parse(reply(full(a, "First"), full(a, "Second")), emptyMap())
        assertEquals(1, out.size)
        assertEquals("First", out.single().title)
    }

    @Test fun `an entry with no title falls back to its id`() {
        assertEquals(a, Uploads.parse(reply(full(a, title = "")), emptyMap()).single().title)
        assertEquals(a, Uploads.parse(reply(full(a, title = "   ")), emptyMap()).single().title)
    }

    /* JSON cannot tell a missing number from a zero, and 1970 is not an upload
       time. It has to read as "no date", which sorts last. */
    @Test fun `a missing or zero published time reads as no date`() {
        assertNull(Uploads.parse(reply(full(a, published = null)), emptyMap()).single().publishedAt)
        assertNull(Uploads.parse(reply(full(a, published = 0)), emptyMap()).single().publishedAt)
    }

    /* org.json COERCES and Swift's `as?` casts do not, so a reply carrying the
       wrong type used to build a different grid on each platform: a number for
       an id became a valid-looking eleven-character string and a playable tile
       here while iOS dropped it, and a numeric STRING for published dated a
       video here and left it undated — bottom of the grid — there. The type the
       Worker actually sends is the only one either platform reads. */
    @Test fun `a value of the wrong type is not coerced into one of the right type`() {
        assertTrue(Uploads.parse("""{"videos":[{"id":12345678901,"title":"x"}]}""", emptyMap()).isEmpty())
        assertTrue(Uploads.parse("""{"videos":[{"id":null,"title":"x"}]}""", emptyMap()).isEmpty())

        val stringDate = Uploads.parse("""{"videos":[{"id":"$a","published":"1700000000"}]}""", emptyMap())
        assertNull("a numeric string is not a date", stringDate.single().publishedAt)

        val numberTitle = Uploads.parse("""{"videos":[{"id":"$a","title":2026}]}""", emptyMap())
        assertEquals("a number is not a title", a, numberTitle.single().title)
    }

    /* Whatever is stored here is later fetched and drawn. It does not get to
       be an arbitrary URL somebody sent us. */
    @Test fun `a thumbnail from anywhere but youtube's own hosts is dropped`() {
        for (bad in listOf(
            "https://attacker.example/x.jpg",
            "https://i.ytimg.com.attacker.example/x.jpg",
            "http://evil/i.ytimg.com/x.jpg",
            "javascript:alert(1)",
            "//i.ytimg.com/x.jpg",
            "not a url",
        )) {
            assertNull("should have refused $bad", Uploads.thumb(bad, a))
            /* and the video still appears, with the derived poster */
            val v = Uploads.parse(reply(full(a, thumb = bad)), emptyMap()).single()
            assertEquals("https://i.ytimg.com/vi/$a/hqdefault.jpg", v.thumbnailUrl)
        }
    }

    @Test fun `youtube's own thumbnail hosts are kept`() {
        assertEquals(
            "https://i.ytimg.com/vi/$a/hqdefault.jpg",
            Uploads.thumb("https://i.ytimg.com/vi/$a/hqdefault.jpg", a),
        )
        assertEquals(
            "https://img.youtube.com/vi/$a/0.jpg",
            Uploads.thumb("https://img.youtube.com/vi/$a/0.jpg", a),
        )
    }

    /* Every one of these is reachable: a Worker that is down and returning an
       error page, a truncated body, a captive portal, a shape from a future
       Worker. Each has to come back empty so the caller keeps what it had. */
    @Test fun `junk comes back empty rather than throwing`() {
        for (junk in listOf(
            "", "   ", "not json", "<html>502 Bad Gateway</html>",
            "{}", "{\"videos\":null}", "{\"videos\":{}}", "[1,2,3]",
            "{\"videos\":[null,1,true,[],{}]}",
            "{\"videos\":[{\"id\":null}]}",
        )) {
            assertTrue("should have been empty for '$junk'", Uploads.parse(junk, emptyMap()).isEmpty())
        }
    }

    @Test fun `an empty list is an empty list`() {
        assertTrue(Uploads.parse("{\"videos\":[]}", emptyMap()).isEmpty())
    }

    /* ---- what we send ---- */

    @Test fun `the request names the channel and the ids we have`() {
        val body = JSONObject(Uploads.request("UC${"a".repeat(22)}", listOf(a, b)))
        assertEquals("UC${"a".repeat(22)}", body.getString("channel"))
        val known = body.getJSONArray("known")
        assertEquals(2, known.length())
        assertEquals(a, known.getString(0))
        assertEquals(b, known.getString(1))
    }

    /* A malformed id would only come back in full, but there is no reason to
       send one — and this is the request that decides what the Worker leaves
       out, so it should say exactly what is true. */
    @Test fun `the request leaves out ids that are not well-formed`() {
        val body = JSONObject(Uploads.request("UC${"a".repeat(22)}", listOf(a, "short", "", b)))
        val known = body.getJSONArray("known")
        assertEquals(2, known.length())
        assertEquals(a, known.getString(0))
        assertEquals(b, known.getString(1))
    }

    @Test fun `a request with nothing known is still well-formed`() {
        val body = JSONObject(Uploads.request("UC${"a".repeat(22)}", emptyList()))
        assertEquals(0, body.getJSONArray("known").length())
    }
}
