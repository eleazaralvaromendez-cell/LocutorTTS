package com.locutortts.app;

import android.content.Context;
import android.util.AtomicFile;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

final class ProjectStore {
    private ProjectStore() {}

    static final class Project {
        final String projectName;
        final String text;
        final String audioName;
        final String voiceName;
        final String voiceLabel;
        final int speedProgress;
        final int pitchProgress;
        final long updatedAt;
        final File file;

        Project(String projectName,
                String text,
                String audioName,
                String voiceName,
                String voiceLabel,
                int speedProgress,
                int pitchProgress,
                long updatedAt,
                File file) {
            this.projectName = projectName;
            this.text = text;
            this.audioName = audioName;
            this.voiceName = voiceName;
            this.voiceLabel = voiceLabel;
            this.speedProgress = speedProgress;
            this.pitchProgress = pitchProgress;
            this.updatedAt = updatedAt;
            this.file = file;
        }
    }

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
            File file = fileFor(context, projectName);
            return file.exists() || new File(file.getPath() + ".bak").exists();
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
        AtomicFile atomic = new AtomicFile(destination);
        FileOutputStream stream = null;
        try {
            stream = atomic.startWrite();
            OutputStreamWriter writer = new OutputStreamWriter(stream, StandardCharsets.UTF_8);
            writer.write(project.toString(2));
            writer.flush();
            stream.getFD().sync();
            atomic.finishWrite(stream);
            stream = null;
        } catch (Exception e) {
            if (stream != null) atomic.failWrite(stream);
            throw e;
        }
    }

    static Project rename(Context context, Project project, String newName) throws Exception {
        if (project == null) throw new Exception("Proyecto inválido.");
        String cleaned = newName == null ? "" : newName.trim();
        if (cleaned.isEmpty()) throw new Exception("El nombre no puede estar vacío.");

        File destination = fileFor(context, cleaned);
        if (!destination.equals(project.file) && exists(context, cleaned)) {
            throw new Exception("Ya existe un proyecto con ese nombre.");
        }

        save(context, cleaned, project.text, project.audioName, project.voiceName,
                project.voiceLabel, project.speedProgress, project.pitchProgress);

        if (!destination.equals(project.file)) {
            AtomicFile oldAtomic = new AtomicFile(project.file);
            oldAtomic.delete();
            if (project.file.exists()) {
                new AtomicFile(destination).delete();
                throw new Exception("No se pudo completar el cambio de nombre.");
            }
        }
        return load(destination);
    }

    static void delete(Project project) throws Exception {
        if (project == null) throw new Exception("Proyecto inválido.");
        AtomicFile atomic = new AtomicFile(project.file);
        atomic.delete();
        if (project.file.exists() || new File(project.file.getPath() + ".bak").exists()) {
            throw new Exception("No se pudo eliminar el proyecto.");
        }
    }

    static List<Project> list(Context context) throws Exception {
        File[] files = directory(context).listFiles((dir, name) -> name.toLowerCase().endsWith(".json"));
        if (files == null || files.length == 0) return Collections.emptyList();

        List<Project> projects = new ArrayList<>();
        for (File file : files) {
            try {
                projects.add(load(file));
            } catch (Exception ignored) {
                // Un archivo dañado no debe impedir abrir los demás proyectos.
            }
        }
        projects.sort(Comparator.comparingLong((Project p) -> p.updatedAt).reversed());
        return projects;
    }

    static Project load(Context context, String projectName) throws Exception {
        return load(fileFor(context, projectName));
    }

    private static Project load(File file) throws Exception {
        String json;
        AtomicFile atomic = new AtomicFile(file);
        try (FileInputStream in = atomic.openRead();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            json = new String(out.toByteArray(), StandardCharsets.UTF_8);
        }

        JSONObject object = new JSONObject(json);
        String fallbackName = file.getName().replaceFirst("\\.json$", "");
        String name = object.optString("projectName", fallbackName);
        String text = object.optString("text", "");
        String audioName = object.optString("audioName", "locucion");
        String voiceName = object.optString("voiceName", "");
        String voiceLabel = object.optString("voiceLabel", "Voz predeterminada · Español México");
        int speed = clamp(object.optInt("speedProgress", 50), 0, 150);
        int pitch = clamp(object.optInt("pitchProgress", 50), 0, 150);
        long updatedAt = object.optLong("updatedAt", file.lastModified());

        return new Project(name, text, audioName, voiceName, voiceLabel, speed, pitch, updatedAt, file);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
