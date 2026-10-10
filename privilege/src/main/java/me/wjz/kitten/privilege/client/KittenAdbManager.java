// 移植自 libadb-android 示例 AdbConnectionManager
// (SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0)，改了类名、设备名和证书有效期。
package me.wjz.kitten.privilege.client;

import android.content.Context;
import android.os.Build;
import android.sun.misc.BASE64Encoder;
import android.sun.security.provider.X509Factory;
import android.sun.security.x509.AlgorithmId;
import android.sun.security.x509.CertificateAlgorithmId;
import android.sun.security.x509.CertificateExtensions;
import android.sun.security.x509.CertificateIssuerName;
import android.sun.security.x509.CertificateSerialNumber;
import android.sun.security.x509.CertificateSubjectName;
import android.sun.security.x509.CertificateValidity;
import android.sun.security.x509.CertificateVersion;
import android.sun.security.x509.CertificateX509Key;
import android.sun.security.x509.KeyIdentifier;
import android.sun.security.x509.PrivateKeyUsageExtension;
import android.sun.security.x509.SubjectKeyIdentifierExtension;
import android.sun.security.x509.X500Name;
import android.sun.security.x509.X509CertImpl;
import android.sun.security.x509.X509CertInfo;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.spec.EncodedKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import java.util.Random;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;

/** 持有 ADB 用的 RSA 密钥对和自签证书，首次生成后存到 App 私有目录，之后复用。 */
public class KittenAdbManager extends AbsAdbConnectionManager {

    private static AbsAdbConnectionManager INSTANCE;

    public static AbsAdbConnectionManager getInstance(Context context) throws Exception {
        if (INSTANCE == null) {
            INSTANCE = new KittenAdbManager(context.getApplicationContext());
        }
        return INSTANCE;
    }

    private PrivateKey mPrivateKey;
    private Certificate mCertificate;

    private KittenAdbManager(Context context) throws Exception {
        setApi(Build.VERSION.SDK_INT);
        mPrivateKey = readPrivateKeyFromFile(context);
        mCertificate = readCertificateFromFile(context);
        if (mPrivateKey == null) {
            int keySize = 2048;
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(keySize, SecureRandom.getInstance("SHA1PRNG"));
            KeyPair generateKeyPair = keyPairGenerator.generateKeyPair();
            PublicKey publicKey = generateKeyPair.getPublic();
            mPrivateKey = generateKeyPair.getPrivate();

            String subject = "CN=Kitten";
            String algorithmName = "SHA512withRSA";
            // 10 年有效期：密钥会长期复用，别让证书过早过期。
            long expiryDate = System.currentTimeMillis() + 10L * 365 * 24 * 60 * 60 * 1000;
            CertificateExtensions certificateExtensions = new CertificateExtensions();
            certificateExtensions.set("SubjectKeyIdentifier", new SubjectKeyIdentifierExtension(
                    new KeyIdentifier(publicKey).getIdentifier()));
            X500Name x500Name = new X500Name(subject);
            Date notBefore = new Date();
            Date notAfter = new Date(expiryDate);
            certificateExtensions.set("PrivateKeyUsage", new PrivateKeyUsageExtension(notBefore, notAfter));
            CertificateValidity certificateValidity = new CertificateValidity(notBefore, notAfter);
            X509CertInfo x509CertInfo = new X509CertInfo();
            x509CertInfo.set("version", new CertificateVersion(2));
            x509CertInfo.set("serialNumber", new CertificateSerialNumber(new Random().nextInt() & Integer.MAX_VALUE));
            x509CertInfo.set("algorithmID", new CertificateAlgorithmId(AlgorithmId.get(algorithmName)));
            x509CertInfo.set("subject", new CertificateSubjectName(x500Name));
            x509CertInfo.set("key", new CertificateX509Key(publicKey));
            x509CertInfo.set("validity", certificateValidity);
            x509CertInfo.set("issuer", new CertificateIssuerName(x500Name));
            x509CertInfo.set("extensions", certificateExtensions);
            X509CertImpl x509CertImpl = new X509CertImpl(x509CertInfo);
            x509CertImpl.sign(mPrivateKey, algorithmName);
            mCertificate = x509CertImpl;

            writePrivateKeyToFile(context, mPrivateKey);
            writeCertificateToFile(context, mCertificate);
        }
    }

    @Override
    protected PrivateKey getPrivateKey() {
        return mPrivateKey;
    }

    @Override
    protected Certificate getCertificate() {
        return mCertificate;
    }

    @Override
    protected String getDeviceName() {
        return "Kitten";
    }

    private static Certificate readCertificateFromFile(Context context)
            throws IOException, CertificateException {
        File certFile = new File(context.getFilesDir(), "adb_cert.pem");
        if (!certFile.exists()) return null;
        try (InputStream cert = new FileInputStream(certFile)) {
            return CertificateFactory.getInstance("X.509").generateCertificate(cert);
        }
    }

    private static void writeCertificateToFile(Context context, Certificate certificate)
            throws CertificateEncodingException, IOException {
        File certFile = new File(context.getFilesDir(), "adb_cert.pem");
        BASE64Encoder encoder = new BASE64Encoder();
        try (OutputStream os = new FileOutputStream(certFile)) {
            os.write(X509Factory.BEGIN_CERT.getBytes(StandardCharsets.UTF_8));
            os.write('\n');
            encoder.encode(certificate.getEncoded(), os);
            os.write('\n');
            os.write(X509Factory.END_CERT.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static PrivateKey readPrivateKeyFromFile(Context context)
            throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        File privateKeyFile = new File(context.getFilesDir(), "adb_private.key");
        if (!privateKeyFile.exists()) return null;
        byte[] privKeyBytes = new byte[(int) privateKeyFile.length()];
        try (InputStream is = new FileInputStream(privateKeyFile)) {
            //noinspection ResultOfMethodCallIgnored
            is.read(privKeyBytes);
        }
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        EncodedKeySpec privateKeySpec = new PKCS8EncodedKeySpec(privKeyBytes);
        return keyFactory.generatePrivate(privateKeySpec);
    }

    private static void writePrivateKeyToFile(Context context, PrivateKey privateKey)
            throws IOException {
        File privateKeyFile = new File(context.getFilesDir(), "adb_private.key");
        try (OutputStream os = new FileOutputStream(privateKeyFile)) {
            os.write(privateKey.getEncoded());
        }
    }
}
