/*
 * Global Connect unified offline data installer.
 * Copyright (C) 2026 Global Connect.
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2.
 */
package com.github.olga_yakovleva.rhvoice.android;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Process;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class BundledDataInstaller {
    private static final String TAG = "RHVoiceBundledData";
    private static final String MANIFEST_ASSET = "bundled-manifest.txt";
    private static final String DATA_ASSET_ROOT = "bundled-data/";
    private static final String ROOT_DIRECTORY = "rhvoice-data";
    private static final String READY_FILE = "READY.sha256";
    private static final Object PROCESS_LOCK = new Object();

    private static volatile File installedDirectory;

    private BundledDataInstaller() {
    }

    public static File ensureInstalled(Context context) throws IOException {
        File cached = installedDirectory;
        if (cached != null) {
            return cached;
        }

        synchronized (PROCESS_LOCK) {
            cached = installedDirectory;
            if (cached != null) {
                return cached;
            }

            Manifest manifest = readManifest(context.getAssets());
            if (!BuildConfig.BUNDLED_DATA_VERSION.equals(manifest.version)) {
                throw new IOException(
                    "Build/manifest version mismatch: " + BuildConfig.BUNDLED_DATA_VERSION +
                        " != " + manifest.version
                );
            }

            File root = new File(context.getFilesDir(), ROOT_DIRECTORY);
            if (!root.isDirectory() && !root.mkdirs()) {
                throw new IOException("Cannot create data root " + root);
            }

            File lockFile = new File(root, "install.lock");
            try (
                RandomAccessFile randomAccessFile = new RandomAccessFile(lockFile, "rw");
                FileChannel channel = randomAccessFile.getChannel();
                FileLock ignored = channel.lock()
            ) {
                File target = new File(root, "bundle-" + manifest.version);
                Log.i(TAG, "Validating bundled resources version=" + manifest.version);
                if (!validate(target, manifest)) {
                    install(context.getAssets(), root, target, manifest);
                }
                if (!validate(target, manifest)) {
                    throw new IOException("Installed resource validation failed");
                }
                removeObsoleteDirectories(root, target);
                installedDirectory = target;
                Log.i(
                    TAG,
                    "Bundled resources ready version=" + manifest.version +
                        " files=" + manifest.entries.size()
                );
                return target;
            }
        }
    }

    public static List<String> getResourcePaths(File root) {
        List<String> paths = new ArrayList<>();
        paths.add(new File(root, "languages/English").getAbsolutePath());
        paths.add(new File(root, "languages/Spanish").getAbsolutePath());
        paths.add(new File(root, "voices/slt").getAbsolutePath());
        paths.add(new File(root, "voices/Mateo").getAbsolutePath());
        return paths;
    }

    private static Manifest readManifest(AssetManager assets) throws IOException {
        byte[] bytes;
        try (InputStream input = assets.open(MANIFEST_ASSET, AssetManager.ACCESS_STREAMING)) {
            bytes = readAll(input);
        }
        String digest = sha256(bytes);
        String content = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = content.split("\\r?\\n");
        if (lines.length == 0) {
            throw new IOException("Empty bundled resource manifest");
        }
        String[] header = lines[0].split("\\t", -1);
        if (header.length != 2 || !"bundle.version".equals(header[0]) || header[1].isEmpty()) {
            throw new IOException("Invalid bundled resource manifest header");
        }

        List<Entry> entries = new ArrayList<>();
        Set<String> paths = new HashSet<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty()) {
                continue;
            }
            String[] fields = lines[i].split("\\t", -1);
            if (fields.length != 3) {
                throw new IOException("Invalid manifest entry at line " + (i + 1));
            }
            String path = fields[0];
            if (
                path.isEmpty() || path.startsWith("/") || path.startsWith("\\") ||
                    path.contains("..") || path.contains("\\") || !paths.add(path)
            ) {
                throw new IOException("Unsafe or duplicate manifest path: " + path);
            }
            long size;
            try {
                size = Long.parseLong(fields[1]);
            } catch (NumberFormatException error) {
                throw new IOException("Invalid size for " + path, error);
            }
            String expectedSha256 = fields[2].toLowerCase(Locale.US);
            if (!expectedSha256.matches("[0-9a-f]{64}")) {
                throw new IOException("Invalid SHA-256 for " + path);
            }
            entries.add(new Entry(path, size, expectedSha256));
        }
        if (entries.isEmpty()) {
            throw new IOException("Bundled resource manifest has no entries");
        }
        return new Manifest(header[1], digest, entries);
    }

    private static void install(
        AssetManager assets,
        File root,
        File target,
        Manifest manifest
    ) throws IOException {
        File staging = new File(
            root,
            target.getName() + ".staging-" + Process.myPid() + "-" + System.nanoTime()
        );
        deleteTree(staging);
        if (!staging.mkdirs()) {
            throw new IOException("Cannot create staging directory " + staging);
        }

        Log.i(
            TAG,
            "Extracting bundled resources version=" + manifest.version +
                " files=" + manifest.entries.size()
        );
        boolean complete = false;
        try {
            for (Entry entry : manifest.entries) {
                extractAndVerify(assets, staging, entry);
            }
            writeReadyFile(staging, manifest.digest);
            if (!validate(staging, manifest)) {
                throw new IOException("Staging validation failed");
            }

            File obsolete = null;
            if (target.exists()) {
                obsolete = new File(root, target.getName() + ".obsolete-" + System.nanoTime());
                move(target.toPath(), obsolete.toPath());
            }
            try {
                move(staging.toPath(), target.toPath());
                complete = true;
            } catch (IOException error) {
                if (obsolete != null && obsolete.exists() && !target.exists()) {
                    move(obsolete.toPath(), target.toPath());
                }
                throw error;
            }
            if (obsolete != null) {
                deleteTree(obsolete);
            }
        } finally {
            if (!complete) {
                deleteTree(staging);
            }
        }
    }

    private static void extractAndVerify(
        AssetManager assets,
        File staging,
        Entry entry
    ) throws IOException {
        File destination = new File(staging, entry.path);
        File canonicalRoot = staging.getCanonicalFile();
        File canonicalDestination = destination.getCanonicalFile();
        if (!canonicalDestination.toPath().startsWith(canonicalRoot.toPath())) {
            throw new IOException("Manifest path escapes data root: " + entry.path);
        }
        File parent = canonicalDestination.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create directory " + parent);
        }

        File temporary = new File(parent, canonicalDestination.getName() + ".tmp");
        MessageDigest digest = newSha256();
        long size = 0;
        try (
            InputStream input = new BufferedInputStream(
                assets.open(DATA_ASSET_ROOT + entry.path, AssetManager.ACCESS_STREAMING)
            );
            FileOutputStream fileOutput = new FileOutputStream(temporary);
            BufferedOutputStream output = new BufferedOutputStream(fileOutput)
        ) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count == 0) {
                    continue;
                }
                output.write(buffer, 0, count);
                digest.update(buffer, 0, count);
                size += count;
            }
            output.flush();
            fileOutput.getFD().sync();
        }

        String actualSha256 = toHex(digest.digest());
        if (size != entry.size || !actualSha256.equals(entry.sha256)) {
            Files.deleteIfExists(temporary.toPath());
            throw new IOException("Checksum mismatch for " + entry.path);
        }
        move(temporary.toPath(), canonicalDestination.toPath());
    }

    private static boolean validate(File directory, Manifest manifest) {
        try {
            if (!directory.isDirectory()) {
                return false;
            }
            File ready = new File(directory, READY_FILE);
            if (!ready.isFile()) {
                return false;
            }
            String readyDigest = new String(Files.readAllBytes(ready.toPath()), StandardCharsets.US_ASCII)
                .trim();
            if (!manifest.digest.equals(readyDigest)) {
                return false;
            }
            for (Entry entry : manifest.entries) {
                File file = new File(directory, entry.path);
                if (!file.isFile() || file.length() != entry.size) {
                    return false;
                }
                if (!entry.sha256.equals(sha256(file))) {
                    return false;
                }
            }
            long regularFileCount;
            try (java.util.stream.Stream<Path> stream = Files.walk(directory.toPath())) {
                regularFileCount = stream.filter(Files::isRegularFile).count();
            }
            return regularFileCount == manifest.entries.size() + 1L;
        } catch (Exception error) {
            Log.w(TAG, "Bundled resource validation error", error);
            return false;
        }
    }

    private static void writeReadyFile(File directory, String digest) throws IOException {
        File temporary = new File(directory, READY_FILE + ".tmp");
        File ready = new File(directory, READY_FILE);
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write((digest + "\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();
            output.getFD().sync();
        }
        move(temporary.toPath(), ready.toPath());
    }

    private static void removeObsoleteDirectories(File root, File current) {
        File[] children = root.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.equals(current) || child.getName().equals("install.lock")) {
                continue;
            }
            if (
                child.isDirectory() &&
                    (child.getName().startsWith("bundle-") || child.getName().contains(".staging-"))
            ) {
                try {
                    deleteTree(child);
                    Log.i(TAG, "Removed obsolete bundled resources name=" + child.getName());
                } catch (IOException error) {
                    Log.w(TAG, "Could not remove obsolete resources name=" + child.getName(), error);
                }
            }
        }
    }

    private static void move(Path source, Path destination) throws IOException {
        try {
            Files.move(
                source,
                destination,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteTree(File file) throws IOException {
        if (!file.exists()) {
            return;
        }
        try (java.util.stream.Stream<Path> stream = Files.walk(file.toPath())) {
            Path[] paths = stream.sorted(Comparator.reverseOrder()).toArray(Path[]::new);
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static String sha256(File file) throws IOException {
        MessageDigest digest = newSha256();
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) {
                    digest.update(buffer, 0, count);
                }
            }
        }
        return toHex(digest.digest());
    }

    private static String sha256(byte[] bytes) throws IOException {
        MessageDigest digest = newSha256();
        digest.update(bytes);
        return toHex(digest.digest());
    }

    private static MessageDigest newSha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable", error);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) {
            value.append(String.format(Locale.US, "%02x", item & 0xff));
        }
        return value.toString();
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count > 0) {
                output.write(buffer, 0, count);
            }
        }
        return output.toByteArray();
    }

    private static final class Manifest {
        final String version;
        final String digest;
        final List<Entry> entries;

        Manifest(String version, String digest, List<Entry> entries) {
            this.version = version;
            this.digest = digest;
            this.entries = entries;
        }
    }

    private static final class Entry {
        final String path;
        final long size;
        final String sha256;

        Entry(String path, long size, String sha256) {
            this.path = path;
            this.size = size;
            this.sha256 = sha256;
        }
    }
}
