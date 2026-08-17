package com.example.requirementrag.integration.gitlab;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitLabCredentialCipherTest {

    @Test
    void encryptsWithRandomNonceAndDecrypts() {
        GitLabCredentialCipher cipher = new GitLabCredentialCipher(properties(key()));

        String first = cipher.encrypt("glpat-secret");
        String second = cipher.encrypt("glpat-secret");

        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo("glpat-secret");
        assertThat(cipher.decrypt(second)).isEqualTo("glpat-secret");
    }

    @Test
    void rejectsKeysThatAreNotExactly256Bits() {
        assertThatThrownBy(() -> new GitLabCredentialCipher(properties(
                Base64.getEncoder().encodeToString(new byte[16]))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 字节");
    }

    private GitLabIntegrationProperties properties(String key) {
        return new GitLabIntegrationProperties(true, null, null, key, 10, 1);
    }

    private String key() {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i + 1);
        }
        return Base64.getEncoder().encodeToString(bytes);
    }
}
