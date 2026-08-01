import XCTest
@testable import TinyTubeCore

/* Ported from SchemaTest.kt, minus the half that needs an engine.
 *
 * The Kotlin suite runs every statement against a real SQLite through
 * sqlite-jdbc, which is the right thing to do and is why the migrations are
 * kept out of the Android plumbing. This package has no SQLite dependency —
 * adding one to a target whose whole point is that a plain Linux toolchain runs
 * it would be a poor trade — so what is checked here is the LADDER: that a
 * fresh install and an upgraded one are handed the same statements, that no
 * version's block goes missing, and that the two platforms agree on the shape.
 *
 * That leaves "does this SQL parse" to the Android suite and to the iOS app
 * target, which will have a real database under it. Worth being clear that it
 * is a gap rather than pretending otherwise. */
final class SchemaTests: XCTestCase {

    /* The property that actually matters, and the one Schema's comment warns
       about: a device that installed fresh and a device that upgraded through
       every version must end up with the SAME schema. When they drift, the bug
       only shows on whichever population you didn't test on. */
    func testAFreshInstallAndAnUpgradedInstallGetTheSameStatements() {
        let fresh = Schema.statements(from: 0, to: Schema.version)
        var upgraded: [String] = []
        for v in 1...Schema.version {
            upgraded += Schema.statements(from: v - 1, to: v)
        }
        XCTAssertEqual(fresh, upgraded)
    }

    func testAnUpToDateDatabaseNeedsNoStatements() {
        XCTAssertTrue(Schema.statements(from: Schema.version, to: Schema.version).isEmpty)
    }

    /* Every version contributes something. A block that silently stopped being
       reachable would leave a column missing on exactly the devices that
       upgraded through it. */
    func testEveryVersionContributesStatements() {
        for v in 1...Schema.version {
            XCTAssertFalse(Schema.statements(from: v - 1, to: v).isEmpty,
                           "v\(v) contributes nothing")
        }
    }

    /* The two platforms answer the same questions from the same pure code, so a
       table that exists on one and not the other is a bug waiting for whichever
       platform gets the next feature. Pinned by name against Schema.kt. */
    func testTheTablesMatchAndroid() {
        XCTAssertEqual(Schema.version, 5)
        XCTAssertEqual(Schema.channels, "channels")
        XCTAssertEqual(Schema.videos, "videos")
        XCTAssertEqual(Schema.watches, "watches")

        let all = Schema.statements(from: 0, to: Schema.version).joined(separator: "\n")
        for table in [Schema.channels, Schema.videos, Schema.watches] {
            XCTAssertTrue(all.contains("CREATE TABLE IF NOT EXISTS \(table)"),
                          "no CREATE TABLE for \(table)")
        }
        /* The columns the pure code depends on, by name. */
        for column in ["channel_id", "added_at", "handle", "avatar_url", "uploads_at",
                       "video_id", "published_at", "thumb_url", "position", "watched_at"] {
            XCTAssertTrue(all.contains(column), "no column \(column)")
        }
    }

    /* NULL means never fetched, which is why a channel approved a moment ago
       fills at once rather than waiting a day. A DEFAULT would break that. */
    func testUploadsAtHasNoDefault() {
        let all = Schema.statements(from: 0, to: Schema.version).joined(separator: "\n")
        XCTAssertTrue(all.contains("ALTER TABLE channels ADD COLUMN uploads_at INTEGER"))
        XCTAssertFalse(all.contains("uploads_at INTEGER DEFAULT"))
    }

    /* A row with no position is a row the grid cannot place. */
    func testPositionIsRequired() {
        let all = Schema.statements(from: 0, to: Schema.version).joined(separator: "\n")
        XCTAssertTrue(all.contains("position     INTEGER NOT NULL"))
    }
}
