package com.example.requirementrag.integration.gitlab;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/** 使用 AES-256-GCM 加密 GitLab PAT 与 Webhook Secret。 */
@Component
@ConditionalOnProperty(name = "app.rag.gitlab.enabled", havingValue = "true")
public class GitLabCredentialCipher {

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final SecretKeySpec key;
    private final SecureRandom secureRandom = new SecureRandom();

    public GitLabCredentialCipher(GitLabIntegrationProperties properties) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(properties.encryptionKey());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("GITLAB_ENCRYPTION_KEY 必须是 Base64 编码的 32 字节密钥", exception);
        }
        if (decoded.length != 32) {
            throw new IllegalStateException("GITLAB_ENCRYPTION_KEY 必须是 Base64 编码的 32 字节密钥");
        }
        this.key = new SecretKeySpec(decoded, "AES");
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("凭据不能为空");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(nonce.length + ciphertext.length).put(nonce).put(ciphertext).array());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("GitLab 凭据加密失败", exception);
        }
    }

    public String decrypt(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalArgumentException("凭据密文不能为空");
        }
        try {
            byte[] payload = Base64.getDecoder().decode(encoded);
            if (payload.length <= NONCE_BYTES) {
                throw new IllegalArgumentException("凭据密文格式无效");
            }
            byte[] nonce = new byte[NONCE_BYTES];
            byte[] ciphertext = new byte[payload.length - NONCE_BYTES];
            ByteBuffer.wrap(payload).get(nonce).get(ciphertext);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("GitLab 凭据解密失败", exception);
        }
    }
}
