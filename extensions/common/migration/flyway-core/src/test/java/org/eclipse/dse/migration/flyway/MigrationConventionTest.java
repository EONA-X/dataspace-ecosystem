package org.eclipse.dse.migration.flyway;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Conventions the shared history table makes load-bearing rather than cosmetic. Every module writes
 * its versions into the same table, so two modules picking the same version is a boot failure on a
 * database that carries both — and it would only surface on the environments where they meet.
 * <p>
 * Checked here rather than left to review, because the failure is invisible in a single module's diff.
 */
class MigrationConventionTest {

    private static final Pattern MIGRATION_NAME = Pattern.compile("^V(?<date>\\d{8})\\.(?<sequence>\\d+)__(?<description>[a-z0-9_]+)\\.sql$");
    private static final DateTimeFormatter VERSION_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{(?<name>[^}]+)}");
    private static final Pattern SETTING_KEY = Pattern.compile("key\\s*=\\s*\"(?<key>[^\"]+)\"");
    private static final String RESERVED_DATASOURCE_PREFIX = "edc.datasource.";

    @Test
    void everyMigrationIsFound() {
        // Guards the discovery itself: a walk that silently matches nothing would make every other
        // test in this class vacuously green.
        assertThat(MigrationScripts.directories()).isNotEmpty();
        assertThat(MigrationScripts.all()).isNotEmpty();
    }

    @Test
    void everyMigrationFollowsTheNamingConvention() {
        var violations = new ArrayList<String>();

        for (Path script : MigrationScripts.all()) {
            var name = script.getFileName().toString();
            Matcher matcher = MIGRATION_NAME.matcher(name);
            if (!matcher.matches()) {
                violations.add("%s: expected V<YYYYMMDD>.<n>__<lower_snake_case>.sql".formatted(name));
                continue;
            }
            try {
                LocalDate.parse(matcher.group("date"), VERSION_DATE);
            } catch (DateTimeParseException e) {
                violations.add("%s: '%s' is not a valid date".formatted(name, matcher.group("date")));
            }
        }

        assertThat(violations)
                .describedAs("Sequential integer versions collide across modules sharing one history table")
                .isEmpty();
    }

    /**
     * A script may only reference a placeholder the runtime actually supplies. Flyway resolves them at
     * migration time, so an unknown name fails the boot rather than the build — on whichever
     * environment happens to carry that script.
     */
    @Test
    void everyPlaceholderUsedByScriptsIsSuppliedByTheRuntime() {
        var supplied = Set.of(FlywayMigrationExtension.PARTICIPANT_CONTEXT_ID_PLACEHOLDER);
        var violations = new ArrayList<String>();

        for (Path script : MigrationScripts.all()) {
            Matcher matcher = PLACEHOLDER.matcher(read(script));
            while (matcher.find()) {
                if (!supplied.contains(matcher.group("name"))) {
                    violations.add("%s: '%s'".formatted(script.getFileName(), matcher.group("name")));
                }
            }
        }

        assertThat(violations)
                .describedAs("Placeholders the runtime does not provide, see FlywayMigrationExtension#buildPlaceholders")
                .isEmpty();
    }

    @Test
    void noTwoMigrationsUseTheSameVersion() {
        Map<String, List<String>> byVersion = new HashMap<>();

        for (Path script : MigrationScripts.all()) {
            var name = script.getFileName().toString();
            Matcher matcher = MIGRATION_NAME.matcher(name);
            if (matcher.matches()) {
                var version = "%s.%s".formatted(matcher.group("date"), matcher.group("sequence"));
                byVersion.computeIfAbsent(version, key -> new ArrayList<>()).add(script.toString());
            }
        }

        var collisions = byVersion.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(entry -> "version %s used by %s".formatted(entry.getKey(), entry.getValue()))
                .toList();

        assertThat(collisions)
                .describedAs("Two migrations with the same version fail the boot of any runtime carrying both")
                .isEmpty();
    }

    /**
     * {@code edc.datasource} is not a free namespace. {@code CommonsConnectionPoolServiceExtension}
     * binds everything under it as a map of datasources keyed by the next path segment, so a setting
     * called {@code edc.datasource.migration.controlplane} declares a datasource named
     * {@code migration} — and the pool then fails the boot on the {@code url} it cannot find for it.
     * <p>
     * Invisible while the defaults are used, because nothing lands under the prefix and the map stays
     * empty. Fatal the first time the setting is set to what it exists to be set to.
     */
    @Test
    void noMigrationSettingSitsInTheDatasourceNamespace() {
        var violations = new ArrayList<String>();

        for (Path module : MigrationScripts.moduleRoots()) {
            for (Path source : javaSourcesIn(module)) {
                Matcher matcher = SETTING_KEY.matcher(read(source));
                while (matcher.find()) {
                    var key = matcher.group("key");
                    if (key.startsWith(RESERVED_DATASOURCE_PREFIX)) {
                        violations.add("%s declares '%s'".formatted(module.getFileName(), key));
                    }
                }
            }
        }

        assertThat(violations)
                .describedAs("a setting under '%s' is read as a datasource declaration, not as a value", RESERVED_DATASOURCE_PREFIX)
                .isEmpty();
    }

    private static List<Path> javaSourcesIn(Path module) {
        Path sources = module.resolve("src/main/java");
        if (!Files.isDirectory(sources)) {
            return List.of();
        }
        try (var files = Files.walk(sources)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".java")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path script) {
        try {
            return Files.readString(script);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
