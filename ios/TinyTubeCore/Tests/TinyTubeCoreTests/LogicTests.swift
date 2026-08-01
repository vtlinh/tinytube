import XCTest
@testable import TinyTubeCore

/* Ported from PlaylistTest.kt. */
final class PlaylistTests: XCTestCase {

    private let never: (Int) -> Int = { _ in
        XCTFail("should not have rolled")
        return 0
    }

    func testInOrderWalksDownTheList() {
        XCTAssertEqual(Playlist.next(count: 5, current: 0, mode: .inOrder, roll: never), 1)
        XCTAssertEqual(Playlist.next(count: 5, current: 3, mode: .inOrder, roll: never), 4)
    }

    /* Not a loop. A grid that plays forever with no edge is a different product
       from one that plays what a parent approved and stops. */
    func testInOrderStopsAtTheEnd() {
        XCTAssertNil(Playlist.next(count: 5, current: 4, mode: .inOrder, roll: never))
        XCTAssertNil(Playlist.next(count: 1, current: 0, mode: .inOrder, roll: never))
    }

    func testRandomNeverRepeatsTheVideoThatJustPlayed() {
        for current in 0..<5 {
            for roll in 0..<4 {
                let next = Playlist.next(count: 5, current: current, mode: .random) { _ in roll }
                XCTAssertNotEqual(next, current, "rolled \(roll) at \(current)")
                XCTAssertTrue((0...4).contains(next!), "out of range: \(next!)")
            }
        }
    }

    func testRandomReachesEveryOtherVideoExactlyOnce() {
        let count = 6
        for current in 0..<count {
            let reached = (0..<(count - 1)).map { roll in
                Playlist.next(count: count, current: current, mode: .random) { _ in roll }!
            }
            XCTAssertEqual(Set(reached), Set(0..<count).subtracting([current]))
            XCTAssertEqual(Set(reached).count, count - 1)
        }
    }

    func testRandomWithASingleVideoStops() {
        XCTAssertNil(Playlist.next(count: 1, current: 0, mode: .random, roll: never))
    }

    /* The one input that comes from outside the pure code. */
    func testAnOutOfRangeRollIsClamped() {
        for roll in [-5, -1, 4, 99, Int.max, Int.min] {
            let next = Playlist.next(count: 5, current: 2, mode: .random) { _ in roll }
            XCTAssertNotNil(next)
            XCTAssertTrue((0...4).contains(next!) && next! != 2, "roll \(roll) gave \(next!)")
        }
    }

    func testAnEmptyOrOutOfRangeListHasNoNext() {
        XCTAssertNil(Playlist.next(count: 0, current: 0, mode: .inOrder, roll: never))
        XCTAssertNil(Playlist.next(count: 3, current: -1, mode: .inOrder, roll: never))
        XCTAssertNil(Playlist.next(count: 3, current: 3, mode: .inOrder, roll: never))
        XCTAssertNil(Playlist.next(count: 3, current: 99, mode: .random, roll: never))
    }

    /* The stored value is a name, and it has to be the SAME name Android
       writes. */
    func testModeNamesMatchTheKotlinEnum() {
        XCTAssertEqual(Playlist.Mode.inOrder.rawValue, "IN_ORDER")
        XCTAssertEqual(Playlist.Mode.random.rawValue, "RANDOM")
    }

    func testAnUnreadableStoredModeReadsAsInOrder() {
        XCTAssertEqual(Playlist.mode(of: nil), .inOrder)
        XCTAssertEqual(Playlist.mode(of: ""), .inOrder)
        XCTAssertEqual(Playlist.mode(of: "SHUFFLE"), .inOrder)
        XCTAssertEqual(Playlist.mode(of: "in_order"), .inOrder)
    }
}

/* Ported from HoldTimeTest.kt. */
final class HoldTimeTests: XCTestCase {

    func testTheRangeIsOneToFiveDefaultingToOne() {
        XCTAssertEqual(HoldTime.minSeconds, 1)
        XCTAssertEqual(HoldTime.maxSeconds, 5)
        XCTAssertEqual(HoldTime.defaultSeconds, 1)
        XCTAssertEqual(HoldTime.defaultInterval, 1.0)
    }

    func testAnythingOutOfRangeIsClamped() {
        XCTAssertEqual(HoldTime.clamp(0), 1)
        XCTAssertEqual(HoldTime.clamp(-7), 1)
        XCTAssertEqual(HoldTime.clamp(Int.min), 1)
        XCTAssertEqual(HoldTime.clamp(6), 5)
        XCTAssertEqual(HoldTime.clamp(Int.max), 5)
        for s in 1...5 { XCTAssertEqual(HoldTime.clamp(s), s) }
    }

    /* A slider counts steps from zero while this range starts at one, which is
       exactly where an off-by-one hides — and the end that would break is the
       short one, where a hold becomes something a resting thumb finishes. */
    func testTheSliderCoversEveryValueAndNothingElse() {
        XCTAssertEqual(HoldTime.sliderMax, 4)
        XCTAssertEqual((0...HoldTime.sliderMax).map(HoldTime.seconds(forProgress:)), [1, 2, 3, 4, 5])
    }

    func testProgressAndSecondsRoundTrip() {
        for s in HoldTime.minSeconds...HoldTime.maxSeconds {
            XCTAssertEqual(HoldTime.seconds(forProgress: HoldTime.progress(forSeconds: s)), s)
        }
        for p in 0...HoldTime.sliderMax {
            XCTAssertEqual(HoldTime.progress(forSeconds: HoldTime.seconds(forProgress: p)), p)
        }
    }

    func testAProgressOutsideTheTrackStillLandsOnAUsableHold() {
        for p in [-3, -1, 5, 99, Int.max, Int.min] {
            XCTAssertTrue((1...5).contains(HoldTime.seconds(forProgress: p)), "progress \(p)")
        }
    }
}

/* Ported from ChannelSortTest.kt. */
final class ChannelSortTests: XCTestCase {

    /* The store hands its list over newest-approved first, so addedAt descends
       down the list this builds. */
    private func channels(_ titles: String...) -> [Channel] {
        titles.enumerated().map { i, title in
            Channel(id: id(title), title: title, addedAt: Int64(1000 - i))
        }
    }

    private func id(_ title: String) -> String {
        "UC" + String(repeating: String(title.prefix(1)), count: 22)
    }

    private let none: [[String: Int]] = [[:], [:], [:]]

    func testLastAddedLeavesTheStoresOrderAlone() {
        let list = channels("Zebra", "Apple", "Mango")
        XCTAssertEqual(
            ChannelSort.sort(list, mode: .lastAdded, countsByWindow: none).map(\.id),
            list.map(\.id)
        )
    }

    func testAToZIgnoresCase() {
        let out = ChannelSort.sort(channels("zebra", "Apple", "mango"), mode: .aToZ)
        XCTAssertEqual(out.map(\.title), ["Apple", "mango", "zebra"])
    }

    func testAToZFallsBackToTheIdForAChannelWithNoTitle() {
        let list = [
            Channel(id: "UC" + String(repeating: "m", count: 22), title: "   ", addedAt: 1),
            Channel(id: "UC" + String(repeating: "a", count: 22), title: "Zebra", addedAt: 2),
        ]
        XCTAssertEqual(
            ChannelSort.sort(list, mode: .aToZ).map(\.id),
            [list[0].id, list[1].id]
        )
    }

    func testTheLadderIsSevenThirtyThreeSixtyFive() {
        XCTAssertEqual(ChannelSort.windowsInDays, [7, 30, 365])
    }

    func testTheNarrowestWindowWithAnythingInItWins() {
        let week = ["UCa": 1]
        let month = ["UCb": 99]
        XCTAssertEqual(ChannelSort.windowIndex([week, month, month]), 0)
        XCTAssertEqual(ChannelSort.windowIndex([[:], month, month]), 1)
        XCTAssertEqual(ChannelSort.windowIndex([[:], [:], month]), 2)
    }

    func testNoHistoryAnywhereHasNoWindow() {
        XCTAssertNil(ChannelSort.windowIndex(none))
        XCTAssertNil(ChannelSort.windowIndex([]))
    }

    /* Otherwise one stale row that groups to zero pins the ladder to its rung
       forever. */
    func testAWindowOfZeroesIsNotAnAnswer() {
        XCTAssertNil(ChannelSort.windowIndex([["UCa": 0], ["UCb": 0]]))
        XCTAssertEqual(ChannelSort.windowIndex([["UCa": 0], ["UCb": 3]]), 1)
    }

    func testMostWatchedSortsByTheCountInTheWinningWindow() {
        let list = channels("Apple", "Banana", "Cherry")
        let counts = [id("Apple"): 2, id("Banana"): 9, id("Cherry"): 5]
        let out = ChannelSort.sort(list, mode: .mostWatched, countsByWindow: [counts, [:], [:]])
        XCTAssertEqual(out.map(\.id), [id("Banana"), id("Cherry"), id("Apple")])
    }

    /* The whole point of narrowing first. */
    func testABusyWeekBeatsABusierYear() {
        let list = channels("Recent", "Ancient")
        let out = ChannelSort.sort(
            list, mode: .mostWatched,
            countsByWindow: [
                [id("Recent"): 1],
                [id("Recent"): 1],
                [id("Recent"): 1, id("Ancient"): 500],
            ]
        )
        XCTAssertEqual(out.map(\.id), [id("Recent"), id("Ancient")])
    }

    func testEqualCountsBreakAlphabetically() {
        let list = channels("Zebra", "Apple", "Mango")
        var counts: [String: Int] = [:]
        for t in ["Zebra", "Apple", "Mango"] { counts[id(t)] = 4 }
        let out = ChannelSort.sort(list, mode: .mostWatched, countsByWindow: [counts, [:], [:]])
        XCTAssertEqual(out.map(\.title), ["Apple", "Mango", "Zebra"])
    }

    /* A-Z rather than last-added, so an absent answer cannot masquerade as the
       default one having agreed with it. */
    func testMostWatchedWithNoHistoryFallsBackToAToZ() {
        let list = channels("Zebra", "Apple", "Mango")
        XCTAssertEqual(
            ChannelSort.sort(list, mode: .mostWatched, countsByWindow: none).map(\.title),
            ["Apple", "Mango", "Zebra"]
        )
        XCTAssertEqual(
            ChannelSort.sort(list, mode: .mostWatched, countsByWindow: []).map(\.title),
            ["Apple", "Mango", "Zebra"]
        )
    }

    func testTheButtonCyclesThroughEveryOrderAndBack() {
        var mode = ChannelSort.Mode.lastAdded
        var seen: Set<ChannelSort.Mode> = [mode]
        for _ in ChannelSort.Mode.allCases {
            mode = ChannelSort.next(mode)
            seen.insert(mode)
        }
        XCTAssertEqual(seen, Set(ChannelSort.Mode.allCases))
        XCTAssertEqual(mode, .lastAdded)
    }

    func testModeNamesMatchTheKotlinEnum() {
        XCTAssertEqual(ChannelSort.Mode.lastAdded.rawValue, "LAST_ADDED")
        XCTAssertEqual(ChannelSort.Mode.aToZ.rawValue, "A_Z")
        XCTAssertEqual(ChannelSort.Mode.mostWatched.rawValue, "MOST_WATCHED")
    }

    func testAnUnreadableStoredOrderReadsAsLastAdded() {
        XCTAssertEqual(ChannelSort.mode(of: nil), .lastAdded)
        XCTAssertEqual(ChannelSort.mode(of: "BY_VIBES"), .lastAdded)
        XCTAssertEqual(ChannelSort.mode(of: "a_z"), .lastAdded)
    }
}
