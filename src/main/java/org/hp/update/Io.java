package org.hp.update;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Small file / hashing helpers. No third party dependencies, Java 8 compatible.
 */
public final class Io {

    public static final Charset UTF8 = Charset.forName("UTF-8");

    private Io() {
    }

    // ---------------------------------------------------------------- hashing

    public static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static String sha256(Path file) throws IOException {
        MessageDigest md = sha256Digest();
        byte[] buf = new byte[1 << 16];
        InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16);
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        } finally {
            closeQuietly(in);
        }
        return hex(md.digest());
    }

    public static String hex(byte[] bytes) {
        char[] hexChars = "0123456789abcdef".toCharArray();
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            sb.append(hexChars[v >>> 4]).append(hexChars[v & 0x0F]);
        }
        return sb.toString();
    }

    public static boolean hashEquals(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    /** True when the file exists, has the expected size and the expected digest. */
    public static boolean matches(Path file, long expectedSize, String expectedSha256) {
        try {
            if (!Files.isRegularFile(file)) {
                return false;
            }
            if (expectedSize >= 0 && Files.size(file) != expectedSize) {
                return false;
            }
            return hashEquals(sha256(file), expectedSha256);
        } catch (IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ files

    public static void ensureParent(Path p) throws IOException {
        Path parent = p.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    public static String readString(Path p) {
        try {
            if (!Files.isRegularFile(p)) {
                return null;
            }
            byte[] b = Files.readAllBytes(p);
            String t = new String(b, UTF8);
            if (t.startsWith("\uFEFF")) {
                t = t.substring(1); // a byte-order mark, as Notepad writes
            }
            return t.trim();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Writes to a sibling temp file and then moves it into place, so a crash or a
     * power cut can never leave a half written pointer file behind.
     */
    public static void writeStringAtomic(Path p, String content) throws IOException {
        writeBytesAtomic(p, content.getBytes(UTF8));
    }

    /**
     * Writes to a sibling temp file, forces it to disk, then moves it into place: after a
     * power cut the file is either the old one or the new one, never empty or half written.
     */
    public static void writeBytesAtomic(Path p, byte[] content) throws IOException {
        ensureParent(p);
        Path tmp = p.resolveSibling(p.getFileName().toString() + ".tmp");
        java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(tmp,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        try {
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(content);
            while (buf.hasRemaining()) {
                ch.write(buf);
            }
            ch.force(true);
        } finally {
            ch.close();
        }
        moveAtomic(tmp, p);
    }

    /**
     * Moves a file over another. Never deletes a folder, and never leaves the destination
     * missing: if the replace cannot be done in one step, the old file is moved aside
     * first and put back if the move fails.
     */
    public static void moveAtomic(Path src, Path dst) throws IOException {
        try {
            Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return;
        } catch (IOException atomicFailed) {
            // fall through to the ordinary replace
        }
        try {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
            return;
        } catch (IOException plainFailed) {
            if (!Files.isRegularFile(dst, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw plainFailed;
            }
            Path aside = dst.resolveSibling(dst.getFileName() + ".old");
            Files.move(dst, aside, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(src, dst);
            } catch (IOException e) {
                Files.move(aside, dst, StandardCopyOption.REPLACE_EXISTING);
                throw e;
            }
            Files.deleteIfExists(aside);
        }
    }

    /** Hard link when the file system allows it (free, instant), otherwise copy. */
    public static void copyOrLink(Path src, Path dst) throws IOException {
        ensureParent(dst);
        deleteQuietly(dst);
        try {
            Files.createLink(dst, src);
        } catch (Exception e) {
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Never throws. Deleting an old version directory can fail on Windows while a
     * jar in it is still mapped by a running JVM; we simply retry on a later boot.
     */
    public static boolean deleteQuietly(Path p) {
        try {
            if (p != null && Files.isDirectory(p)) {
                deleteRecursively(p);
            } else if (p != null) {
                Files.deleteIfExists(p);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static List<Path> listFilesRecursively(Path root) throws IOException {
        final List<Path> out = new ArrayList<Path>();
        if (root == null || !Files.isDirectory(root)) {
            return out;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                out.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        return out;
    }

    /** Relative path with forward slashes, the form used inside the manifest. */
    public static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    public static Properties loadProps(Path p) {
        Properties props = new Properties();
        if (!Files.isRegularFile(p)) {
            return props;
        }
        byte[] raw;
        try {
            raw = Files.readAllBytes(p);
        } catch (IOException e) {
            return props; // treat an unreadable state file as empty state
        }
        // storeProps writes a "#date" line first. Files without it come from older versions,
        // which wrote values as they were: read those literally, or every backslash in a
        // Windows path would be taken for an escape.
        String text = new String(raw, java.nio.charset.Charset.forName("ISO-8859-1"));
        if (text.startsWith("#")) {
            try {
                props.load(new java.io.ByteArrayInputStream(raw));
                return props;
            } catch (IOException ignored) {
                return props;
            } catch (IllegalArgumentException ignored) {
                props.clear(); // a damaged escape: fall through to the literal reading
            }
        }
        for (String line : new String(raw, UTF8).split("\r?\n")) {
            int eq = line.indexOf('=');
            if (eq > 0 && !line.startsWith("#")) {
                props.setProperty(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        return props;
    }

    /**
     * Writes properties the way {@link #loadProps} reads them back: backslashes (Windows
     * paths), spaces, '=' and non-ASCII characters are escaped.
     */
    public static void storeProps(Path p, Properties props) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        props.store(out, null);
        writeBytesAtomic(p, out.toByteArray());
    }

    public static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // nothing useful to do here
            }
        }
    }

    public static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Percent-encodes each segment of a relative path for use in a URL ("a b/c" -> "a%20b/c"). */
    public static String encodeUrlPath(String path) {
        StringBuilder sb = new StringBuilder();
        for (String seg : path.split("/", -1)) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            try {
                sb.append(java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20"));
            } catch (java.io.UnsupportedEncodingException e) {
                throw new IllegalStateException(e);
            }
        }
        return sb.toString();
    }
}
