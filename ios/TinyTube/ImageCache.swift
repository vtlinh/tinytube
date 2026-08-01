import Foundation

/* Forgetting the pictures a channel put on this device.

   ⚠️ THIS EXISTS BECAUSE iOS CACHES THEM TO DISK AND ANDROID DOES NOT.

   `AsyncImage` loads through `URLSession.shared`, which uses `URLCache.shared`
   — memory AND disk, in the app's Caches directory. So every poster frame and
   every channel avatar the grid has ever drawn is a file on the phone.

   Android's `Thumbnails` is an in-memory `LruCache` over an OkHttp client with
   no `.cache(…)` configured, so nothing there survives the process. That
   difference is not a decision anyone made; it fell out of the two platforms'
   defaults, and it is why removing a channel needed different work on each.

   What both now guarantee is the thing that matters: removing a channel removes
   what it put on the device. `ChannelStore.remove` calls this with the
   channel's avatar and every one of its posters, and the entries go.

   Nothing here touches the rest of the cache. A blanket
   `URLCache.shared.removeAllCachedResponses()` would be simpler and would also
   throw away every still-approved channel's posters, turning one removal into a
   full re-download of the whole grid on the next launch. */
enum ImageCache {

    /* Drop these URLs from the shared cache, memory and disk both.
     *
     * A URL that was never cached is not an error — a channel removed before
     * its grid was ever scrolled has nothing stored, and asking for its posters
     * to be forgotten is still the right call to make. */
    static func forget(_ urls: [String], cache: URLCache = .shared) {
        for string in urls {
            guard let url = URL(string: string) else { continue }
            cache.removeCachedResponse(for: URLRequest(url: url))
        }
    }
}
