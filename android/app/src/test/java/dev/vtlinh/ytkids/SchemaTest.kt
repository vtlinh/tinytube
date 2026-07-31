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
        val fresh = onDb { c -> apply(c, 0, Schema.VERSION); columnsOf(c) }
        val upgraded = onDb { c ->
            apply(c, 0, 1)
            apply(c, 1, 2)
            columnsOf(c)
        }
        assertEquals(fresh, upgraded)
        assertTrue("handle should exist in both, got $fresh", "handle" in fresh)
    }

    private fun columnsOf(c: java.sql.Connection): List<String> {
        val out = mutableListOf<String>()
        c.createStatement().use { st ->
            val rs = st.executeQuery("PRAGMA table_info(channels)")
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
}
