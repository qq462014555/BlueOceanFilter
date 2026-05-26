package com.blueocean.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 通用图片文件操作：列出、替换、创建、删除、重排序
 */
@Service
public class ImageFileService {

    private static final Logger log = LoggerFactory.getLogger(ImageFileService.class);

    /**
     * 列出目录下指定后缀的图片文件（自动清理残留的 __reorder_tmp_* 临时文件）
     */
    public List<String> listImagePaths(Path dir, String subDirName) {
        List<String> paths = new ArrayList<>();
        Path subDir = dir.resolve(subDirName);
        if (!Files.exists(subDir)) return paths;

        // 清理残留的临时文件
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(subDir,
                p -> p.getFileName().toString().startsWith("__reorder_tmp_"))) {
            for (Path tmp : stream) {
                try { Files.delete(tmp); log.info("已清理残留临时文件: {}", tmp.getFileName()); }
                catch (IOException ignored) {}
            }
        } catch (IOException ignored) {}

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(subDir,
                p -> {
                    String name = p.getFileName().toString().toLowerCase();
                    return (name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".jpeg") || name.endsWith(".webp"))
                        && !name.startsWith("__reorder_tmp_");
                })) {
            for (Path p : stream) {
                paths.add(p.toString());
            }
            paths.sort(String::compareTo);
        } catch (IOException e) {
            log.warn("读取 {} 目录失败: {}", subDirName, e.getMessage());
        }
        return paths;
    }

    /**
     * 替换现有文件内容
     */
    public void replaceFile(Path targetPath, byte[] bytes) throws IOException {
        if (!Files.exists(targetPath)) {
            throw new IOException("目标文件不存在: " + targetPath);
        }
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(targetPath.toFile())) {
            fos.write(bytes);
            fos.flush();
            fos.getFD().sync();
        }
        log.info("已替换文件: {} ({} bytes)", targetPath, bytes.length);
    }

    /**
     * 在指定路径创建新文件，自动创建父目录
     * 如果目标已存在，取最大数字+1 作为新文件名
     */
    public Path createFileWithAutoNumber(Path targetPath, byte[] bytes) throws IOException {
        // 如果目标不存在，直接创建
        if (!Files.exists(targetPath)) {
            Files.createDirectories(targetPath.getParent());
            Files.write(targetPath, bytes);
            log.info("已创建文件: {} ({} bytes)", targetPath, bytes.length);
            return targetPath;
        }

        // 目标已存在，取最大数字+1
        Path dir = targetPath.getParent();
        String fileName = targetPath.getFileName().toString();
        int dotIdx = fileName.lastIndexOf('.');
        String ext = dotIdx >= 0 ? fileName.substring(dotIdx) : "";
        String baseName = dotIdx >= 0 ? fileName.substring(0, dotIdx) : fileName;
        // 提取前缀和数字模式
        int underscoreIdx = baseName.lastIndexOf('_');
        String prefix = underscoreIdx >= 0 ? baseName.substring(0, underscoreIdx + 1) : baseName;

        int maxNum = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                java.util.regex.Matcher m = java.util.regex.Pattern.compile(prefix + "(\\d{2})").matcher(name);
                if (m.find()) {
                    maxNum = Math.max(maxNum, Integer.parseInt(m.group(1)));
                }
            }
        }

        String newName = prefix + String.format("%02d", maxNum + 1) + ext;
        Path newPath = dir.resolve(newName);
        Files.createDirectories(dir);
        Files.write(newPath, bytes);
        log.info("已创建文件(自动编号): {} ({} bytes)", newPath, bytes.length);
        return newPath;
    }

    /**
     * 在指定路径创建新文件，自动创建父目录
     */
    public void createFile(Path targetPath, byte[] bytes) throws IOException {
        Files.createDirectories(targetPath.getParent());
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(targetPath.toFile())) {
            fos.write(bytes);
            fos.flush();
        }
        log.info("已创建文件: {} ({} bytes)", targetPath, bytes.length);
    }

    /**
     * 删除文件
     */
    public void deleteFile(Path targetPath) throws IOException {
        if (!Files.exists(targetPath)) {
            throw new IOException("文件不存在: " + targetPath);
        }
        Files.delete(targetPath);
        log.info("已删除文件: {}", targetPath);
    }

    /**
     * 通用重排序：先 copy 到临时副本 → 删原文件 → move 到最终位置
     * 临时文件使用 __reorder_tmp_ 前缀，listImagePaths 会自动过滤
     */
    public void reorderFiles(String prefix, List<String> orderedPaths) throws IOException {
        if (orderedPaths.isEmpty()) return;

        Path dir = Paths.get(orderedPaths.get(0)).getParent();

        List<Path> existing = new ArrayList<>();
        for (String path : orderedPaths) {
            Path p = Paths.get(path);
            if (Files.exists(p)) existing.add(p);
        }

        if (existing.isEmpty()) return;

        // 第1步：copy 所有文件到临时副本
        List<Path> copies = new ArrayList<>();
        for (int i = 0; i < existing.size(); i++) {
            Path copyPath = dir.resolve("__reorder_tmp_" + i + getExtension(existing.get(i).getFileName().toString()));
            Files.copy(existing.get(i), copyPath, StandardCopyOption.REPLACE_EXISTING);
            copies.add(copyPath);
        }

        // 第2步：删除所有原文件
        for (Path p : existing) {
            if (Files.exists(p)) Files.delete(p);
        }

        // 第3步：按新顺序 move 到最终位置
        for (int i = 0; i < copies.size(); i++) {
            String newName = prefix + String.format("_%02d", i + 1) + getExtension(copies.get(i).getFileName().toString());
            Path newPath = dir.resolve(newName);
            Files.move(copies.get(i), newPath, StandardCopyOption.ATOMIC_MOVE);
        }

        log.info("已重排序{}: {} 个文件", prefix, copies.size());
    }

    /**
     * 交换两个文件：三步交换法
     */
    public void swapFiles(String filePath1, String filePath2) throws IOException {
        Path path1 = Paths.get(filePath1);
        Path path2 = Paths.get(filePath2);

        if (!Files.exists(path1) || !Files.exists(path2)) {
            throw new IOException("文件不存在");
        }

        Path tmpPath = path1.resolveSibling("__swap_tmp" + System.currentTimeMillis() + getExtension(path1.getFileName().toString()));
        Files.move(path1, tmpPath, StandardCopyOption.ATOMIC_MOVE);
        Files.move(path2, path1, StandardCopyOption.ATOMIC_MOVE);
        Files.move(tmpPath, path2, StandardCopyOption.ATOMIC_MOVE);

        log.info("已交换文件: {} <-> {}", path1.getFileName(), path2.getFileName());
    }

    /**
     * 检查目录名是否是已知子目录
     */
    public boolean isKnownSubDir(String dirName) {
        return dirName.equals("主图") || dirName.equals("详情图") || dirName.equals("SKU图") || dirName.equals("视频");
    }

    private String getExtension(String fileName) {
        int dotIdx = fileName.lastIndexOf('.');
        return dotIdx >= 0 ? fileName.substring(dotIdx) : "";
    }
}
