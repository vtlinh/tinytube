package dev.vtlinh.ytkids

/* The page the player WebView runs, and the rule for what that WebView is
   allowed to navigate to.

   Both are pure string logic with no Android in them so CatalogTest's sibling,
   PlayerTest, can hold them to their promises under a plain JVM. The navigation
   allowlist in particular is the last thing standing between a tap on a related
   video and the open internet, and it is far too easy to get subtly wrong to
   leave untested. */
object Player {

    /* Hosts the player frame itself is served from. youtube-nocookie.com is the
       privacy-preserving embed origin; the others carry the player's own assets
       and API traffic. Nothing else loads, at all. */
    private val ALLOWED_HOSTS = setOf(
        "www.youtube-nocookie.com",
        "youtube-nocookie.com",
        "www.youtube.com",       // the IFrame API script and player XHRs
        "youtube.com",
        "s.ytimg.com",           // player javascript and css
        "i.ytimg.com",           // thumbnails/posters
        "yt3.ggpht.com",         // channel avatars inside the player chrome
        "googlevideo.com",       // the media streams themselves
    )

    /* Does this URL belong to the embedded player, or is it somewhere a tap is
       trying to take the child?

       Matching is on the parsed host only. Substring checks on the whole URL
       are the classic way to get this wrong: "youtube.com" appears in
       "https://youtube.com.attacker.example/", and a naive endsWith accepts
       "https://notyoutube.com/". */
    fun isPlayerUrl(url: String): Boolean {
        val host = hostOf(url) ?: return false
        if (host in ALLOWED_HOSTS) return true
        /* googlevideo serves media from per-request subdomains
           (rr3---sn-abc.googlevideo.com), so that one is matched by suffix —
           on a dot, so "evilgooglevideo.com" does not qualify. */
        return host.endsWith(".googlevideo.com")
    }

    /* Scheme-aware host extraction. Only http(s) has a host worth trusting:
       "javascript:", "intent:", "file:" and friends must never be treated as
       navigable, and returning null for them makes isPlayerUrl refuse. */
    fun hostOf(url: String): String? {
        val m = Regex("^(https?)://([^/?#]+)", RegexOption.IGNORE_CASE).find(url.trim())
            ?: return null
        var host = m.groupValues[2].lowercase()
        /* strip userinfo — "https://www.youtube.com@attacker.example/" has host
           attacker.example, and reading up to the '@' would get it backwards */
        host.indexOf('@').let { if (it >= 0) host = host.substring(it + 1) }
        /* strip port */
        host.indexOf(':').let { if (it >= 0) host = host.substring(0, it) }
        return host.ifEmpty { null }
    }

    const val ORIGIN = "https://www.youtube-nocookie.com"

    /* The document loaded into the WebView, built around one approved id.

       The id is re-validated here rather than trusted from the caller. It is
       interpolated into a JS string literal, so this is the point where a bad
       value would become script; Catalog already refuses those, and this is the
       second lock on the same door. Returns null instead of building a page. */
    fun pageFor(videoId: String): String? {
        if (!Catalog.isValidId(videoId)) return null
        return """
<!doctype html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=no">
<style>
  html,body{margin:0;padding:0;height:100%;background:#000;overflow:hidden}
  #p{position:absolute;inset:0;width:100%;height:100%}
</style>
</head>
<body>
<div id="p"></div>
<script src="https://www.youtube.com/iframe_api"></script>
<script>
  // rel=0 no longer suppresses the end-screen grid outright; it limits it to
  // the same channel. So the grid is not relied on being absent — ENDED closes
  // the whole activity before it can be tapped, and any navigation it might
  // still provoke is refused by the host allowlist on the Kotlin side.
  var player;
  function onYouTubeIframeAPIReady() {
    player = new YT.Player('p', {
      videoId: '$videoId',
      playerVars: {
        autoplay: 1,
        playsinline: 1,
        rel: 0,
        modestbranding: 1,
        controls: 1,
        disablekb: 1,       // no keyboard shortcuts into other videos
        fs: 0,              // already fullscreen; the button only confuses
        iv_load_policy: 3   // no annotation cards linking out
      },
      events: {
        onReady: function(e){ e.target.playVideo(); },
        onStateChange: function(e){
          if (e.data === YT.PlayerState.ENDED) Bridge.onEnded();
        },
        onError: function(e){ Bridge.onError(String(e.data)); }
      }
    });
  }
</script>
</body>
</html>
""".trimIndent()
    }
}
