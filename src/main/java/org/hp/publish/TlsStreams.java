package org.hp.publish;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;

/**
 * TLS over an FTP data connection, driven through an {@link SSLEngine}.
 *
 * <p>Why not an SSLSocket: secure FTP servers (vsftpd by default, many hosting servers)
 * insist that each data connection <em>resumes</em> the TLS session of the control
 * connection, so they know it is the same client. Java only resumes a session for the same
 * host and port it was made with, and an SSLSocket takes the port from the socket, which for
 * a data connection is a different one. An SSLEngine is created with the host and port we
 * give it, so it is created with the control connection's, and resumes that session.</p>
 */
final class TlsStreams {

    private final SSLEngine engine;
    private final Socket socket;
    private final InputStream rawIn;
    private final OutputStream rawOut;
    private ByteBuffer netIn;   // bytes received, not yet decrypted (read mode)
    private ByteBuffer netOut;  // scratch for encrypted bytes to send
    private ByteBuffer appIn;   // decrypted bytes not yet handed to the caller (read mode)
    private boolean closed;

    TlsStreams(SSLEngine engine, Socket socket) throws IOException {
        this.engine = engine;
        this.socket = socket;
        this.rawIn = socket.getInputStream();
        this.rawOut = socket.getOutputStream();
        int packet = engine.getSession().getPacketBufferSize();
        int app = engine.getSession().getApplicationBufferSize();
        netIn = ByteBuffer.allocate(packet);
        netIn.flip();
        netOut = ByteBuffer.allocate(packet);
        appIn = ByteBuffer.allocate(app);
        appIn.flip();
    }

    void handshake() throws IOException {
        engine.beginHandshake();
        process(ByteBuffer.allocate(0));
    }

    /**
     * Runs the engine until {@code app} is fully sent and no handshake step is pending.
     * Encrypted output goes straight to the socket.
     */
    private void process(ByteBuffer app) throws IOException {
        while (true) {
            HandshakeStatus hs = engine.getHandshakeStatus();
            if (hs == HandshakeStatus.NEED_TASK) {
                runTasks();
            } else if (hs == HandshakeStatus.NEED_UNWRAP
                    || "NEED_UNWRAP_AGAIN".equals(hs.name())) {
                if (!unwrapOnce()) {
                    throw new EOFException("The server closed the secure data connection");
                }
            } else if (hs == HandshakeStatus.NEED_WRAP || app.hasRemaining()) {
                if (wrapOnce(app) == Status.CLOSED) {
                    return;
                }
            } else {
                return;
            }
        }
    }

    private Status wrapOnce(ByteBuffer app) throws IOException {
        while (true) {
            netOut.clear();
            SSLEngineResult r = engine.wrap(app, netOut);
            if (r.getStatus() == Status.BUFFER_OVERFLOW) {
                netOut = ByteBuffer.allocate(netOut.capacity() * 2);
                continue;
            }
            netOut.flip();
            if (netOut.hasRemaining()) {
                rawOut.write(netOut.array(), netOut.arrayOffset() + netOut.position(),
                        netOut.remaining());
                rawOut.flush();
            }
            if (r.getHandshakeStatus() == HandshakeStatus.NEED_TASK) {
                runTasks();
            }
            return r.getStatus();
        }
    }

    /** Decrypts one record into appIn. False at end of stream. */
    private boolean unwrapOnce() throws IOException {
        while (true) {
            appIn.compact();
            SSLEngineResult r;
            try {
                r = engine.unwrap(netIn, appIn);
            } finally {
                appIn.flip();
            }
            switch (r.getStatus()) {
                case OK:
                    if (r.getHandshakeStatus() == HandshakeStatus.NEED_TASK) {
                        runTasks();
                    }
                    return true;
                case CLOSED:
                    return false;
                case BUFFER_OVERFLOW:
                    ByteBuffer bigger = ByteBuffer.allocate(appIn.capacity() * 2);
                    bigger.put(appIn);
                    bigger.flip();
                    appIn = bigger;
                    break;
                case BUFFER_UNDERFLOW:
                default:
                    if (netIn.limit() == netIn.capacity() && netIn.position() == 0) {
                        ByteBuffer grown = ByteBuffer.allocate(netIn.capacity() * 2);
                        grown.put(netIn);
                        grown.flip();
                        netIn = grown;
                    }
                    netIn.compact();
                    int n;
                    try {
                        n = rawIn.read(netIn.array(), netIn.arrayOffset() + netIn.position(),
                                netIn.remaining());
                    } finally {
                        netIn.flip(); // back to read mode, even when the read times out
                    }
                    if (n < 0) {
                        return false;
                    }
                    netIn.limit(netIn.limit() + n);
                    break;
            }
        }
    }

    private void runTasks() {
        Runnable t;
        while ((t = engine.getDelegatedTask()) != null) {
            t.run();
        }
    }

    final OutputStream output = new OutputStream() {
        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (closed) {
                throw new IOException("Data connection already closed");
            }
            process(ByteBuffer.wrap(b, off, len));
        }

        @Override
        public void close() throws IOException {
            TlsStreams.this.close();
        }
    };

    final InputStream input = new InputStream() {
        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            while (!appIn.hasRemaining()) {
                if (engine.isInboundDone() || !unwrapOnce()) {
                    return -1;
                }
                if (engine.getHandshakeStatus() != HandshakeStatus.NOT_HANDSHAKING
                        && engine.getHandshakeStatus() != HandshakeStatus.FINISHED) {
                    process(ByteBuffer.allocate(0));
                }
            }
            int n = Math.min(len, appIn.remaining());
            appIn.get(b, off, n);
            return n;
        }

        @Override
        public void close() throws IOException {
            TlsStreams.this.close();
        }
    };

    /**
     * Sends close_notify, then reads what the server still has to say before closing. That
     * last part matters: with TLS 1.3 the server hands out the ticket for the next data
     * connection on this one, and a ticket never read means the next one cannot resume.
     */
    void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            engine.closeOutbound();
            ByteBuffer none = ByteBuffer.allocate(0);
            for (int i = 0; i < 4 && !engine.isOutboundDone(); i++) {
                if (wrapOnce(none) == Status.CLOSED) {
                    break;
                }
            }
            socket.shutdownOutput(); // all data, then our close_notify, then end of stream
        } catch (IOException ignored) {
            // the server may already have closed its side; nothing was lost
        }
        try {
            while (!engine.isInboundDone()) {
                appIn.clear();
                appIn.flip();
                if (!unwrapOnce()) {
                    break;
                }
            }
        } catch (SSLException ignored) {
            // a server that just drops the connection instead of close_notify
        } catch (IOException ignored) {
            // same
        } finally {
            socket.close();
        }
    }
}
