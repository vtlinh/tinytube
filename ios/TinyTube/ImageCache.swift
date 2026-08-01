import Foundation

/* Forgetting the pictures a channel put on this device.

   A FORWARDER NOW. This used to reach into `URLCache.shared`, because that was
   where `AsyncImage` put things. Both halves of that are gone: images live in
   `ImageStore`, which is a permanent URL-keyed store rather than an HTTP cache,
   and it owns its own removal.

   Kept as a name rather than deleted because `ChannelStore.remove` calls it,
   and that call site is the rule this whole thing exists to serve — removing a
   channel removes everything it put on the device, pictures included. Pointing
   the name at the new store is a smaller change than editing the rule. */
enum ImageCache {

    /* A URL that was never stored is not an error — a channel removed before
       its grid was ever scrolled has nothing on disk, and asking for its
       posters to be forgotten is still the right call to make. */
    static func forget(_ urls: [String]) {
        ImageStore.forget(urls)
    }
}
