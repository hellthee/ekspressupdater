package org.hp.update;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * Proves that a release really comes from you.
 *
 * <p>The publisher signs each {@code app.txt} with a private key that never leaves your
 * computer. PCs check that signature with your public key - the {@code publickey} line in
 * their own {@code app.txt} - before they act on anything in the release. Because app.txt
 * lists the checksum of every file, the signature covers the whole release: nobody between
 * the PC and the server (the connection is plain HTTP) can slip in other files, other
 * JVM options or another main class.</p>
 *
 * <p>The signature is a line in app.txt itself ({@code signature = ...}, just before the end
 * marker), so app.txt and its signature can never be out of step on the server. What is
 * signed is the whole text with that line taken out. ECDSA P-256 with SHA-256, which every
 * Java from 8 on has.</p>
 */
public final class Signing {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String ALGORITHM = "SHA256withECDSA";

    private Signing() {
    }

    /** The release does not carry a valid signature by the expected key. */
    public static final class BadSignature extends IOException {
        private static final long serialVersionUID = 1L;

        BadSignature(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------ text format

    /** The release text without its signature line: exactly what is signed. */
    static String signedPart(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (String line : text.split("\n", -1)) {
            if (isSignatureLine(line)) {
                continue;
            }
            sb.append(line).append('\n');
        }
        sb.setLength(sb.length() - 1); // split/append added one newline too many
        return sb.toString();
    }

    private static boolean isSignatureLine(String line) {
        String t = line.trim();
        int eq = t.indexOf('=');
        return eq > 0 && t.substring(0, eq).trim().equalsIgnoreCase("signature");
    }

    /** The value of the signature line, or null. */
    static String signatureOf(String text) {
        for (String line : text.split("\n")) {
            if (isSignatureLine(line)) {
                return line.substring(line.indexOf('=') + 1).trim();
            }
        }
        return null;
    }

    /** The release text with a signature line added just before the end marker. */
    public static String sign(String text, PrivateKey key) throws IOException {
        String unsigned = signedPart(text);
        try {
            Signature s = Signature.getInstance(ALGORITHM);
            s.initSign(key);
            s.update(unsigned.getBytes(UTF8));
            String sig = b64(s.sign());
            int end = unsigned.lastIndexOf(UpdateManifest.END_MARKER);
            if (end < 0) {
                throw new IOException("Only a complete release can be signed");
            }
            return unsigned.substring(0, end) + "signature = " + sig + "\n" + unsigned.substring(end);
        } catch (GeneralSecurityException e) {
            throw new IOException("Signing failed: " + e.getMessage(), e);
        }
    }

    /** True when the text carries a valid signature by this key. */
    public static boolean verify(String text, PublicKey key) {
        String sig = signatureOf(text);
        if (sig == null) {
            return false;
        }
        try {
            Signature s = Signature.getInstance(ALGORITHM);
            s.initVerify(key);
            s.update(signedPart(text).getBytes(UTF8));
            return s.verify(unb64(sig));
        } catch (GeneralSecurityException e) {
            return false;
        } catch (IllegalArgumentException e) {
            return false; // not valid base64
        }
    }

    // ------------------------------------------------------------------ keys

    public static PublicKey publicKey(String base64) throws IOException {
        try {
            return KeyFactory.getInstance("EC").generatePublic(
                    new X509EncodedKeySpec(unb64(base64.trim())));
        } catch (GeneralSecurityException e) {
            throw new IOException("Not a valid public key: " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new IOException("Not a valid public key (bad base64)", e);
        }
    }

    public static String encode(PublicKey key) {
        return b64(key.getEncoded());
    }

    /** A short, comparable name for a public key: the first 16 hex digits of its SHA-256. */
    public static String fingerprint(PublicKey key) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                if (i > 0 && i % 2 == 0) {
                    sb.append('-');
                }
                sb.append(String.format("%02X", d[i] & 0xff));
            }
            return sb.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Your signing key: {@code HP_SIGNING_KEY} if set, otherwise
     * {@code .hp-publisher/signing-key.txt} in your user folder. Created on first use.
     */
    public static Path keyFile() {
        String env = System.getenv("HP_SIGNING_KEY");
        if (env != null && !env.trim().isEmpty()) {
            return Paths.get(env.trim());
        }
        return Paths.get(System.getProperty("user.home"), ".hp-publisher", "signing-key.txt");
    }

    /** Loads the signing key, creating one the first time. */
    public static KeyPair publisherKey() throws IOException {
        Path f = keyFile();
        if (Files.isRegularFile(f)) {
            return load(f);
        }
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair kp = g.generateKeyPair();
            String text = "# hp-updater signing key. KEEP THIS FILE SECRET AND BACKED UP.\n"
                    + "# PCs only accept releases signed with it. Copy it to every computer you\n"
                    + "# publish from; without it, PCs will refuse your future releases.\n"
                    + "public  = " + encode(kp.getPublic()) + "\n"
                    + "private = " + b64(kp.getPrivate().getEncoded()) + "\n";
            Io.ensureParent(f);
            Io.writeStringAtomic(f, text);
            ownerOnly(f);
            return kp;
        } catch (GeneralSecurityException e) {
            throw new IOException("Cannot create a signing key: " + e.getMessage(), e);
        }
    }

    static KeyPair load(Path f) throws IOException {
        String text = Io.readString(f);
        String pub = null;
        String priv = null;
        if (text != null) {
            for (String line : text.split("\n")) {
                String t = line.trim();
                if (t.startsWith("public") && t.indexOf('=') > 0) {
                    pub = t.substring(t.indexOf('=') + 1).trim();
                } else if (t.startsWith("private") && t.indexOf('=') > 0) {
                    priv = t.substring(t.indexOf('=') + 1).trim();
                }
            }
        }
        if (pub == null || priv == null) {
            throw new IOException(f + " is not a signing key file");
        }
        try {
            PrivateKey pk = KeyFactory.getInstance("EC").generatePrivate(
                    new PKCS8EncodedKeySpec(unb64(priv)));
            return new KeyPair(publicKey(pub), pk);
        } catch (GeneralSecurityException e) {
            throw new IOException(f + " holds an unreadable key: " + e.getMessage(), e);
        }
    }

    private static void ownerOnly(Path f) {
        try {
            Files.setPosixFilePermissions(f,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (Exception ignored) {
            // Windows: the file is in the user's own profile folder, which is private already
        }
    }

    private static String b64(byte[] b) {
        return java.util.Base64.getEncoder().encodeToString(b);
    }

    private static byte[] unb64(String s) {
        return java.util.Base64.getDecoder().decode(s.trim());
    }
}
