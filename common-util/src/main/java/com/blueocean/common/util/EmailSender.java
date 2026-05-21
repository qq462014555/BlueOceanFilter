package com.blueocean.common.util;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * 通用邮件发送工具
 */
@Component
public class EmailSender {

    private static final Logger log = LoggerFactory.getLogger(EmailSender.class);
    private static final String DEFAULT_EMAIL = "462014555@qq.com";

    private final JavaMailSender mailSender;

    public EmailSender(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /**
     * 发送 HTML 邮件
     */
    public void sendHtml(String to, String subject, String htmlContent) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            String from = System.getenv("MAIL_FROM") != null ? System.getenv("MAIL_FROM") : DEFAULT_EMAIL;
            String actualTo = to != null && !to.isEmpty() ? to : (System.getenv("MAIL_TO") != null ? System.getenv("MAIL_TO") : DEFAULT_EMAIL);

            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(actualTo);
            helper.setSubject(subject);
            helper.setText(htmlContent, true);

            mailSender.send(message);
            log.info("HTML 邮件已发送至: {}", actualTo);
        } catch (Exception e) {
            log.error("HTML 邮件发送失败: {}", e.getMessage(), e);
            throw new RuntimeException("邮件发送失败: " + e.getMessage(), e);
        }
    }

    /**
     * 发送纯文本邮件
     */
    public void sendText(String to, String subject, String textContent) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            String from = System.getenv("MAIL_FROM") != null ? System.getenv("MAIL_FROM") : DEFAULT_EMAIL;
            String actualTo = to != null && !to.isEmpty() ? to : (System.getenv("MAIL_TO") != null ? System.getenv("MAIL_TO") : DEFAULT_EMAIL);

            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(actualTo);
            helper.setSubject(subject);
            helper.setText(textContent, false);

            mailSender.send(message);
            log.info("纯文本邮件已发送至: {}", actualTo);
        } catch (Exception e) {
            log.error("纯文本邮件发送失败: {}", e.getMessage(), e);
            throw new RuntimeException("邮件发送失败: " + e.getMessage(), e);
        }
    }
}
