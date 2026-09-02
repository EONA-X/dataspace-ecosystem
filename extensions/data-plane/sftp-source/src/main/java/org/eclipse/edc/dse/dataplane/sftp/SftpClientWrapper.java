package org.eclipse.edc.dse.dataplane.sftp;

import java.io.InputStream;

/**
 * Thin abstraction over a single SFTP connection used to read one remote file. Connecting is lazy — no I/O
 * happens until {@link #openInputStream()} is called, so building an instance never blocks or fails on its own.
 */
public interface SftpClientWrapper extends AutoCloseable {

    /**
     * Opens the remote file configured for this wrapper and returns a stream of its content. Connects, verifies
     * the host key and authenticates on first call.
     */
    InputStream openInputStream();

    /**
     * Closes the underlying SFTP client and SSH session, if open. Never throws.
     */
    @Override
    void close();
}
