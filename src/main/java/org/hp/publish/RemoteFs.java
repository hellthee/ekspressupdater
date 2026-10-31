package org.hp.publish;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The few things the uploader needs from a server, whatever the protocol. Paths are
 * absolute, with forward slashes.
 */
interface RemoteFs extends Closeable {

    /** Size in bytes, or -1 when there is no such file. */
    long size(String path) throws IOException;

    /** Creates the folder and any missing parents. */
    void mkdirs(String dir) throws IOException;

    /**
     * Writes a local file to {@code remotePath}. With {@code offset > 0} the remote file
     * already holds that many correct bytes and only the rest is sent (resume).
     */
    void upload(Path local, String remotePath, long offset, Progress progress,
            AtomicBoolean cancel) throws IOException;

    /** Renames. Whether an existing {@code to} is replaced depends on the server. */
    void rename(String from, String to) throws IOException;

    /** True when a file (or folder) exists at this path. */
    boolean exists(String path) throws IOException;

    /** Closes the connection at once, from any thread, interrupting a transfer. */
    void abort();

    void delete(String path) throws IOException;

    void rmdir(String path) throws IOException;

    /** Names in a folder (no "." or ".."); empty when the folder does not exist. */
    List<String> list(String dir) throws IOException;

    /** A small file's content, or null when it does not exist. */
    byte[] read(String path) throws IOException;

    /** The absolute form of a folder given relative to the login folder, or as is. */
    String absolute(String path) throws IOException;

    /** "SFTP to deploy@1.2.3.4:22 (OpenSSH_9.6)", for the log. */
    String describe();

    /** Bytes written so far for the current file. */
    interface Progress {
        void bytes(long written);
    }

    /** The upload was cancelled by the user. */
    final class Cancelled extends IOException {
        private static final long serialVersionUID = 1L;

        Cancelled() {
            super("Cancelled");
        }
    }
}
