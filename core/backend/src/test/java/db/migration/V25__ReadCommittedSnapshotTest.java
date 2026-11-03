package db.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

class V25__ReadCommittedSnapshotTest {

    private final Context context = mock(Context.class);
    private final Connection connection = mock(Connection.class);
    private final DatabaseMetaData meta = mock(DatabaseMetaData.class);
    private final Statement statement = mock(Statement.class);
    private final ResultSet rs = mock(ResultSet.class);
    private final V25__ReadCommittedSnapshot migration = new V25__ReadCommittedSnapshot();

    private void database(String product, Boolean snapshotOn) throws SQLException {
        when(context.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(meta);
        when(meta.getDatabaseProductName()).thenReturn(product);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(V25__ReadCommittedSnapshot.CHECK)).thenReturn(rs);
        when(rs.next()).thenReturn(snapshotOn != null);
        when(rs.getBoolean(1)).thenReturn(Boolean.TRUE.equals(snapshotOn));
    }

    @Test
    void runsOutsideATransaction() {
        assertThat(migration.canExecuteInTransaction()).isFalse();
    }

    @Test
    void enablesSnapshotOnSqlServerWhenOff() throws SQLException {
        database("Microsoft SQL Server", false);

        migration.migrate(context);

        verify(statement).execute(V25__ReadCommittedSnapshot.ENABLE);
    }

    @Test
    void doesNothingWhenAlreadyOnOrNotSqlServer() throws SQLException {
        database("Microsoft SQL Server", true);
        migration.migrate(context);
        verify(statement, never()).execute(V25__ReadCommittedSnapshot.ENABLE);

        database("H2", null);
        migration.migrate(context);
        when(meta.getDatabaseProductName()).thenReturn(null);
        migration.migrate(context);
        verify(statement, never()).execute(V25__ReadCommittedSnapshot.ENABLE);
    }

    @Test
    void enablesWhenTheDatabaseRowIsMissing() throws SQLException {
        database("Microsoft SQL Server", null);

        migration.migrate(context);

        verify(statement).execute(V25__ReadCommittedSnapshot.ENABLE);
    }

    @Test
    void permissionFailureNamesTheStatementForTheDba() throws SQLException {
        database("Microsoft SQL Server", false);
        when(statement.execute(V25__ReadCommittedSnapshot.ENABLE))
                .thenThrow(new SQLException("User does not have permission", "42000"));

        assertThatThrownBy(() -> migration.migrate(context))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Ask a DBA to run: ALTER DATABASE CURRENT SET READ_COMMITTED_SNAPSHOT ON");
    }
}
