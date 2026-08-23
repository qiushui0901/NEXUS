package com.example.requirementrag.knowledge.multisource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 结构化 Evidence ID 稳定生成器：
 * {@code ev:<projectId>:<documentVersionId>:<sha256(locator|excerptHash) 前 40 位>}。
 *
 * <p>同一项目/版本/位置/摘录恒等；LLM 只能引用候选 Evidence ID，不能伪造 ID 或位置。
 */
public final class KnowledgeEvidenceIdGenerator {

    private static final int ID_HASH_CHARS = 40;

    private KnowledgeEvidenceIdGenerator() {
    }

    /** 生成稳定 Evidence ID。 */
    public static String generate(String projectId, String documentVersionId, String locator, String excerptHash) {
        String project = safe(projectId);
        String version = safe(documentVersionId);
        String location = safe(locator);
        String excerpt = safe(excerptHash);
        String digest = sha256(location + "|" + excerpt).substring(0, ID_HASH_CHARS);
        return "ev:" + project + ":" + version + ":" + digest;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}