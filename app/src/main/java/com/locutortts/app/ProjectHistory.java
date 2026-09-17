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

final class ProjectHistory {
    private static final int MAX_REVISIONS = 20;
    private static final long REGULAR_CHECKPOINT_MS = 60_000L;

    private ProjectHistory() {}

    static final class Revision {
        final String projectName;
        final String text;
        final String audioName;
        final String voiceName;
        final String voiceLabel;
        final int speedProgress;
        final int pitchProgress;
        final long createdAt;
        final File file;

        Revision(String projectName,
                 String text,
                 String audioName,
                 String voiceName,
                 String voiceLabel,
                 int speedProgress,
                 int pitchProgress,
                 long createdAt,
                 File file) {
            this.projectName = projectName;
            this.text = text;
            this.audioName = audioName;
            this.voiceName = voiceName;
            this.voiceLabel = voiceLabel;
            this.speedProgress = speedProgress;
            this.pitchProgress = pitchProgress;
            this.createdAt = createdAt;
            this.file = file;
        }
    }

    static void captureIfNeeded(Context context,
                                String projectName,
                                String newText,
                                String newAudioName,
                                String newVoiceName,
                                String newVoiceLabel,
                                int newSpeed,
                                int newPitch) throws Exception {
        if (!ProjectStore.exists(context, projectName)) return;

        ProjectStore.Project current = ProjectStore.load(context, projectName);
        if (same(current, newText, newAudioName, newVoiceName, newVoiceLabel, newSpeed, newPitch)) return;

        long now = System.currentTimeMillis();
        long latest = latestRevisionTime(context, projectName);
        String before = current.text == null ? "" : current.text;
        String after = newText == null ? "" : newText;

        boolean becameEmpty = !before.trim().isEmpty() && after.trim().isEmpty();
        boolean largeReduction = before.length() >= 30
                && after.length() + 20 < before.length()
                && after.length() < (int) (before.length() * 0.85f);
        boolean timedCheckpoint = !before.trim().isEmpty()
                && (latest == 0L || now - latest >= REGULAR_CHECKPOINT_MS);

        if (becameEmpty || largeReduction || timedCheckpoint) {
            writeRevision(context, projectName, current, now);
        }
    }

    static void checkpoint(Context context, String projectName) throws Exception {
        if (!ProjectStore.exists(context, projectName)) return;
        ProjectStore.Project current = ProjectStore.load(context, projectName);
        writeRevision(context, projectName, current, System.currentTimeMillis());
    }

    static List<Revision> list(Context context, String projectName) throws Exception {
        File dir = historyDirectory(context, projectName, false);
        if (dir == null || !dir.exists()) return Collections.emptyList();

        File[] files = dir.listFiles((parent, name) -> name.toLowerCase().endsWith(".json"));
        if (files == null || files.length == 0) return Collections.emptyList();

        List<Revision> revisions = new ArrayList<>();
        for (File file : files) {
            try {
                revisions.add(readRevision(file));
            } catch (Exception ignored) {
                // Una revisión dañada no debe bloquear las demás.
            }
        }
        revisions.sort(Comparator.comparingLong((Revision r) -> r.createdAt).reversed());
        return revisions;
    }

    static ProjectStore.Project restore(Context context, String currentProjectName, Revision revision) throws Exception {
        if (revision == null) throw new Exception("Versión inválida.");
        checkpoint(context, currentProjectName);
        ProjectStore.save(context,
                currentProjectName,
                revision.text,
                revision.audioName,
                revision.voiceName,
                revision.voiceLabel,
                revision.speedProgress,
                revision.pitchProgress);
        return ProjectStore.load(context, currentProjectName);
    }

    static void renameHistory(Context context, String oldName, String newName) throws Exception {
        File source = historyDirectory(context, oldName, false);
        if (source == null || !source.exists()) return;

        File destination = historyDirectory(context, newName, true);
        if (source.equals(destination)) return;

        File[] files = source.listFiles();
        if (files != null) {
            for (File file : files) {
                if (!file.isFile()) continue;
                File target = uniqueFile(destination, file.getName());
                if (!file.renameTo(target)) {
                    copyFile(file, target);
                    if (!file.delete()) throw new Exception("No se pudo mover una versión del historial.");
                }
            }
        }
        if (!source.delete() && source.exists()) {
            throw new Exception("El proyecto se renombró, pero no se pudo mover todo su historial.");
        }
        trim(context, newName);
    }

    static void deleteHistory(Context context, String projectName) throws Exception {
        File dir = historyDirectory(context, projectName, false);
        if (dir == null || !dir.exists()) return;
        deleteRecursively(dir);
        if (dir.exists()) throw new Exception("No se pudo borrar el historial del proyecto.");
    }

    static void clearHistory(Context context, String projectName) throws Exception {
        deleteHistory(context, projectName);
    }

    private static boolean same(ProjectStore.Project current,
                                String text,
                                String audioName,
                                String voiceName,
                                String voiceLabel,
                                int speed,
                                int pitch) {
        return safe(current.text).equals(safe(text))
                && safe(current.audioName).equals(safe(audioName))
                && safe(current.voiceName).equals(safe(voiceName))
                && safe(current.voiceLabel).equals(safe(voiceLabel))
                && current.speedProgress == speed
                && current.pitchProgress == pitch;
    }

    private static boolean same(Revision revision, ProjectStore.Project project) {
        return safe(revision.text).equals(safe(project.text))
                && safe(revision.audioName).equals(safe(project.audioName))
                && safe(revision.voiceName).equals(safe(project.voiceName))
                && safe(revision.voiceLabel).equals(safe(project.voiceLabel))
                && revision.speedProgress == project.speedProgress
                && revision.pitchProgress == project.pitchProgress;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static long latestRevisionTime(Context context, String projectName) throws Exception {
        List<Revision> revisions = list(context, projectName);
        return revisions.isEmpty() ? 0L : revisions.get(0).createdAt;
    }

    private static void writeRevision(Context context,
                                      String projectName,
                                      ProjectStore.Project project,
                                      long timestamp) throws Exception {
        List<Revision> existing = list(context, projectName);
        if (!existing.isEmpty() && same(existing.get(0), project)) return;

        File dir = historyDirectory(context, projectName, true);
        File destination = uniqueFile(dir, "rev_" + timestamp + ".json");

        JSONObject object = new JSONObject();
        object.put("schema", 1);
        object.put("projectName", projectName);
        object.put("text", safe(project.text));
        object.put("audioName", safe(project.audioName));
        object.put("voiceName", safe(project.voiceName));
        object.put("voiceLabel", safe(project.voiceLabel));
        object.put("speedProgress", project.speedProgress);
        object.put("pitchProgress", project.pitchProgress);
        object.put("createdAt", timestamp);

        AtomicFile atomic = new AtomicFile(destination);
        FileOutputStream stream = null;
        try {
            stream = atomic.startWrite();
            OutputStreamWriter writer = new OutputStreamWriter(stream, StandardCharsets.UTF_8);
            writer.write(object.toString(2));
            writer.flush();
            stream.getFD().sync();
            atomic.finishWrite(stream);
            stream = null;
        } catch (Exception e) {
            if (stream != null) atomic.failWrite(stream);
            throw e;
        }
        trim(context, projectName);
    }

    private static Revision readRevision(File file) throws Exception {
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
        return new Revision(
                object.optString("projectName", "Proyecto"),
                object.optString("text", ""),
                object.optString("audioName", "locucion"),
                object.optString("voiceName", ""),
                object.optString("voiceLabel", "Voz predeterminada · Español México"),
                clamp(object.optInt("speedProgress", 50), 0, 150),
                clamp(object.optInt("pitchProgress", 50), 0, 150),
                object.optLong("createdAt", file.lastModified()),
                file);
    }

    private static File historyRoot(Context context, boolean create) throws Exception {
        File root = new File(context.getFilesDir(), "project_history");
        if (create && !root.exists() && !root.mkdirs()) {
            throw new Exception("No se pudo crear el historial de proyectos.");
        }
        return root;
    }

    private static File historyDirectory(Context context, String projectName, boolean create) throws Exception {
        File root = historyRoot(context, create);
        if (!root.exists() && !create) return null;
        File dir = new File(root, safeProjectName(projectName));
        if (create && !dir.exists() && !dir.mkdirs()) {
            throw new Exception("No se pudo crear el historial de este proyecto.");
        }
        return dir;
    }

    private static String safeProjectName(String projectName) {
        String safe = projectName == null ? "" : projectName.trim();
        safe = safe.replaceAll("[\\\\/:*?\"<>|]+", "_").replaceAll("\\s+", " ").trim();
        if (safe.isEmpty()) safe = "Proyecto";
        if (safe.length() > 80) safe = safe.substring(0, 80).trim();
        return safe;
    }

    private static File uniqueFile(File dir, String preferredName) {
        File candidate = new File(dir, preferredName);
        if (!candidate.exists()) return candidate;
        String base = preferredName.endsWith(".json")
                ? preferredName.substring(0, preferredName.length() - 5)
                : preferredName;
        int i = 1;
        while (candidate.exists()) {
            candidate = new File(dir, base + "_" + i + ".json");
            i++;
        }
        return candidate;
    }

    private static void trim(Context context, String projectName) throws Exception {
        List<Revision> revisions = list(context, projectName);
        for (int i = MAX_REVISIONS; i < revisions.size(); i++) {
            AtomicFile atomic = new AtomicFile(revisions.get(i).file);
            atomic.delete();
        }
    }

    private static void copyFile(File source, File destination) throws Exception {
        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            out.flush();
            out.getFD().sync();
        }
    }

    private static void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        file.delete();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
