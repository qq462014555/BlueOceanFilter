package com.blueocean.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;

/**
 * 视频专属操作
 */
@Service
public class VideoService {

    private static final Logger log = LoggerFactory.getLogger(VideoService.class);
    private static final String SUB_DIR = "视频";
    private static final String VIDEO_FILE = "视频.mp4";

    private final ImageFileService imageFileService;

    public VideoService(ImageFileService imageFileService) {
        this.imageFileService = imageFileService;
    }

    /**
     * 查找商品目录下的视频文件
     */
    public String findVideo(Path prodDir) {
        Path videoDir = prodDir.resolve(SUB_DIR);
        if (!Files.exists(videoDir)) return null;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(videoDir,
                p -> {
                    String name = p.getFileName().toString().toLowerCase();
                    return name.endsWith(".mp4") || name.endsWith(".webm");
                })) {
            for (Path p : stream) {
                return p.toString();
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    /**
     * 创建视频文件
     */
    public String createVideo(String targetPath, byte[] bytes) throws IOException {
        Path path = Paths.get(targetPath);
        imageFileService.createFile(path, bytes);
        return path.toString();
    }

    /**
     * 替换视频文件
     */
    public void replaceVideo(String targetPath, byte[] bytes) throws IOException {
        Path path = Paths.get(targetPath);
        if (!Files.exists(path)) {
            throw new IOException("目标文件不存在: " + targetPath);
        }
        imageFileService.replaceFile(path, bytes);
    }

    /**
     * 删除视频文件
     */
    public void deleteVideo(String targetPath) throws IOException {
        imageFileService.deleteFile(Paths.get(targetPath));
    }

    public String getSubDir() { return SUB_DIR; }
}
