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

    /* SQLiteOpenHelper re-runs onCreate on a fresh file and onUpgrade on an
       existing one; neither may blow up on a schema that is already there. */
    @Test fun `applying the same version twice is harmless`() = onDb { c ->
        apply(c, 0, Schema.VERSION)
        apply(c, 0, Schema.VERSION)
        c.createStatement().use { st ->
            val rs = st.executeQuery("SELECT COUNT(*) FROM channels")
            rs.next()
            assertEquals(0, rs.getInt(1))
        }
    }

    @Test fun `an up-to-date database needs no statements`() {
        assertTrue(Schema.statementsFor(Schema.VERSION, Schema.VERSION).isEmpty())
    }
}
