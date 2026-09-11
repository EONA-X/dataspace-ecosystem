package org.eclipse.dse.migration.flyway;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The order this helper hands scripts back in is the order the replay test applies them in. It has to
 * be the order Flyway would use, or that test exercises a sequence production never runs.
 */
class MigrationScriptsTest {

    @Test
    void ordersTheTenthMigrationOfOneDayAfterTheSecond() {
        var scripts = new ArrayList<>(List.of(
                Path.of("db/migration/shared/V20260804.10__tenth.sql"),
                Path.of("db/migration/shared/V20260804.2__second.sql"),
                Path.of("db/migration/shared/V20260804.1__first.sql")));

        scripts.sort(MigrationScripts.BY_VERSION);

        assertThat(scripts).map(script -> script.getFileName().toString())
                .describedAs("Flyway compares version segments as numbers, not as text")
                .containsExactly("V20260804.1__first.sql", "V20260804.2__second.sql", "V20260804.10__tenth.sql");
    }

    @Test
    void ordersAcrossDaysAndShorterVersions() {
        var scripts = new ArrayList<>(List.of(
                Path.of("V20260805.1__next_day.sql"),
                Path.of("V20260804.3__same_day.sql"),
                Path.of("V20260804__no_sequence.sql")));

        scripts.sort(MigrationScripts.BY_VERSION);

        assertThat(scripts).map(script -> script.getFileName().toString())
                .containsExactly("V20260804__no_sequence.sql", "V20260804.3__same_day.sql", "V20260805.1__next_day.sql");
    }

    /**
     * A name the convention test is meant to report. Discovery must not throw before it gets the
     * chance, or the failure arrives as a stack trace instead of the message explaining what to fix.
     */
    @Test
    void toleratesNamesThatDoNotFollowTheConvention() {
        var scripts = new ArrayList<>(List.of(
                Path.of("V20260804.1__first.sql"),
                Path.of("not_a_migration.sql")));

        scripts.sort(MigrationScripts.BY_VERSION);

        assertThat(scripts).map(script -> script.getFileName().toString())
                .containsExactly("not_a_migration.sql", "V20260804.1__first.sql");
    }
}
