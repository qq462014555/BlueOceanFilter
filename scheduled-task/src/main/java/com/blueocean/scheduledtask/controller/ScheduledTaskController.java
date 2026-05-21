package com.blueocean.scheduledtask.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.blueocean.scheduledtask.entity.ScheduledTask;
import com.blueocean.scheduledtask.entity.TaskExecutionLog;
import com.blueocean.scheduledtask.service.ScheduledTaskDiscoveryService;
import com.blueocean.scheduledtask.service.ScheduledTaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/scheduled-task")
public class ScheduledTaskController {

    private final ScheduledTaskService taskService;
    private final ScheduledTaskDiscoveryService discoveryService;

    public ScheduledTaskController(ScheduledTaskService taskService,
                                   ScheduledTaskDiscoveryService discoveryService) {
        this.taskService = taskService;
        this.discoveryService = discoveryService;
    }

    @GetMapping("/discover")
    public ResponseEntity<Map<String, Object>> discover() {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> methods = discoveryService.discoverTasks();
        result.put("methods", methods);
        result.put("total", methods.size());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        Map<String, Object> result = new HashMap<>();
        Page<ScheduledTask> pageResult = taskService.listTasks(page, size, status);
        result.put("records", pageResult.getRecords());
        result.put("total", pageResult.getTotal());
        result.put("current", pageResult.getCurrent());
        result.put("size", pageResult.getSize());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getById(@PathVariable Long id) {
        Map<String, Object> result = new HashMap<>();
        ScheduledTask task = taskService.getTask(id);
        if (task == null) {
            result.put("error", "任务不存在");
            return ResponseEntity.notFound().build();
        }
        result.put("task", task);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/create")
    public ResponseEntity<Map<String, Object>> create(@RequestBody ScheduledTask task) {
        Map<String, Object> result = new HashMap<>();
        if (task.getName() == null || task.getName().trim().isEmpty()) {
            result.put("error", "任务名称不能为空");
            return ResponseEntity.badRequest().body(result);
        }
        ScheduledTask created = taskService.createTask(task);
        result.put("task", created);
        result.put("message", "任务创建成功");
        return ResponseEntity.ok(result);
    }

    @PostMapping("/update")
    public ResponseEntity<Map<String, Object>> update(@RequestBody ScheduledTask task) {
        Map<String, Object> result = new HashMap<>();
        if (task.getId() == null) {
            result.put("error", "任务ID不能为空");
            return ResponseEntity.badRequest().body(result);
        }
        ScheduledTask updated = taskService.updateTask(task);
        if (updated == null) {
            result.put("error", "任务不存在");
            return ResponseEntity.notFound().build();
        }
        result.put("task", updated);
        result.put("message", "任务更新成功");
        return ResponseEntity.ok(result);
    }

    @PostMapping("/delete/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        Map<String, Object> result = new HashMap<>();
        boolean success = taskService.deleteTask(id);
        if (!success) {
            result.put("error", "任务不存在");
            return ResponseEntity.notFound().build();
        }
        result.put("message", "任务已删除");
        return ResponseEntity.ok(result);
    }

    @PostMapping("/activate/{id}")
    public ResponseEntity<Map<String, Object>> activate(@PathVariable Long id) {
        Map<String, Object> result = new HashMap<>();
        boolean success = taskService.activateTask(id);
        if (!success) {
            result.put("error", "任务不存在或未设置定时表达式");
            return ResponseEntity.badRequest().body(result);
        }
        result.put("message", "任务已启动");
        return ResponseEntity.ok(result);
    }

    @PostMapping("/pause/{id}")
    public ResponseEntity<Map<String, Object>> pause(@PathVariable Long id) {
        Map<String, Object> result = new HashMap<>();
        boolean success = taskService.pauseTask(id);
        if (!success) {
            result.put("error", "任务不存在");
            return ResponseEntity.notFound().build();
        }
        result.put("message", "任务已暂停");
        return ResponseEntity.ok(result);
    }

    @PostMapping("/execute/{id}")
    public ResponseEntity<Map<String, Object>> executeNow(@PathVariable Long id) {
        Map<String, Object> result = new HashMap<>();
        boolean success = taskService.executeNow(id);
        if (!success) {
            result.put("error", "任务不存在");
            return ResponseEntity.notFound().build();
        }
        result.put("message", "任务已触发执行");
        return ResponseEntity.ok(result);
    }

    @GetMapping("/logs/{taskId}")
    public ResponseEntity<Map<String, Object>> getLogs(
            @PathVariable Long taskId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Map<String, Object> result = new HashMap<>();
        Page<TaskExecutionLog> pageResult = taskService.getExecutionLogs(taskId, page, size);
        result.put("records", pageResult.getRecords());
        result.put("total", pageResult.getTotal());
        result.put("current", pageResult.getCurrent());
        result.put("size", pageResult.getSize());
        return ResponseEntity.ok(result);
    }
}
