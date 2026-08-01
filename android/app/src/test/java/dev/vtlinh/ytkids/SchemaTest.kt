package dev.vtlinh.ytkids

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.DriverManager

/* The statements the app runs on a device, run here against a real SQLite.
   Migrations execute once, on the only copy of the approved-channel list a
   parent has — a broken one is not something to find out about on a phone. */
class SchemaTest {

    private fun <T> onDb(block: (java.sql.Connection) -> T): T =
        DriverManager.getConnection("jdbc:sqlite::memory:").use(block)

    private fun apply(c: java.sql.Connection, from: Int, to: Int) {
        c.createStatement().use { st ->
            for (sql in Schema.statementsFor(from, to)) st.executeUpdate(sql)
        }
    }

    @Test fun `a fresh install creates a usable schema`() = onDb { c ->
        apply(c, 0, Schema.VERSION)
        c.createStatement().use { st ->
            st.executeUpdate(
                "INSERT INTO channels (channel_id, title, added_at) " +
                    "VALUES ('UCaaaaaaaaaaaaaaaaaaaaaa', 'Some Channel', 100)",
            )
            val rs = st.executeQuery("SELECT channel_id, title, added_at FROM channels")
            assertTrue(rs.next())
            assertEquals("UCaaaaaaaaaaaaaaaaaaaaaa", rs.getString(1))
            assertEquals("Some Channel", rs.getString(2))
            assertEquals(100L, rs.getLong(3))
        }
    }

    /* Approving the same channel twice is an ordinary thing for a parent to
       do — it must update the row, not fail or duplicate it. */
    @Test fun `channel_id is the primary key`() = onDb { c ->
        apply(c, 0, Schema.VERSION)
        c.createStatement().use { st ->
            st.executeUpdate(
                "INSERT OR REPLACE INTO channels (channel_id, title, added_at) " +
                    "VALUES ('UCaaaaaaaaaaaaaaaaaaaaaa', 'First', 1)",
            )
            st.executeUpdate(
                "INSERT OR REPLACE INTO channels (channel_id, title, added_at) " +
                    "VALUES ('UCaaaaaaaaaaaaaaaaaaaaaa', 'Renamed', 2)",
            )
            val rs = st.executeQuery("SELECT COUNT(*), MAX(title) FROM channels")
            rs.next()
            assertEquals(1, rs.getInt(1))
            assertEquals("Renamed", rs.getString(2))
        }
    }

    /* The property that actually matters, and the one Schema's comment warns
       about: a device that installed fresh and a device that upgraded through
       every version must end up with the SAME schema. When they drift, the bug
       only shows on whichever population you didn't test on.

       Note this is not idempotency — ALTER TABLE ADD COLUMN cannot be re-run,
       and SQLiteOpenHelper never asks it to. */
    @Test fun `a fresh install and an upgraded install agree on the schema`() {
        /* Walked one version at a time up to whatever VERSION is now, rather
           than a hand-written chain. The hand-written one silently stopped
           checking the newest migration every time somebody added one — which
           is precisely the migration nobody has run on a real device yet. */
        for (table in listOf(Schema.CHANNELS, Schema.VIDEOS, Schema.WATCHES)) {
            val fresh = onDb { c -> apply(c, 0, Schema.VERSION); columnsOf(c, table) }
            val upgraded = onDb { c ->
                for (v in 1..Schema.VERSION) apply(c, v - 1, v)
                columnsOf(c, table)
            }
            assertEquals("$table drifted between fresh and upgraded", fresh, upgraded)
            assertTrue("$table should exist in both, got $fresh", fresh.isNotEmpty())
        }
        val channels = onDb { c -> apply(c, 0, Schema.VERSION); columnsOf(c, Schema.CHANNELS) }
        assertTrue("handle should exist, got $channels", "handle" in channels)
        assertTrue("avatar_url should exist, got $channels", "avatar_url" in channels)
        assertTrue("uploads_at should exist, got $channels", "uploads_at" in channels)
    }

    private fun columnsOf(c: java.sql.Connection, table: String): List<String> {
        val out = mutableListOf<String>()
        c.createStatement().use { st ->
            val rs = st.executeQuery("PRAGMA table_info($table)")
            while (rs.next()) out.add(rs.getString("name"))
        }
        return out.sorted()
    }

    @Test fun `an up-to-date database needs no statements`() {
        assertTrue(Schema.statementsFor(Schema.VERSION, Schema.VERSION).isEmpty())
    }

    /* The upgrade a device that already ran v1 will actually perform. It runs
       once, on the only copy of the approved list a parent has. */
    @Test fun `upgrading from v1 adds the handle column and keeps the rows`() = onDb { c ->
        apply(c, 0, 1)
        c.createStatement().use { st ->
            st.executeUpdate(
                "INSERT INTO channels (channel_id, title, added_at) " +
                    "VALUES ('UCaaaaaaaaaaaaaaaaaaaaaa', 'Approved before v2', 5)",
            )
        }
        apply(c, 1, 2)
        c.createStatement().use { st ->
            val rs = st.executeQuery("SELECT title, handle FROM channels")
            assertTrue(rs.next())
            assertEquals("Approved before v2", rs.getString(1))
            /* rows that predate the column have no handle, and matching by
               handle has to cope with that rather than treat it as a match */
            assertEquals(null, rs.getString(2))
        }
    }

    @Test fun `a fresh install gets the handle column too`() = onDb { c ->
        apply(c, 0, Schema.VERSION)
        c.createStatement().use { st ->
            st.executeUpdate(
                "INSERT INTO channels (channel_id, title, added_at, handle) " +
                    "VALUES ('UCaaaaaaaaaaaaaaaaaaaaaa', 'T', 1, 'SomeHandle')",
            )
            val rs = st.executeQuery("SELECT handle FROM channels")
            rs.next()
            assertEquals("SomeHandle", rs.getString(1))
        }
    }

    /* ---- the grid ---- */

    /* A channel approved before v4 has no fetch time, and that has to read as
       "never fetched" rather than as "fetched at the epoch" — otherwise every
       upgraded device would wait a day before it ever filled its grid. */
    @Test fun `upgrading keeps the channels and leaves uploads_at null`() = onDb { c ->
        apply(c, 0, 3)
        c.createStatement().use { st ->
            st.executeUpdate(
                "INSERT INTO channels (channel_id, title, added_at) " +
                    "VALUES ('UCaaaaaaaaaaaaaaaaaaaaaa', 'Approved before v4', 5)",
            )
        }
        apply(c, 3, 4)
        c.createStatement().use { st ->
            val rs = st.executeQuery("SELECT title, uploads_at FROM channels")
            assertTrue(rs.next())
            assertEquals("Approved before v4", rs.getString(1))
            rs.getLong(2)
            assertTrue("uploads_at should be null for a pre-v4 row", rs.wasNull())
        }
    }

    /* The same video legitimately appears in two channels' lists after a
       collaboration. The key is the video, so the second write updates the row
       rather than failing or duplicating the tile. */
    @Test fun `video_id is the primary key across channels`() = onDb { c ->
        apply(c, 0, Schema.VERSION)
        c.createStatement().use { st ->
            st.executeUpdate(insertVideo("aaaaaaaaaaa", "UC1", "As channel one has it", 0))
            st.executeUpdate(insertVideo("aaaaaaaaaaa", "UC2", "As channel two has it", 0))
            val rs = st.executeQuery("SELECT COUNT(*), MAX(channel_id) FROM videos")
            rs.next()
            assertEquals(1, rs.getInt(1))
            assertEquals("UC2", rs.getString(2))
        }
    }

    /* An undated video is stored, not refused. It sorts last; a missing
       timestamp is not a reason to hide something a parent approved. */
    @Test fun `published_at and thumb_url are optional`() = onDb { c ->
        apply(c, 0, Schema.VERSION)
        c.createStatement().use { st ->
            st.executeUpdate(
                "INSERT INTO videos (video_id, channel_id, title, position) " +
                    "VALUES ('aaaaaaaaaaa', 'UC1', 'No date', 0)",
            )
            val rs = st.executeQuery("SELECT published_at, thumb_url FROM videos")
            rs.next()
            rs.getLong(1)
            assertTrue(rs.wasNull())
            assertEquals(null, rs.getString(2))
        }
    }

    /* Position is upload order and the reads depend on it, so it is required
       rather than defaulted — a row with no position is a row the grid cannot
       place. */
    @Test fun `a video needs a position`() = onDb { c ->
        apply(c, 0, Schema.VERSION)
        c.createStatement().use { st ->
            try {
                st.executeUpdate(
                    "INSERT INTO videos (video_id, channel_id, title) " +
                        "VALUES ('aaaaaaaaaaa', 'UC1', 'No position')",
                )
                throw AssertionError("should have refused a row with no position")
            } catch (e: java.sql.SQLException) {
                /* what NOT NULL is for */
            }
        }
    }

    /* ---- watch history ---- */

    /* The query the approved list is sorted by: how many plays each channel
       has had since a moment. */
    @Test fun `watches count per channel within a window`() = onDb { c ->
        apply(c, 0, Schema.VERSION)
        c.createStatement().use { st ->
            st.executeUpdate(watch("UC1", "aaaaaaaaaaa", 100))
            st.executeUpdate(watch("UC1", "bbbbbbbbbbb", 200))
            st.executeUpdate(watch("UC2", "ccccccccccc", 150))
            st.executeUpdate(watch("UC1", "ddddddddddd", 50))
            val rs = st.executeQuery(
                "SELECT channel_id, COUNT(*) FROM watches WHERE watched_at >= 100 " +
                    "GROUP BY channel_id ORDER BY channel_id",
            )
            rs.next(); assertEquals("UC1", rs.getString(1)); assertEquals(2, rs.getInt(2))
            rs.next(); assertEquals("UC2", rs.getString(1)); assertEquals(1, rs.getInt(2))
            assertTrue(!rs.next())
        }
    }

    /* The same video watched twice is two rows. A counter per channel could
       not answer "in the last seven days" at all, which is the whole point. */
    @Test fun `the same video can be watched more than once`() = onDb { c ->
        apply(c, 0, Schema.VERSION)
        c.createStatement().use { st ->
            st.executeUpdate(watch("UC1", "aaaaaaaaaaa", 100))
            st.executeUpdate(watch("UC1", "aaaaaaaaaaa", 200))
            val rs = st.executeQuery("SELECT COUNT(*) FROM watches")
            rs.next()
            assertEquals(2, rs.getInt(1))
        }
    }

    /* Rows outlive the videos they name — a refresh replaces a channel's list
       and an uploader can delete one — which is why the channel is stored
       alongside rather than joined for. The oldest history is exactly what the
       365-day rung is for, and it is the history a join would drop. */
    @Test fun `a watch survives its video being replaced`() = onDb { c ->
        apply(c, 0, Schema.VERSION)
        c.createStatement().use { st ->
            st.executeUpdate(insertVideo("aaaaaaaaaaa", "UC1", "Was here", 0))
            st.executeUpdate(watch("UC1", "aaaaaaaaaaa", 100))
            st.executeUpdate("DELETE FROM videos WHERE channel_id = 'UC1'")
            val rs = st.executeQuery("SELECT channel_id FROM watches")
            rs.next()
            assertEquals("UC1", rs.getString(1))
        }
    }

    @Test fun `pruning by age leaves the rows inside the window`() = onDb { c ->
        apply(c, 0, Schema.VERSION)
        c.createStatement().use { st ->
            st.executeUpdate(watch("UC1", "aaaaaaaaaaa", 10))
            st.executeUpdate(watch("UC1", "bbbbbbbbbbb", 500))
            st.executeUpdate("DELETE FROM watches WHERE watched_at < 100")
            val rs = st.executeQuery("SELECT COUNT(*), MIN(watched_at) FROM watches")
            rs.next()
            assertEquals(1, rs.getInt(1))
            assertEquals(500L, rs.getLong(2))
        }
    }

    private fun watch(channel: String, video: String, at: Long) =
        "INSERT INTO watches (channel_id, video_id, watched_at) " +
            "VALUES ('$channel', '$video', $at)"

    private fun insertVideo(id: String, channel: String, title: String, position: Int) =
        "INSERT OR REPLACE INTO videos " +
            "(video_id, channel_id, title, published_at, thumb_url, position) VALUES " +
            "('$id', '$channel', '$title', 100, 'https://i.ytimg.com/vi/$id/hqdefault.jpg', $position)"
}
