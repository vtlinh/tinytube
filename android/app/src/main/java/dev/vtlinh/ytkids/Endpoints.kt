package dev.vtlinh.ytkids

/* Everything the app talks to that isn't YouTube.

   Just the Worker, and only for the app's own updates: the repository is
   private, so a device with no credential gets a 404 from the release assets,
   and the Worker holds a read-only token and re-serves them.

   Curation does not come through here. Approved channels live on the device
   and their uploads come straight from YouTube's per-channel feeds. */
object Endpoints {
    /* Set by wrangler.toml's `name` plus the account's workers.dev subdomain.
       Changing the worker name means changing this, and old installs keep
       asking the old hostname until they update — which they can only do if
       the old hostname still answers. Rename with that in mind. */
    const val WORKER = "https://yt-kids.vtlinh87.workers.dev"
}
