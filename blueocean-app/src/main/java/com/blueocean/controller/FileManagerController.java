package com.blueocean.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

@RestController
@RequestMapping("/api/file")
@Slf4j
public class FileManagerController {

    private static final String BASE_DIR = "C:\\Users\\46201\\Documents\\无极RPA文件处理";

    @GetMapping("/list")
    public Map<String, Object> list(@RequestParam(defaultValue = "") String path) {
        Map<String, Object> result = new HashMap<>();
        Path target = path.isEmpty() ? Paths.get(BASE_DIR) : Paths.get(BASE_DIR, path);

        if (!Files.exists(target)) {
            result.put("error", "目录不存在");
            return result;
        }

        List<Map<String, Object>> items = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(target)) {
            for (Path entry : stream) {
                Map<String, Object> item = new HashMap<>();
                String name = entry.getFileName().toString();
                item.put("name", name);
                item.put("isDirectory", Files.isDirectory(entry));
                items.add(item);
            }
        } catch (IOException e) {
            result.put("error", "读取目录失败: " + e.getMessage());
            return result;
        }

        items.sort((a, b) -> {
            if ((boolean) a.get("isDirectory") != (boolean) b.get("isDirectory")) {
                return (boolean) a.get("isDirectory") ? -1 : 1;
            }
            return ((String) a.get("name")).compareToIgnoreCase((String) b.get("name"));
        });

        result.put("path", path);
        result.put("items", items);
        return result;
    }

    @PostMapping("/mkdir")
    public Map<String, Object> mkdir(@RequestBody Map<String, String> request) {
        Map<String, Object> result = new HashMap<>();
        String path = request.getOrDefault("path", "");
        String name = request.get("name");

        if (name == null || name.isEmpty()) {
            result.put("error", "名称不能为空");
            return result;
        }

        Path target = path.isEmpty() ? Paths.get(BASE_DIR, name) : Paths.get(BASE_DIR, path, name);
        try {
            Files.createDirectories(target);
            result.put("success", true);
            result.put("message", "已创建文件夹: " + name);
        } catch (IOException e) {
            result.put("error", "创建失败: " + e.getMessage());
        }
        return result;
    }

    @PostMapping("/createTxt")
    public Map<String, Object> createTxt(@RequestBody Map<String, String> request) {
        Map<String, Object> result = new HashMap<>();
        String path = request.getOrDefault("path", "");
        String name = request.get("name");

        if (name == null || name.isEmpty()) {
            result.put("error", "名称不能为空");
            return result;
        }

        if (!name.endsWith(".txt")) {
            name = name + ".txt";
        }

        Path target = path.isEmpty() ? Paths.get(BASE_DIR, name) : Paths.get(BASE_DIR, path, name);
        try {
            Files.write(target, java.util.List.of(), StandardCharsets.UTF_8);
            result.put("success", true);
            result.put("message", "已创建文件: " + name);
        } catch (IOException e) {
            result.put("error", "创建失败: " + e.getMessage());
        }
        return result;
    }

    @PostMapping("/rename")
    public Map<String, Object> rename(@RequestBody Map<String, String> request) {
        Map<String, Object> result = new HashMap<>();
        String oldPath = request.get("oldPath");
        String newName = request.get("newName");

        if (oldPath == null || newName == null || newName.isEmpty()) {
            result.put("error", "参数不完整");
            return result;
        }

        Path target = Paths.get(BASE_DIR).resolve(oldPath).normalize();
        Path parent = target.getParent();
        Path dest = parent != null ? parent.resolve(newName).normalize() : Paths.get(BASE_DIR).resolve(newName).normalize();

        if (!Files.exists(target)) {
            result.put("error", "文件/文件夹不存在: " + target.toString());
            return result;
        }

        try {
            Files.move(target, dest, StandardCopyOption.REPLACE_EXISTING);
            result.put("success", true);
            result.put("message", "已重命名: " + oldPath + " → " + newName);
        } catch (IOException e) {
            result.put("error", "重命名失败: " + e.getMessage());
        }
        return result;
    }

    @DeleteMapping("/delete")
    public Map<String, Object> delete(@RequestBody Map<String, String> request) {
        Map<String, Object> result = new HashMap<>();
        String path = request.get("path");

        if (path == null || path.isEmpty()) {
            result.put("error", "路径不能为空");
            return result;
        }

        Path target = Paths.get(BASE_DIR).resolve(path).normalize();
        try {
            if (Files.isDirectory(target)) {
                Files.walkFileTree(target, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }
                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
            } else {
                Files.delete(target);
            }
            result.put("success", true);
            result.put("message", "已删除: " + path);
        } catch (IOException e) {
            result.put("error", "删除失败: " + e.getMessage());
        }
        return result;
    }

    @PostMapping("/open")
    public Map<String, Object> open(@RequestBody Map<String, String> request) {
        Map<String, Object> result = new HashMap<>();
        String path = request.get("path");

        if (path == null || path.isEmpty()) {
            result.put("error", "路径不能为空");
            return result;
        }

        Path target = Paths.get(BASE_DIR).resolve(path).normalize();

        log.info("[FileOpen] path={}, resolved={}, exists={}", path, target.toString(), Files.exists(target));

        if (!Files.exists(target)) {
            result.put("error", "文件/文件夹不存在: " + target.toString());
            return result;
        }

        try {
            new ProcessBuilder("cmd.exe", "/c", "start", "", target.toString()).start();
            result.put("success", true);
            result.put("message", "已打开: " + path);
        } catch (Exception e) {
            log.error("[FileOpen] open failed", e);
            result.put("error", "打开失败: " + e.getMessage());
        }
        return result;
    }
}
