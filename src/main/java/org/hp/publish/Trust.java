package org.hp.publish;

import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Properties;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;
import org.hp.update.Io;

/**
 * Who we are willing to send a password to.
 *
 * <p>FTPS: a certificate is accepted when Java trusts it for that host name (a normal,
 * valid certificate), or when you have confirmed that exact certificate before. Shared
 * hosting often presents the hosting company's certificate under its own name, and a VPS
 * often has a self-signed one; both are fine once you have checked the fingerprint, and
 * from then on only that certificate is accepted without asking again.</p>
 *
 * <p>SFTP: the server's host key is remembered on first connection, like ssh and
 * FileZilla do, in {@code ~/.hp-publisher/known_hosts}. A different key later is refused
 * unless you confirm it.</p>
 */
public final class Trust {

    private Trust() {
    }

    static Path dir() {
        return Paths.get(System.getProperty("user.home"), ".hp-publisher");
    }

    static Path knownHostsFile() {
        return dir().resolve("known_hosts");
    }

    private static Path pinsFile() {
        return dir().resolve("trusted-certificates.properties");
    }

    // ------------------------------------------------------------------ fingerprints

    /** "SHA256:47DEQpj8HBSa..." exactly as {@code ssh-keygen -l} and FileZilla show it. */
    public static String sshFingerprint(byte[] hostKeyBlob) {
        return "SHA256:" + base64NoPad(sha256(hostKeyBlob));
    }

    /** "AB:12:..." SHA-256 of the certificate, as browsers and FileZilla show it. */
    public static String certFingerprint(X509Certificate cert) {
        try {
            byte[] d = sha256(cert.getEncoded());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < d.length; i++) {
                if (i > 0) {
                    sb.append(':');
                }
                sb.append(String.format("%02X", d[i] & 0xff));
            }
            return sb.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String base64NoPad(byte[] b) {
        return java.util.Base64.getEncoder().withoutPadding().encodeToString(b);
    }

    /** What the user sees before deciding to trust a certificate. */
    public static String describe(X509Certificate cert) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd");
        return "Issued to : " + cert.getSubjectX500Principal().getName() + "\n"
                + "Issued by : " + cert.getIssuerX500Principal().getName() + "\n"
                + "Valid     : " + f.format(cert.getNotBefore()) + " to "
                + f.format(cert.getNotAfter()) + "\n"
                + "SHA-256   : " + certFingerprint(cert);
    }

    // ---------------------------------------------------------------- certificates

    /** Java's reason for distrust, in words. */
    static String explain(CertificateException why, X509Certificate cert) {
        String m = String.valueOf(why.getMessage());
        String low = m.toLowerCase();
        if (low.contains("no subject alternative") || low.contains("no name matching")
                || low.contains("doesn't match") || low.contains("does not match")) {
            return "it was issued for another name (" + cert.getSubjectX500Principal().getName() + ")";
        }
        if (low.contains("validity") || low.contains("expired") || low.contains("not yet valid")) {
            return "it has expired or is not valid yet";
        }
        if (low.contains("pkix") || low.contains("certification path")) {
            boolean selfSigned = cert.getSubjectX500Principal().equals(cert.getIssuerX500Principal());
            return selfSigned ? "it is self-signed"
                    : "it is not issued by an authority Java knows";
        }
        return m;
    }

    /** Thrown during the TLS handshake when the server's certificate needs a decision. */
    public static final class UntrustedCertificate extends CertificateException {
        private static final long serialVersionUID = 1L;
        public final transient X509Certificate certificate;
        public final String host;
        public final int port;
        /** Why Java does not trust it on its own, e.g. a name mismatch or self-signed. */
        public final String reason;
        /** True when a different certificate was confirmed for this server before. */
        public final boolean changed;

        UntrustedCertificate(X509Certificate certificate, String host, int port, String reason,
                boolean changed) {
            super("The server's certificate is not trusted yet: " + reason);
            this.certificate = certificate;
            this.host = host;
            this.port = port;
            this.reason = reason;
            this.changed = changed;
        }
    }

    /** Finds our exception anywhere in a handshake failure's causes. */
    public static UntrustedCertificate untrusted(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof UntrustedCertificate) {
                return (UntrustedCertificate) c;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return null;
    }

    private static String pinKey(String host, int port) {
        return host.toLowerCase() + ":" + port;
    }

    static synchronized String pinnedFingerprint(String host, int port) {
        Properties p = new Properties();
        String text = Io.readString(pinsFile());
        if (text != null) {
            try {
                p.load(new java.io.StringReader(text));
            } catch (IOException ignored) {
                // an unreadable pin file trusts nothing extra
            }
        }
        return p.getProperty(pinKey(host, port));
    }

    /** Remembers this certificate for this server (replacing any earlier one). */
    public static synchronized void pin(String host, int port, X509Certificate cert)
            throws IOException {
        Properties p = new Properties();
        String text = Io.readString(pinsFile());
        if (text != null) {
            p.load(new java.io.StringReader(text));
        }
        p.setProperty(pinKey(host, port), certFingerprint(cert));
        java.io.StringWriter w = new java.io.StringWriter();
        p.store(w, "Certificates you confirmed in the hp-updater publisher (host:port = SHA-256)");
        Files.createDirectories(dir());
        Io.writeStringAtomic(pinsFile(), w.toString());
    }

    /**
     * A TLS context for one server: Java's normal checks including the host name, or the
     * certificate you confirmed for this host and port.
     */
    static SSLContext tlsContext(String host, int port) throws IOException {
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);
            X509ExtendedTrustManager system = null;
            for (TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509ExtendedTrustManager) {
                    system = (X509ExtendedTrustManager) tm;
                }
            }
            if (system == null) {
                throw new IOException("This Java has no usable certificate checks");
            }
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] {new PinningTrustManager(system, host, port)}, null);
            return ctx;
        } catch (GeneralSecurityException e) {
            throw new IOException("TLS is not available: " + e.getMessage(), e);
        }
    }

    private static final class PinningTrustManager extends X509ExtendedTrustManager {
        private final X509ExtendedTrustManager system;
        private final String host;
        private final int port;

        PinningTrustManager(X509ExtendedTrustManager system, String host, int port) {
            this.system = system;
            this.host = host;
            this.port = port;
        }

        private void decide(X509Certificate[] chain, CertificateException why)
                throws CertificateException {
            if (chain == null || chain.length == 0) {
                throw why;
            }
            String pinned = pinnedFingerprint(host, port);
            String actual = certFingerprint(chain[0]);
            if (pinned != null && pinned.equalsIgnoreCase(actual)) {
                return; // the certificate you confirmed for this server
            }
            throw new UntrustedCertificate(chain[0], host, port, explain(why, chain[0]),
                    pinned != null);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            try {
                system.checkServerTrusted(chain, authType, socket);
            } catch (CertificateException e) {
                decide(chain, e);
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            try {
                system.checkServerTrusted(chain, authType, engine);
            } catch (CertificateException e) {
                decide(chain, e);
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            // No connection to read the host name from: only a confirmed certificate passes.
            decide(chain, new CertificateException("host name could not be checked"));
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            throw new CertificateException("not a server");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            throw new CertificateException("not a server");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            throw new CertificateException("not a server");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return ((X509TrustManager) system).getAcceptedIssuers();
        }
    }
}
