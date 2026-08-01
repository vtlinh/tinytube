package dev.vtlinh.tinytube

/* How long the player's corner must be held to lift the overlay.

   A second by default, and the parent can set anything from one to five.

   The range is the interesting part. Android's own long-press is half a
   second, which is short enough for a child to trigger by resting a thumb —
   so one second is the floor rather than the default-and-floor being the same
   number by accident. Five is the ceiling because a hold nobody will sit
   through is not a stronger lock, it is a control an adult gives up on; the
   thing standing between a child and YouTube's controls is that the corner is
   invisible and in a place nothing else is, not the duration.

   Android-free and tested. A slider is exactly the kind of control that ships
   with an off-by-one at one end of its range. */
object HoldTime {

    const val MIN_SECONDS = 1
    const val MAX_SECONDS = 5
    const val DEFAULT_SECONDS = 1

    const val DEFAULT_MILLIS = DEFAULT_SECONDS * 1000L

    /* Anything stored, out of range, or absent becomes something usable. A
       preference file survives an app update and can be edited on a rooted
       device; a hold of zero would make the corner a tap. */
    fun clamp(seconds: Int): Int = seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)

    fun millisFor(seconds: Int): Long = clamp(seconds) * 1000L

    /* A SeekBar counts from zero, and its range is a count of steps rather
       than the values themselves. These two are where an off-by-one would
       live, so they are here rather than inline in the Activity. */
    fun sliderMax(): Int = MAX_SECONDS - MIN_SECONDS

    fun secondsForProgress(progress: Int): Int = clamp(progress + MIN_SECONDS)

    fun progressForSeconds(seconds: Int): Int = clamp(seconds) - MIN_SECONDS
}
