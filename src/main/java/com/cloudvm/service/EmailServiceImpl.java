package com.cloudvm.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailServiceImpl implements EmailService {

    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    @Value("${app.mail.from:}")
    private String fromEmail;

    @Override
    public void sendEmail(String to, String subject, String htmlContent) {
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            log.warn("Bo qua gui email vi chua cau hinh spring.mail.*");
            throw new IllegalStateException(
                    "Chua cau hinh gui email. Hay them spring.mail.host, spring.mail.username, "
                            + "spring.mail.password va app.mail.from trong application-secrets.properties"
            );
        }

        if (fromEmail == null || fromEmail.isBlank()) {
            throw new IllegalStateException("Chua cau hinh app.mail.from trong application-secrets.properties");
        }

        try {
            InternetAddress fromAddress = new InternetAddress(fromEmail);
            fromAddress.validate();

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlContent, true);
            mailSender.send(message);
        } catch (MessagingException | MailException e) {
            log.warn("Khong the gui email toi {}: {}", to, e.getMessage());
            throw new IllegalStateException("Khong the gui email: " + e.getMessage(), e);
        }
    }
}
