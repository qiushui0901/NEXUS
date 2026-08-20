package com.example.requirementrag.project;

import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/** 从仓库受控构建元数据解析业务版本，并保留 commit 审计锚点。 */
@Component
public class RepositoryVersionResolver {

    public ResolvedVersion resolve(CodeRepository repository) {
        if (repository == null || !"MAVEN_POM".equals(repository.versionSourceType())) {
            return ResolvedVersion.unavailable("UNSUPPORTED_VERSION_SOURCE");
        }
        try {
            Path root = Path.of(repository.repositoryPath()).toRealPath();
            Path source = root.resolve(repository.versionSourcePath()).normalize();
            if (!source.startsWith(root) || !Files.isRegularFile(source)) {
                return ResolvedVersion.unavailable("VERSION_SOURCE_NOT_FOUND");
            }
            String raw = mavenVersion(source);
            if (raw == null || raw.isBlank()) {
                return ResolvedVersion.unavailable("VERSION_NOT_DECLARED");
            }
            return new ResolvedVersion("v" + stripPrefix(raw.trim()), raw.trim(),
                    repository.versionSourcePath(), gitCommit(root), Instant.now().toString(),
                    "AVAILABLE", null);
        } catch (Exception exception) {
            return ResolvedVersion.unavailable("VERSION_RESOLUTION_FAILED");
        }
    }

    private String mavenVersion(Path pom) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Element project = factory.newDocumentBuilder().parse(pom.toFile()).getDocumentElement();
        String version = directChildText(project, "version");
        if (version != null && version.startsWith("${") && version.endsWith("}")) {
            String property = version.substring(2, version.length() - 1);
            Element properties = directChild(project, "properties");
            version = properties == null ? null : directChildText(properties, property);
        }
        return version;
    }

    private Element directChild(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child instanceof Element element && name.equals(element.getLocalName() == null
                    ? element.getNodeName() : element.getLocalName())) {
                return element;
            }
        }
        return null;
    }

    private String directChildText(Element parent, String name) {
        Element child = directChild(parent, name);
        return child == null ? null : child.getTextContent().trim();
    }

    private String gitCommit(Path root) {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "HEAD")
                    .directory(root.toFile()).redirectErrorStream(true).start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            String output = new String(process.getInputStream().readNBytes(128),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            return process.exitValue() == 0 && output.matches("[0-9a-fA-F]{40,64}") ? output : null;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception exception) {
            return null;
        }
    }

    private String stripPrefix(String value) {
        return value.startsWith("v") || value.startsWith("V") ? value.substring(1) : value;
    }

    public record ResolvedVersion(String displayVersion, String rawVersion, String sourcePath,
                                  String commitSha, String resolvedAt, String status, String warningCode) {
        static ResolvedVersion unavailable(String warningCode) {
            return new ResolvedVersion(null, null, null, null, Instant.now().toString(),
                    "UNAVAILABLE", warningCode);
        }
    }
}
