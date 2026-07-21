package com.blueocean.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ReferenceService {

    private static final Logger log = LoggerFactory.getLogger(ReferenceService.class);

    private static final Map<String, List<String>> REF_IMAGE_CACHE = new ConcurrentHashMap<>();

    public void saveRefImages(String productDir, List<String> images) {
        REF_IMAGE_CACHE.put(productDir, new ArrayList<>(images));
    }

    public List<String> getRefImages(String productDir) {
        List<String> cached = REF_IMAGE_CACHE.get(productDir);
        return cached != null ? new ArrayList<>(cached) : new ArrayList<>();
    }

    public void clearRefImages(String productDir) {
        REF_IMAGE_CACHE.remove(productDir);
    }

    public List<Map<String, Object>> listRefImages(String productDir) {
        List<Map<String, Object>> images = new ArrayList<>();
        Path dir = Paths.get(productDir, "参考图");
        if (Files.exists(dir)) {
            try (var files = Files.list(dir)) {
                files.filter(f -> f.toString().toLowerCase().endsWith(".jpg")
                                || f.toString().toLowerCase().endsWith(".png"))
                     .sorted().forEach(f -> {
                         Map<String, Object> item = new LinkedHashMap<>();
                         item.put("path", f.toString());
                         item.put("name", f.getFileName().toString());
                         images.add(item);
                     });
            } catch (Exception ignored) {}
        }
        return images;
    }
}
