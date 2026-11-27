package com.github.carwithinforandroid.outputs.zeeho;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.UUID;

/**
 * 本机 RSA-2048 身份。移植自 CarWithin App/TailingIdentity.swift。
 * 注意：协议对 HUID 原始字节做 PKCS#1 v1.5 签名，不做 SHA 摘要。
 */
public final class ZeehoIdentity {

    private static final String PREFS = "zeeho.identity.v1";
    private static final String KEY_PRIVATE = "privateKey";
    private static final String KEY_PHONE_ID = "phoneId";

    private final KeyPair keyPair;
    private final String phoneId;

    private ZeehoIdentity(KeyPair keyPair, String phoneId) {
        this.keyPair = keyPair;
        this.phoneId = phoneId;
    }

    public static ZeehoIdentity load(Context context) throws GeneralSecurityException, IOException {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String encoded = prefs.getString(KEY_PRIVATE, null);
        String id = prefs.getString(KEY_PHONE_ID, null);
        if (encoded != null && id != null) {
            byte[] der = Base64.decode(encoded, Base64.DEFAULT);
            PrivateKey privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
            // 由私钥推公钥在 Android 上不可靠，因此初次生成时同时保存公钥。
            String pub = prefs.getString("publicKey", null);
            if (pub == null) throw new IOException("身份缺少公钥字段");
            PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(
                    new java.security.spec.X509EncodedKeySpec(Base64.decode(pub, Base64.DEFAULT)));
            return new ZeehoIdentity(new KeyPair(publicKey, privateKey), id);
        }
        return generateAndSave(context);
    }

    private static ZeehoIdentity generateAndSave(Context context) throws GeneralSecurityException, IOException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        String id = UUID.randomUUID().toString();
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(KEY_PRIVATE, Base64.encodeToString(pair.getPrivate().getEncoded(), Base64.DEFAULT))
                .putString("publicKey", Base64.encodeToString(pair.getPublic().getEncoded(), Base64.DEFAULT))
                .putString(KEY_PHONE_ID, id)
                .apply();
        return new ZeehoIdentity(pair, id);
    }

    public byte[] sign(String huid) throws GeneralSecurityException {
        byte[] message = huid.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (message.length == 0 || message.length > 245) {
            throw new IllegalArgumentException("仪表身份长度异常");
        }
        Signature signature = Signature.getInstance("NONEwithRSA");
        signature.initSign(keyPair.getPrivate());
        signature.update(message);
        return signature.sign();
    }

    public boolean verify(byte[] signatureBytes, String huid) throws GeneralSecurityException {
        Signature signature = Signature.getInstance("NONEwithRSA");
        signature.initVerify(keyPair.getPublic());
        signature.update(huid.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return signature.verify(signatureBytes);
    }

    /** X.509 SubjectPublicKeyInfo DER，直接 base64 后放进控制 JSON。 */
    public byte[] publicKeyDer() {
        return keyPair.getPublic().getEncoded();
    }

    public String phoneId() {
        return phoneId;
    }
}
