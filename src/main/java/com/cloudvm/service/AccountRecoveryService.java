package com.cloudvm.service;

import com.cloudvm.entity.User;
import com.cloudvm.entity.VerificationToken;
import com.cloudvm.enums.AuthProvider;
import com.cloudvm.enums.VerificationTokenType;
import com.cloudvm.repository.UserRepository;
import com.cloudvm.repository.VerificationTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AccountRecoveryService {

    private final UserRepository userRepository;
    private final VerificationTokenRepository verificationTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthTokenService authTokenService;
    private final EmailService emailService;

    @Value("${app.base-url}")
    private String appBaseUrl;

    @Value("${app.auth.verification-expiration-minutes}")
    private long verificationExpirationMinutes;

    @Value("${app.auth.reset-expiration-minutes}")
    private long resetExpirationMinutes;

    @Transactional
    public void sendVerificationEmail(User user) {
        if (!AuthProvider.LOCAL.equals(user.getAuthProvider())) {
            return;
        }

        revokeOpenTokens(user.getId(), VerificationTokenType.EMAIL_VERIFICATION);
        String tokenValue = createToken(user, VerificationTokenType.EMAIL_VERIFICATION, verificationExpirationMinutes);
        String verifyUrl = appBaseUrl + "/?verifyToken=" + tokenValue;

        emailService.sendEmail(
                user.getEmail(),
                "CloudVM - Xac thuc email",
                """
                <p>Xin chao %s,</p>
                <p>Vui long xac thuc email de kich hoat tai khoan CloudVM.</p>
                <p><a href="%s">Xac thuc email</a></p>
                <p>Link co hieu luc trong %d phut.</p>
                """.formatted(user.getUsername(), verifyUrl, verificationExpirationMinutes)
        );
    }

    @Transactional
    public void verifyEmail(String tokenValue) {
        VerificationToken token = findValidToken(tokenValue, VerificationTokenType.EMAIL_VERIFICATION);
        User user = token.getUser();
        user.setEmailVerified(true);
        userRepository.save(user);
        token.setUsed(true);
        verificationTokenRepository.save(token);
    }

    @Transactional
    public void sendPasswordResetEmail(String email) {
        userRepository.findByEmail(email.trim().toLowerCase())
                .filter(user -> AuthProvider.LOCAL.equals(user.getAuthProvider()))
                .ifPresent(user -> {
                    revokeOpenTokens(user.getId(), VerificationTokenType.PASSWORD_RESET);
                    String tokenValue = createToken(user, VerificationTokenType.PASSWORD_RESET, resetExpirationMinutes);
                    String resetUrl = appBaseUrl + "/?resetToken=" + tokenValue;
                    emailService.sendEmail(
                            user.getEmail(),
                            "CloudVM - Đặt lại mật khẩu",
                            """
                            <!doctype html>
                            <html lang="vi">
                            <head>
                                <meta charset="UTF-8">
                                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                                <title>Đặt lại mật khẩu CloudVM</title>
                            </head>
                            <body style="margin:0;padding:0;background:#f3f4f6;font-family:Arial,'Helvetica Neue',Helvetica,sans-serif;color:#0f172a;">
                                <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="background:#f3f4f6;padding:28px 12px;">
                                    <tr>
                                        <td align="center">
                                            <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="max-width:560px;background:#ffffff;border-radius:18px;padding:34px 34px 28px;box-shadow:0 18px 50px rgba(15,23,42,0.08);">
                                                <tr>
                                                    <td align="center" style="padding-bottom:18px;">
                                                       
                                                    </td>
                                                </tr>
                                                
                                                <tr>
                                                    <td>
                                                        <p style="margin:0 0 18px;font-size:15px;line-height:1.7;color:#0f172a;">Xin chào <strong>%s</strong>,</p>
                                                        <p style="margin:0 0 26px;font-size:15px;line-height:1.7;color:#334155;">Bạn có thể đặt lại mật khẩu bằng cách nhấn vào nút bên dưới. Liên kết này sẽ hết hạn sau <strong>%d phút</strong> để bảo vệ tài khoản của bạn.</p>
                                                    </td>
                                                </tr>
                                                <tr>
                                                    <td align="center" style="padding:8px 0 28px;">
                                                        <a href="%s" style="display:inline-block;min-width:210px;padding:15px 26px;background:#2563eb;border-radius:999px;color:#ffffff;text-decoration:none;font-size:15px;font-weight:700;text-align:center;">Đặt lại mật khẩu</a>
                                                    </td>
                                                </tr>
                                                <tr>
                                                    <td>
                                                        <div style="background:#f8fafc;border:1px solid #e2e8f0;border-radius:14px;padding:18px 18px;margin-bottom:26px;">
                                                            <p style="margin:0;font-size:14px;line-height:1.7;color:#334155;">Nếu bạn không yêu cầu đặt lại mật khẩu, bạn có thể bỏ qua email này. Mật khẩu hiện tại của bạn sẽ không bị thay đổi.</p>
                                                        </div>
                                                    </td>
                                                </tr>
                                                <tr>
                                                    <td style="border-top:1px solid #e2e8f0;padding-top:20px;text-align:center;">
                                                        <p style="margin:0 0 6px;font-size:12px;color:#94a3b8;">© 2026 CloudVM. All rights reserved.</p>
                                                        <p style="margin:0;font-size:12px;color:#94a3b8;">Email này được gửi tự động, vui lòng không trả lời email này.</p>
                                                    </td>
                                                </tr>
                                            </table>
                                        </td>
                                    </tr>
                                </table>
                            </body>
                            </html>
                            """.formatted(user.getUsername(), resetExpirationMinutes, resetUrl, resetUrl, resetUrl)
                    );
                });
    }

    @Transactional
    public void resetPassword(String tokenValue, String newPassword) {
        VerificationToken token = findValidToken(tokenValue, VerificationTokenType.PASSWORD_RESET);
        User user = token.getUser();
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        token.setUsed(true);
        verificationTokenRepository.save(token);
        authTokenService.revokeAllForUser(user.getId());
    }

    private VerificationToken findValidToken(String tokenValue, VerificationTokenType type) {
        VerificationToken token = verificationTokenRepository
                .findByTokenAndTypeAndUsedFalse(tokenValue, type)
                .orElseThrow(() -> new BadCredentialsException("Token khong hop le"));

        if (token.getExpiresAt().isBefore(LocalDateTime.now())) {
            token.setUsed(true);
            verificationTokenRepository.save(token);
            throw new BadCredentialsException("Token da het han");
        }

        return token;
    }

    private String createToken(User user, VerificationTokenType type, long expirationMinutes) {
        String tokenValue = UUID.randomUUID().toString() + "-" + UUID.randomUUID();
        verificationTokenRepository.save(VerificationToken.builder()
                .user(user)
                .token(tokenValue)
                .type(type)
                .expiresAt(LocalDateTime.now().plusMinutes(expirationMinutes))
                .used(false)
                .build());
        return tokenValue;
    }

    private void revokeOpenTokens(Integer userId, VerificationTokenType type) {
        List<VerificationToken> tokens = verificationTokenRepository
                .findByUserIdAndTypeAndUsedFalse(userId, type);
        tokens.forEach(token -> token.setUsed(true));
        verificationTokenRepository.saveAll(tokens);
    }
}
