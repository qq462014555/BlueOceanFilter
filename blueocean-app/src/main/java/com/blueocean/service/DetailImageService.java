package com.blueocean.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 详情图专属操作
 */
@Service
public class DetailImageService {

    private static final Logger log = LoggerFactory.getLogger(DetailImageService.class);
    private static final String SUB_DIR = "详情图";
    private static final String PREFIX = "详情图";

    private final ImageFileService imageFileService;
    private final ReentrantLock reorderLock = new ReentrantLock();

    public DetailImageService(ImageFileService imageFileService) {
        this.imageFileService = imageFileService;
    }

    public List<String> list(Path prodDir) {
        return imageFileService.listImagePaths(prodDir, SUB_DIR);
    }

    public void reorder(String productDir, List<String> orderedPaths) throws IOException {
        reorderLock.lock();
        try {
            imageFileService.reorderFiles(PREFIX, orderedPaths);
        } finally {
            reorderLock.unlock();
        }
    }

    public String getSubDir() { return SUB_DIR; }
    public String getPrefix() { return PREFIX; }
}
