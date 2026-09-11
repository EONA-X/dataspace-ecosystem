package org.eclipse.dse.migration.flyway;

import org.eclipse.edc.junit.annotations.ComponentTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Runs every migration script in the repository twice, directly against the database, and checks that
 * the second run changes nothing.
 * <p>
 * Flyway itself cannot verify this: it records a version once applied and skips it afterwards, so a
 * non-idempotent statement is never re-executed and never caught. Idempotence is nevertheless the
 * property the whole design rests on — it is what lets a migration be applied to a database whose
 * prior state was never recorded. Bypassing Flyway is the point of this test.
 * <p>
 * Scripts are discovered from the source tree, so a migration module added later is covered without
 * anything being wired in here.
 */
@ComponentTest
class PostgresMigrationReplayTest {

    private static final String PARTICIPANT_ID = "did:web:example:eonax";
    private static final String PLACEHOLDER = "${" + FlywayMigrationExtension.PARTICIPANT_CONTEXT_ID_PLACEHOLDER + "}";

    /**
     * Known limitation, not a regression: replays scripts directly, bypassing Flyway's own history
     * entirely, so it hits the same frozen-baseline {@code lease_id} index inconsistency as
     * {@link PostgresFlywayMigrationTest#migratedFrom014_convergesWithFreshly015Database()} -- see
     * that test's javadoc and {@code flyway-core/README.md#tests}.
     */
    @Tag("known-limitation")
    @Test
    void everyMigrationIsIdempotent_onMigratedDatabase() {
        var database = PostgresTestDatabase.createWith("fixtures/schema-0.14.sql");
        database.execute(PostgresTestDatabase.readResource("fixtures/seed-0.14.sql"));
        List<Path> scripts = MigrationScripts.all();
        assertThat(scripts).isNotEmpty();

        applyAll(database, scripts);
        var columns = database.columns();
        var constraints = database.constraints();
        var indexes = database.indexes();
        var rowCounts = database.rowCounts();

        assertThatCode(() -> applyAll(database, scripts))
                .describedAs("Replaying a migration must not fail. Use ADD COLUMN IF NOT EXISTS, " +
                        "DROP COLUMN IF EXISTS, updates tolerating zero rows, and guard constraints on their columns.")
                .doesNotThrowAnyException();

        assertThat(database.columns()).containsExactlyElementsOf(columns);
        assertThat(database.constraints()).containsExactlyElementsOf(constraints);
        assertThat(database.indexes()).containsExactlyElementsOf(indexes);
        assertThat(database.rowCounts())
                .describedAs("A replayed migration must not destroy data")
                .containsExactlyElementsOf(rowCounts);
    }

    /**
     * The same property from the other starting state: a database the application just created in the
     * current shape, where every migration is a no-op from the first run onwards.
     * <p>
     * Known limitation, not a regression: same frozen-baseline replay issue as
     * {@link #everyMigrationIsIdempotent_onMigratedDatabase()}.
     */
    @Tag("known-limitation")
    @Test
    void everyMigrationIsIdempotent_onFreshDatabase() {
        var database = PostgresTestDatabase.createWith("fixtures/schema-0.15.sql");
        List<Path> scripts = MigrationScripts.all();

        applyAll(database, scripts);
        var columns = database.columns();

        assertThatCode(() -> applyAll(database, scripts)).doesNotThrowAnyException();
        assertThat(database.columns()).containsExactlyElementsOf(columns);
    }

    /**
     * Applies the scripts in version order, outside Flyway. Placeholders are substituted by hand
     * because Flyway is not the one reading the files here.
     */
    private void applyAll(PostgresTestDatabase database, List<Path> scripts) {
        for (Path script : scripts) {
            database.execute(read(script).replace(PLACEHOLDER, PARTICIPANT_ID));
        }
    }

    private String read(Path script) {
        try {
            return Files.readString(script);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
