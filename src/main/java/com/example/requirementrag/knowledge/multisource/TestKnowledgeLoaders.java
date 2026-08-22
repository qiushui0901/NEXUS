package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeStatus;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.TestCaseClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.TestResultClaim;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 测试用例与测试结果加载器：首期支持 JSON/JSONL 与 JUnit XML 结果。
 * 保持确定性解析，复杂语义关联后续再交给 LLM 辅助。
 */
@Component
public class TestKnowledgeLoaders {
    private final ObjectMapper objectMapper;

    public TestKnowledgeLoaders(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 从 JSON 行解析测试用例 Claim。 */
    public TestCaseClaim parseTestCase(String json, String projectId, String version, String filePath) {
        try {
            JsonNode node = objectMapper.readTree(json);
            String testCaseId = text(node, "testCaseId", "id", "name");
            if (testCaseId == null) throw new IllegalArgumentException("测试用例缺少 testCaseId");
            String title = text(node, "title", "name");
            String module = text(node, "module", "subsystem");
            String preconditions = text(node, "preconditions", "given");
            String steps = text(node, "steps", "when");
            String expectedResult = text(node, "expectedResult", "then", "expected");
            String coveredRequirementId = text(node, "coveredRequirementId", "requirementId", "requirement");
            String framework = text(node, "framework", "framework");
            String testMethod = text(node, "testMethod", "method");
            String evidenceLocation = filePath == null ? testCaseId : filePath + "#" + testMethod;
            String claimId = "tc:" + sha256(projectId + "|" + version + "|" + testCaseId).substring(0, 32);
            return new TestCaseClaim(claimId, projectId, version, testCaseId, title, module,
                    preconditions, steps, expectedResult, coveredRequirementId, framework,
                    filePath, testMethod, evidenceLocation, KnowledgeStatus.SUPPORTED);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalArgumentException("测试用例 JSON 解析失败", exception);
        }
    }

    /** 从 JSON 行解析测试结果 Claim。 */
    public TestResultClaim parseTestResult(String json, String projectId, String version) {
        try {
            JsonNode node = objectMapper.readTree(json);
            String testCaseId = text(node, "testCaseId", "id", "name");
            if (testCaseId == null) throw new IllegalArgumentException("测试结果缺少 testCaseId");
            String testRunId = text(node, "testRunId", "runId");
            String executionStatus = text(node, "executionStatus", "status");
            String executedAt = text(node, "executedAt", "timestamp");
            String environment = text(node, "environment", "env");
            String actualResult = text(node, "actualResult", "result");
            String failureMessage = text(node, "failureMessage", "failure");
            String evidenceLocation = testRunId == null ? testCaseId : testRunId + "#" + testCaseId;
            String claimId = "tr:" + sha256(projectId + "|" + version + "|" + testCaseId + "|"
                    + (testRunId == null ? "" : testRunId)).substring(0, 32);
            return new TestResultClaim(claimId, projectId, version, testRunId, testCaseId,
                    executionStatus, executedAt, environment, actualResult, failureMessage,
                    evidenceLocation, KnowledgeStatus.SUPPORTED);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalArgumentException("测试结果 JSON 解析失败", exception);
        }
    }

    /** 解析 JUnit XML 为测试结果 Claim，区分 PASSED/FAILED/SKIPPED/ERROR。 */
    public List<TestResultClaim> parseJunitXml(String xml, String projectId, String version, String testRunId,
                                               String environment, String executedAt) {
        List<TestResultClaim> result = new ArrayList<>();
        if (xml == null || xml.isBlank()) return result;
        try {
            Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            NodeList testcases = document.getElementsByTagName("testcase");
            for (int index = 0; index < testcases.getLength(); index++) {
                Element testcase = (Element) testcases.item(index);
                String name = testcase.getAttribute("name");
                String className = testcase.getAttribute("classname");
                String testCaseId = (className == null || className.isBlank() ? "" : className + ".") + safe(name);
                String status = "PASSED";
                String failure = null;
                NodeList children = testcase.getChildNodes();
                for (int childIndex = 0; childIndex < children.getLength(); childIndex++) {
                    Node child = children.item(childIndex);
                    if (!(child instanceof Element element)) continue;
                    String tag = element.getTagName();
                    if ("failure".equalsIgnoreCase(tag)) {
                        status = "FAILED";
                        failure = element.getAttribute("message");
                    } else if ("error".equalsIgnoreCase(tag)) {
                        status = "ERROR";
                        failure = element.getAttribute("message");
                    } else if ("skipped".equalsIgnoreCase(tag)) {
                        status = "SKIPPED";
                    }
                }
                String claimId = "tr:" + sha256(projectId + "|" + version + "|" + testCaseId + "|"
                        + (testRunId == null ? "" : testRunId)).substring(0, 32);
                result.add(new TestResultClaim(claimId, projectId, version, testRunId, testCaseId,
                        status, executedAt, environment, status.equals("PASSED") ? "PASSED" : safe(failure),
                        failure, testRunId + "#" + testCaseId, KnowledgeStatus.SUPPORTED));
            }
        } catch (Exception exception) {
            throw new IllegalArgumentException("JUnit XML 解析失败: " + exception.getMessage(), exception);
        }
        return result;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String text(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && !value.isNull() && !value.asText().isBlank()) return value.asText().trim();
        }
        return null;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}