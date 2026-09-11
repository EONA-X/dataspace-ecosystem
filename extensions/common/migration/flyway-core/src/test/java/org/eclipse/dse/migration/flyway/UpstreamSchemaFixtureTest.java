package org.eclipse.dse.migration.flyway;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Detects drift between the schema EDC creates at boot and the fixture the migration tests start
 * from.
 * <p>
 * The migration tests compare a migrated database against one the application just created, using
 * fixtures that stand in for the schema bootstrapper. Nothing regenerates those fixtures: on the next
 * EDC upgrade the DDL changes, the fixtures do not, and every migration test stays green while
 * testing a schema that no longer exists. This is the check that fails instead.
 * <p>
 * The DDL is read from the store jars on the test classpath, so the comparison is against the exact
 * version being built. The fixture it applies to is the one named after that version — an EDC upgrade
 * therefore fails here with a missing fixture until someone produces it.
 */
class UpstreamSchemaFixtureTest {

    private static final String SCHEMA_SUFFIX = "-schema.sql";

    /**
     * DDL resources of every store whose tables live in a participant database, in the order the
     * fixture concatenates them. Extend this together with the migration sets when a store is added.
     * <p>
     * The runtimes of one participant share a single database, so this is one list rather than one per
     * runtime. Several of these modules declare {@code edc_lease}; the repetition is harmless because
     * the DDL is written with {@code CREATE TABLE IF NOT EXISTS}.
     */
    private static final List<String> DDL_RESOURCES = List.of(
            // control-plane and data-plane
            "transfer-process-schema.sql",
            "contract-negotiation-schema.sql",
            "asset-index-schema.sql",
            "policy-definition-schema.sql",
            "contract-definition-schema.sql",
            "policy-monitor-schema.sql",
            "edr-index-schema.sql",
            "dataplane-schema.sql",
            "dataplane-instance-schema.sql",
            "telemetry-record-schema.sql",
            "accesstoken-data-schema.sql",
            // identity-hub, and the stores its SQL BOM shares with the issuer-service
            "credentials-schema.sql",
            "did-schema.sql",
            "keypairs-schema.sql",
            "participant-context-schema.sql",
            "sts-client-schema.sql",
            "credential-offer-schema.sql",
            "holder-credential-request-schema.sql",
            // issuer-service only
            "holder-schema.sql",
            "attestation-definition-schema.sql",
            "credential-definition-schema.sql",
            "issuance-process-schema.sql",
            // control-plane, identity-hub, issuer-service (not data-plane)
            "jti-validation-schema.sql");

    @Test
    void fixtureMatchesTheSchemaThisEdcVersionCreates() throws IOException {
        var fixtureName = "schema-%s.sql".formatted(shortEdcVersion());
        Path fixture = Path.of("src/test/resources/fixtures", fixtureName);
        var expected = concatenatedDdl();

        assertThat(fixture)
                .describedAs("No fixture for EDC %s. The migration tests need one describing the schema " +
                        "this version creates: generate it from the classpath DDL, then check which " +
                        "migrations the change requires.", shortEdcVersion())
                .exists();

        var actual = Files.readString(fixture);
        if (!actual.equals(expected)) {
            Path report = Path.of("build/reports").resolve(fixtureName);
            Files.createDirectories(report.getParent());
            Files.writeString(report, expected);
            assertThat(actual)
                    .describedAs("The upstream DDL changed and %s no longer matches it. The regenerated " +
                            "content is at %s — diff it, copy it over the fixture, and write the migrations " +
                            "the difference calls for. A fixture updated without the matching migration " +
                            "makes the convergence test green on a schema no existing database has.",
                            fixture, report.toAbsolutePath())
                    .isEqualTo(expected);
        }
    }

    /**
     * The other direction, which the list alone cannot guard. Both sides of
     * {@link #fixtureMatchesTheSchemaThisEdcVersionCreates()} are built from {@code DDL_RESOURCES}, so a
     * store added to {@code build.gradle.kts} but forgotten here is invisible: its tables are in neither
     * the fixture nor the expectation, and every migration test stays green on a partial schema.
     */
    @Test
    void everySchemaResourceOnTheClasspathIsListed() {
        assertThat(schemaResourcesOnClasspath())
                .describedAs("Store DDL reachable at boot but absent from DDL_RESOURCES, so absent from " +
                        "the fixtures the migration tests compare on")
                .containsExactlyInAnyOrderElementsOf(DDL_RESOURCES);
    }

    @Test
    void everyDdlResourceIsOnTheClasspath() {
        // Guards the check itself: a missing resource would otherwise shrink the expected content and
        // the fixture would be "wrong" for the wrong reason.
        for (String resource : DDL_RESOURCES) {
            assertThat(getClass().getClassLoader().getResource(resource))
                    .describedAs("DDL resource %s is not on the test classpath — its store module is missing " +
                            "from build.gradle.kts", resource)
                    .isNotNull();
        }
    }

    /**
     * Every {@code *-schema.sql} sitting at the root of the test classpath, which is where the EDC store
     * modules put theirs and where {@code SqlSchemaBootstrapper} looks for them at boot.
     */
    private List<String> schemaResourcesOnClasspath() {
        var resources = new TreeSet<String>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            var path = Path.of(entry);
            if (entry.endsWith(".jar") && Files.exists(path)) {
                try (var jar = new ZipFile(entry)) {
                    jar.stream()
                            .map(ZipEntry::getName)
                            .filter(name -> name.endsWith(SCHEMA_SUFFIX) && !name.contains("/"))
                            .forEach(resources::add);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            } else if (Files.isDirectory(path)) {
                try (Stream<Path> files = Files.list(path)) {
                    files.map(file -> file.getFileName().toString())
                            .filter(name -> name.endsWith(SCHEMA_SUFFIX))
                            .forEach(resources::add);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        return List.copyOf(resources);
    }

    private String concatenatedDdl() {
        var content = new StringBuilder();
        for (String resource : DDL_RESOURCES) {
            content.append("-- ---- ").append(resource).append('\n')
                    .append(read(resource))
                    .append('\n');
        }
        return content.toString();
    }

    private String read(String resource) {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("DDL resource not found on the classpath: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * {@code 0.14.0} to {@code 0.14}: fixtures are named per minor version, since a patch release does
     * not change the schema.
     */
    private String shortEdcVersion() {
        var version = System.getProperty("edcVersion");
        if (version == null) {
            throw new IllegalStateException("System property 'edcVersion' is not set — see the test task configuration");
        }
        var parts = version.split("\\.");
        return parts.length < 2 ? version : parts[0] + "." + parts[1];
    }
}
