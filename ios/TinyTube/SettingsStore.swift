import Foundation
import TinyTubeCore

/* The parent's choices. Counterpart of SettingsStore.kt.

   UserDefaults rather than the database, matching Android's SharedPreferences:
   these are three small values with no relations, and they are read on every
   screen that draws. Stored as the enums' raw strings rather than as ordinals,
   so reordering a case in TinyTubeCore cannot silently change what a device
   already chose. */
enum SettingsStore {

    private static let keyNext = "next_mode"
    private static let keyChannelSort = "channel_sort"
    private static let keyHoldSeconds = "hold_seconds"

    /* What plays when a video ends. */
    static func nextMode(_ d: UserDefaults = .standard) -> Playlist.Mode {
        Playlist.mode(of: d.string(forKey: keyNext))
    }

    static func setNextMode(_ mode: Playlist.Mode, _ d: UserDefaults = .standard) {
        d.set(mode.rawValue, forKey: keyNext)
    }

    /* What order the approved channels are listed in — the parent's list and
       the child's Channels tab both. It is one list. */
    static func channelSort(_ d: UserDefaults = .standard) -> ChannelSort.Mode {
        ChannelSort.mode(of: d.string(forKey: keyChannelSort))
    }

    static func setChannelSort(_ mode: ChannelSort.Mode, _ d: UserDefaults = .standard) {
        d.set(mode.rawValue, forKey: keyChannelSort)
    }

    /* How long the reveal corner must be held. `HoldTime` clamps it, so a value
       written by an older build — or a corrupt one — cannot produce a corner
       that unlocks instantly or one nobody can hold long enough. */
    static func holdSeconds(_ d: UserDefaults = .standard) -> Int {
        guard d.object(forKey: keyHoldSeconds) != nil else { return HoldTime.defaultSeconds }
        return HoldTime.clamp(d.integer(forKey: keyHoldSeconds))
    }

    static func setHoldSeconds(_ seconds: Int, _ d: UserDefaults = .standard) {
        d.set(HoldTime.clamp(seconds), forKey: keyHoldSeconds)
    }
}
