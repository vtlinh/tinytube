import Foundation
import SQLite3
import TinyTubeCore

/* The one SQLite file, and the migration ladder that builds it.

   `Schema` decides WHAT the SQL is and is pure, so it is tested on Linux. This
   runs it, and running it is the half the Swift package deliberately does not
   do: `SchemaTests` checks the ladder — that fresh and upgraded end up with
   identical statements, that no version's block goes missing — but never
   executes a statement, because adding a SQLite dependency to a package whose
   whole point is that a plain Linux toolchain can run it would be a poor trade.

   So "does this SQL parse" is answered HERE, on a simulator, in the `ios-app`
   job. That is the gap `Schema.swift` names in its own header, and this is the
   thing that closes it.

   One file for channels, videos and watch history together, exactly as on
   Android. `user_version` is SQLite's own integer, which is what makes the
   migration ladder possible without a table to track it. */
final class Database {

    /* SQLITE_TRANSIENT: tell SQLite to copy a bound string rather than hold the
       pointer. Swift's String bridging does not promise the buffer outlives the
       call, and the default (STATIC) means "I promise it does" — which is how a
       bound value turns into garbage between bind and step. */
    private static let transient = unsafeBitCast(
        -1, to: sqlite3_destructor_type.self
    )

    private var handle: OpaquePointer?

    static let shared = Database()

    private init() {}

    /* Opened lazily and kept, so the migration runs once per process rather
       than once per query. */
    private func open() throws -> OpaquePointer {
        if let handle { return handle }

        let dir = try FileManager.default.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let url = dir.appendingPathComponent(Schema.database)

        var db: OpaquePointer?
        guard sqlite3_open(url.path, &db) == SQLITE_OK, let db else {
            throw DatabaseError.cannotOpen(String(cString: sqlite3_errmsg(db)))
        }
        handle = db
        try migrate(db)
        return db
    }

    /* The ladder. `user_version` says where this file is; Schema says what to
       run to get it to the current version. A fresh file reads 0 and gets
       every block in order.
     *
     * In one transaction: a migration that fails halfway leaves a file that is
     * neither the old schema nor the new one, and the next launch would read a
     * version number that does not describe what is actually there. */
    private func migrate(_ db: OpaquePointer) throws {
        let from = Int(try scalar(db, "PRAGMA user_version") ?? 0)
        guard from < Schema.version else { return }

        try exec(db, "BEGIN")
        do {
            for statement in Schema.statements(from: from, to: Schema.version) {
                try exec(db, statement)
            }
            /* PRAGMA will not take a bound parameter, and the value is an Int
               from Schema rather than anything a caller supplies. */
            try exec(db, "PRAGMA user_version = \(Schema.version)")
            try exec(db, "COMMIT")
        } catch {
            try? exec(db, "ROLLBACK")
            throw error
        }
    }

    // MARK: - The small surface the stores use

    func write(_ sql: String, _ args: [Value] = []) throws {
        let db = try open()
        let statement = try prepare(db, sql, args)
        defer { sqlite3_finalize(statement) }
        guard sqlite3_step(statement) == SQLITE_DONE else {
            throw DatabaseError.step(String(cString: sqlite3_errmsg(db)))
        }
    }

    /* Every row, as a closure applied to each. A closure rather than an array
       of dictionaries so nothing has to box a column it does not want. */
    func read<T>(_ sql: String, _ args: [Value] = [], row: (Row) -> T) throws -> [T] {
        let db = try open()
        let statement = try prepare(db, sql, args)
        defer { sqlite3_finalize(statement) }

        var out: [T] = []
        while sqlite3_step(statement) == SQLITE_ROW {
            out.append(row(Row(statement)))
        }
        return out
    }

    /* Several writes or none. Used where a half-applied change would be worse
       than no change — replacing a channel's videos, above all. */
    func transaction(_ body: () throws -> Void) throws {
        let db = try open()
        try exec(db, "BEGIN")
        do {
            try body()
            try exec(db, "COMMIT")
        } catch {
            try? exec(db, "ROLLBACK")
            throw error
        }
    }

    // MARK: - Plumbing

    private func prepare(_ db: OpaquePointer, _ sql: String, _ args: [Value]) throws -> OpaquePointer? {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &statement, nil) == SQLITE_OK else {
            throw DatabaseError.prepare(sql, String(cString: sqlite3_errmsg(db)))
        }
        for (i, value) in args.enumerated() {
            let index = Int32(i + 1)
            switch value {
            case .text(let s): sqlite3_bind_text(statement, index, s, -1, Self.transient)
            case .int(let n): sqlite3_bind_int64(statement, index, n)
            case .null: sqlite3_bind_null(statement, index)
            }
        }
        return statement
    }

    private func exec(_ db: OpaquePointer, _ sql: String) throws {
        var message: UnsafeMutablePointer<CChar>?
        guard sqlite3_exec(db, sql, nil, nil, &message) == SQLITE_OK else {
            let text = message.map { String(cString: $0) } ?? "unknown"
            sqlite3_free(message)
            throw DatabaseError.exec(sql, text)
        }
    }

    private func scalar(_ db: OpaquePointer, _ sql: String) throws -> Int64? {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &statement, nil) == SQLITE_OK else {
            throw DatabaseError.prepare(sql, String(cString: sqlite3_errmsg(db)))
        }
        defer { sqlite3_finalize(statement) }
        guard sqlite3_step(statement) == SQLITE_ROW else { return nil }
        return sqlite3_column_int64(statement, 0)
    }

    enum Value {
        case text(String)
        case int(Int64)
        case null

        /* Nullable columns are common enough here — handle, avatar_url,
           published_at, uploads_at — that the alternative is a ternary at every
           call site. */
        static func text(_ s: String?) -> Value { s.map { .text($0) } ?? .null }
        static func int(_ n: Int64?) -> Value { n.map { .int($0) } ?? .null }
    }

    /* One row, read positionally. Column ORDER is the contract, so every query
       here names its columns explicitly rather than using SELECT *. */
    struct Row {
        private let statement: OpaquePointer?
        init(_ statement: OpaquePointer?) { self.statement = statement }

        func string(_ i: Int32) -> String {
            guard let c = sqlite3_column_text(statement, i) else { return "" }
            return String(cString: c)
        }

        func stringOrNil(_ i: Int32) -> String? {
            guard sqlite3_column_type(statement, i) != SQLITE_NULL else { return nil }
            return string(i)
        }

        func int(_ i: Int32) -> Int64 { sqlite3_column_int64(statement, i) }

        func intOrNil(_ i: Int32) -> Int64? {
            guard sqlite3_column_type(statement, i) != SQLITE_NULL else { return nil }
            return int(i)
        }
    }

    enum DatabaseError: Error {
        case cannotOpen(String)
        case prepare(String, String)
        case step(String)
        case exec(String, String)
    }
}
