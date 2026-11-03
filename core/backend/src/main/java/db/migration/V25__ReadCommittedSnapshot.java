package db.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Turns on READ_COMMITTED_SNAPSHOT for the Core database on SQL Server (other databases: no-op).
 *
 * <p>Under SQL Server's default locking read committed, an index seek for a key that does not exist yet
 * locks the next key. Two RMs applying covenants at once then deadlock: one holds Quartz's lock row
 * and waits for the next CP's uncommitted covenant row, the other holds that row and waits for the
 * Quartz lock. SQL Server kills one transaction, Quartz silently retries its lock in a fresh one, and
 * the apply fails with a foreign-key error (load test: 57% of 8 parallel applies). With row versioning,
 * reads never wait for writers.
 *
 * <p>ALTER DATABASE cannot run inside a transaction, and {@code WITH ROLLBACK IMMEDIATE} rolls back other
 * open transactions on this database, so this runs once, at deploy time. If the login lacks ALTER
 * permission the migration fails with the statement a DBA should run.
 */
public class V25__ReadCommittedSnapshot extends BaseJavaMigration {

    static final String CHECK = "SELECT is_read_committed_snapshot_on FROM sys.databases WHERE name = DB_NAME()";
    static final String ENABLE = "ALTER DATABASE CURRENT SET READ_COMMITTED_SNAPSHOT ON WITH ROLLBACK IMMEDIATE";

    @Override
    public boolean canExecuteInTransaction() {
        return false;
    }

    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        String product = connection.getMetaData().getDatabaseProductName();
        if (product == null || !product.toLowerCase(Locale.ROOT).contains("sql server")) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            if (isOn(statement)) {
                return;
            }
            try {
                statement.execute(ENABLE);
            } catch (SQLException e) {
                throw new SQLException("Could not turn on READ_COMMITTED_SNAPSHOT (" + e.getMessage()
                        + "). Ask a DBA to run: " + ENABLE, e.getSQLState(), e);
            }
        }
    }

    private static boolean isOn(Statement statement) throws SQLException {
        try (ResultSet rs = statement.executeQuery(CHECK)) {
            return rs.next() && rs.getBoolean(1);
        }
    }
}
