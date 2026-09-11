package org.eclipse.dse.migration.flyway;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds every migration set in the repository by walking the source tree, rather than from a hardcoded
 * list. A migration module added later is covered by the tests that use this without anyone having to
 * remember to wire it in — which is the failure mode a hardcoded list has: it is silently incomplete.
 * <p>
 * Sets are addressed as Flyway {@code filesystem:} locations, so no test dependency on the migration
 * modules is needed either.
 */
final class MigrationScripts {

    private static final String MIGRATION_ROOT = "src/main/resources/db/migration";
    private static final int MIGRATION_ROOT_DEPTH = Path.of(MIGRATION_ROOT).getNameCount() + 1;
    private static final Set<String> PRUNED_DIRECTORIES = Set.of("build", ".git", ".gradle", ".idea");
    private static final Pattern VERSION = Pattern.compile("^V(?<version>[0-9]+(?:\\.[0-9]+)*)__");

    /**
     * Flyway compares versions segment by segment, as numbers. Sorting the file names as text would put
     * {@code V20260804.10} before {@code V20260804.2}, so from the tenth migration of a day onwards the
     * tests would apply the scripts in an order production never uses.
     */
    static final Comparator<Path> BY_VERSION = Comparator
            .comparing(MigrationScripts::versionOf, MigrationScripts::compareVersions)
            .thenComparing(script -> script.getFileName().toString());

    private MigrationScripts() {
    }

    /**
     * Every migration set directory in the repository, e.g. {@code .../db/migration/control-plane}.
     */
    static List<Path> directories() {
        var directories = new ArrayList<Path>();
        Path migrationRoot = Path.of(MIGRATION_ROOT);

        try {
            Files.walkFileTree(repositoryRoot(), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (PRUNED_DIRECTORIES.contains(directory.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    Path parent = directory.getParent();
                    if (parent != null && parent.endsWith(migrationRoot)) {
                        directories.add(directory);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        directories.sort(Comparator.naturalOrder());
        return directories;
    }

    static List<String> locations() {
        return directories().stream().map(MigrationScripts::location).toList();
    }

    /**
     * Root directory of every module that takes part in migrations: those owning a set, plus the one
     * the test runs in. Derived from the same walk as the sets, so a module added later is included
     * without anyone remembering to list it.
     */
    static List<Path> moduleRoots() {
        var roots = new ArrayList<Path>();
        roots.add(Path.of("").toAbsolutePath());
        directories().stream().map(MigrationScripts::moduleRootOf).distinct().forEach(roots::add);
        return roots;
    }

    /**
     * From {@code <module>/src/main/resources/db/migration/<set>} back up to {@code <module>}.
     */
    private static Path moduleRootOf(Path setDirectory) {
        Path candidate = setDirectory;
        for (var level = 0; level < MIGRATION_ROOT_DEPTH; level++) {
            candidate = candidate.getParent();
        }
        return candidate;
    }

    static String location(Path directory) {
        return "filesystem:" + directory;
    }

    /**
     * Flyway locations of the named sets, e.g. {@code locationsOf("shared", "control-plane")} for what
     * a control-plane runtime carries.
     */
    static List<String> locationsOf(String... setNames) {
        List<String> names = List.of(setNames);
        return directories().stream()
                .filter(directory -> names.contains(directory.getFileName().toString()))
                .map(MigrationScripts::location)
                .toList();
    }

    /**
     * Every migration script in the repository, ordered by version — the order Flyway applies them in.
     */
    static List<Path> all() {
        return directories().stream()
                .flatMap(MigrationScripts::scriptsIn)
                .sorted(BY_VERSION)
                .toList();
    }

    static List<Path> in(String... setNames) {
        return locationsOf(setNames).stream()
                .map(location -> Path.of(location.substring("filesystem:".length())))
                .flatMap(MigrationScripts::scriptsIn)
                .sorted(BY_VERSION)
                .toList();
    }

    /**
     * Version segments of a script, empty when the name does not follow the convention. Malformed names
     * are tolerated rather than rejected here, so that {@code MigrationConventionTest} is the one to
     * report them, with a message saying what is wrong.
     */
    private static List<Integer> versionOf(Path script) {
        Matcher matcher = VERSION.matcher(script.getFileName().toString());
        if (!matcher.find()) {
            return List.of();
        }
        return Stream.of(matcher.group("version").split("\\.")).map(Integer::parseInt).toList();
    }

    private static int compareVersions(List<Integer> first, List<Integer> second) {
        for (var segment = 0; segment < Math.max(first.size(), second.size()); segment++) {
            // A missing segment counts as 0, so V1 sorts before V1.1 the way Flyway orders them.
            var compared = Integer.compare(
                    segment < first.size() ? first.get(segment) : 0,
                    segment < second.size() ? second.get(segment) : 0);
            if (compared != 0) {
                return compared;
            }
        }
        return 0;
    }

    private static Stream<Path> scriptsIn(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".sql")).toList().stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Walks up from the module directory the test runs in until the Gradle settings file is found.
     */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.exists(candidate.resolve("settings.gradle.kts"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Repository root not found: no settings.gradle.kts above " + Path.of("").toAbsolutePath());
    }
}
