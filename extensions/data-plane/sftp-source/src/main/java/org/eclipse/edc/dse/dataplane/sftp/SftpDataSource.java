package org.eclipse.edc.dse.dataplane.sftp;

import org.eclipse.edc.connector.dataplane.spi.pipeline.DataSource;
import org.eclipse.edc.connector.dataplane.spi.pipeline.StreamResult;

import java.util.Objects;
import java.util.stream.Stream;

/**
 * A {@link DataSource} exposing a single file read from a provider-owned SFTP server. Reading is entirely
 * lazy — no SSH connection is opened until a consumer actually requests the stream (via
 * {@link SftpPart#openStream()}), consistent with {@code PipelineServiceImpl} creating a new source per
 * request rather than once at transfer STARTED.
 */
public class SftpDataSource implements DataSource {

    private String name;
    private String contentType;
    private SftpClientWrapper sftpClientWrapper;

    private SftpDataSource() {
    }

    @Override
    public StreamResult<Stream<Part>> openPartStream() {
        return StreamResult.success(Stream.of(new SftpPart(name, contentType, sftpClientWrapper)));
    }

    @Override
    public void close() {
        sftpClientWrapper.close();
    }

    public static class Builder {

        private final SftpDataSource dataSource;

        public static Builder newInstance() {
            return new Builder();
        }

        private Builder() {
            dataSource = new SftpDataSource();
        }

        public Builder name(String name) {
            dataSource.name = name;
            return this;
        }

        public Builder contentType(String contentType) {
            dataSource.contentType = contentType;
            return this;
        }

        public Builder sftpClientWrapper(SftpClientWrapper sftpClientWrapper) {
            dataSource.sftpClientWrapper = sftpClientWrapper;
            return this;
        }

        public SftpDataSource build() {
            Objects.requireNonNull(dataSource.name, "name");
            Objects.requireNonNull(dataSource.contentType, "contentType");
            Objects.requireNonNull(dataSource.sftpClientWrapper, "sftpClientWrapper");
            return dataSource;
        }
    }
}
