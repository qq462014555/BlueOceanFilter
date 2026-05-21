package com.blueocean.taobao.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import jakarta.mail.internet.MimeMessage;

/**
 * HTML 邮件发送工具类
 */
@Component
public class EmailUtil {

    private static final Logger log = LoggerFactory.getLogger(EmailUtil.class);
    private static final String DEFAULT_EMAIL = "462014555@qq.com";

    private final JavaMailSender mailSender;

    public EmailUtil(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /**
     * 发送 HTML 邮件
     */
    public void sendHtml(String subject, String htmlContent) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            String from = System.getenv("MAIL_FROM") != null ? System.getenv("MAIL_FROM") : DEFAULT_EMAIL;
            String to = System.getenv("MAIL_TO") != null ? System.getenv("MAIL_TO") : DEFAULT_EMAIL;

            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject("[淘宝推广] " + subject);
            helper.setText(htmlContent, true);

            mailSender.send(message);
            log.info("邮件已发送至: {}", to);
        } catch (Exception e) {
            log.error("邮件发送失败: {}", e.getMessage(), e);
        }
    }
}
