package com.kai.agent.voice;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

/** Copies only the requested engine and shared phonemizer; all bytes are bundled and pinned. */
final class LocalVoicePack {
    static File prepare(Context context, String family) throws Exception {
        if (!family.equals("kitten")) throw new IOException("Unknown voice engine");
        JSONObject catalog;
        try (InputStream input = context.getAssets().open("test-voices/catalog.json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (out.size() + count > 262144) throw new IOException("Voice catalog exceeds limit");
                out.write(buffer, 0, count);
            }
            catalog = new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
        }
        String revision = catalog.getString("revision");
        if (!revision.matches("[a-f0-9]{64}")) throw new IOException("Invalid voice revision");
        File root = new File(context.getNoBackupFilesDir(), "test-voices/" + revision);
        JSONArray files = catalog.getJSONArray("files");
        for (int i = 0; i < files.length(); i++) {
            JSONObject part = files.getJSONObject(i);
            String path = part.getString("path"), hash = part.getString("sha256");
            long size = part.getLong("sizeBytes");
            if (!path.matches("(?:kitten|shared)/[a-zA-Z0-9_ ./+!-]+") ||
                path.contains("..") || !hash.matches("[a-f0-9]{64}") || size <= 0 || size > 200_000_000)
                throw new IOException("Invalid voice asset");
            if (!path.startsWith(family + "/") && !path.startsWith("shared/")) continue;
            File destination = new File(root, path);
            if (valid(destination, size, hash)) continue;
            File parent = destination.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Voice storage unavailable");
            File stage = new File(parent, destination.getName() + ".part");
            try {
                try (InputStream input = context.getAssets().open("test-voices/" + path);
                     OutputStream output = new BufferedOutputStream(new FileOutputStream(stage))) {
                    byte[] buffer = new byte[65536]; int count; long total = 0;
                    while ((count = input.read(buffer)) != -1) {
                        if ((total += count) > size) throw new IOException("Voice asset exceeds limit");
                        output.write(buffer, 0, count);
                    }
                }
                if (!valid(stage, size, hash)) throw new IOException("Voice integrity check failed");
                Files.move(stage.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } finally { stage.delete(); }
        }
        return root;
    }
    /** Retire only app-managed model caches after the replacement Kiki engine is ready. */
    static void removeRetiredPacks(Context context, File active) {
        File base = new File(context.getNoBackupFilesDir(), "test-voices");
        File[] revisions = base.listFiles();
        if (revisions != null) for (File revision : revisions) {
            if (revision.getName().matches("[a-f0-9]{64}") && !revision.equals(active)) deleteOwnedTree(revision);
        }
        deleteOwnedTree(new File(context.getNoBackupFilesDir(), "desktop-voice"));
    }
    private static void deleteOwnedTree(File directory) {
        // walkFileTree does not follow symlinks. Chats, keys and memory are outside these caches.
        try { Files.walkFileTree(directory.toPath(), new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                Files.deleteIfExists(dir); return FileVisitResult.CONTINUE;
            }
        }); } catch (IOException | SecurityException ignored) { /* Cache cleanup must not stop speech. */ }
    }
    static boolean valid(File file, long size, String expected) throws Exception {
        if (!file.isFile() || file.length() != size) return false;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return expected.contentEquals(hex);
    }
}
