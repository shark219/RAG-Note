package com.rag.notebook.rag;

import com.rag.notebook.config.ApplicationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class Md5Store {

    private final Path storePath;
    private final Map<String, Map<String, String>> records = new ConcurrentHashMap<>();

    public Md5Store(ApplicationProperties props) {
        this.storePath = Path.of(props.getMd5().getStoreDir());
        try {
            Files.createDirectories(storePath);
            loadFromFile();
        } catch (IOException e) {
            log.warn("Failed to initialize MD5 store: {}", e.getMessage());
        }
    }

    public synchronized boolean exists(String md5, String userId) {
        Map<String, String> userRecords = records.get(userId);
        return userRecords != null && userRecords.containsKey(md5);
    }

    public void save(String md5, String filename, String originalFilename, String userId) {
        records.computeIfAbsent(userId, k -> new ConcurrentHashMap<>())
                .put(md5, filename + "|" + originalFilename);
        saveToFile();
    }

    public void deleteByMd5(String md5, String userId) {
        Map<String, String> userRecords = records.get(userId);
        if (userRecords != null) {
            userRecords.remove(md5);
            saveToFile();
        }
    }

    public void deleteByUser(String userId) {
        records.remove(userId);
        saveToFile();
    }

    public synchronized List<Map<String, String>> getUserRecords(String userId) {
        Map<String, String> userRecords = records.getOrDefault(userId, Map.of());
        List<Map<String, String>> result = new ArrayList<>();
        userRecords.forEach((md5, value) -> {
            String[] parts = value.split("\\|", 2);
            result.add(Map.of(
                    "md5", md5,
                    "filename", parts.length > 0 ? parts[0] : "",
                    "original_filename", parts.length > 1 ? parts[1] : ""
            ));
        });
        return result;
    }

    public synchronized Map<String, String> getRecord(String md5, String userId) {
        Map<String, String> userRecords = records.get(userId);
        if (userRecords == null || !userRecords.containsKey(md5)) return null;
        String[] parts = userRecords.get(md5).split("\\|", 2);
        return Map.of("md5", md5, "filename", parts[0], "original_filename", parts.length > 1 ? parts[1] : "");
    }

    private synchronized void loadFromFile() {
        Path file = storePath.resolve("md5_hex_store.txt");
        if (!Files.exists(file)) return;
        try {
            List<String> lines = Files.readAllLines(file);
            for (String line : lines) {
                String[] parts = line.split("\\|", 4);
                if (parts.length >= 4) {
                    records.computeIfAbsent(parts[3], k -> new ConcurrentHashMap<>())
                            .put(parts[0], parts[1] + "|" + parts[2]);
                }
            }
        } catch (IOException e) {
            log.warn("Failed to load MD5 store: {}", e.getMessage());
        }
    }

    private synchronized void saveToFile() {
        Path file = storePath.resolve("md5_hex_store.txt");
        try (BufferedWriter writer = Files.newBufferedWriter(file)) {
            records.forEach((userId, userRecords) ->
                    userRecords.forEach((md5, value) -> {
                        try {
                            writer.write(md5 + "|" + value + "|" + userId);
                            writer.newLine();
                        } catch (IOException e) {
                            log.warn("Failed to write MD5 record: {}", e.getMessage());
                        }
                    }));
        } catch (IOException e) {
            log.warn("Failed to save MD5 store: {}", e.getMessage());
        }
    }
}
