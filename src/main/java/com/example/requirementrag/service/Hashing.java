package com.example.requirementrag.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 内容哈希与确定性 UUID 工具，用于分块去重与 ID 生成。
 */
final class Hashing {

    /** 禁止实例化。 */
    private Hashing() {
    }

    /**
     * 计算字符串的 SHA-256 十六进制摘要，用于内容去重。
     */
    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * 基于输入字符串生成确定性 UUID，保证相同输入得到相同 ID。
     */
    static String uuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
