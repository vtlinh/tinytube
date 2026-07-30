package dev.vtlinh.ytkids

/* Everything the app talks to, in one place.

   All of it is the Worker. The repository is private, so neither the release
   assets nor the catalog can be fetched from GitHub by a device with no
   credential — the Worker holds a read-only token and re-serves exactly the
   handful of files that installed copies need. */
object Endpoints {
    /* Set by wrangler.toml's `name` plus the account's workers.dev subdomain.
       Changing the worker name means changing this, and old installs keep
       asking the old hostname until they update — which they can only do if
       the old hostname still answers. Rename with that in mind. */
    const val WORKER = "https://yt-kids.vtlinh87.workers.dev"

    /* The approved-video list. */
    const val CATALOG = "$WORKER/catalog.json"
}
