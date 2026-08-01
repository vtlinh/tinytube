import Foundation

/* Everything the app talks to that isn't YouTube. Counterpart of Endpoints.kt.

   Just the Worker, and on iOS only one of its two jobs. Android also asks it
   for updates; there is no self-update here, so this app only ever asks what an
   approved channel has posted. See README's Platform differences.

   Curation does not come through here. Which channels a child may watch is
   SQLite on this device and nothing the Worker says can change it — the uploads
   route answers a question about a channel already approved. */
enum Endpoints {

    /* Set by wrangler.toml's `name` plus the account's workers.dev subdomain,
       and the same string the Android app has compiled in.

       Changing it strands every installed copy: they keep asking the old
       hostname, and Cloudflare's git build RENAMES the service rather than
       adding a second, so the old name starts answering 404 the moment the new
       one deploys. That happened once already, and it cost every phone
       its approved channels. Android survived it only because the app carrying
       the new hostname was a fresh install rather than an update.

       iOS has even less recourse than Android did — there is no self-update to
       carry a new hostname at all, so every installed copy would have to be
       re-sideloaded by hand. Don't change it. */
    static let worker = "https://tinytube.vtlinh87.workers.dev"

    static var uploads: URL { URL(string: "\(worker)/uploads")! }

    /* Which channel a page is for. Takes a handle or a channel id — never a
       URL, which is what lets this route take input at all. See ChannelResolver
       and the note above channel() in worker.js. */
    static var channel: URL { URL(string: "\(worker)/channel")! }
}
