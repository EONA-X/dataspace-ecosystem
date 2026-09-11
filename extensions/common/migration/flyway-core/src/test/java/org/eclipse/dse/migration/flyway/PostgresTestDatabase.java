package org.eclipse.dse.migration.flyway;

import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * One throwaway PostgreSQL database, plus the schema introspection the migration tests compare on.
 * <p>
 * The container is a singleton started on first use and left to Ryuk to reap, so the test classes
 * sharing it pay for one container rather than one each.
 */
final class PostgresTestDatabase {

    private static final PostgreSQLContainer<?> CONTAINER = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        CONTAINER.start();
    }

    private final DataSource dataSource;

    private PostgresTestDatabase(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Creates a database of its own and applies the given DDL fixture to it, standing in for what the
     * EDC schema bootstrapper does at boot.
     */
    static PostgresTestDatabase createWith(String fixture) {
        var database = createEmpty();
        database.execute(readResource(fixture));
        return database;
    }

    /**
     * A database with no tables at all, standing in for {@code edc.sql.schema.autocreate=false}: no
     * bootstrapper ever ran, flyway is the only thing that will ever create a table here.
     */
    static PostgresTestDatabase createEmpty() {
        var name = "db_" + UUID.randomUUID().toString().replace("-", "");
        execute(dataSourceFor(CONTAINER.getDatabaseName()), "CREATE DATABASE " + name);
        return new PostgresTestDatabase(dataSourceFor(name));
    }

    static String readResource(String name) {
        try (InputStream stream = PostgresTestDatabase.class.getClassLoader().getResourceAsStream(name)) {
            if (stream == null) {
                throw new IllegalStateException("Test fixture not found: " + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    DataSource dataSource() {
        return dataSource;
    }

    void execute(String sql) {
        execute(dataSource, sql);
    }

    /**
     * {@code table.column type nullability default} for every column, ordered by name so that the
     * position at which a migration appended a column does not count as a difference.
     */
    List<String> columns() {
        return query("""
                SELECT table_name || '.' || column_name || ' ' || data_type || ' ' || is_nullable
                           || ' ' || coalesce(column_default, '-')
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history'
                ORDER BY table_name, column_name
                """);
    }

    List<String> columnNames() {
        return query("""
                SELECT table_name || '.' || column_name
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history'
                ORDER BY table_name, column_name
                """);
    }

    /**
     * Constraints by table, type and column list. Names are deliberately excluded: a constraint
     * declared inline by the upstream DDL is auto-named by PostgreSQL, while the same constraint added
     * by a migration carries the name the migration gives it.
     */
    List<String> constraints() {
        return query("""
                SELECT t.relname || ' ' || c.contype::text || ' ' ||
                       coalesce((SELECT string_agg(a.attname, ',' ORDER BY a.attname)
                                 FROM unnest(c.conkey) AS k
                                          JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k), '')
                FROM pg_constraint c
                         JOIN pg_class t ON t.oid = c.conrelid
                WHERE t.relnamespace = 'public'::regnamespace
                  AND t.relname <> 'flyway_schema_history'
                ORDER BY 1
                """);
    }

    /**
     * Index definitions, with the index name stripped. An index only the upstream DDL creates is
     * absent from a migrated database unless a migration creates it too, which is what this catches.
     * Names are dropped for the same reason as in {@link #constraints()}.
     */
    List<String> indexes() {
        return query("""
                SELECT regexp_replace(indexdef, ' INDEX .* ON ', ' INDEX ON ')
                FROM pg_indexes
                WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'
                ORDER BY 1
                """);
    }

    /**
     * Row counts per table, so that a migration destroying data is not mistaken for a migration that
     * merely reshapes it.
     */
    List<String> rowCounts() {
        List<String> tables = query("""
                SELECT tablename
                FROM pg_tables
                WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'
                ORDER BY 1
                """);
        var counts = new ArrayList<String>();
        for (String table : tables) {
            counts.add(table + " " + query("SELECT count(*)::text FROM " + table).get(0));
        }
        return counts;
    }

    List<String> distinctValues(String table, String column) {
        return query("SELECT DISTINCT %s FROM %s ORDER BY 1".formatted(column, table));
    }

    int historyRowCount() {
        return Integer.parseInt(query("SELECT count(*)::text FROM " + FlywayMigrationRunner.HISTORY_TABLE).get(0));
    }

    List<String> query(String sql) {
        var rows = new ArrayList<String>();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                rows.add(resultSet.getString(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Query failed: " + sql, e);
        }
        return rows;
    }

    private static void execute(DataSource dataSource, String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Statement failed: " + sql, e);
        }
    }

    private static DataSource dataSourceFor(String databaseName) {
        var dataSource = new PGSimpleDataSource();
        dataSource.setUrl("jdbc:postgresql://%s:%d/%s".formatted(CONTAINER.getHost(), CONTAINER.getFirstMappedPort(), databaseName));
        dataSource.setUser(CONTAINER.getUsername());
        dataSource.setPassword(CONTAINER.getPassword());
        return dataSource;
    }
}
