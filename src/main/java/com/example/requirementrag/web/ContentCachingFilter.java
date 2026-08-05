package com.example.requirementrag.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 对 /api/** 请求缓存 body，使拦截器可解析 JSON 中的 projectId。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ContentCachingFilter extends OncePerRequestFilter {

    /** 对需要缓存的请求包装 body 后继续过滤链，其余请求原样放行。 */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (shouldCache(request)) {
            chain.doFilter(new CachedBodyHttpServletRequest(request), response);
        }
        else {
            chain.doFilter(request, response);
        }
    }

    /** 判断是否需缓存：仅 /api/ 前缀下的 JSON 类型 POST/PUT/PATCH 请求。 */
    private boolean shouldCache(HttpServletRequest request) {
        if (!request.getRequestURI().startsWith("/api/")) {
            return false;
        }
        if (!HttpMethod.POST.matches(request.getMethod())
                && !HttpMethod.PUT.matches(request.getMethod())
                && !HttpMethod.PATCH.matches(request.getMethod())) {
            return false;
        }
        String contentType = request.getContentType();
        return contentType != null && contentType.contains("application/json");
    }
}
