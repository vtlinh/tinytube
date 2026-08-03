import Foundation

/* Where the player's web view is allowed to navigate.

   Pure string logic, so the tests can hold it to its promises without a
   simulator. The navigation allowlist is the last thing standing between a tap
   on a related video and the open internet, and it is far too easy to get
   subtly wrong to leave untested.

   Ported from Player.kt, allowlist and all. If a host is added on one platform
   it has to be added on the other, with the same lookalike cases. */
public enum Player {

    /* youtube.com is the embed origin (see `origin`); youtube-nocookie stays
       allowed because it was the origin until recently. The others carry
       the player's own assets and API traffic. Nothing else loads, at all. */
    static let allowedHosts: Set<String> = [
        "www.youtube-nocookie.com",
        "youtube-nocookie.com",
        "www.youtube.com",       // the IFrame API script and player XHRs
        "youtube.com",
        "s.ytimg.com",           // player javascript and css
        "i.ytimg.com",           // thumbnails/posters
        "yt3.ggpht.com",         // channel avatars inside the player chrome
        "googlevideo.com",       // the media streams themselves
    ]

    /* Matching is on the parsed host only. Substring checks on the whole URL
       are the classic way to get this wrong: "youtube.com" appears in
       "https://youtube.com.attacker.example/", and a naive hasSuffix accepts
       "https://notyoutube.com/". */
    public static func isPlayerURL(_ url: String) -> Bool {
        guard let host = hostOf(url) else { return false }
        if allowedHosts.contains(host) { return true }
        /* Media comes from per-datacentre subdomains
           (rr3---sn-abc.googlevideo.com), so that one is matched by suffix —
           on a dot, so "evilgooglevideo.com" does not qualify. */
        return host.hasSuffix(".googlevideo.com")
    }

    /* The same question, asked of a navigation rather than of a URL — and the
       answer differs for the MAIN FRAME. Ported from Player.kt, where the whole
       reasoning is written out; the short version is that allowedHosts must
       contain www.youtube.com for the embed, the API script and the player's
       XHRs, the match is host-only, and that makes /watch and /results equally
       acceptable — fine for a subframe, not fine for the top document, which a
       tap on the branding YouTube draws over an ad would otherwise replace with
       the whole mobile site inside a child's player.

       So the main frame may only ever be the wrapper's own origin, which is
       also what the app itself loads there. Everything else is unchanged and
       still goes through isPlayerURL. */
    public static func isPlayerNavigation(_ url: String, mainFrame: Bool) -> Bool {
        guard mainFrame else { return isPlayerURL(url) }
        guard let host = hostOf(url), let ours = hostOf(origin) else { return false }
        return host == ours
    }

    /* The host of an http(s) URL, lowercased, or nil.
     *
     * Parsed by hand rather than with URLComponents, deliberately. This has to
     * agree with the Kotlin original character for character — it is the same
     * lock on two doors — and Foundation's parser differs from Android's in
     * exactly the corners that matter here, including what it does with
     * userinfo and with a URL it considers malformed. A hand parser is a few
     * lines and is the same few lines on both platforms.
     *
     * "javascript:", "intent:", "file:" and friends must never be treated as
     * navigable, and returning nil for them makes isPlayerURL refuse. */
    public static func hostOf(_ url: String) -> String? {
        let trimmed = url.trimmingCharacters(in: .whitespacesAndNewlines)
        let lower = trimmed.lowercased()
        let scheme: String
        if lower.hasPrefix("https://") { scheme = "https://" }
        else if lower.hasPrefix("http://") { scheme = "http://" }
        else { return nil }

        var rest = String(trimmed.dropFirst(scheme.count))
        /* Stop at the first of / ? # — everything after is path, query or
           fragment and none of it is the host. */
        if let end = rest.firstIndex(where: { $0 == "/" || $0 == "?" || $0 == "#" }) {
            rest = String(rest[rest.startIndex..<end])
        }
        var host = rest.lowercased()

        /* "https://www.youtube.com@attacker.example/" is a request to
           attacker.example, and reading up to the '@' would get it backwards. */
        if let at = host.lastIndex(of: "@") {
            host = String(host[host.index(after: at)...])
        }
        if let colon = host.firstIndex(of: ":") {
            host = String(host[host.startIndex..<colon])
        }
        return host.isEmpty ? nil : host
    }

    /* The origin the WRAPPER document runs on — NOT where the player is served
       from. The embed iframe has always been www.youtube.com: `YT.Player` only
       points at the nocookie domain when passed `host:`, and the page below
       never has.

       BACK ON www.youtube-nocookie.com, AND IT HAS TO STAY THERE. Ported from
       Player.kt, where the whole reasoning is written out; the short version is
       that moving it to www.youtube.com to pick up a Premium session broke
       playback on BOTH platforms — every video came up "Video unavailable" —
       because this page is a synthetic document (`loadHTMLString` here,
       `loadDataWithBaseURL` on Android) claiming an origin it cannot prove, and
       YouTube's embed refuses to serve a player to it. The nocookie domain
       exists precisely to be embedded by pages that are not YouTube.

       Don't reinstate the other value on its own: it is not a configuration
       choice, it is the bug — and it was never the Premium lever either, which
       is the cookie policy on the third-party iframe rather than this string.
       Android probes that with setAcceptThirdPartyCookies; WKWebView has no
       equivalent, so iOS needs the wrapper served from a real origin. See
       README. */
    public static let origin = "https://www.youtube-nocookie.com"

    /* The page loaded into the player's web view. Line for line what
       Player.kt builds, and it has to stay that way — see the tick loop below,
       which was missing here for a while and took ad detection and end-screen
       suppression with it.
     *
     * The id is re-validated here rather than trusted from the caller. It is
     * interpolated into a JS string literal, so this is the point where a bad
     * value would become script; VideoId already refuses those, and this is the
     * second lock on the same door. Returns nil instead of building a page. */
    public static func pageFor(videoId: String) -> String? {
        guard VideoId.isValid(videoId) else { return nil }
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
          // YouTube's controls are left ON, deliberately. Nothing in the API
          // turns the mobile embed's own centre play/pause and title bar off,
          // so the approach is the other way round: let YouTube draw whatever
          // it wants and put a native layer over the whole thing.
          //
          // The end screen is a separate thing and cannot be turned off by any
          // player parameter. Playback is ended a fraction before the video
          // runs out instead, so the terminal end screen never gets to render.
          var player;
          var ourId = '\(videoId)';
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

          // Best-effort ad detection. The IFrame API has no ad event, and the
          // player is cross-origin so its DOM cannot be inspected — but during
          // an ad getVideoData() reports the ad's video id rather than ours.
          // That is undocumented and may stop being true; the overlay treats
          // "probably an ad" as a reason to stop intercepting touches, so being
          // wrong costs a tappable player rather than a blocked one.
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

              // End a moment early so the end screen never renders. Not during
              // an ad, and not for a live stream, where getDuration() is 0 and
              // this would fire immediately.
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
        """
    }
}
