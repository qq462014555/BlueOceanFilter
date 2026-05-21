package com.blueocean.common.controller;

import com.blueocean.common.annotation.ScheduledTask;
import com.blueocean.common.util.EmailSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 邮件发送 Controller，所有方法标记为可定时任务
 */
@Slf4j
@RestController
@RequestMapping("/api/email")
public class EmailController {

    private final EmailSender emailSender;

    public EmailController(EmailSender emailSender) {
        this.emailSender = emailSender;
    }

    @ScheduledTask(name = "发送 HTML 邮件", description = "发送自定义 HTML 格式邮件到指定邮箱")
    @PostMapping("/send-html")
    public ResponseEntity<Map<String, Object>> sendHtmlEmail(
            @RequestParam(required = false, defaultValue = "") String to,
            @RequestParam String subject,
            @RequestParam String content) {
        try {
            emailSender.sendHtml(to, subject, content);
            Map<String, Object> result = new HashMap<>();
            result.put("message", "邮件发送成功");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("发送邮件失败", e);
            Map<String, Object> result = new HashMap<>();
            result.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    @ScheduledTask(name = "发送纯文本邮件", description = "发送纯文本邮件到指定邮箱")
    @PostMapping("/send-text")
    public ResponseEntity<Map<String, Object>> sendTextEmail(
            @RequestParam(required = false, defaultValue = "") String to,
            @RequestParam String subject,
            @RequestParam String content) {
        try {
            emailSender.sendText(to, subject, content);
            Map<String, Object> result = new HashMap<>();
            result.put("message", "邮件发送成功");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("发送邮件失败", e);
            Map<String, Object> result = new HashMap<>();
            result.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    @ScheduledTask(name = "发送快捷邮件", description = "只填标题和内容，自动发到默认邮箱（环境变量 MAIL_FROM/MAIL_TO）")
    @PostMapping("/send-simple")
    public ResponseEntity<Map<String, Object>> sendSimpleEmail(
            @RequestParam String subject,
            @RequestParam String content) {
        try {
            emailSender.sendHtml("", subject, content);
            Map<String, Object> result = new HashMap<>();
            result.put("message", "邮件发送成功");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("发送邮件失败", e);
            Map<String, Object> result = new HashMap<>();
            result.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }
}
