package com.blueocean.scheduledtask.service;

import com.blueocean.common.annotation.ScheduledTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 扫描所有 @RestController 中带有 @ScheduledTask 注解的方法
 */
@Service
public class ScheduledTaskDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskDiscoveryService.class);

    private final ApplicationContext applicationContext;

    public ScheduledTaskDiscoveryService(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    public List<Map<String, Object>> discoverTasks() {
        List<Map<String, Object>> methods = new ArrayList<>();

        // 获取所有带 @RestController 注解的 bean
        String[] beanNames = applicationContext.getBeanNamesForAnnotation(RestController.class);

        for (String beanName : beanNames) {
            Object bean = applicationContext.getBean(beanName);
            Class<?> clazz = bean.getClass();

            // 处理 CGLIB 代理类，获取原始类
            if (clazz.getName().contains("$$")) {
                clazz = clazz.getSuperclass();
            }

            for (Method method : clazz.getDeclaredMethods()) {
                ScheduledTask annotation = method.getAnnotation(ScheduledTask.class);
                if (annotation != null) {
                    Map<String, Object> info = new HashMap<>();
                    info.put("beanName", beanName);
                    info.put("methodName", method.getName());
                    info.put("displayName", annotation.name());
                    info.put("description", annotation.description());

                    List<Map<String, Object>> params = new ArrayList<>();
                    for (Parameter param : method.getParameters()) {
                        Map<String, Object> paramInfo = new HashMap<>();
                        paramInfo.put("name", param.getName());
                        paramInfo.put("type", param.getType().getName());
                        paramInfo.put("required", !param.isAnnotationPresent(org.springframework.web.bind.annotation.RequestParam.class)
                                || param.getAnnotation(org.springframework.web.bind.annotation.RequestParam.class).required());
                        params.add(paramInfo);
                    }
                    info.put("params", params);

                    methods.add(info);
                    log.info("发现定时任务方法: {}#{} - {}", clazz.getSimpleName(), method.getName(), annotation.name());
                }
            }
        }

        return methods;
    }
}
