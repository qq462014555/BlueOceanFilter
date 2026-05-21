package com.blueocean.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailNotificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationService.class);

    private static final String DEFAULT_EMAIL = "462014555@qq.com";

    private final JavaMailSender mailSender;
    private final String from;
    private final String to;

    public EmailNotificationService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
        this.from = System.getenv("MAIL_FROM") != null ? System.getenv("MAIL_FROM") : DEFAULT_EMAIL;
        this.to = System.getenv("MAIL_TO") != null ? System.getenv("MAIL_TO") : DEFAULT_EMAIL;
    }

    public void sendNotification(String subject, String content) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(to);
            message.setSubject("[蓝海词筛选] " + subject);
            message.setText(content);
            mailSender.send(message);
            log.info("邮件通知已发送: {}", subject);
        } catch (Exception e) {
            log.error("邮件通知发送失败: {}", e.getMessage(), e);
        }
    }
}
