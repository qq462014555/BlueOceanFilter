package com.blueocean.scheduledtask.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.blueocean.scheduledtask.entity.ScheduledTask;
import com.blueocean.scheduledtask.entity.TaskExecutionLog;
import com.blueocean.scheduledtask.mapper.ScheduledTaskMapper;
import com.blueocean.scheduledtask.mapper.TaskExecutionLogMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.TriggerContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Slf4j
@Service
public class ScheduledTaskService {

    @Resource
    private ScheduledTaskMapper taskMapper;

    @Resource
    private TaskExecutionLogMapper logMapper;

    @Resource
    private ThreadPoolTaskScheduler taskScheduler;

    @Resource
    private ApplicationContext applicationContext;

    private final Map<Long, ScheduledFuture<?>> scheduledFutures = new ConcurrentHashMap<>();


    @PostConstruct
    public void initActiveTasks() {
        LambdaQueryWrapper<ScheduledTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ScheduledTask::getStatus, "ACTIVE");
        List<ScheduledTask> activeTasks = taskMapper.selectList(wrapper);
        for (ScheduledTask task : activeTasks) {
            try {
                registerTask(task.getId(), task.getCronExpression());
                log.info("Registered active task: {} (id={})", task.getName(), task.getId());
            } catch (Exception e) {
                log.error("Failed to register task {}: {}", task.getName(), e.getMessage());
            }
        }
    }

    public Page<ScheduledTask> listTasks(int page, int size, String status) {
        Page<ScheduledTask> p = new Page<>(page, size);
        LambdaQueryWrapper<ScheduledTask> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.isEmpty()) {
            wrapper.eq(ScheduledTask::getStatus, status);
        }
        wrapper.orderByDesc(ScheduledTask::getCreateTime);
        return taskMapper.selectPage(p, wrapper);
    }

    public ScheduledTask createTask(ScheduledTask task) {
        task.setStatus("PAUSED");
        task.setExecuteCount(0);
        task.setCreateTime(LocalDateTime.now());
        task.setUpdateTime(LocalDateTime.now());
        taskMapper.insert(task);
        return task;
    }

    public ScheduledTask updateTask(ScheduledTask task) {
        ScheduledTask existing = taskMapper.selectById(task.getId());
        if (existing == null) return null;

        existing.setName(task.getName());
        existing.setContent(task.getContent());
        existing.setCronExpression(task.getCronExpression());
        existing.setMethodBeanName(task.getMethodBeanName());
        existing.setMethodSignature(task.getMethodSignature());
        existing.setMethodParams(task.getMethodParams());
        existing.setUpdateTime(LocalDateTime.now());

        if ("ACTIVE".equals(existing.getStatus())) {
            unregisterTask(existing.getId());
            if (existing.getCronExpression() != null && !existing.getCronExpression().isEmpty()) {
                registerTask(existing.getId(), existing.getCronExpression());
            }
        }

        taskMapper.updateById(existing);
        return existing;
    }

    public boolean deleteTask(Long id) {
        ScheduledTask task = taskMapper.selectById(id);
        if (task == null) return false;

        unregisterTask(id);
        LambdaQueryWrapper<TaskExecutionLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TaskExecutionLog::getTaskId, id);
        logMapper.delete(wrapper);
        taskMapper.deleteById(id);
        return true;
    }

    public ScheduledTask getTask(Long id) {
        return taskMapper.selectById(id);
    }

    public boolean activateTask(Long id) {
        ScheduledTask task = taskMapper.selectById(id);
        if (task == null || task.getCronExpression() == null || task.getCronExpression().isEmpty()) {
            return false;
        }

        if ("ACTIVE".equals(task.getStatus())) return true;

        task.setStatus("ACTIVE");
        task.setUpdateTime(LocalDateTime.now());
        taskMapper.updateById(task);

        registerTask(id, task.getCronExpression());
        log.info("Activated task: {} (id={})", task.getName(), id);
        return true;
    }

    public boolean pauseTask(Long id) {
        ScheduledTask task = taskMapper.selectById(id);
        if (task == null) return false;

        if ("PAUSED".equals(task.getStatus())) return true;

        task.setStatus("PAUSED");
        task.setUpdateTime(LocalDateTime.now());
        taskMapper.updateById(task);

        unregisterTask(id);
        log.info("Paused task: {} (id={})", task.getName(), id);
        return true;
    }

    public boolean executeNow(Long id) {
        ScheduledTask task = taskMapper.selectById(id);
        if (task == null) return false;

        executeTaskInternal(task);
        return true;
    }

    public Page<TaskExecutionLog> getExecutionLogs(Long taskId, int page, int size) {
        Page<TaskExecutionLog> p = new Page<>(page, size);
        LambdaQueryWrapper<TaskExecutionLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TaskExecutionLog::getTaskId, taskId);
        wrapper.orderByDesc(TaskExecutionLog::getExecuteTime);
        return logMapper.selectPage(p, wrapper);
    }

    private void registerTask(Long taskId, String cronExpression) {
        if (scheduledFutures.containsKey(taskId)) {
            unregisterTask(taskId);
        }

        Trigger trigger = new Trigger() {
            @Override
            public Instant nextExecution(TriggerContext context) {
                CronTrigger cronTrigger = new CronTrigger(cronExpression);
                Instant next = cronTrigger.nextExecution(context);
                if (next != null) {
                    LocalDateTime nextTime = LocalDateTime.ofInstant(next, java.time.ZoneId.systemDefault());
                    ScheduledTask t = taskMapper.selectById(taskId);
                    if (t != null) {
                        t.setNextExecuteTime(nextTime);
                        taskMapper.updateById(t);
                    }
                }
                return next;
            }
        };

        ScheduledFuture<?> future = taskScheduler.schedule(() -> {
            ScheduledTask task = taskMapper.selectById(taskId);
            if (task != null && "ACTIVE".equals(task.getStatus())) {
                if (isInExcludeTime(task)) {
                    log.info("任务 [{}] 处于静默时间段({}-{})，跳过执行", task.getName(),
                            task.getExcludeTimeStart(), task.getExcludeTimeEnd());
                    return;
                }
                executeTaskInternal(task);
            }
        }, trigger);

        scheduledFutures.put(taskId, future);
    }

    private void unregisterTask(Long taskId) {
        ScheduledFuture<?> future = scheduledFutures.remove(taskId);
        if (future != null) {
            future.cancel(false);
        }
    }

    private void executeTaskInternal(ScheduledTask task) {
        LocalDateTime execTime = LocalDateTime.now();
        TaskExecutionLog execLog = new TaskExecutionLog();
        execLog.setTaskId(task.getId());
        execLog.setTaskName(task.getName());
        execLog.setContent(task.getContent());
        execLog.setExecuteTime(execTime);

        try {
            log.info("Executing task [{}]: {}", task.getName(),
                task.getContent() != null && task.getContent().length() > 100
                    ? task.getContent().substring(0, 100) + "..."
                    : task.getContent());

            if (task.getMethodBeanName() != null && task.getMethodSignature() != null) {
                Object bean = applicationContext.getBean(task.getMethodBeanName());
                Method targetMethod = findMethod(bean, task.getMethodSignature());
                if (targetMethod == null) {
                    throw new RuntimeException("找不到方法: " + task.getMethodSignature());
                }
                Object[] args = buildMethodArgs(task, targetMethod);
                Object result = targetMethod.invoke(bean, args);
                execLog.setStatus("SUCCESS");
                execLog.setResult("执行成功: " + (result != null ? result.toString() : "无返回值"));
            } else {
                execLog.setStatus("SUCCESS");
                execLog.setResult("执行成功（待实现具体逻辑）");
            }
        } catch (Exception e) {
            log.error("Task execution failed: {}", task.getName(), e);
            execLog.setStatus("FAILED");
            execLog.setResult("执行失败: " + e.getMessage());
        }

        logMapper.insert(execLog);

        LambdaUpdateWrapper<ScheduledTask> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(ScheduledTask::getId, task.getId())
            .set(ScheduledTask::getLastExecuteTime, execTime)
            .setSql("execute_count = execute_count + 1");
        taskMapper.update(null, updateWrapper);
    }

    private boolean isInExcludeTime(ScheduledTask task) {
        String start = task.getExcludeTimeStart();
        String end = task.getExcludeTimeEnd();
        if (start == null || start.isEmpty() || end == null || end.isEmpty()) {
            return false;
        }
        try {
            String now = java.time.LocalTime.now().toString().substring(0, 5);
            if (start.compareTo(end) < 0) {
                return now.compareTo(start) >= 0 && now.compareTo(end) < 0;
            } else {
                return now.compareTo(start) >= 0 || now.compareTo(end) < 0;
            }
        } catch (Exception e) {
            return false;
        }
    }

    private Method findMethod(Object bean, String methodName) {
        for (Method method : bean.getClass().getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                return method;
            }
        }
        if (bean.getClass().getSuperclass() != null) {
            for (Method method : bean.getClass().getSuperclass().getDeclaredMethods()) {
                if (method.getName().equals(methodName)) {
                    return method;
                }
            }
        }
        return null;
    }

    private Object[] buildMethodArgs(ScheduledTask task, Method method) {
        Parameter[] params = method.getParameters();
        Object[] args = new Object[params.length];

        if (task.getMethodParams() == null || task.getMethodParams().isEmpty()) {
            return args;
        }

        JSONArray paramArray = JSON.parseArray(task.getMethodParams());
        for (int i = 0; i < params.length; i++) {
            String paramName = params[i].getName();
            Class<?> paramType = params[i].getType();

            String value = null;
            for (int j = 0; j < paramArray.size(); j++) {
                JSONObject obj = paramArray.getJSONObject(j);
                if (paramName.equals(obj.getString("name"))) {
                    value = obj.getString("value");
                    break;
                }
            }

            if (value == null || value.isEmpty()) {
                args[i] = getDefaultValue(paramType);
            } else {
                args[i] = convertValue(value, paramType);
            }
        }
        return args;
    }

    private Object getDefaultValue(Class<?> type) {
        if (type == String.class) return "";
        if (type == Integer.class || type == int.class) return 0;
        if (type == Long.class || type == long.class) return 0L;
        if (type == Double.class || type == double.class) return 0.0;
        if (type == Boolean.class || type == boolean.class) return false;
        return null;
    }

    private Object convertValue(String value, Class<?> type) {
        if (type == String.class) return value;
        if (type == Integer.class || type == int.class) return Integer.parseInt(value);
        if (type == Long.class || type == long.class) return Long.parseLong(value);
        if (type == Double.class || type == double.class) return Double.parseDouble(value);
        if (type == Boolean.class || type == boolean.class) return Boolean.parseBoolean(value);
        return value;
    }
}
