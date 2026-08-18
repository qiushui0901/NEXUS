package com.example.requirementrag.integration.gitlab;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** GitLab API 与 Git Clone 共用的 Host、DNS 和私网访问策略。 */
@Component
@ConditionalOnProperty(name = "app.rag.gitlab.enabled", havingValue = "true")
public class GitLabHostPolicy {
    private static final Pattern IPV4_LITERAL = Pattern.compile("\\d{1,3}(?:\\.\\d{1,3}){3}");
    private final Set<String> allowedHosts;
    private final boolean allowPrivateHosts;
    private final AddressResolver addressResolver;

    @Autowired
    public GitLabHostPolicy(GitLabIntegrationProperties properties) {
        this(properties, InetAddress::getAllByName);
    }

    GitLabHostPolicy(GitLabIntegrationProperties properties, AddressResolver addressResolver) {
        allowedHosts = Set.copyOf(properties.allowedHosts());
        allowPrivateHosts = properties.allowPrivateHosts();
        this.addressResolver = addressResolver;
    }

    public URI validateBaseUrl(String value) {
        URI uri = parse(value, "GitLab 实例地址");
        validateCommon(uri, "GitLab 实例地址");
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (path.contains("..") || path.endsWith(".git")) {
            throw new IllegalArgumentException("GitLab 实例地址路径无效");
        }
        String normalizedPath = "/".equals(path) ? "" : path.replaceFirst("/+$", "");
        try {
            return new URI("https", null, normalizeHost(uri.getHost()), uri.getPort(),
                    normalizedPath, null, null);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("GitLab 实例地址格式无效");
        }
    }

    public URI validateCloneUrl(String value) {
        URI uri = parse(value, "GitLab cloneUrl");
        validateCommon(uri, "GitLab cloneUrl");
        if (uri.getPath() == null || uri.getPath().isBlank()) {
            throw new IllegalArgumentException("GitLab cloneUrl 路径不能为空");
        }
        return uri;
    }

    /** PAT 只能用于与所属连接完全相同的 GitLab Host 和有效端口。 */
    public URI validateCloneUrlForBaseUrl(String cloneUrl, String baseUrl) {
        URI clone = validateCloneUrl(cloneUrl);
        URI base = validateBaseUrl(baseUrl);
        if (!normalizeHost(clone.getHost()).equals(normalizeHost(base.getHost()))
                || effectivePort(clone) != effectivePort(base)) {
            throw new IllegalArgumentException("GitLab cloneUrl 与账号连接主机或端口不一致");
        }
        return clone;
    }

    public String normalizeHost(String host) {
        String unwrapped = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : host;
        try {
            return IDN.toASCII(unwrapped).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("GitLab 主机名无效");
        }
    }

    private URI parse(String value, String field) {
        try {
            return new URI(value);
        } catch (URISyntaxException | NullPointerException exception) {
            throw new IllegalArgumentException(field + "格式无效");
        }
    }

    private void validateCommon(URI uri, String field) {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getHost().isBlank() || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException(field + "必须是不含凭据、查询参数和片段的 HTTPS URL");
        }
        String host = normalizeHost(uri.getHost());
        if (!allowedHosts.contains(host)) {
            throw new IllegalArgumentException(field + "主机不在 allowedHosts 白名单中");
        }
        InetAddress[] addresses = resolve(host, field);
        if (!allowPrivateHosts && (isIpLiteral(host)
                || Arrays.stream(addresses).anyMatch(GitLabHostPolicy::isUnsafeAddress))) {
            throw new IllegalArgumentException(field + "默认禁止 IP、回环和内网地址");
        }
    }

    private InetAddress[] resolve(String host, String field) {
        try {
            InetAddress[] addresses = addressResolver.resolve(host);
            if (addresses == null || addresses.length == 0) {
                throw new IllegalArgumentException(field + "主机无法解析");
            }
            return addresses;
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException(field + "主机无法解析");
        }
    }

    private boolean isIpLiteral(String host) {
        if (host.contains(":")) return true;
        if (!IPV4_LITERAL.matcher(host).matches()) return false;
        return Arrays.stream(host.split("\\."))
                .mapToInt(Integer::parseInt)
                .allMatch(part -> part >= 0 && part <= 255);
    }

    private int effectivePort(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : 443;
    }

    private static boolean isUnsafeAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        boolean ipv6UniqueLocal = bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
        return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()
                || ipv6UniqueLocal;
    }

    @FunctionalInterface
    interface AddressResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }
}
