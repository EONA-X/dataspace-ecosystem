package org.eclipse.edc.dse.dataplane.sftp;

import org.eclipse.edc.spi.EdcException;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.security.Vault;
import org.eclipse.edc.spi.types.domain.DataAddress;
import org.eclipse.edc.spi.types.domain.transfer.DataFlowStartMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.eclipse.edc.dse.dataplane.sftp.SftpDataAddress.SFTP_TYPE;
import static org.mockito.Mockito.mock;

class SftpDataSourceFactoryTest {

    private final Vault vault = mock(Vault.class);
    private SftpDataSourceFactory factory;

    @BeforeEach
    void setUp() {
        factory = new SftpDataSourceFactory(mock(Monitor.class), vault);
    }

    @Test
    void supportedType_isSftp() {
        assertThat(factory.supportedType()).isEqualTo(SFTP_TYPE);
    }

    @Test
    void validateRequest_succeeds_whenAllRequiredPropertiesPresent() {
        var request = createRequest(Map.of(
                "host", "sftp.example.com",
                "path", "/data/file.png",
                "publicSshKeySecretAlias", "hostkey-alias",
                "clientPasswordSecretAlias", "password-alias"));

        var result = factory.validateRequest(request);

        assertThat(result.succeeded()).isTrue();
    }

    @Test
    void validateRequest_fails_whenHostMissing() {
        var request = createRequest(Map.of(
                "path", "/data/file.png",
                "publicSshKeySecretAlias", "hostkey-alias",
                "clientPasswordSecretAlias", "password-alias"));

        var result = factory.validateRequest(request);

        assertThat(result.succeeded()).isFalse();
        assertThat(result.getFailureDetail()).contains("host");
    }

    @Test
    void validateRequest_fails_whenHostKeyAliasMissing() {
        var request = createRequest(Map.of(
                "host", "sftp.example.com",
                "path", "/data/file.png",
                "clientPasswordSecretAlias", "password-alias"));

        var result = factory.validateRequest(request);

        assertThat(result.succeeded()).isFalse();
        assertThat(result.getFailureDetail()).contains("publicSshKeySecretAlias");
    }

    @Test
    void validateRequest_fails_whenPasswordAliasMissingForPasswordAuth() {
        var request = createRequest(Map.of(
                "host", "sftp.example.com",
                "path", "/data/file.png",
                "publicSshKeySecretAlias", "hostkey-alias"));

        var result = factory.validateRequest(request);

        assertThat(result.succeeded()).isFalse();
        assertThat(result.getFailureDetail()).contains("clientPasswordSecretAlias");
    }

    @Test
    void validateRequest_fails_whenPrivateKeyAliasMissingForKeyAuth() {
        var request = createRequest(Map.of(
                "host", "sftp.example.com",
                "path", "/data/file.png",
                "authType", "key",
                "publicSshKeySecretAlias", "hostkey-alias"));

        var result = factory.validateRequest(request);

        assertThat(result.succeeded()).isFalse();
        assertThat(result.getFailureDetail()).contains("privateKeySecretAlias");
    }

    @Test
    void createSource_throws_whenValidationFails() {
        var request = createRequest(Map.of("path", "/data/file.png"));

        assertThatExceptionOfType(EdcException.class).isThrownBy(() -> factory.createSource(request));
    }

    @Test
    void createSource_derivesNameFromPath_andDefaultsContentType() {
        var request = createRequest(Map.of(
                "host", "sftp.example.com",
                "path", "/data/eonax.png",
                "publicSshKeySecretAlias", "hostkey-alias",
                "clientPasswordSecretAlias", "password-alias"));

        var source = factory.createSource(request);

        assertThat(source).isNotNull();
    }

    @Test
    void createSource_usesDeclaredContentType_whenProvided() {
        var properties = new HashMap<String, Object>(Map.of(
                "host", "sftp.example.com",
                "path", "/data/eonax.svg",
                "publicSshKeySecretAlias", "hostkey-alias",
                "clientPasswordSecretAlias", "password-alias"));
        properties.put("contentType", "image/svg+xml");
        var request = createRequest(properties);

        var source = factory.createSource(request);

        assertThat(source).isNotNull();
    }

    private DataFlowStartMessage createRequest(Map<String, Object> sourceProperties) {
        return DataFlowStartMessage.Builder.newInstance()
                .id("id")
                .processId("processId")
                .destinationDataAddress(DataAddress.Builder.newInstance().type("notused").build())
                .sourceDataAddress(DataAddress.Builder.newInstance()
                        .type(SFTP_TYPE)
                        .properties(sourceProperties)
                        .build())
                .build();
    }
}
