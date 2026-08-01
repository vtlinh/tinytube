import Foundation
import CryptoKit
import UIKit

/* Where poster frames and channel avatars live on this device.

   Counterpart of Thumbnails.kt, and it makes the same promise: a URL that has
   been fetched once is never fetched again — not re-validated, not refreshed,
   not aged out.

   ⚠️ THIS REPLACED AsyncImage, AND THE REASON IS THAT AsyncImage COULD NOT
   MAKE THAT PROMISE. It loads through `URLSession.shared`, which caches into
   `URLCache.shared`: disk-backed, but small by default, evicted by capacity,
   and governed by HTTP freshness. So a grid could be re-downloaded because the
   cache filled, or because a response went stale, or because the system
   reclaimed the Caches directory — and offline it drew nothing at all once any
   of that had happened. None of those are things a parent can see or act on.

   Two consequences of the promise, both deliberate:

     - The grid draws with no network, indefinitely.
     - A poster or avatar that CHANGES upstream is not picked up. A thumbnail is
       a picture of a video that already exists and an avatar changes about
       never; neither is worth a conditional request per tile per launch.

   In Application Support rather than Caches, precisely so iOS cannot reclaim it
   under storage pressure and quietly reintroduce the re-download. Excluded from
   backup, because every byte of it is re-fetchable and a restore that arrives
   without it simply fills again.

   What bounds it is removal, not expiry: `forget` runs when a channel goes and
   when a refresh drops videos off the end of a channel's list. See
   ChannelStore.remove and VideoStore.replace. */
enum ImageStore {

    /* Small, and only an accelerator — every hit here is also on disk. NSCache
       rather than a dictionary so iOS can drop it under memory pressure without
       any of this having to notice. */
    private static let memory: NSCache<NSString, UIImage> = {
        let c = NSCache<NSString, UIImage>()
        c.countLimit = 300
        return c
    }()

    private static let session: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 20
        /* No URLCache: this file IS the cache, and a second one underneath it
           would store every image twice. */
        config.urlCache = nil
        return URLSession(configuration: config)
    }()

    private static let directory: URL? = {
        guard let base = try? FileManager.default.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        ) else { return nil }

        var dir = base.appendingPathComponent("images", isDirectory: true)
        do {
            try FileManager.default.createDirectory(
                at: dir, withIntermediateDirectories: true
            )
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try? dir.setResourceValues(values)
        } catch {
            return nil
        }
        return dir
    }()

    /* SHA-256 of the URL, hex. A file name has to be filesystem-safe and stable
       across launches, and a URL is neither — it carries slashes and query
       strings and can exceed the name limit. Hashing gives both. */
    private static func file(for url: String) -> URL? {
        guard let dir = directory else { return nil }
        let digest = SHA256.hash(data: Data(url.utf8))
        return dir.appendingPathComponent(digest.map { String(format: "%02x", $0) }.joined())
    }

    /* Memory, then disk, then the network — and the network only ever once per
       URL. Returns nil for "no picture", which every caller draws a placeholder
       for. */
    static func load(_ string: String?) async -> UIImage? {
        guard let string, !string.isEmpty, let url = URL(string: string) else { return nil }

        if let hit = memory.object(forKey: string as NSString) { return hit }

        let path = file(for: string)
        if let path, let data = try? Data(contentsOf: path), let image = UIImage(data: data) {
            memory.setObject(image, forKey: string as NSString)
            return image
        }

        guard let (data, response) = try? await session.data(from: url),
              (response as? HTTPURLResponse)?.statusCode ?? 200 < 400,
              let image = UIImage(data: data)
        else { return nil }

        /* Written atomically, so a kill mid-write cannot leave half a JPEG
           under a name that says it is whole. */
        if let path { try? data.write(to: path, options: .atomic) }
        memory.setObject(image, forKey: string as NSString)
        return image
    }

    /* Drop these, because the channel they belong to is no longer approved — or
       because a refresh pushed them off the end of its list.
     *
     * Nothing here expires on its own, so this is the ONLY thing that ever
     * removes a picture from the phone. "Removed" has to mean removed, or a
     * parent who takes a channel away leaves its posters on the device for the
     * life of the install. */
    static func forget(_ urls: [String]) {
        for url in urls where !url.isEmpty {
            memory.removeObject(forKey: url as NSString)
            if let path = file(for: url) { try? FileManager.default.removeItem(at: path) }
        }
    }
}
