package dev.vtlinh.tinytube

/* Everything the app talks to that isn't YouTube.

   Just the Worker, and two things now rather than one:

   - The app's own updates. The repository is private, so a device with no
     credential gets a 404 from the release assets; the Worker holds a
     read-only token and re-serves them.
   - What an approved channel has posted. The phone used to fetch two megabytes
     of YouTube's web app per channel and parse it; the Worker does that and
     answers with the list. See Uploads and ChannelFeeds.

   Curation still does not come through here. Which channels a child may watch
   is SQLite on this device and nothing the Worker says can change it — the
   uploads route answers a question about a channel already approved. */
object Endpoints {
    /* Set by wrangler.toml's `name` plus the account's workers.dev subdomain.
       Changing the worker name means changing this, and old installs keep
       asking the old hostname until they update — which they can only do if
       the old hostname still answers. Rename with that in mind. */
    const val WORKER = "https://yt-kids.vtlinh87.workers.dev"

    fun uploads(): String = "$WORKER/uploads"
}
