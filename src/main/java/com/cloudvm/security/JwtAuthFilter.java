package com.cloudvm.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT Authentication Filter — chạy một lần cho mỗi HTTP request.
 *
 * Logic:
 * 1. Đọc header "Authorization: Bearer <token>"
 * 2. Extract và validate token
 * 3. Load UserDetails từ DB
 * 4. Set Authentication vào SecurityContext để Spring biết user đã xác thực
 *
 * Nếu không có token hoặc token không hợp lệ, filter bỏ qua (không set auth),
 * và Spring Security sẽ từ chối request với 401 Unauthorized.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final UserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        final String authHeader = request.getHeader("Authorization");

        // Không có header hoặc không phải Bearer token → bỏ qua
        if (!StringUtils.hasText(authHeader) || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Lấy token (bỏ prefix "Bearer ")
        final String jwt = authHeader.substring(7);

        try {
            final String username = jwtUtil.extractUsername(jwt);

            // Chỉ xử lý nếu chưa có authentication trong context
            if (StringUtils.hasText(username)
                    && SecurityContextHolder.getContext().getAuthentication() == null) {

                UserDetails userDetails = userDetailsService.loadUserByUsername(username);

                if (jwtUtil.validateToken(jwt, userDetails.getUsername())) {
                    UsernamePasswordAuthenticationToken authToken =
                            new UsernamePasswordAuthenticationToken(
                                    userDetails,
                                    null,
                                    userDetails.getAuthorities()
                            );
                    authToken.setDetails(
                            new WebAuthenticationDetailsSource().buildDetails(request)
                    );
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                    log.debug("Authenticated user: {}, URI: {}", username, request.getRequestURI());
                }
            }
        } catch (Exception e) {
            log.error("Không thể set authentication từ JWT: {}", e.getMessage());
        }

        filterChain.doFilter(request, response);
    }
}
