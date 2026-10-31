package org.hp.publish;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

/**
 * FTP, and FTP over TLS ("FTPS", explicit: AUTH TLS on port 21), with nothing but the JDK.
 *
 * <p>Passive mode only, always connecting to the server's own address for data (what
 * FileZilla does), so a server behind NAT that announces a private address still works.
 * With TLS, both the login and every file are encrypted (PROT P), and the data
 * connections resume the control connection's TLS session, which most secure servers
 * require.</p>
 */
final class FtpFs implements RemoteFs {

    static {
        prepareTls();
    }

    /**
     * Java's TLS 1.2 session tickets get in the way of resuming the control connection's
     * session on data connections; plain session IDs work with every server. Must be set
     * before the first TLS connection in this JVM.
     */
    static void prepareTls() {
        if (System.getProperty("jdk.tls.client.enableSessionTicketExtension") == null) {
            System.setProperty("jdk.tls.client.enableSessionTicketExtension", "false");
        }
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 60000;

    private final String host;
    private final int port;
    private final boolean tls;
    private final Uploader.Log log;
    private Socket control;
    private InetAddress serverAddress;
    private BufferedReader in;
    private Writer out;
    private SSLContext tlsContext;
    private boolean epsv = true;
    private boolean sizeSupported = true;
    private String home = "/";
    private String greeting = "";
    private final Set<String> knownDirs = new HashSet<String>();
    private volatile Socket currentData;

    private FtpFs(String host, int port, boolean tls, Uploader.Log log) {
        this.host = host;
        this.port = port;
        this.tls = tls;
        this.log = log;
    }

    /** Connects and logs in. Throws {@link Trust.UntrustedCertificate} for a decision. */
    static FtpFs connect(String host, int port, boolean tls, String user, char[] password,
            Uploader.Log log, Connecting connecting) throws IOException {
        FtpFs fs = new FtpFs(host, port, tls, log);
        boolean ok = false;
        try {
            fs.open(user, password, connecting);
            ok = true;
            return fs;
        } finally {
            if (!ok) {
                fs.closeQuietly();
            }
        }
    }

    private void open(String user, char[] password, Connecting connecting) throws IOException {
        Socket plain = new Socket();
        if (connecting != null) {
            connecting.socket(plain); // Cancel closes it, even while connecting
        }
        plain.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT);
        plain.setSoTimeout(READ_TIMEOUT);
        control = plain;
        serverAddress = plain.getInetAddress();
        streams(plain);
        String first = peekLine();
        if (first.startsWith("SSH-")) {
            throw new IOException("Port " + port + " on " + host + " is an SSH server, not FTP."
                    + " Choose SFTP, or use the server's FTP port (usually 21).");
        }
        Reply hello = reply();
        if (hello.code != 220) {
            throw new IOException("The server did not greet as an FTP server: " + hello);
        }
        greeting = hello.lastLine();

        if (tls) {
            Reply auth = command("AUTH TLS");
            if (auth.code != 234) {
                // Never fall back to sending the password in the clear.
                throw new IOException("This server does not offer FTP over TLS (it answered: "
                        + auth + "). Ask the host whether it supports FTPS or SFTP;"
                        + " plain FTP would send your password unencrypted.");
            }
            tlsContext = Trust.tlsContext(host, port);
            SSLSocket s = (SSLSocket) tlsContext.getSocketFactory().createSocket(plain, host, port, true);
            SSLParameters params = s.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS"); // check the name, too
            s.setSSLParameters(params);
            s.setUseClientMode(true);
            try {
                s.startHandshake();
            } catch (SSLHandshakeException e) {
                Trust.UntrustedCertificate u = Trust.untrusted(e);
                if (u != null) {
                    throw new TrustNeeded(u);
                }
                throw new IOException("Secure connection failed: " + e.getMessage(), e);
            }
            control = s;
            streams(s);
        }

        Reply r = command("USER " + user);
        if (r.code == 331 || r.code == 332) {
            r = commandSecret("PASS ", password);
        }
        if (r.code != 230 && r.code != 202) {
            throw new LoginFailed("Login refused: " + r);
        }
        if (tls) {
            command("PBSZ 0");
            Reply prot = command("PROT P");
            if (prot.code != 200) {
                throw new IOException("The server will not encrypt file transfers (PROT P: "
                        + prot + ")");
            }
        }
        expect(command("TYPE I"), 200, "binary mode");
        command("OPTS UTF8 ON"); // harmless where unsupported
        Reply pwd = command("PWD");
        if (pwd.code == 257) {
            String t = pwd.lastLine();
            int a = t.indexOf('"');
            int b = t.lastIndexOf('"');
            if (a >= 0 && b > a) {
                home = t.substring(a + 1, b).replace("\"\"", "\"");
            }
        }
    }

    private void streams(Socket s) throws IOException {
        in = new BufferedReader(new InputStreamReader(s.getInputStream(), UTF8));
        out = new OutputStreamWriter(s.getOutputStream(), UTF8);
    }

    // ------------------------------------------------------------------ protocol

    static final class Reply {
        final int code;
        final List<String> lines;

        Reply(int code, List<String> lines) {
            this.code = code;
            this.lines = lines;
        }

        String lastLine() {
            return lines.isEmpty() ? "" : lines.get(lines.size() - 1);
        }

        @Override
        public String toString() {
            return lastLine();
        }
    }

    /** The certificate needs your decision before we may continue. */
    static final class TrustNeeded extends IOException {
        private static final long serialVersionUID = 1L;
        final transient Trust.UntrustedCertificate certificate;

        TrustNeeded(Trust.UntrustedCertificate c) {
            super(c.getMessage());
            this.certificate = c;
        }
    }

    /** Wrong user name or password: asking again will not help. */
    static final class LoginFailed extends IOException {
        private static final long serialVersionUID = 1L;

        LoginFailed(String m) {
            super(m);
        }
    }

    private String pending;

    /** Reads the first line without consuming it, to recognise a server that is not FTP. */
    private String peekLine() throws IOException {
        pending = in.readLine();
        if (pending == null) {
            throw new IOException("The server closed the connection without a greeting");
        }
        return pending;
    }

    private Reply reply() throws IOException {
        List<String> lines = new ArrayList<String>();
        String first = pending != null ? pending : in.readLine();
        pending = null;
        if (first == null) {
            throw new IOException("The FTP server closed the connection");
        }
        lines.add(first);
        if (first.length() < 3) {
            throw new IOException("Unexpected answer from the FTP server: " + first);
        }
        int code;
        try {
            code = Integer.parseInt(first.substring(0, 3));
        } catch (NumberFormatException e) {
            throw new IOException("Unexpected answer from the FTP server: " + first);
        }
        if (first.length() > 3 && first.charAt(3) == '-') {
            String end = first.substring(0, 3) + " ";
            String l;
            while ((l = in.readLine()) != null) {
                lines.add(l);
                if (l.startsWith(end)) {
                    break;
                }
            }
            if (l == null) {
                throw new IOException("The FTP server closed the connection");
            }
        }
        if (code == 421) {
            throw new IOException("The FTP server closed the session: " + first);
        }
        return new Reply(code, lines);
    }

    private Reply command(String cmd) throws IOException {
        send(cmd);
        return reply();
    }

    private void send(String cmd) throws IOException {
        if (cmd.indexOf('\r') >= 0 || cmd.indexOf('\n') >= 0) {
            throw new IOException("Refusing a file name with a line break in it");
        }
        out.write(cmd);
        out.write("\r\n");
        out.flush();
    }

    private Reply commandSecret(String prefix, char[] secret) throws IOException {
        for (char c : secret) {
            if (c == '\r' || c == '\n') {
                throw new IOException("The password contains a line break");
            }
        }
        out.write(prefix);
        out.write(secret);
        out.write("\r\n");
        out.flush();
        return reply();
    }

    private static void expect(Reply r, int code, String what) throws IOException {
        if (r.code != code) {
            throw new IOException("FTP " + what + " failed: " + r);
        }
    }

    /** Opens a passive data connection (not yet started). */
    private Socket openData() throws IOException {
        int dataPort = -1;
        if (epsv) {
            Reply r = command("EPSV");
            if (r.code == 229) {
                String t = r.lastLine();
                int a = t.indexOf("(|||");
                int b = t.indexOf("|)", a + 4);
                if (a >= 0 && b > a) {
                    dataPort = number(t.substring(a + 4, b), r);
                }
            } else {
                epsv = false; // an older server; PASV from now on
            }
        }
        if (dataPort < 0) {
            Reply r = command("PASV");
            if (r.code != 227) {
                throw new IOException("The server refused passive mode: " + r);
            }
            String t = r.lastLine();
            int a = t.indexOf('(');
            int b = t.indexOf(')', a);
            String[] n = (a >= 0 && b > a ? t.substring(a + 1, b) : t.replaceAll("[^0-9,]", ""))
                    .split(",");
            if (n.length < 6) {
                throw new IOException("Unreadable passive mode answer: " + r);
            }
            dataPort = number(n[4], r) * 256 + number(n[5], r);
            // The address in the answer is ignored on purpose: behind NAT it is often a
            // private one. The server we are talking to is the one to connect to.
        }
        if (dataPort <= 0 || dataPort > 65535) {
            throw new IOException("Unreadable passive mode answer (port " + dataPort + ")");
        }
        Socket s = new Socket();
        currentData = s;
        s.connect(new InetSocketAddress(serverAddress, dataPort), CONNECT_TIMEOUT);
        s.setSoTimeout(READ_TIMEOUT);
        return s;
    }

    private static int number(String text, Reply r) throws IOException {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw new IOException("Unreadable passive mode answer: " + r);
        }
    }

    /** Streams over a data connection the server has just accepted with 1xx. */
    private TlsStreams secure(Socket data) throws IOException {
        SSLEngine engine = tlsContext.createSSLEngine(host, port); // control's host:port
        engine.setUseClientMode(true);
        SSLParameters params = engine.getSSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS");
        engine.setSSLParameters(params);
        TlsStreams t = new TlsStreams(engine, data);
        t.handshake();
        return t;
    }

    private interface DataWork {
        void run(InputStream in, OutputStream out) throws IOException;
    }

    /**
     * Runs one transfer command. Returns the final reply code, or the refusal code when
     * the server would not start it ({@code >= 400}).
     */
    private Reply transfer(String cmd, DataWork work) throws IOException {
        Socket data = openData();
        TlsStreams secured = null;
        try {
            Reply start = command(cmd);
            if (start.code >= 400) {
                return start;
            }
            if (start.code / 100 != 1) {
                throw new IOException("Unexpected answer to " + cmd.split(" ")[0] + ": " + start);
            }
            if (tls) {
                secured = secure(data);
                work.run(secured.input, secured.output);
                secured.close();
            } else {
                work.run(data.getInputStream(), data.getOutputStream());
                data.shutdownOutput();
                data.close();
            }
        } finally {
            if (secured == null) {
                closeQuietly(data);
            } else {
                try {
                    secured.close();
                } catch (IOException ignored) {
                    // already reported by the transfer itself
                }
            }
        }
        return reply();
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // closing anyway
        }
    }

    // ------------------------------------------------------------------ RemoteFs

    @Override
    public long size(String path) throws IOException {
        if (!sizeSupported) {
            return -1;
        }
        Reply r = command("SIZE " + path);
        if (r.code == 213) {
            String t = r.lastLine().substring(3).trim();
            try {
                return Long.parseLong(t);
            } catch (NumberFormatException e) {
                throw new IOException("Unreadable SIZE answer: " + r);
            }
        }
        if (r.code == 500 || r.code == 502) {
            sizeSupported = false;
            log.line("This server cannot report file sizes (SIZE), so every file will be"
                    + " uploaded.");
        }
        return -1;
    }

    private boolean isDir(String dir) throws IOException {
        if (knownDirs.contains(dir)) {
            return true;
        }
        Reply r = command("CWD " + dir);
        if (r.code == 250) {
            knownDirs.add(dir);
            return true;
        }
        return false;
    }

    @Override
    public void mkdirs(String dir) throws IOException {
        if (isDir(dir)) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String seg : dir.split("/")) {
            if (seg.isEmpty()) {
                continue;
            }
            sb.append('/').append(seg);
            String p = sb.toString();
            if (!isDir(p)) {
                Reply r = command("MKD " + p);
                if (r.code != 257 && !isDir(p)) {
                    throw new IOException("Cannot create folder " + p + ": " + r);
                }
                knownDirs.add(p);
            }
        }
    }

    @Override
    public void upload(final Path local, String remotePath, final long offset,
            final Progress progress, final AtomicBoolean cancel) throws IOException {
        final long start = offset;
        Socket data = openData(); // REST goes after PASV/EPSV and right before STOR
        TlsStreams secured = null;
        Reply done;
        try {
            long resumeAt = start;
            if (resumeAt > 0) {
                Reply rest = command("REST " + resumeAt);
                if (rest.code != 350) {
                    resumeAt = 0; // no resume here; send the whole file
                }
            }
            Reply begin = command("STOR " + remotePath);
            if (begin.code / 100 != 1) {
                throw new IOException("The server refused to store " + remotePath + ": " + begin);
            }
            OutputStream os;
            if (tls) {
                secured = secure(data);
                os = secured.output;
            } else {
                os = data.getOutputStream();
            }
            copy(local, resumeAt, os, progress, cancel);
            if (secured != null) {
                secured.close();
            } else {
                data.shutdownOutput();
                data.close();
            }
            done = reply();
        } finally {
            if (secured == null) {
                closeQuietly(data);
            } else {
                try {
                    secured.close();
                } catch (IOException ignored) {
                    // the copy's own error is the one that matters
                }
            }
        }
        if (done.code != 226 && done.code != 250) {
            throw new IOException("Upload of " + remotePath + " failed: " + done);
        }
    }

    static void copy(Path local, long offset, OutputStream os, Progress progress,
            AtomicBoolean cancel) throws IOException {
        InputStream fin = Files.newInputStream(local);
        try {
            long skipped = 0;
            while (skipped < offset) {
                long n = fin.skip(offset - skipped);
                if (n <= 0) {
                    throw new IOException("Local file is shorter than the part already uploaded");
                }
                skipped += n;
            }
            byte[] buf = new byte[64 * 1024];
            long written = offset;
            int n;
            while ((n = fin.read(buf)) > 0) {
                if (cancel != null && cancel.get()) {
                    throw new Cancelled();
                }
                os.write(buf, 0, n);
                written += n;
                if (progress != null) {
                    progress.bytes(written);
                }
            }
            os.flush();
        } finally {
            fin.close();
        }
    }

    @Override
    public void rename(String from, String to) throws IOException {
        Reply r = command("RNFR " + from);
        if (r.code != 350) {
            throw new IOException("Cannot rename " + from + ": " + r);
        }
        r = command("RNTO " + to);
        if (r.code != 250) {
            throw new IOException("Cannot rename " + from + " to " + to + ": " + r);
        }
    }

    @Override
    public boolean exists(String path) throws IOException {
        if (sizeSupported && size(path) >= 0) {
            return true;
        }
        if (sizeSupported) {
            return isDir(path); // SIZE works, so the only other possibility is a folder
        }
        int slash = path.lastIndexOf('/');
        String parent = slash <= 0 ? "/" : path.substring(0, slash);
        return list(parent).contains(path.substring(slash + 1));
    }

    @Override
    public void abort() {
        Socket d = currentData;
        if (d != null) {
            closeQuietly(d);
        }
        if (control != null) {
            closeQuietly(control);
        }
    }

    @Override
    public void delete(String path) throws IOException {
        Reply r = command("DELE " + path);
        if (r.code != 250 && r.code != 200) {
            throw new IOException("Cannot delete " + path + ": " + r);
        }
    }

    @Override
    public void rmdir(String path) throws IOException {
        Reply r = command("RMD " + path);
        if (r.code != 250 && r.code != 200) {
            throw new IOException("Cannot remove folder " + path + ": " + r);
        }
        knownDirs.remove(path);
    }

    @Override
    public List<String> list(String dir) throws IOException {
        if (!isDir(dir)) {
            return new ArrayList<String>();
        }
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Reply r = transfer("NLST " + dir, new DataWork() {
            @Override
            public void run(InputStream in, OutputStream out) throws IOException {
                pump(in, buf);
            }
        });
        List<String> names = new ArrayList<String>();
        if (r.code == 450 || r.code == 550) {
            return names; // "no files": an empty folder on some servers
        }
        if (r.code != 226 && r.code != 250) {
            throw new IOException("Cannot list " + dir + ": " + r);
        }
        for (String line : new String(buf.toByteArray(), UTF8).split("\n")) {
            String name = line.replace("\r", "");
            int slash = name.lastIndexOf('/');
            if (slash >= 0) {
                name = name.substring(slash + 1); // some servers answer with full paths
            }
            if (!name.isEmpty() && !name.equals(".") && !name.equals("..")) {
                names.add(name);
            }
        }
        return names;
    }

    @Override
    public byte[] read(String path) throws IOException {
        if (size(path) < 0 && sizeSupported) {
            return null;
        }
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Reply r = transfer("RETR " + path, new DataWork() {
            @Override
            public void run(InputStream in, OutputStream out) throws IOException {
                pump(in, buf);
            }
        });
        if (r.code == 550 || r.code == 450) {
            return null;
        }
        if (r.code != 226 && r.code != 250) {
            throw new IOException("Cannot read " + path + ": " + r);
        }
        return buf.toByteArray();
    }

    private static void pump(InputStream in, ByteArrayOutputStream out) throws IOException {
        byte[] b = new byte[16 * 1024];
        int n;
        while ((n = in.read(b)) > 0) {
            out.write(b, 0, n);
            if (out.size() > 64L * 1024 * 1024) {
                throw new IOException("Unexpectedly large answer from the server");
            }
        }
    }

    @Override
    public String absolute(String path) {
        String p = path == null ? "" : path.trim().replace('\\', '/');
        if (!p.startsWith("/")) {
            p = (home.endsWith("/") ? home : home + "/") + p;
        }
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    @Override
    public String describe() {
        return (tls ? "FTPS (FTP over TLS)" : "FTP (not encrypted)") + " to " + host + ":" + port
                + (greeting.isEmpty() ? "" : "  [" + greeting + "]");
    }

    @Override
    public void close() {
        try {
            if (out != null) {
                send("QUIT");
                reply();
            }
        } catch (IOException ignored) {
            // leaving anyway
        }
        closeQuietly();
    }

    private void closeQuietly() {
        if (control != null) {
            closeQuietly(control);
        }
    }
}
