package com.blueocean.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 主图专属操作：5槽位管理、排序、交换
 */
@Service
public class MainImageService {

    private static final Logger log = LoggerFactory.getLogger(MainImageService.class);
    private static final int MAIN_IMAGE_SLOTS = 5;
    private static final String SUB_DIR = "主图";
    private static final String PREFIX = "主图";

    private final ImageFileService imageFileService;
    private final ReentrantLock reorderLock = new ReentrantLock();

    public MainImageService(ImageFileService imageFileService) {
        this.imageFileService = imageFileService;
    }

    public List<String> listWithSlots(Path prodDir) {
        List<String> existing = imageFileService.listImagePaths(prodDir, SUB_DIR);
        List<String> slots = new ArrayList<>(Collections.nCopies(MAIN_IMAGE_SLOTS, null));

        for (String path : existing) {
            String name = new File(path).getName().toLowerCase();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(PREFIX + "_(\\d{2})").matcher(name);
            if (m.find()) {
                int slotIdx = Integer.parseInt(m.group(1)) - 1;
                if (slotIdx >= 0 && slotIdx < MAIN_IMAGE_SLOTS) {
                    slots.set(slotIdx, path);
                }
            }
        }

        int nextSlot = 0;
        for (int i = 0; i < existing.size(); i++) {
            String path = existing.get(i);
            boolean placed = false;
            for (int j = 0; j < MAIN_IMAGE_SLOTS; j++) {
                if (slots.get(j) != null && slots.get(j).equals(path)) { placed = true; break; }
            }
            if (!placed) {
                while (nextSlot < MAIN_IMAGE_SLOTS && slots.get(nextSlot) != null) nextSlot++;
                if (nextSlot < MAIN_IMAGE_SLOTS) { slots.set(nextSlot, path); nextSlot++; }
            }
        }

        return slots;
    }

    public void reorder(String productDir, List<String> orderedPaths) throws IOException {
        reorderLock.lock();
        try {
            imageFileService.reorderFiles(PREFIX, orderedPaths);
        } finally {
            reorderLock.unlock();
        }
    }

    public void swap(String filePath1, String filePath2) throws IOException {
        imageFileService.swapFiles(filePath1, filePath2);
    }

    public String getSubDir() { return SUB_DIR; }
    public String getPrefix() { return PREFIX; }
    public int getSlotCount() { return MAIN_IMAGE_SLOTS; }
}
