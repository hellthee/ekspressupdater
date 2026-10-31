package org.hp.publish;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.SftpProgressMonitor;
import com.jcraft.jsch.UIKeyboardInteractive;
import com.jcraft.jsch.UserInfo;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.hp.update.Io;

/**
 * SFTP (file transfer over SSH), the usual way onto a VPS. Uses JSch, which lives in
 * {@code jsch.jar} next to {@code publisher.jar}.
 */
final class SftpFs implements RemoteFs {

    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 60000;

    private final Session session;
    private final ChannelSftp sftp;
    private final String label;
    private final Connecting socket;
    private volatile boolean aborted;

    private SftpFs(Session session, ChannelSftp sftp, String label, Connecting socket) {
        this.session = session;
        this.sftp = sftp;
        this.label = label;
        this.socket = socket;
    }

    /**
     * Opens the SSH connection's socket ourselves, so that it can be closed directly. JSch's
     * own disconnect waits for a lock that a write stuck in the network holds forever.
     */
    private static final class Sockets implements com.jcraft.jsch.SocketFactory {
        private final Connecting holder;

        Sockets(Connecting holder) {
            this.holder = holder;
        }

        @Override
        public java.net.Socket createSocket(String host, int port) throws IOException {
            java.net.Socket s = new java.net.Socket();
            holder.socket(s);
            s.connect(new java.net.InetSocketAddress(host, port), CONNECT_TIMEOUT);
            return s;
        }

        @Override
        public InputStream getInputStream(java.net.Socket s) throws IOException {
            return s.getInputStream();
        }

        @Override
        public java.io.OutputStream getOutputStream(java.net.Socket s) throws IOException {
            return s.getOutputStream();
        }
    }

    /** No answer within this long: the connection is treated as dead (JSch waits forever). */
    private static final long OP_TIMEOUT = Long.getLong("hp.upload.stallSeconds", 60L) * 1000L;
    private static final java.util.Timer TIMER = new java.util.Timer("sftp-timeouts", true);

    private interface Op<T> {
        T run() throws SftpException, IOException;
    }

    /** Runs one SFTP request; one that gets no answer closes the connection. */
    private <T> T guard(String what, Op<T> op) throws IOException {
        java.util.TimerTask dog = new java.util.TimerTask() {
            @Override
            public void run() {
                abort();
            }
        };
        TIMER.schedule(dog, OP_TIMEOUT);
        try {
            return op.run();
        } catch (SftpException e) {
            if (aborted) {
                throw new IOException("no answer from the server (" + what + ")", e);
            }
            throw io(what, e);
        } catch (RuntimeException e) {
            if (aborted) {
                throw new IOException("no answer from the server (" + what + ")", e);
            }
            throw e;
        } finally {
            dog.cancel();
        }
    }

    /** The server's host key is not known yet, or has changed: needs your decision. */
    static final class HostKeyNeeded extends IOException {
        private static final long serialVersionUID = 1L;
        final String knownHostsName; // "host" or "[host]:port"
        final String type;
        final byte[] key;
        final String fingerprint;
        final boolean changed;

        HostKeyNeeded(String knownHostsName, String type, byte[] key, boolean changed) {
            super((changed ? "The server's host key has CHANGED: " : "Unknown server: ")
                    + knownHostsName + " " + type + " " + Trust.sshFingerprint(key));
            this.knownHostsName = knownHostsName;
            this.type = type;
            this.key = key;
            this.fingerprint = Trust.sshFingerprint(key);
            this.changed = changed;
        }
    }

    static String libraryVersion() {
        return "JSch " + JSch.VERSION;
    }

    private static JSch jsch() throws IOException {
        JSch j = new JSch();
        Path kh = Trust.knownHostsFile();
        Files.createDirectories(kh.getParent());
        if (!Files.exists(kh)) {
            Files.createFile(kh);
        }
        try {
            j.setKnownHosts(kh.toString());
        } catch (JSchException e) {
            throw new IOException("Cannot read " + kh + ": " + e.getMessage(), e);
        }
        return j;
    }

    /** Remembers a host key you confirmed, replacing an older one for that server. */
    static void trust(HostKeyNeeded k) throws IOException {
        JSch j = jsch();
        HostKeyRepository repo = j.getHostKeyRepository();
        try {
            if (k.changed) {
                repo.remove(k.knownHostsName, k.type);
            }
            repo.add(new HostKey(k.knownHostsName, k.key), null);
        } catch (JSchException e) {
            throw new IOException("Cannot store the host key: " + e.getMessage(), e);
        }
    }

    static SftpFs connect(String host, int port, String user, final char[] password,
            Path keyFile, char[] passphrase, Uploader.Log log, Connecting connecting)
            throws IOException {
        JSch j = jsch();
        if (keyFile != null) {
            checkKeyFormat(keyFile);
            try {
                j.addIdentity(keyFile.toString(),
                        passphrase == null || passphrase.length == 0 ? null : new String(passphrase).getBytes("UTF-8"));
            } catch (JSchException e) {
                throw new IOException("Cannot use the key file " + keyFile + ": " + e.getMessage()
                        + keyHint(keyFile), e);
            }
        }
        Session s;
        try {
            s = j.getSession(user, host, port);
        } catch (JSchException e) {
            throw new IOException(e.getMessage(), e);
        }
        s.setSocketFactory(new Sockets(connecting));
        s.setConfig("StrictHostKeyChecking", "yes");
        s.setConfig("PreferredAuthentications", keyFile != null
                ? "publickey,keyboard-interactive,password" : "keyboard-interactive,password");
        dropWeak(s, "kex", "diffie-hellman-group1-sha1", "diffie-hellman-group-exchange-sha1");
        dropWeak(s, "server_host_key", "ssh-dss");
        for (String dir : new String[] {"cipher.s2c", "cipher.c2s"}) {
            dropWeak(s, dir, "3des-cbc", "blowfish-cbc", "arcfour", "arcfour128", "arcfour256",
                    "aes128-cbc", "aes192-cbc", "aes256-cbc");
        }
        for (String dir : new String[] {"mac.s2c", "mac.c2s"}) {
            dropWeak(s, dir, "hmac-md5", "hmac-md5-96", "hmac-sha1-96");
        }
        if (password != null && password.length > 0) {
            s.setPassword(new String(password));
        }
        s.setUserInfo(new Answers(password));
        try {
            s.setTimeout(READ_TIMEOUT);
            s.setServerAliveInterval(15000);
            s.connect(CONNECT_TIMEOUT);
        } catch (JSchException e) {
            HostKey k = s.getHostKey();
            String m = String.valueOf(e.getMessage());
            if (k != null && (m.startsWith("reject HostKey") || m.startsWith("HostKey has been changed"))) {
                byte[] blob = java.util.Base64.getDecoder().decode(k.getKey());
                int state = j.getHostKeyRepository().check(k.getHost(), blob);
                throw new HostKeyNeeded(k.getHost(), k.getType(), blob,
                        state == HostKeyRepository.CHANGED || m.startsWith("HostKey has been changed"));
            }
            if (m.contains("Auth fail") || m.contains("Auth cancel")
                    || m.contains("Too many authentication failures")) {
                throw new FtpFs.LoginFailed("Login refused for " + user + "@" + host
                        + (keyFile != null ? keyHint(keyFile) : ""));
            }
            if (m.contains("invalid server's version string") || m.contains("Connection reset")
                    && port != 22) {
                throw new IOException("Port " + port + " on " + host + " does not answer like an"
                        + " SSH server. If it is FTP, choose FTPS (or FTP).", e);
            }
            if (m.contains("Algorithm negotiation fail")) {
                throw new IOException("The server and " + libraryVersion() + " have no secure"
                        + " algorithm in common. Replace jsch.jar next to publisher.jar with the"
                        + " current version (com.github.mwiede:jsch from Maven Central).", e);
            }
            throw new IOException("SFTP connection to " + host + ":" + port + " failed: " + m, e);
        }
        ChannelSftp c;
        try {
            c = (ChannelSftp) s.openChannel("sftp");
            c.connect(CONNECT_TIMEOUT);
        } catch (JSchException e) {
            s.disconnect();
            throw new IOException("The server accepted the login but has no SFTP: "
                    + e.getMessage(), e);
        }
        String label = "SFTP to " + user + "@" + host + ":" + port + "  [" + s.getServerVersion()
                + ", " + libraryVersion() + ", host key " + s.getHostKey().getType() + "]";
        return new SftpFs(s, c, label, connecting);
    }

    private static void dropWeak(Session s, String key, String... weak) {
        String list = JSch.getConfig(key);
        if (list == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        outer:
        for (String a : list.split(",")) {
            for (String w : weak) {
                if (a.trim().equals(w)) {
                    continue outer;
                }
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(a.trim());
        }
        if (sb.length() > 0) {
            s.setConfig(key, sb.toString());
        }
    }

    /** Old JSch reads only PEM keys; say so plainly instead of "invalid privatekey". */
    private static void checkKeyFormat(Path keyFile) throws IOException {
        if (!Files.isRegularFile(keyFile)) {
            throw new IOException("Key file not found: " + keyFile);
        }
    }

    private static String keyHint(Path keyFile) {
        String head = Io.readString(keyFile);
        boolean oldJsch = JSch.VERSION.startsWith("0.1.");
        if (head == null || !oldJsch) {
            return "";
        }
        if (head.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----")) {
            return "\n\nThis key is in the newer OpenSSH format, which " + libraryVersion()
                    + " cannot read. Either replace jsch.jar with the current version"
                    + " (com.github.mwiede:jsch), or convert the key:  ssh-keygen -p -m PEM -f "
                    + keyFile.getFileName();
        }
        if (head.contains("RSA PRIVATE KEY")) {
            return "\n\nWith " + libraryVersion() + ", RSA keys are refused by current OpenSSH"
                    + " servers (they no longer accept SHA-1 signatures). Use an ECDSA key"
                    + " (ssh-keygen -t ecdsa -m PEM), a password, or replace jsch.jar with the"
                    + " current version (com.github.mwiede:jsch).";
        }
        return "";
    }

    /** Answers the server's password questions, never anything else. */
    private static final class Answers implements UserInfo, UIKeyboardInteractive {
        private final char[] password;
        private int keyboardAnswers;

        Answers(char[] password) {
            this.password = password;
        }

        @Override
        public String getPassphrase() {
            return null;
        }

        @Override
        public String getPassword() {
            return password == null ? null : new String(password);
        }

        @Override
        public boolean promptPassword(String message) {
            // The password was given up front; if the server asks again it was wrong, and
            // repeating it only gets the address blocked for too many attempts.
            return false;
        }

        @Override
        public boolean promptPassphrase(String message) {
            return false;
        }

        @Override
        public boolean promptYesNo(String message) {
            return false; // host keys are decided by the caller, never silently here
        }

        @Override
        public void showMessage(String message) {
        }

        @Override
        public String[] promptKeyboardInteractive(String destination, String name,
                String instruction, String[] prompt, boolean[] echo) {
            if (password == null || password.length == 0 || keyboardAnswers++ > 0) {
                return null; // answer once; a second question means the password was wrong
            }
            String[] answers = new String[prompt.length];
            for (int i = 0; i < prompt.length; i++) {
                if (!prompt[i].toLowerCase().contains("password")) {
                    return null; // a one-time code or similar: not something we can answer
                }
                answers[i] = new String(password);
            }
            return answers;
        }
    }

    // ------------------------------------------------------------------ RemoteFs

    private static boolean missing(SftpException e) {
        return e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE;
    }

    private static IOException io(String what, SftpException e) {
        return new IOException(what + ": " + e.getMessage(), e);
    }

    @Override
    public long size(final String path) throws IOException {
        return guard("Cannot check " + path, new Op<Long>() {
            @Override
            public Long run() throws SftpException {
                try {
                    return Long.valueOf(sftp.stat(path).getSize());
                } catch (SftpException e) {
                    if (missing(e)) {
                        return Long.valueOf(-1);
                    }
                    throw e;
                }
            }
        }).longValue();
    }

    @Override
    public void mkdirs(final String dir) throws IOException {
        guard("Cannot create folder " + dir, new Op<Void>() {
            @Override
            public Void run() throws SftpException, IOException {
                StringBuilder sb = new StringBuilder();
                for (String seg : dir.split("/")) {
                    if (seg.isEmpty()) {
                        continue;
                    }
                    sb.append('/').append(seg);
                    String p = sb.toString();
                    try {
                        if (!sftp.stat(p).isDir()) {
                            throw new IOException(p + " exists but is not a folder");
                        }
                        continue;
                    } catch (SftpException e) {
                        if (!missing(e)) {
                            throw e;
                        }
                    }
                    sftp.mkdir(p);
                }
                return null;
            }
        });
    }

    @Override
    public void upload(Path local, String remotePath, long offset, final Progress progress,
            final AtomicBoolean cancel) throws IOException {
        InputStream in = Files.newInputStream(local);
        final AtomicLong written = new AtomicLong(offset);
        try {
            sftp.put(in, remotePath, new SftpProgressMonitor() {
                @Override
                public void init(int op, String src, String dest, long max) {
                }

                @Override
                public boolean count(long count) {
                    long w = written.addAndGet(count);
                    if (progress != null) {
                        progress.bytes(w);
                    }
                    return cancel == null || !cancel.get();
                }

                @Override
                public void end() {
                }
            }, offset > 0 ? ChannelSftp.RESUME : ChannelSftp.OVERWRITE);
        } catch (SftpException e) {
            if (cancel != null && cancel.get()) {
                throw new Cancelled();
            }
            throw io("Upload of " + remotePath + " failed", e);
        } finally {
            in.close();
        }
        if (cancel != null && cancel.get()) {
            throw new Cancelled();
        }
    }

    @Override
    public void rename(final String from, final String to) throws IOException {
        guard("Cannot rename " + from + " to " + to, new Op<Void>() {
            @Override
            public Void run() throws SftpException {
                sftp.rename(from, to);
                return null;
            }
        });
    }

    @Override
    public boolean exists(final String path) throws IOException {
        return guard("Cannot check " + path, new Op<Boolean>() {
            @Override
            public Boolean run() throws SftpException {
                try {
                    sftp.stat(path);
                    return Boolean.TRUE;
                } catch (SftpException e) {
                    if (missing(e)) {
                        return Boolean.FALSE;
                    }
                    throw e;
                }
            }
        }).booleanValue();
    }

    @Override
    public void abort() {
        aborted = true;
        java.net.Socket raw = socket.socket();
        if (raw != null) {
            Connecting.close(raw); // ends a write stuck in the network at once
        }
        Thread t = new Thread("sftp-disconnect") {
            @Override
            public void run() {
                session.disconnect();
            }
        };
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void delete(final String path) throws IOException {
        guard("Cannot delete " + path, new Op<Void>() {
            @Override
            public Void run() throws SftpException {
                sftp.rm(path);
                return null;
            }
        });
    }

    @Override
    public void rmdir(final String path) throws IOException {
        guard("Cannot remove folder " + path, new Op<Void>() {
            @Override
            public Void run() throws SftpException {
                sftp.rmdir(path);
                return null;
            }
        });
    }

    @Override
    public List<String> list(final String dir) throws IOException {
        return guard("Cannot list " + dir, new Op<List<String>>() {
            @Override
            public List<String> run() throws SftpException {
                List<String> names = new ArrayList<String>();
                try {
                    Vector<?> v = sftp.ls(dir);
                    for (Object o : v) {
                        String n = ((ChannelSftp.LsEntry) o).getFilename();
                        if (!n.equals(".") && !n.equals("..")) {
                            names.add(n);
                        }
                    }
                } catch (SftpException e) {
                    if (!missing(e)) {
                        throw e;
                    }
                }
                return names;
            }
        });
    }

    @Override
    public byte[] read(final String path) throws IOException {
        return guard("Cannot read " + path, new Op<byte[]>() {
            @Override
            public byte[] run() throws SftpException {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try {
                    sftp.get(path, out);
                    return out.toByteArray();
                } catch (SftpException e) {
                    if (missing(e)) {
                        return null;
                    }
                    throw e;
                }
            }
        });
    }

    @Override
    public String absolute(String path) throws IOException {
        String p = path == null ? "" : path.trim().replace('\\', '/');
        if (!p.startsWith("/")) {
            String home = guard("Cannot find the login folder", new Op<String>() {
                @Override
                public String run() throws SftpException {
                    return sftp.getHome();
                }
            });
            p = (home.endsWith("/") ? home : home + "/") + p;
        }
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    @Override
    public String describe() {
        return label;
    }

    @Override
    public void close() {
        sftp.disconnect();
        session.disconnect();
    }
}
