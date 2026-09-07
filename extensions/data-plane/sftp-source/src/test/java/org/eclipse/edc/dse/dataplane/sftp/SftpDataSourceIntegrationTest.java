package org.eclipse.edc.dse.dataplane.sftp;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.eclipse.edc.connector.dataplane.spi.pipeline.DataSource;
import org.eclipse.edc.connector.dataplane.spi.pipeline.DataSourceFactory;
import org.eclipse.edc.spi.EdcException;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.security.Vault;
import org.eclipse.edc.spi.types.domain.DataAddress;
import org.eclipse.edc.spi.types.domain.transfer.DataFlowStartMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Reads a real file over SFTP from a containerized server (atmoz/sftp), end to end through
 * {@link SftpDataSourceFactory} — mirrors the manual byte-exact validation done in spike #647, but via the
 * production {@link DataSourceFactory}/{@link DataSource} path instead of a standalone script.
 * <p>
 * Also exercises the fail-closed host key check (finding 8): a wrong expected fingerprint must make the
 * connection fail rather than silently falling back to any other trust mechanism.
 */
@Testcontainers
class SftpDataSourceIntegrationTest {

    private static final String USERNAME = "provider";
    private static final String PASSWORD = "s3cr3t-password";
    private static final String REMOTE_FILE_NAME = "eonax.txt";
    private static final String FILE_CONTENT = "Hello SFTP integration test";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private static final Path SAMPLE_FILE = createSampleFile();

    @Container
    private static final GenericContainer<?> SFTP_SERVER = new GenericContainer<>("atmoz/sftp:alpine")
            .withExposedPorts(22)
            .withCommand(USERNAME + ":" + PASSWORD + ":1001")
            .withCopyFileToContainer(MountableFile.forHostPath(SAMPLE_FILE), "/home/" + USERNAME + "/upload/" + REMOTE_FILE_NAME)
            .waitingFor(Wait.forListeningPort());

    private static String realHostKeyFingerprint;

    private Vault vault;
    private SftpDataSourceFactory factory;

    @BeforeAll
    static void setUpContainer() throws IOException, InterruptedException {
        // atmoz/sftp copies files in as root before the entrypoint chowns the upload dir to the SFTP user —
        // pre-existing files aren't covered by that chown, so make the sample file readable explicitly.
        SFTP_SERVER.execInContainer("chmod", "644", "/home/" + USERNAME + "/upload/" + REMOTE_FILE_NAME);

        realHostKeyFingerprint = fetchHostKeyFingerprint();
    }

    @BeforeEach
    void setUp() {
        vault = mock(Vault.class);
        factory = new SftpDataSourceFactory(mock(Monitor.class), vault);
    }

    @AfterEach
    void tearDown() {
        // no-op: each DataSource created in a test closes itself via try-with-resources
    }

    @Test
    void shouldReadFileContent_whenHostKeyAndPasswordAreCorrect() throws Exception {
        when(vault.resolveSecret("hostkey-alias")).thenReturn(realHostKeyFingerprint);
        when(vault.resolveSecret("password-alias")).thenReturn(PASSWORD);

        var request = createRequest("hostkey-alias", "password-alias");

        try (DataSource source = factory.createSource(request)) {
            var streamResult = source.openPartStream();
            assertThat(streamResult.succeeded()).isTrue();

            var parts = streamResult.getContent().toList();
            assertThat(parts).hasSize(1);

            var part = parts.get(0);
            assertThat(part.name()).isEqualTo(REMOTE_FILE_NAME);

            try (var in = part.openStream()) {
                var content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                assertThat(content).isEqualTo(FILE_CONTENT);
            }
        }
    }

    @Test
    void shouldFailClosed_whenHostKeyDoesNotMatch() throws Exception {
        when(vault.resolveSecret("hostkey-alias")).thenReturn("SHA256:this-is-not-the-real-fingerprint");
        when(vault.resolveSecret("password-alias")).thenReturn(PASSWORD);

        var request = createRequest("hostkey-alias", "password-alias");

        try (DataSource source = factory.createSource(request)) {
            var parts = source.openPartStream().getContent().toList();
            var part = parts.get(0);

            assertThatExceptionOfType(EdcException.class).isThrownBy(part::openStream);
        }
    }

    @Test
    void shouldFail_whenPasswordIsWrong() throws Exception {
        when(vault.resolveSecret("hostkey-alias")).thenReturn(realHostKeyFingerprint);
        when(vault.resolveSecret("password-alias")).thenReturn("wrong-password");

        var request = createRequest("hostkey-alias", "password-alias");

        try (DataSource source = factory.createSource(request)) {
            var parts = source.openPartStream().getContent().toList();
            var part = parts.get(0);

            assertThatExceptionOfType(EdcException.class).isThrownBy(part::openStream);
        }
    }

    @Test
    void shouldFail_whenPasswordAliasDoesNotResolve() throws Exception {
        // unlike shouldFail_whenHostKeyAliasDoesNotResolve, this alias fails to resolve AFTER the SSH
        // session is already open (host key check passed) — the path that used to leak the session/client
        // before authenticate()'s EdcException was caught in SftpClientWrapper.connect()
        when(vault.resolveSecret("hostkey-alias")).thenReturn(realHostKeyFingerprint);
        when(vault.resolveSecret("missing-password-alias")).thenReturn(null);

        var request = createRequest("hostkey-alias", "missing-password-alias");

        try (DataSource source = factory.createSource(request)) {
            var parts = source.openPartStream().getContent().toList();
            var part = parts.get(0);

            assertThatExceptionOfType(EdcException.class)
                    .isThrownBy(part::openStream)
                    .withMessageContaining("missing-password-alias");
        }
    }

    @Test
    void shouldFail_whenHostKeyAliasDoesNotResolve() throws Exception {
        when(vault.resolveSecret("missing-alias")).thenReturn(null);
        when(vault.resolveSecret("password-alias")).thenReturn(PASSWORD);

        var request = createRequest("missing-alias", "password-alias");

        try (DataSource source = factory.createSource(request)) {
            var parts = source.openPartStream().getContent().toList();
            var part = parts.get(0);

            assertThatExceptionOfType(EdcException.class).isThrownBy(part::openStream);
        }
    }

    private DataFlowStartMessage createRequest(String hostKeyAlias, String passwordAlias) {
        return DataFlowStartMessage.Builder.newInstance()
                .id("id")
                .processId("processId")
                .destinationDataAddress(DataAddress.Builder.newInstance().type("notused").build())
                .sourceDataAddress(SftpDataAddress.Builder.newInstance()
                        .host(SFTP_SERVER.getHost())
                        .port(SFTP_SERVER.getMappedPort(22))
                        .path("/upload/" + REMOTE_FILE_NAME)
                        .username(USERNAME)
                        .authType(SftpDataAddress.AUTH_TYPE_PASSWORD)
                        .hostKeyFingerprintSecretAlias(hostKeyAlias)
                        .clientPasswordSecretAlias(passwordAlias)
                        .build())
                .build();
    }

    /**
     * Establishes one throwaway connection with an accept-all verifier just to capture the server's real host
     * key, then computes its fingerprint the same way {@code SftpClientWrapper} does. Mirrors what a
     * provider does in practice: look up the SFTP server's real host key once, out of band, before declaring
     * it on the asset.
     */
    private static String fetchHostKeyFingerprint() throws IOException {
        AtomicReference<PublicKey> captured = new AtomicReference<>();
        try (SshClient client = SshClient.setUpDefaultClient()) {
            client.setServerKeyVerifier((session, remoteAddress, serverKey) -> {
                captured.set(serverKey);
                return true;
            });
            client.start();
            try (ClientSession session = client.connect(USERNAME, SFTP_SERVER.getHost(), SFTP_SERVER.getMappedPort(22))
                    .verify(CONNECT_TIMEOUT)
                    .getSession()) {
                // the verifier only fires once key exchange completes, which isn't guaranteed by connect()
                // alone — force it by driving an (intentionally failing) auth attempt to completion
                session.addPasswordIdentity("bootstrap-only-not-a-real-credential");
                try {
                    session.auth().verify(CONNECT_TIMEOUT);
                } catch (Exception expectedAuthFailure) {
                    // ignored: we only needed key exchange (and the verifier callback) to run
                }
            } finally {
                client.stop();
            }
        }
        return KeyUtils.getFingerPrint(BuiltinDigests.sha256, captured.get());
    }

    private static Path createSampleFile() {
        try {
            Path file = Files.createTempFile("sftp-source-it", ".txt");
            Files.writeString(file, FILE_CONTENT, StandardCharsets.UTF_8);
            file.toFile().deleteOnExit();
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
