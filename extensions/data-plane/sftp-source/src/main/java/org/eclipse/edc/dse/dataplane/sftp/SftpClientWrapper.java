package org.eclipse.edc.dse.dataplane.sftp;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.eclipse.edc.spi.EdcException;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.security.Vault;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.temporal.ChronoUnit;

import static java.lang.String.format;
import static org.eclipse.edc.dse.dataplane.sftp.SftpDataAddress.AUTH_TYPE_KEY;

/**
 * Opens exactly one SFTP connection to read the single remote file configured on a {@link SftpDataAddress}.
 * Connection, host-key verification and authentication all happen lazily, on the first call to
 * {@link #openInputStream()} — never in the constructor, so building this wrapper cannot fail or block on I/O.
 * <p>
 * Credentials and the expected host key are Vault aliases on the {@link SftpDataAddress}; the actual secret
 * values are resolved here, at connection time, mirroring {@code HeaderSecretParamsDecorator}
 * (data-plane-http-header-secrets). Host key verification is fail-closed: there is no fallback to a local
 * {@code known_hosts} file, since each participant's SFTP server has its own key and nothing here can be a
 * connector-wide default.
 */
public class SftpClientWrapper implements AutoCloseable {

    private static final Duration SSH_CLIENT_CONNECT_TIMEOUT = Duration.of(10, ChronoUnit.SECONDS);
    private static final Duration SSH_CLIENT_AUTH_TIMEOUT = Duration.of(10, ChronoUnit.SECONDS);

    private final SftpDataAddress dataAddress;
    private final Vault vault;
    private final Monitor monitor;

    private SshClient sshClient;
    private ClientSession session;
    private SftpClient sftpClient;

    public SftpClientWrapper(SftpDataAddress dataAddress, Vault vault, Monitor monitor) {
        this.dataAddress = dataAddress;
        this.vault = vault;
        this.monitor = monitor;
    }

    /**
     * Opens the remote file configured for this wrapper and returns a stream of its content. Connects, verifies
     * the host key and authenticates on first call.
     */
    public InputStream openInputStream() {
        connect();
        try {
            return sftpClient.read(dataAddress.getPath());
        } catch (IOException e) {
            close();
            throw new EdcException(format("SFTP: failed to read '%s' on %s:%d: %s",
                    dataAddress.getPath(), dataAddress.getHost(), dataAddress.getPort(), e.getMessage()), e);
        }
    }

    /**
     * Closes the underlying SFTP client and SSH session, if open. Never throws.
     */
    @Override
    public void close() {
        closeQuietly(sftpClient);
        closeQuietly(session);
        if (sshClient != null) {
            sshClient.stop();
        }
        sftpClient = null;
        session = null;
        sshClient = null;
    }

    private void connect() {
        if (sftpClient != null) {
            return;
        }

        String expectedHostKey = resolveRequiredSecret(dataAddress.getHostKeyFingerprintSecretAlias(), "host key");

        sshClient = SshClient.setUpDefaultClient();
        sshClient.setServerKeyVerifier(fingerprintVerifier(expectedHostKey));
        sshClient.start();

        try {
            openSftpClient();
        } catch (EdcException e) {
            close();
            throw e;
        } catch (Exception e) {
            close();
            throw new EdcException(format("SFTP: connection to %s:%d failed: %s",
                    dataAddress.getHost(), dataAddress.getPort(), e.getMessage()), e);
        }
    }

    private void openSftpClient() throws IOException {
        session = sshClient.connect(dataAddress.getUsername(), dataAddress.getHost(), dataAddress.getPort())
                .verify(SSH_CLIENT_CONNECT_TIMEOUT)
                .getSession();

        authenticate(session);

        session.auth().verify(SSH_CLIENT_AUTH_TIMEOUT);
        sftpClient = SftpClientFactory.instance().createSftpClient(session);
    }

    private void authenticate(ClientSession session) {
        if (AUTH_TYPE_KEY.equals(dataAddress.getAuthType())) {
            String privateKey = resolveRequiredSecret(dataAddress.getPrivateKeySecretAlias(), "private key");
            String passphraseAlias = dataAddress.getPrivateKeyPassphraseSecretAlias();
            String passphrase = passphraseAlias == null ? null : resolveRequiredSecret(passphraseAlias, "passphrase");
            SshKeyLoader.loadKeyPairs(privateKey, passphrase).forEach(session::addPublicKeyIdentity);
        } else {
            String password = resolveRequiredSecret(dataAddress.getClientPasswordSecretAlias(), "password");
            session.addPasswordIdentity(password);
        }
    }

    private String resolveRequiredSecret(String alias, String label) {
        if (alias == null) {
            throw new EdcException(format("SFTP: no %s alias configured on the DataAddress", label));
        }
        String value = vault.resolveSecret(alias);
        if (value == null) {
            throw new EdcException(format("SFTP: %s alias '%s' not found in vault", label, alias));
        }
        return value;
    }

    private ServerKeyVerifier fingerprintVerifier(String expectedFingerprint) {
        return (clientSession, remoteAddress, serverKey) -> {
            String actual = KeyUtils.getFingerPrint(BuiltinDigests.sha256, serverKey);
            // fingerprints are base64 (case-sensitive) — a case-insensitive compare would weaken this
            // fail-closed check by accepting keys that differ only in case
            boolean matches = expectedFingerprint.equals(actual);
            if (!matches) {
                monitor.severe(format("SFTP: host key mismatch for %s:%d — expected '%s', got '%s'",
                        dataAddress.getHost(), dataAddress.getPort(), expectedFingerprint, actual));
            }
            return matches;
        };
    }

    private void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException e) {
            monitor.warning(format("SFTP: error while closing %s: %s", closeable.getClass().getSimpleName(), e.getMessage()));
        }
    }
}
