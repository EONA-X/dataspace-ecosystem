package org.eclipse.edc.dse.dataplane.sftp;

import org.eclipse.edc.connector.dataplane.spi.pipeline.DataSource;
import org.eclipse.edc.connector.dataplane.spi.pipeline.DataSourceFactory;
import org.eclipse.edc.spi.EdcException;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.spi.security.Vault;
import org.eclipse.edc.spi.types.domain.transfer.DataFlowStartMessage;
import org.jetbrains.annotations.NotNull;

import java.net.URLConnection;

import static org.eclipse.edc.dse.dataplane.sftp.SftpDataAddress.KEY_AUTH_TYPE;
import static org.eclipse.edc.dse.dataplane.sftp.SftpDataAddress.SFTP_TYPE;

/**
 * Instantiates {@link SftpDataSource}s for requests whose source data type is {@link SftpDataAddress#SFTP_TYPE}.
 * Host, credentials and expected host key are read entirely from the request's {@link SftpDataAddress} — this
 * factory holds no connector-wide SFTP configuration, since every participant runs its own server.
 */
public class SftpDataSourceFactory implements DataSourceFactory {

    private final Monitor monitor;
    private final Vault vault;

    public SftpDataSourceFactory(Monitor monitor, Vault vault) {
        this.monitor = monitor;
        this.vault = vault;
    }

    @Override
    public String supportedType() {
        return SFTP_TYPE;
    }

    @Override
    public @NotNull Result<Void> validateRequest(DataFlowStartMessage request) {
        SftpDataAddress address = asSftpDataAddress(request);

        if (address.getHost() == null) {
            return Result.failure("SFTP DataAddress: missing 'host'");
        }
        if (address.getPath() == null) {
            return Result.failure("SFTP DataAddress: missing 'path'");
        }
        if (address.getPublicSshKeySecretAlias() == null) {
            return Result.failure("SFTP DataAddress: missing 'publicSshKeySecretAlias' — host key verification is mandatory, no known_hosts fallback");
        }
        if (KEY_AUTH_TYPE.equals(address.getAuthType())) {
            if (address.getPrivateKeySecretAlias() == null) {
                return Result.failure("SFTP DataAddress: authType 'key' requires 'privateKeySecretAlias'");
            }
        } else if (address.getClientPasswordSecretAlias() == null) {
            return Result.failure("SFTP DataAddress: missing 'clientPasswordSecretAlias'");
        }

        return Result.success();
    }

    @Override
    public DataSource createSource(DataFlowStartMessage request) {
        Result<Void> validation = validateRequest(request);
        if (validation.failed()) {
            throw new EdcException(validation.getFailureDetail());
        }

        SftpDataAddress address = asSftpDataAddress(request);
        String name = fileName(address.getPath());
        String contentType = resolveContentType(address, name);
        SftpClientWrapperImpl sftpClientWrapper = new SftpClientWrapperImpl(address, vault, monitor);

        return SftpDataSource.Builder.newInstance()
                .name(name)
                .contentType(contentType)
                .sftpClientWrapper(sftpClientWrapper)
                .build();
    }

    private SftpDataAddress asSftpDataAddress(DataFlowStartMessage request) {
        return SftpDataAddress.Builder.newInstance()
                .copyFrom(request.getSourceDataAddress())
                .build();
    }

    private String fileName(String path) {
        int lastSlash = path.lastIndexOf('/');
        return lastSlash < 0 ? path : path.substring(lastSlash + 1);
    }

    private String resolveContentType(SftpDataAddress address, String fileName) {
        String declared = address.getContentType();
        if (declared != null) {
            return declared;
        }
        String guessed = URLConnection.guessContentTypeFromName(fileName);
        return guessed != null ? guessed : "application/octet-stream";
    }
}
