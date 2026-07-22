package gb.extension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.util.Arrays;
import java.util.Iterator;

import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureGenerator;
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder;
import org.bouncycastle.util.encoders.Hex;
import org.eclipse.jgit.api.errors.CanceledException;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.GpgSigner;
import org.eclipse.jgit.lib.GpgSignature;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.transport.CredentialsProvider;

public class PastedKeyGpgSigner extends GpgSigner {

    private final byte[] privateKeyData;
    private final char[] passphrase;

    public PastedKeyGpgSigner(String privateKeyArmored, String passphrase) {
        this.privateKeyData = privateKeyArmored.getBytes(StandardCharsets.UTF_8);
        this.passphrase = passphrase != null ? passphrase.toCharArray() : new char[0];
    }

    public void clearSensitiveData() {
        Arrays.fill(privateKeyData, (byte) 0);
        Arrays.fill(passphrase, '\0');
    }

    @Override
    public void sign(CommitBuilder commit, String signingKey, PersonIdent committer,
                     CredentialsProvider credentialsProvider) throws CanceledException {
        try {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(new BouncyCastleProvider());
            }

            PGPSecretKey secretKey = findSecretKey(privateKeyData, signingKey);
            if (secretKey == null) {
                throw new RuntimeException("No suitable PGP secret key found in the provided key data");
            }

            PGPPrivateKey privateKey = secretKey.extractPrivateKey(
                    new JcePBESecretKeyDecryptorBuilder()
                            .setProvider("BC")
                            .build(passphrase));

            int algorithm = secretKey.getPublicKey().getAlgorithm();

            PGPSignatureGenerator sigGen = new PGPSignatureGenerator(
                    new JcaPGPContentSignerBuilder(algorithm, PGPUtil.SHA256)
                            .setProvider("BC"));

            sigGen.init(PGPSignature.BINARY_DOCUMENT, privateKey);

            PGPSignatureSubpacketGenerator subGen = new PGPSignatureSubpacketGenerator();
            String userId = committer.getName() + " <" + committer.getEmailAddress() + ">";
            subGen.addSignerUserID(false, userId);
            sigGen.setHashedSubpackets(subGen.generate());

            commit.setGpgSignature(null);
            byte[] payload = commit.build();

            sigGen.update(payload);
            PGPSignature signature = sigGen.generate();

            ByteArrayOutputStream sigOut = new ByteArrayOutputStream();
            try (OutputStream armoredOut = new ArmoredOutputStream(sigOut)) {
                signature.encode(armoredOut);
            }

            byte[] signatureBytes = sigOut.toByteArray();
            commit.setGpgSignature(new GpgSignature(signatureBytes));

        } catch (PGPException | IOException e) {
            throw new RuntimeException("Failed to sign commit with pasted GPG key", e);
        }
    }

    @Override
    public boolean canLocateSigningKey(String signingKey, PersonIdent committer,
                                       CredentialsProvider credentialsProvider) throws CanceledException {
        try {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(new BouncyCastleProvider());
            }
            PGPSecretKey key = findSecretKey(privateKeyData, signingKey);
            return key != null;
        } catch (Exception e) {
            return false;
        }
    }

    public String getFingerprint() throws IOException, PGPException {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        PGPSecretKey key = findSecretKey(privateKeyData, null);
        if (key == null) {
            return null;
        }
        byte[] fp = key.getPublicKey().getFingerprint();
        return Hex.toHexString(fp);
    }

    private PGPSecretKey findSecretKey(byte[] keyData, String signingKey) throws IOException, PGPException {
        try (InputStream in = PGPUtil.getDecoderStream(new ByteArrayInputStream(keyData))) {
            PGPSecretKeyRingCollection keyRings = new PGPSecretKeyRingCollection(in, new JcaKeyFingerprintCalculator());

            Iterator<PGPSecretKeyRing> ringIter = keyRings.getKeyRings();
            while (ringIter.hasNext()) {
                PGPSecretKeyRing keyRing = ringIter.next();
                Iterator<PGPSecretKey> keyIter = keyRing.getSecretKeys();
                while (keyIter.hasNext()) {
                    PGPSecretKey key = keyIter.next();
                    if (key.isSigningKey()) {
                        if (signingKey == null || signingKey.isEmpty()) {
                            return key;
                        }
                        String keyIdStr = Long.toHexString(key.getKeyID()).toLowerCase();
                        if (keyIdStr.contains(signingKey.toLowerCase())) {
                            return key;
                        }
                        byte[] fp = key.getPublicKey().getFingerprint();
                        String fingerprint = Hex.toHexString(fp);
                        if (fingerprint.contains(signingKey.toUpperCase())
                                || fingerprint.contains(signingKey.toLowerCase())) {
                            return key;
                        }
                    }
                }
            }
        }
        return null;
    }
}
