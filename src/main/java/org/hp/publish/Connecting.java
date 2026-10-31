package org.hp.publish;

import java.io.IOException;
import java.net.Socket;

/**
 * Lets Cancel reach a connection that is still being made: the socket is registered here
 * before it connects, so closing it ends a connect, a login or a TLS/SSH handshake at
 * once instead of after a timeout.
 */
final class Connecting {

    private volatile Socket socket;
    private volatile boolean aborted;

    /** Registers the socket about to be used; refuses if Cancel has already been pressed. */
    void socket(Socket s) throws IOException {
        socket = s;
        if (aborted) {
            close(s);
            throw new RemoteFs.Cancelled();
        }
    }

    Socket socket() {
        return socket;
    }

    void abort() {
        aborted = true;
        Socket s = socket;
        if (s != null) {
            close(s);
        }
    }

    static void close(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // closing anyway
        }
    }
}
