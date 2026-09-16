package com.locutortts.app;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

final class ProjectStore {
    private ProjectStore() {}

    static File directory(Context context) throws Exception {
        File dir = new File(context.getFilesDir(), "projects");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("No se pudo crear la carpeta de proyectos.");
        return dir;
    }

    static File fileFor(Context context, String projectName) throws Exception {
        String safe = projectName == null ? "" : projectName.trim();
        safe = safe.replaceAll("[\\\\/:*?\"<>|]+", "_").replaceAll("\\s+", " ").trim();
        if (safe.isEmpty()) safe = "Proyecto";
        if (safe.length() > 80) safe = safe.substring(0, 80).trim();
        return new File(directory(context), safe + ".json");
    }

    static boolean exists(Context context, String projectName) {
        try {
            return fileFor(context, projectName).exists();
        } catch (Exception e) {
            return false;
        }
    }

    static void save(Context context,
                     String projectName,
                     String text,
                     String audioName,
                     String voiceName,
                     String voiceLabel,
                     int speedProgress,
                     int pitchProgress) throws Exception {
        JSONObject project = new JSONObject();
        project.put("schema", 1);
        project.put("projectName", projectName);
        project.put("text", text);
        project.put("audioName", audioName);
        project.put("voiceName", voiceName == null ? "" : voiceName);
        project.put("voiceLabel", voiceLabel == null ? "" : voiceLabel);
        project.put("speedProgress", speedProgress);
        project.put("pitchProgress", pitchProgress);
        project.put("updatedAt", System.currentTimeMillis());

        File destination = fileFor(context, projectName);
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(destination, false), StandardCharsets.UTF_8)) {
            writer.write(project.toString(2));
            writer.flush();
        }
    }
}
