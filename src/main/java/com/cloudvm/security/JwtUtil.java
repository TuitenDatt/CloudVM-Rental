package com.cloudvm.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Utility class xử lý toàn bộ logic JWT:
 * - Tạo token (generateToken)
 * - Validate token (validateToken)
 * - Extract claims (username, userId, expiration)
 *
 * Dùng thuật toán HS256 với secret key đọc từ application.properties.
 */
@Component
@Slf4j
public class JwtUtil {

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${jwt.expiration-ms}")
    private long jwtExpirationMs;

    /**
     * Tạo SecretKey từ Base64-encoded secret string trong config.
     * Key phải ít nhất 256-bit (32 bytes) để dùng với HS256.
     */
    private SecretKey getSigningKey() {
        byte[] keyBytes = Base64.getDecoder().decode(jwtSecret);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * Tạo JWT token cho user sau khi đăng nhập thành công.
     *
     * @param username  Username của user (subject của token)
     * @param userId    ID của user trong DB (custom claim)
     * @return          JWT token string
     */
    public String generateToken(String username, Integer userId) {
        Map<String, Object> extraClaims = new HashMap<>();
        extraClaims.put("userId", userId);

        return Jwts.builder()
                .claims(extraClaims)
                .subject(username)
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + jwtExpirationMs))
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * Extract username (subject) từ token.
     */
    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    /**
     * Extract userId từ custom claim.
     */
    public Integer extractUserId(String token) {
        Claims claims = extractAllClaims(token);
        return claims.get("userId", Integer.class);
    }

    /**
     * Extract expiration date từ token.
     */
    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    /**
     * Generic method để extract bất kỳ claim nào từ token.
     */
    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    /**
     * Parse và trả về toàn bộ claims từ token.
     * Throws exception nếu token không hợp lệ hoặc đã hết hạn.
     */
    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Validate token: kiểm tra username khớp và token chưa hết hạn.
     *
     * @param token     JWT token string
     * @param username  Username cần kiểm tra
     * @return          true nếu token hợp lệ
     */
    public boolean validateToken(String token, String username) {
        try {
            final String tokenUsername = extractUsername(token);
            return tokenUsername.equals(username) && !isTokenExpired(token);
        } catch (ExpiredJwtException e) {
            log.warn("JWT token đã hết hạn: {}", e.getMessage());
        } catch (UnsupportedJwtException e) {
            log.warn("JWT token không được hỗ trợ: {}", e.getMessage());
        } catch (MalformedJwtException e) {
            log.warn("JWT token không đúng định dạng: {}", e.getMessage());
        } catch (SignatureException e) {
            log.warn("JWT signature không hợp lệ: {}", e.getMessage());
        } catch (IllegalArgumentException e) {
            log.warn("JWT claims rỗng: {}", e.getMessage());
        }
        return false;
    }

    /**
     * Kiểm tra token đã hết hạn chưa.
     */
    private boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }
}
