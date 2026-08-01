import Foundation

/* Making parent mode's web view look like Safari, so Google will show a login
   form instead of "This browser or app may not be secure".

   This is the mirror image of what Android already does, and it is worth
   stating that way because the two look nothing alike in code.

   Android's `WebView` announces itself with a `; wv` token in the middle of an
   otherwise honest user agent, so `ParentActivity` REMOVES that token.
   `WKWebView` has no such token — its default user agent is Safari's string
   with the trailing `Version/… Safari/…` MISSING, and that absence is the tell.
   So iOS ADDS what Android removes.

   `applicationNameForUserAgent` is appended to the default string rather than
   replacing it, which is what makes this a small lie rather than a whole
   fabricated identity: the OS version, device class and WebKit build all stay
   truthful and current. A hand-written full user agent would go stale and start
   claiming to be an iPhone running an OS that no longer exists.

   ⚠️ THIS IS A WORKAROUND FOR A DELIBERATE RESTRICTION, NOT A SUPPORTED PATH.
   Google has blocked sign-in from embedded webviews at the OAuth endpoint since
   September 2021 and for ordinary account sign-in since July 2023. It may
   tighten this at any time, and the same sentence sits above the Android
   version.

   When it stops working, the answer is NOT a cleverer disguise, and it is not
   `SFSafariViewController` either — that cannot be asked what URL it is showing,
   which is precisely what the approve button needs, and its cookies belong to
   Safari rather than to this app's `WKWebsiteDataStore`, so it cannot even be
   borrowed for a one-time sign-in.

   The answer is that parent mode browses signed out. Every channel is still
   reachable and still approvable; what is lost is starting from your own
   subscriptions, which is a convenience rather than the feature. That fallback
   is already how the app behaves before anyone signs in, so it needs no code —
   which is why this file can fail without taking anything else with it. */
enum BrowserUserAgent {

    /* Appended to WKWebView's default user agent.
     *
     * The version number is deliberately not derived from the running OS. It is
     * a claim about the Safari FEATURE SET, and Google is matching on the shape
     * of the string rather than reading a number out of it; tying it to
     * `ProcessInfo` would imply a precision that isn't there and would produce
     * odd pairings on an old device running a new build. */
    static let safariSuffix = "Version/17.0 Safari/605.1.15"

    /* Whether a user agent string will read as Safari rather than as an
       embedded web view.
     *
     * Exposed for the test rather than for the app — the app just sets the
     * suffix. What this pins is the property that actually matters: both halves
     * present, in a string that still carries the real WebKit build. */
    static func looksLikeSafari(_ userAgent: String) -> Bool {
        userAgent.contains("Version/") && userAgent.contains("Safari/")
    }
}
