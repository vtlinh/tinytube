package dev.vtlinh.ytkids

/* Reading YouTube URLs: what a channel id looks like, how to find one in a
   page the parent is standing on, and where parent mode is allowed to go.

   Android-free and tested. The channel id ends up in a feed URL and in the
   database as a primary key, and a wrong one either fetches nothing or — worse
   — fetches somebody else's channel into a child's grid. */
object YouTubeUrls {

    /* Channel ids are "UC" followed by 22 url-safe base64 characters. Anchored,
       for the same reason video ids are: an unanchored match happily finds a
       valid-looking id inside a longer hostile string. */
    private val CHANNEL_ID = Regex("^UC[A-Za-z0-9_-]{22}$")

    fun isValidChannelId(id: String): Boolean = CHANNEL_ID.matches(id)

    /* Hosts parent mode may browse. Wider than the player's allowlist on
       purpose — this is a grown-up looking for channels — but still bounded,
       so a stray tap on an ad or an external link doesn't wander off into the
       open web inside our WebView. */
    private val PARENT_HOSTS = setOf(
        "www.youtube.com",
        "m.youtube.com",
        "youtube.com",
        "www.youtube-nocookie.com",
        "s.ytimg.com",
        "i.ytimg.com",
        "yt3.ggpht.com",
        "yt3.googleusercontent.com",
        "fonts.gstatic.com",
    )

    /* Signing in, so a parent can reach their own subscriptions rather than
       hunting channels from a logged-out home page.

       Google's sign-in is a chain of redirects across several of its hosts,
       and it does not degrade when one is blocked — it simply stops on
       whichever step was refused, which from the inside looks like the app
       hanging rather than like a refusal. Enumerating the chain host by host
       turned out to be a losing game, so the whole of google.com is allowed
       here instead.

       This is PARENT mode only, behind the gate, and it is a browser for an
       adult. The player's allowlist is a separate, much narrower list and
       gains none of it — a signed-in Google page must never be reachable from
       the child's screen, and there is a test to that effect. */
    private val SIGN_IN_HOSTS = setOf(
        "google.com",
        "accounts.youtube.com",
        "consent.youtube.com",
    )

    fun isParentBrowsable(url: String): Boolean {
        val host = Player.hostOf(url) ?: return false
        if (host in PARENT_HOSTS || host in SIGN_IN_HOSTS) return true
        /* All matched on a leading dot, so "evilgooglevideo.com",
           "notgstatic.com" and "google.com.attacker.example" do not qualify. */
        return host.endsWith(".google.com") ||
            host.endsWith(".googlevideo.com") ||
            host.endsWith(".googleusercontent.com") ||
            host.endsWith(".gstatic.com")
    }

    /* The channel id sitting in the URL itself, for /channel/UC… pages. */
    fun channelIdFromUrl(url: String): String? {
        if (Player.hostOf(url) == null) return null
        val m = Regex("/channel/(UC[A-Za-z0-9_-]{22})(?:[/?#]|$)").find(url) ?: return null
        return m.groupValues[1].takeIf { isValidChannelId(it) }
    }

    /* Hosts that serve channel pages. Narrower than PARENT_HOSTS, which also
       covers the images and media a page pulls in — none of those is ever
       somewhere a channel can be approved from. */
    private val PAGE_HOSTS = setOf("www.youtube.com", "m.youtube.com", "youtube.com")

    /* The path, with query and fragment removed. Returns null for anything
       without an http(s) host, so a non-navigable scheme can't be probed. */
    fun pathOf(url: String): String? {
        val m = Regex("^https?://[^/?#]+([^?#]*)", RegexOption.IGNORE_CASE).find(url.trim())
            ?: return null
        return m.groupValues[1].ifEmpty { "/" }
    }

    private val CHANNEL_PATH = Regex("^/channel/UC[A-Za-z0-9_-]{22}(?:/.*)?$")
    private val HANDLE_PATH = Regex("^/@[A-Za-z0-9._\\-]{3,30}(?:/.*)?$")

    /* Is the parent standing on a channel, such that "approve" means something
       unambiguous?

       Anchored at the start of the path on purpose. A watch page mentions its
       uploader and a search result lists a dozen channels, but neither IS a
       channel — approving from one would be a guess about which channel was
       meant, and the guess would sometimes be wrong in a child's grid. */
    fun isChannelPage(url: String): Boolean {
        val host = Player.hostOf(url) ?: return false
        if (host !in PAGE_HOSTS) return false
        val path = pathOf(url) ?: return false
        return CHANNEL_PATH.matches(path) || HANDLE_PATH.matches(path)
    }

    /* The @handle, for the many YouTube URLs that carry one instead. A handle
       is not a channel id and cannot be turned into one locally — it has to be
       resolved against the page, see channelIdFromHtml. */
    fun handleFromUrl(url: String): String? {
        if (Player.hostOf(url) == null) return null
        val m = Regex("/@([A-Za-z0-9._\\-]{3,30})(?:[/?#]|$)").find(url) ?: return null
        return m.groupValues[1]
    }

    /* Pull the channel id out of a fetched YouTube page.
     *
     * Every channel page carries its own id in several places; these are the
     * two that have been stable and that are unambiguous. The canonical link
     * is preferred because it is a declared identity rather than an incidental
     * mention — "channelId" also appears in a watch page's payload referring
     * to the uploader, which is in fact what we want there too. */
    fun channelIdFromHtml(html: String): String? {
        Regex("<link[^>]+rel=\"canonical\"[^>]+href=\"https://www\\.youtube\\.com/channel/(UC[A-Za-z0-9_-]{22})\"")
            .find(html)?.groupValues?.get(1)?.let { if (isValidChannelId(it)) return it }
        Regex("\"channelId\"\\s*:\\s*\"(UC[A-Za-z0-9_-]{22})\"")
            .find(html)?.groupValues?.get(1)?.let { if (isValidChannelId(it)) return it }
        return null
    }

    /* A human name for the channel, so the approved list reads as names rather
       than 24-character ids. Only ever cosmetic — nothing depends on it — so
       any failure here falls back to the id rather than blocking an approval. */
    fun channelTitleFromHtml(html: String): String? {
        Regex("<meta[^>]+property=\"og:title\"[^>]+content=\"([^\"]{1,120})\"")
            .find(html)?.groupValues?.get(1)?.let { return it.trim().ifEmpty { null } }
        Regex("<title>([^<]{1,120})</title>")
            .find(html)?.groupValues?.get(1)?.let {
                /* the page title is "Name - YouTube" */
                return it.removeSuffix(" - YouTube").trim().ifEmpty { null }
            }
        return null
    }

    /* Hosts YouTube serves channel avatars from. Checked before an avatar URL
       is stored, because whatever is stored is later fetched and drawn: an
       og:image tag is page-controlled, and "some URL a page told us about" is
       not something to keep in the database and load on sight. */
    private val AVATAR_HOSTS = setOf("yt3.ggpht.com", "yt3.googleusercontent.com")

    /* The channel's avatar, from its page. Cosmetic — a null just means the
       list shows a blank circle — so anything unexpected returns null rather
       than guessing. */
    fun channelAvatarFromHtml(html: String): String? {
        val url = Regex("<meta[^>]+property=\"og:image\"[^>]+content=\"([^\"]{1,500})\"")
            .find(html)?.groupValues?.get(1)
            ?: return null
        val host = Player.hostOf(url) ?: return null
        return if (host in AVATAR_HOSTS || host.endsWith(".googleusercontent.com")) url else null
    }

    /* Every channel has a set of auto-generated playlists whose ids are the
       channel's own with the leading UC replaced. UU is everything it has ever
       posted; UULF is the same list with SHORTS TAKEN OUT.
     *
     * UULF is what this app uses, and it is the whole of how Shorts are kept
     * off a child's screen. Nothing here classifies a video: the page reports
     * every entry as LOCKUP_CONTENT_TYPE_VIDEO whether it is a Short or not,
     * and duration is not the rule — YouTube sorts by aspect ratio, so a
     * thirty-second landscape video is a video and a three-minute vertical one
     * is a Short. Guessing from length would drop exactly the short, wide
     * videos a children's channel posts. This asks YouTube instead.
     *
     * Measured against a live channel: UU held 100 videos of which 48 appeared
     * on the channel's Shorts tab; UULF held 100 with an intersection of zero,
     * and the channel's own Videos tab was a strict subset of it.
     *
     * The naming convention is YouTube's rather than something derived, but it
     * has held for years and it is the only way to name these playlists
     * without an API key. */
    fun longFormPlaylistId(channelId: String): String? {
        if (!isValidChannelId(channelId)) return null
        return "UULF" + channelId.substring(2)
    }

    /* The long-form uploads playlist as an Atom feed. Fifteen entries, ten
       kilobytes, published for the purpose — and the only source here that
       carries an upload TIME, which is what the grid sorts on.
     *
       So it is fetched for every channel, not merely as a fallback: the page
       gives depth and no dates, this gives dates and no depth, and
       Library.datePositions puts the two together. When the page yields
       nothing this is also the whole answer, and being a UULF feed rather than
       a channel feed means even that degraded state has no Shorts in it. */
    fun feedUrl(channelId: String): String? {
        val playlist = longFormPlaylistId(channelId) ?: return null
        return "https://www.youtube.com/feeds/videos.xml?playlist_id=$playlist"
    }

    /* The same playlist's page, which lists a hundred videos in its first
       payload where the feed lists fifteen.
     *
     * hl=en pins the language: the page is a rendering, and the state embedded
     * in it is shaped by locale. It costs nothing and removes a class of "works
     * for me" that would only ever appear on somebody else's phone.
     *
     * Fetched with a DESKTOP user agent, by the caller. A mobile one is
     * redirected to m.youtube.com, whose page carries twenty videos and hides
     * the rest behind a continuation — the exact thing this is here to avoid.
     * (Unrelated to the player's user agent, which is deliberately left alone;
     * see PlayerActivity.) */
    fun uploadsUrl(channelId: String): String? {
        val playlist = longFormPlaylistId(channelId) ?: return null
        return "https://www.youtube.com/playlist?list=$playlist&hl=en"
    }

    const val PARENT_START = "https://m.youtube.com/"
}
