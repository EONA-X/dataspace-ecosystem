package org.eclipse.edc.dse.dataplane.sftp;

import org.eclipse.edc.connector.dataplane.spi.pipeline.DataSource;

import java.io.InputStream;

/**
 * The single remote file exposed by an {@link SftpDataSource}. Depends only on the {@link SftpClientWrapper}
 * interface — no cast to a specific implementation.
 */
class SftpPart implements DataSource.Part {

    private final String name;
    private final String mediaType;
    private final SftpClientWrapper sftpClientWrapper;

    SftpPart(String name, String mediaType, SftpClientWrapper sftpClientWrapper) {
        this.name = name;
        this.mediaType = mediaType;
        this.sftpClientWrapper = sftpClientWrapper;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public InputStream openStream() {
        return sftpClientWrapper.openInputStream();
    }

    @Override
    public String mediaType() {
        return mediaType;
    }
}
