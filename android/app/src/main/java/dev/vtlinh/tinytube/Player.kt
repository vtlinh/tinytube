package dev.vtlinh.tinytube

/* The page the player WebView runs, and the rule for what that WebView is
   allowed to navigate to.

   Both are pure string logic with no Android in them so PlayerTest can hold
   them to their promises under a plain JVM. The navigation
   allowlist in particular is the last thing standing between a tap on a related
   video and the open internet, and it is far too easy to get subtly wrong to
   leave untested. */
object Player {

    /* Hosts the player frame itself is served from. youtube.com is the embed
       origin (see ORIGIN); the others carry the player's own assets and API
       traffic. youtube-nocookie.com stays allowed because it was the origin
       until recently and an installed app mid-update can still be showing it.
       Nothing else loads, at all. */
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

    /* The origin the player document runs on — and the reason a YouTube
       Premium account sees no ads here.
     *
     * It was www.youtube-nocookie.com, YouTube's privacy-enhanced embed domain,
     * which is DELIBERATELY UNAUTHENTICATED: it carries no Google session, so
     * the player was always signed out and always ad-supported no matter who
     * had signed in to parent mode. Premium is a property of the signed-in
     * account, so it could never apply.
     *
     * On youtube.com the player document is same-origin with the session parent
     * mode established, so a Premium account plays without ads. That was asked
     * for explicitly, and it is a trade rather than a free win — what it costs
     * is written up in README under "Signed in, and what that costs".
     *
     * This does NOT widen where the player may navigate: www.youtube.com was
     * already in ALLOWED_HOSTS above, because the IFrame API script is served
     * from it. What changed is what a navigation would SHOW — signed in rather
     * than signed out — which is why the overlay and the allowlist matter more
     * now, not less. */
    const val ORIGIN = "https://www.youtube.com"

    /* The document loaded into the WebView, built around one approved id.

       The id is re-validated here rather than trusted from the caller. It is
       interpolated into a JS string literal, so this is the point where a bad
       value would become script; VideoId already refuses those, and this is the
       second lock on the same door. Returns null instead of building a page. */
    fun pageFor(videoId: String): String? {
        if (!VideoId.isValid(videoId)) return null
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
  // YouTube's controls are left ON, deliberately.
  //
  // controls: 0 was tried and does not do what it sounds like: it removes the
  // control bar, but the mobile embed keeps its own large centre play/pause
  // and title bar regardless, and those appear on their own rather than in
  // response to a tap. Nothing left in the API turns them off — showinfo=0
  // was removed in 2018 and modestbranding=1 became a no-op in 2023 — and
  // asking for the desktop embed by user agent did not change it either.
  //
  // So the approach is the other way round: let YouTube draw whatever it
  // wants, and put a native layer over the whole thing so none of it can be
  // reached. The layer carries nothing of its own — the video plays start to
  // finish and that is the whole interaction. Holding the top-right corner
  // lifts it for an adult who needs the real controls; see PlayerActivity.
  //
  // The end screen is a separate thing and cannot be turned off by any player
  // parameter. Two things are done about it instead: playback is ended a
  // fraction before the video runs out, so the terminal end screen never gets
  // to render, and the native overlay swallows taps so the creator's
  // end-screen cards — which can appear over the last ~20 seconds — cannot be
  // clicked even while visible.
  var player;
  var ourId = '$videoId';
  var wasAd = null;

  function onYouTubeIframeAPIReady() {
    player = new YT.Player('p', {
      videoId: ourId,
      playerVars: {
        autoplay: 1,
        playsinline: 1,
        rel: 0,
        controls: 1,        // left on deliberately; see the note above
        disablekb: 1,       // no keyboard shortcuts into other videos
        fs: 0,              // already fullscreen; the button only confuses
        iv_load_policy: 3   // no annotation cards linking out
      },
      events: {
        onReady: function(e){ e.target.playVideo(); tick(); },
        onStateChange: function(e){
          Bridge.onState(e.data);
          if (e.data === YT.PlayerState.ENDED) Bridge.onEnded();
        },
        onError: function(e){ Bridge.onError(String(e.data)); }
      }
    });
  }

  // Best-effort ad detection. The IFrame API has no ad event, and the player
  // is cross-origin so its DOM cannot be inspected — but during an ad
  // getVideoData() reports the ad's video id rather than ours. That is
  // undocumented and may stop being true; the overlay treats "probably an ad"
  // as a reason to stop intercepting touches, so being wrong costs a tappable
  // player rather than a blocked one.
  function looksLikeAd() {
    try {
      var d = player && player.getVideoData && player.getVideoData();
      if (!d || !d.video_id) return false;
      return d.video_id !== ourId;
    } catch (e) { return false; }
  }

  function tick() {
    try {
      var ad = looksLikeAd();
      if (ad !== wasAd) { wasAd = ad; Bridge.onAd(ad); }

      // End a moment early so the end screen never renders. Not during an ad,
      // and not for a live stream, where getDuration() is 0 and this would
      // fire immediately.
      if (!ad) {
        var d = player.getDuration ? player.getDuration() : 0;
        var t = player.getCurrentTime ? player.getCurrentTime() : 0;
        if (d > 1 && d - t <= 0.4) { player.pauseVideo(); Bridge.onEnded(); return; }
      }
    } catch (e) {}
    setTimeout(tick, 500);
  }
</script>
</body>
</html>
""".trimIndent()
    }
}
