import XCTest
@testable import TinyTubeCore

/* Ported from ChannelGroupsTest.kt, case for case. The rules a screen will not
   show you are wrong. */
final class ChannelGroupsTests: XCTestCase {

    private func ch(_ id: String, _ title: String, group: String? = nil, addedAt: Int64 = 0) -> Channel {
        Channel(id: id, title: title, addedAt: addedAt, groupId: group)
    }

    private let cartoons = ChannelGroups.Group(id: "g1", name: "Cartoons")
    private let science = ChannelGroups.Group(id: "g2", name: "science")

    private func headers(_ rows: [ChannelGroups.Row]) -> [String] {
        rows.compactMap { if case .header(let g, _) = $0 { return g.name } else { return nil } }
    }

    private func items(_ rows: [ChannelGroups.Row]) -> [String] {
        rows.compactMap { if case .item(let c, _) = $0 { return c.id } else { return nil } }
    }

    /* Groups first and A–Z among themselves, whatever the sort — a parent
       looking for a group wants it where it was last time. */
    func testGroupsLeadAlphabeticallyRegardlessOfSort() {
        let channels = [
            ch("c1", "Zed", group: science.id, addedAt: 100),
            ch("c2", "Alpha", group: science.id, addedAt: 90),
            ch("c3", "Beta", group: cartoons.id, addedAt: 80),
            ch("c4", "Gamma", group: cartoons.id, addedAt: 70),
            ch("c5", "Loose", group: nil, addedAt: 60),
        ]
        let rows = ChannelGroups.arrange(
            channels: channels, groups: [science, cartoons], mode: .lastAdded
        )
        XCTAssertEqual(["Cartoons", "science"], headers(rows))
        XCTAssertEqual("c5", items(rows).last, "the loose channel sits below every group")
    }

    func testTheSortOrdersChannelsWithinAGroup() {
        let channels = [
            ch("c1", "Zed", group: cartoons.id, addedAt: 100),
            ch("c2", "Alpha", group: cartoons.id, addedAt: 90),
        ]
        XCTAssertEqual(
            ["c2", "c1"],
            items(ChannelGroups.arrange(channels: channels, groups: [cartoons], mode: .aToZ))
        )
        XCTAssertEqual(
            ["c1", "c2"],
            items(ChannelGroups.arrange(channels: channels, groups: [cartoons], mode: .lastAdded))
        )
    }

    func testAGroupWithFewerThanTwoMembersIsNotDrawn() {
        let rows = ChannelGroups.arrange(
            channels: [ch("c1", "Only", group: cartoons.id)], groups: [cartoons], mode: .aToZ
        )
        XCTAssertTrue(headers(rows).isEmpty)
    }

    func testGroupNeedsTwoAndUngroupNeedsOneGroup() {
        let a = ch("c1", "A", group: cartoons.id)
        let b = ch("c2", "B", group: cartoons.id)
        let loose = ch("c3", "C")
        let other = ch("c4", "D", group: science.id)

        XCTAssertFalse(ChannelGroups.canGroup([a]))
        XCTAssertTrue(ChannelGroups.canGroup([a, loose]))

        XCTAssertTrue(ChannelGroups.canUngroup([a, b]))
        XCTAssertTrue(ChannelGroups.canUngroup([a]), "a subset of one group still counts")
        XCTAssertFalse(ChannelGroups.canUngroup([loose]), "a loose channel is in no group")
        XCTAssertFalse(ChannelGroups.canUngroup([a, loose]))
        XCTAssertFalse(ChannelGroups.canUngroup([a, other]), "two different groups")
        XCTAssertFalse(ChannelGroups.canUngroup([]))
    }

    func testTheNamePrefillsOnlyForOneWholeGroupPlusLoose() {
        let a = ch("c1", "A", group: cartoons.id)
        let b = ch("c2", "B", group: cartoons.id)
        let loose = ch("c3", "C")
        let other = ch("c4", "D", group: science.id)
        let all = [a, b, loose, other, ch("c5", "E", group: science.id)]
        let groups = [cartoons, science]

        XCTAssertEqual("Cartoons", ChannelGroups.prefillName(selected: [a, b, loose], groups: groups, all: all))
        XCTAssertNil(ChannelGroups.prefillName(selected: [a, b], groups: groups, all: all),
                     "no loose channel — nothing is being added")
        XCTAssertNil(ChannelGroups.prefillName(selected: [a, loose], groups: groups, all: all),
                     "only part of the group")
        XCTAssertNil(ChannelGroups.prefillName(selected: [a, b, other], groups: groups, all: all),
                     "two groups — neither name wins")
        XCTAssertNil(ChannelGroups.prefillName(selected: [loose], groups: groups, all: all),
                     "nothing grouped at all")
    }

    func testANameMustNotBeBlankOrAlreadyTaken() {
        let existing = ["Cartoons", "science"]
        XCTAssertEqual(.empty, ChannelGroups.nameError("", existing: existing))
        XCTAssertEqual(.empty, ChannelGroups.nameError("   ", existing: existing))
        XCTAssertEqual(.taken, ChannelGroups.nameError("Cartoons", existing: existing))
        XCTAssertEqual(.taken, ChannelGroups.nameError("  cartoons ", existing: existing),
                       "trimmed and case-insensitive — a person cannot tell them apart")
        XCTAssertNil(ChannelGroups.nameError("Music", existing: existing))
        XCTAssertNil(ChannelGroups.nameError("Music", existing: []))
    }

    func testGroupsLeftWithFewerThanTwoDissolve() {
        let groups = [cartoons, science]
        let stranded = [
            ch("c1", "A", group: cartoons.id),
            ch("c2", "B", group: science.id),
            ch("c3", "C", group: science.id),
        ]
        XCTAssertEqual([cartoons.id], ChannelGroups.dissolving(channels: stranded, groups: groups))

        let emptied = [ch("c2", "B", group: science.id), ch("c3", "C", group: science.id)]
        XCTAssertEqual([cartoons.id], ChannelGroups.dissolving(channels: emptied, groups: groups),
                       "a group with no channels goes too")

        let healthy = [
            ch("c1", "A", group: cartoons.id), ch("c9", "Z", group: cartoons.id),
            ch("c2", "B", group: science.id), ch("c3", "C", group: science.id),
        ]
        XCTAssertTrue(ChannelGroups.dissolving(channels: healthy, groups: groups).isEmpty)
    }

    func testSelectingAGroupSelectsItsChannels() {
        let all = [ch("c1", "A", group: cartoons.id), ch("c2", "B", group: cartoons.id), ch("c3", "C")]
        XCTAssertEqual(["c1", "c2"], ChannelGroups.membersOf(cartoons.id, in: all))
        XCTAssertTrue(ChannelGroups.membersOf("nope", in: all).isEmpty)
    }
}
