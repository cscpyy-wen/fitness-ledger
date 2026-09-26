import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.Key;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.HexFormat;

/**
 * Minimal source-file-mode helper for detached release-provenance signatures.
 * Signing passwords are accepted only through short-lived child-process
 * environment variables; they never appear in command arguments or evidence.
 */
public final class ReleaseProvenanceSignature {
    private static final String STORE_PASSWORD_ENV = "FITNESS_APKSIGNER_STORE_PASSWORD_TEMP";
    private static final String KEY_PASSWORD_ENV = "FITNESS_APKSIGNER_KEY_PASSWORD_TEMP";

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            fail("expected sign or verify operation");
        }
        switch (args[0]) {
            case "sign" -> sign(args);
            case "verify" -> verify(args);
            default -> fail("unsupported operation");
        }
    }

    private static void sign(String[] args) throws Exception {
        if (args.length != 6) {
            fail("sign requires: keystore alias manifest signature-out certificate-out");
        }
        char[] storePassword = requiredSecret(STORE_PASSWORD_ENV);
        char[] keyPassword = requiredSecret(KEY_PASSWORD_ENV);
        try {
            KeyStore keyStore = KeyStore.getInstance("JKS");
            try (InputStream input = Files.newInputStream(Path.of(args[1]))) {
                keyStore.load(input, storePassword);
            }
            Key key = keyStore.getKey(args[2], keyPassword);
            if (!(key instanceof PrivateKey privateKey)) {
                fail("keystore alias does not contain a private key");
                return;
            }
            X509Certificate certificate = (X509Certificate) keyStore.getCertificate(args[2]);
            if (certificate == null) {
                fail("keystore alias has no signing certificate");
            }
            byte[] manifest = Files.readAllBytes(Path.of(args[3]));
            Signature signer = Signature.getInstance(signatureAlgorithm(privateKey.getAlgorithm()));
            signer.initSign(privateKey);
            signer.update(manifest);
            byte[] signature = signer.sign();
            try {
                Files.write(
                    Path.of(args[4]), signature,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE
                );
                Files.write(
                    Path.of(args[5]), certificate.getEncoded(),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE
                );
            } finally {
                java.util.Arrays.fill(manifest, (byte) 0);
                java.util.Arrays.fill(signature, (byte) 0);
            }
            System.out.println("PASS: detached provenance manifest signed with " + signer.getAlgorithm());
            System.out.println("certificateSha256=" + sha256Hex(certificate.getEncoded()));
        } finally {
            java.util.Arrays.fill(storePassword, '\0');
            java.util.Arrays.fill(keyPassword, '\0');
        }
    }

    private static void verify(String[] args) throws Exception {
        if (args.length != 5) {
            fail("verify requires: manifest signature certificate expected-certificate-sha256");
        }
        X509Certificate certificate;
        try (InputStream input = Files.newInputStream(Path.of(args[3]))) {
            certificate = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
        String actualFingerprint = sha256Hex(certificate.getEncoded());
        String expectedFingerprint = args[4].replace(":", "").toUpperCase(java.util.Locale.ROOT);
        if (!actualFingerprint.equals(expectedFingerprint)) {
            fail("certificate fingerprint does not match the repository trust root");
        }
        byte[] manifest = Files.readAllBytes(Path.of(args[1]));
        byte[] detachedSignature = Files.readAllBytes(Path.of(args[2]));
        try {
            Signature verifier = Signature.getInstance(signatureAlgorithm(certificate.getPublicKey().getAlgorithm()));
            verifier.initVerify(certificate);
            verifier.update(manifest);
            if (!verifier.verify(detachedSignature)) {
                fail("detached provenance signature is invalid");
            }
            System.out.println("PASS: detached provenance signature verified with " + verifier.getAlgorithm());
            System.out.println("certificateSha256=" + actualFingerprint);
        } finally {
            java.util.Arrays.fill(manifest, (byte) 0);
            java.util.Arrays.fill(detachedSignature, (byte) 0);
        }
    }

    private static char[] requiredSecret(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            fail("missing required signing environment");
        }
        return value.toCharArray();
    }

    private static String signatureAlgorithm(String keyAlgorithm) {
        return switch (keyAlgorithm.toUpperCase(java.util.Locale.ROOT)) {
            case "RSA" -> "SHA256withRSA";
            case "EC", "ECDSA" -> "SHA256withECDSA";
            case "DSA" -> "SHA256withDSA";
            default -> throw new IllegalArgumentException("unsupported signing key algorithm");
        };
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void fail(String message) {
        throw new IllegalArgumentException(message);
    }
}
