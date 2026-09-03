package org.eclipse.edc.dse.dataplane.sftp;

import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.eclipse.edc.spi.EdcException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;

import static java.lang.String.format;

/**
 * Parses a PEM-encoded private key into one or more {@link KeyPair}s, generically over key algorithm (RSA,
 * Ed25519, ECDSA...) via MINA SSHD's own resource parser rather than a hard cast to a specific key type.
 */
final class SshKeyLoader {

    private SshKeyLoader() {
    }

    static List<KeyPair> loadKeyPairs(String pemPrivateKey, String passphrase) {
        FilePasswordProvider passwordProvider = passphrase == null
                ? FilePasswordProvider.EMPTY
                : FilePasswordProvider.of(passphrase);

        try (InputStream in = new ByteArrayInputStream(pemPrivateKey.getBytes(StandardCharsets.UTF_8))) {
            List<KeyPair> keyPairs = new ArrayList<>();
            SecurityUtils.loadKeyPairIdentities(null, NamedResource.ofName("sftp-private-key"), in, passwordProvider)
                    .forEach(keyPairs::add);
            return keyPairs;
        } catch (IOException | GeneralSecurityException e) {
            throw new EdcException(format("SFTP: failed to parse private key: %s", e.getMessage()), e);
        }
    }
}
