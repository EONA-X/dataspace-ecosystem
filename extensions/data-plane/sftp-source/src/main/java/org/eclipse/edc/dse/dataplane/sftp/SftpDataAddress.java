package org.eclipse.edc.dse.dataplane.sftp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import org.eclipse.edc.spi.types.domain.DataAddress;

import java.util.Optional;

import static java.util.Collections.emptyMap;

/**
 * Wrapper class for a {@link DataAddress} describing a file on a provider-owned SFTP server. Every connection
 * parameter (host, credentials, expected host key) is carried per asset — this connector never holds a global
 * SFTP configuration, since each participant operates its own server.
 * <p>
 * Credential and host-key properties ({@link #getClientPasswordSecretAlias()}, {@link #getPrivateKeySecretAlias()},
 * {@link #getHostKeyFingerprintSecretAlias()}) are Vault aliases, never raw values — resolved at connection time by
 * {@code SftpClientWrapper}.
 */
@JsonTypeName()
@JsonDeserialize(builder = DataAddress.Builder.class)
public class SftpDataAddress extends DataAddress {

    public static final String SFTP_TYPE = "Sftp";

    public static final String AUTH_TYPE_PASSWORD = "password";
    public static final String AUTH_TYPE_KEY = "key";

    private static final String HOST = "host";
    private static final String PORT = "port";
    private static final String PATH = "path";
    private static final String USERNAME = "username";
    private static final String AUTH_TYPE = "authType";
    private static final String CLIENT_PASSWORD_SECRET_ALIAS = "clientPasswordSecretAlias";
    private static final String PRIVATE_KEY_SECRET_ALIAS = "privateKeySecretAlias";
    private static final String PRIVATE_KEY_PASSPHRASE_SECRET_ALIAS = "privateKeyPassphraseSecretAlias";
    private static final String HOST_KEY_FINGERPRINT_SECRET_ALIAS = "hostKeyFingerprintSecretAlias";
    private static final String CONTENT_TYPE = "contentType";

    private SftpDataAddress() {
        super();
        this.setType(SFTP_TYPE);
    }

    @JsonIgnore
    public String getHost() {
        return getStringProperty(HOST);
    }

    @JsonIgnore
    public int getPort() {
        return Integer.parseInt(getStringProperty(PORT, "22"));
    }

    @JsonIgnore
    public String getPath() {
        return getStringProperty(PATH);
    }

    @JsonIgnore
    public String getUsername() {
        return getStringProperty(USERNAME);
    }

    @JsonIgnore
    public String getAuthType() {
        return getStringProperty(AUTH_TYPE, AUTH_TYPE_PASSWORD);
    }

    @JsonIgnore
    public String getClientPasswordSecretAlias() {
        return getStringProperty(CLIENT_PASSWORD_SECRET_ALIAS);
    }

    @JsonIgnore
    public String getPrivateKeySecretAlias() {
        return getStringProperty(PRIVATE_KEY_SECRET_ALIAS);
    }

    @JsonIgnore
    public String getPrivateKeyPassphraseSecretAlias() {
        return getStringProperty(PRIVATE_KEY_PASSPHRASE_SECRET_ALIAS);
    }

    @JsonIgnore
    public String getHostKeyFingerprintSecretAlias() {
        return getStringProperty(HOST_KEY_FINGERPRINT_SECRET_ALIAS);
    }

    @JsonIgnore
    public String getContentType() {
        return getStringProperty(CONTENT_TYPE);
    }

    @JsonPOJOBuilder(withPrefix = "")
    public static final class Builder extends DataAddress.Builder<SftpDataAddress, Builder> {

        private Builder() {
            super(new SftpDataAddress());
        }

        @JsonCreator
        public static Builder newInstance() {
            return new Builder();
        }

        public Builder host(String host) {
            this.property(HOST, host);
            return this;
        }

        public Builder port(int port) {
            this.property(PORT, String.valueOf(port));
            return this;
        }

        public Builder path(String path) {
            this.property(PATH, path);
            return this;
        }

        public Builder username(String username) {
            this.property(USERNAME, username);
            return this;
        }

        public Builder authType(String authType) {
            this.property(AUTH_TYPE, authType);
            return this;
        }

        public Builder clientPasswordSecretAlias(String alias) {
            this.property(CLIENT_PASSWORD_SECRET_ALIAS, alias);
            return this;
        }

        public Builder privateKeySecretAlias(String alias) {
            this.property(PRIVATE_KEY_SECRET_ALIAS, alias);
            return this;
        }

        public Builder privateKeyPassphraseSecretAlias(String alias) {
            this.property(PRIVATE_KEY_PASSPHRASE_SECRET_ALIAS, alias);
            return this;
        }

        public Builder hostKeyFingerprintSecretAlias(String alias) {
            this.property(HOST_KEY_FINGERPRINT_SECRET_ALIAS, alias);
            return this;
        }

        public Builder contentType(String contentType) {
            this.property(CONTENT_TYPE, contentType);
            return this;
        }

        public Builder copyFrom(DataAddress other) {
            Optional.ofNullable(other).map(DataAddress::getProperties).orElse(emptyMap()).forEach(this::property);
            return this;
        }

        @Override
        public SftpDataAddress build() {
            this.type(SFTP_TYPE);
            return address;
        }
    }
}
