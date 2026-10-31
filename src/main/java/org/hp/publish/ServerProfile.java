package org.hp.publish;

import java.io.IOException;
import java.util.Properties;

/**
 * Where an application's releases go: protocol, server, login and folder. Saved with the
 * publisher's settings - except passwords, which are never written to disk.
 */
public final class ServerProfile {

    public enum Protocol {
        SFTP("SFTP - SSH file transfer (VPS)", 22),
        FTPS("FTPS - FTP over TLS (shared hosting)", 21),
        FTP("FTP - not encrypted", 21);

        public final String label;
        public final int defaultPort;

        Protocol(String label, int defaultPort) {
            this.label = label;
            this.defaultPort = defaultPort;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public Protocol protocol = Protocol.SFTP;
    public String host = "";
    /** 0 means the protocol's usual port. */
    public int port;
    public String user = "";
    /** SFTP only: a private key file instead of (or as well as) a password. */
    public String keyFile = "";
    /** The folder that the download URL (appbase) serves, e.g. /var/www/html/apps/gasx. */
    public String remoteDir = "";
    /** Before switching customers to the release, check it is reachable at the URL. */
    public boolean checkUrl = true;
    /** Releases whose files "Clean up server" keeps (the current one included). */
    public int keepReleases = 3;

    public int port() {
        return port > 0 ? port : protocol.defaultPort;
    }

    public boolean isComplete() {
        return !host.trim().isEmpty() && !user.trim().isEmpty() && !remoteDir.trim().isEmpty();
    }

    /** "sftp://deploy@203.0.113.5:22/var/www/apps/gasx" */
    public String toUrl() {
        String dir = remoteDir.trim();
        return protocol.name().toLowerCase() + "://" + user.trim() + "@" + host.trim() + ":" + port()
                + (dir.startsWith("/") ? "" : "/~/") + dir;
    }

    /**
     * Reads "sftp://user@host[:port]/absolute/folder", or ".../~/folder" for a folder inside
     * the login folder.
     */
    public static ServerProfile parseUrl(String url) throws IOException {
        ServerProfile p = new ServerProfile();
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            throw new IOException("Expected sftp://, ftps:// or ftp:// in " + url);
        }
        String proto = url.substring(0, scheme).toUpperCase();
        try {
            p.protocol = Protocol.valueOf(proto);
        } catch (IllegalArgumentException e) {
            throw new IOException("Unknown protocol " + proto + " (use sftp, ftps or ftp)");
        }
        String rest = url.substring(scheme + 3);
        int slash = rest.indexOf('/');
        String authority = slash < 0 ? rest : rest.substring(0, slash);
        String path = slash < 0 ? "" : rest.substring(slash);
        int at = authority.lastIndexOf('@');
        if (at < 0) {
            throw new IOException("Put the user name in the address: " + p.protocol.name().toLowerCase()
                    + "://user@host/folder");
        }
        p.user = authority.substring(0, at);
        String hostPort = authority.substring(at + 1);
        int colon = hostPort.lastIndexOf(':');
        if (colon > 0 && hostPort.indexOf(']') < colon) {
            p.host = hostPort.substring(0, colon);
            try {
                p.port = Integer.parseInt(hostPort.substring(colon + 1));
            } catch (NumberFormatException e) {
                throw new IOException("Bad port in " + url);
            }
        } else {
            p.host = hostPort;
        }
        p.host = p.host.replace("[", "").replace("]", "");
        p.remoteDir = path.startsWith("/~/") ? path.substring(3) : path;
        return p;
    }

    void save(Properties props, String prefix) {
        props.setProperty(prefix + "protocol", protocol.name());
        props.setProperty(prefix + "host", host.trim());
        props.setProperty(prefix + "port", String.valueOf(port));
        props.setProperty(prefix + "user", user.trim());
        props.setProperty(prefix + "keyFile", keyFile.trim());
        props.setProperty(prefix + "remoteDir", remoteDir.trim());
        props.setProperty(prefix + "checkUrl", String.valueOf(checkUrl));
        props.setProperty(prefix + "keepReleases", String.valueOf(keepReleases));
    }

    static ServerProfile load(Properties props, String prefix) {
        ServerProfile p = new ServerProfile();
        try {
            p.protocol = Protocol.valueOf(props.getProperty(prefix + "protocol", "SFTP"));
        } catch (IllegalArgumentException e) {
            p.protocol = Protocol.SFTP;
        }
        p.host = props.getProperty(prefix + "host", "");
        p.port = parseInt(props.getProperty(prefix + "port"), 0);
        p.user = props.getProperty(prefix + "user", "");
        p.keyFile = props.getProperty(prefix + "keyFile", "");
        p.remoteDir = props.getProperty(prefix + "remoteDir", "");
        p.checkUrl = !"false".equals(props.getProperty(prefix + "checkUrl"));
        p.keepReleases = Math.max(1, parseInt(props.getProperty(prefix + "keepReleases"), 3));
        return p;
    }

    private static int parseInt(String s, int dflt) {
        try {
            return s == null ? dflt : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }
}
