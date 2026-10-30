package com.fuzzlauncher.infrastructure;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Write-ahead recovery records. Recovery only changes files matching a recorded generation. */
public final class OperationJournal {
    public record Store(Path directory, Path dataDirectory) {
        public OperationJournal begin(String kind, Path... roots) throws IOException {
            if (!pending(directory).isEmpty()) {
                throw new IOException("存在待恢复操作，请先重启启动器完成恢复");
            }
            List<String> allowed = new ArrayList<>();
            for (Path root : roots) {
                allowed.add(GameRootMigration.physical(root).toString());
            }
            allowed.add(GameRootMigration.physical(dataDirectory).toString());
            Path work = Files.createDirectories(directory).resolve(UUID.randomUUID().toString());
            Files.createDirectory(work);
            OperationJournal journal = new OperationJournal(work,
                new State(1, kind, "PENDING", allowed, new ArrayList<>(), new ArrayList<>()));
            journal.save();
            return journal;
        }
    }

    private record Change(String target, String backup, String before, List<String> after, Map<String, String> tree, boolean keepRoot) {
    }

    private record State(int schema, String kind, String phase, List<String> roots, List<Change> changes, List<String> workspaces) {
    }

    private final Path directory;
    private State state;

    private OperationJournal(Path directory, State state) {
        this.directory = directory.toAbsolutePath().normalize();
        this.state = state;
    }

    public String description() {
        String phase = switch (state.phase) {
            case "PENDING" -> "未提交";
            case "COMMITTED" -> "已提交，待清理";
            case "ROLLED_BACK" -> "已回退，待清理";
            default -> "未知状态";
        };
        return state.kind + "（" + phase + "）";
    }

    public static List<OperationJournal> pending(Path root) throws IOException {
        if (!Files.exists(root)) {
            return List.of();
        }
        ordinary(root);
        List<OperationJournal> result = new ArrayList<>();
        try (var directories = Files.list(root)) {
            for (Path directory : directories.sorted().toList()) {
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                ordinary(directory);
                Path record = directory.resolve("operation.json");
                if (!Files.exists(record)) {
                    continue; // No record means no external mutation was authorized.
                }
                State state;
                try {
                    state = JsonFiles.GSON.fromJson(JsonFiles.read(record), State.class);
                    if (state.schema != 1 || !Set.of("PENDING", "COMMITTED", "ROLLED_BACK").contains(state.phase) || state.roots == null
                        || state.changes == null || state.workspaces == null) {
                        throw new IllegalArgumentException("未知恢复记录格式");
                    }
                } catch (RuntimeException ex) {
                    throw new IOException("操作恢复记录损坏，请保留并检查：" + record, ex);
                }
                result.add(new OperationJournal(directory, state));
            }
        }
        return result;
    }

    public void workspace(Path path) throws IOException {
        path = path.toAbsolutePath().normalize();
        ordinary(path);
        String name = path.getFileName().toString();
        if (!(path.getParent().getFileName().toString().equals(".fuzz-staging") || name.startsWith(".fuzz-migration-"))) {
            throw new IOException("不是私有操作目录：" + path);
        }
        state.workspaces.add(path.toString());
        save();
    }

    public void configuration(Path file, Object after) throws IOException {
        byte[] bytes = JsonFiles.GSON.toJson(after).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        file(file, bytes);
        file(RecoverableJson.backup(file), Files.exists(file) ? Files.readAllBytes(file) : bytes);
        file(RecoverableJson.backup(file), bytes); // A normal rollback may rotate this generation into the backup.
    }

    public void configuration(Path file, Object after, Object rollback) throws IOException {
        configuration(file, after);
        byte[] previousModel = JsonFiles.GSON.toJson(rollback).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        file(file, previousModel);
        file(RecoverableJson.backup(file), previousModel);
    }

    public void file(Path path, byte[] after) throws IOException {
        path = checked(path);
        String target = path.toString();
        String hash = hash(after);
        for (Change change : state.changes) {
            if (change.target.equals(target)) {
                if (change.tree != null) {
                    throw new IOException("恢复目标类型发生变化");
                }
                change.after.add(hash);
                save();
                return;
            }
        }
        String backup = null, before = null;
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            ordinary(path);
            if (!Files.isRegularFile(path)) {
                throw new IOException("恢复目标不是普通文件：" + path);
            }
            byte[] previous = Files.readAllBytes(path);
            backup = "snapshot-" + state.changes.size();
            before = hash(previous);
            JsonFiles.atomicWrite(directory.resolve(backup), previous);
        }
        state.changes.add(new Change(target, backup, before, new ArrayList<>(List.of(hash)), null, false));
        save();
    }

    public void file(Path path, Path after) throws IOException {
        // Snapshot only the old generation; hash the new artifact without loading it into memory.
        path = checked(path);
        String backup = null, before = null;
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            ordinary(path);
            if (!Files.isRegularFile(path)) {
                throw new IOException("恢复目标不是普通文件：" + path);
            }
            backup = "snapshot-" + state.changes.size();
            before = HttpTransport.digest(path, "SHA-256");
            Files.copy(path, directory.resolve(backup));
            try (var channel = java.nio.channels.FileChannel.open(directory.resolve(backup), StandardOpenOption.WRITE)) {
                channel.force(true);
            }
        }
        state.changes.add(
            new Change(path.toString(), backup, before, new ArrayList<>(List.of(HttpTransport.digest(after, "SHA-256"))), null, false));
        save();
    }

    public void tree(Path target, Path staged, boolean keepRoot) throws IOException {
        target = checked(target);
        Map<String, String> manifest = new LinkedHashMap<>();
        try (var files = Files.walk(staged)) {
            for (Path file : files.toList()) {
                ordinary(file);
                manifest.put(staged.relativize(file).toString(),
                    Files.isDirectory(file) ? "directory" : HttpTransport.digest(file, "SHA-256"));
            }
        }
        state.changes.add(new Change(target.toString(), null, null, new ArrayList<>(), manifest, keepRoot));
        save();
    }

    public void commit() throws IOException {
        phase("COMMITTED");
    }

    private void phase(String phase) throws IOException {
        State next = new State(state.schema, state.kind, phase, state.roots, state.changes, state.workspaces);
        JsonFiles.write(directory.resolve("operation.json"), next);
        state = next;
    }

    private void save() throws IOException {
        JsonFiles.write(directory.resolve("operation.json"), state);
    }

    public void recover() throws IOException {
        boolean interrupted = Thread.interrupted();
        try {
            recoverFiles();
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void recoverFiles() throws IOException {
        if (state.phase.equals("PENDING")) {
            // Validate every target before touching any of them. A changed user file blocks rollback.
            for (Change change : state.changes) {
                validate(change);
            }
            for (Change change : state.changes.reversed()) {
                restore(change);
            }
            phase("ROLLED_BACK");
        }
        for (String workspace : state.workspaces) {
            Path path = Path.of(workspace).toAbsolutePath().normalize();
            if (path.getParent() == null || !(path.getParent().getFileName().toString().equals(".fuzz-staging")
                || path.getFileName().toString().startsWith(".fuzz-migration-"))) {
                throw new IOException("私有操作目录记录无效：" + path);
            }
            removePrivate(path);
        }
        removePrivate(directory);
    }

    private void validate(Change change) throws IOException {
        Path target = checked(Path.of(change.target));
        if (change.tree != null) {
            if (Files.exists(target)) {
                try (var files = Files.walk(target)) {
                    for (Path file : files.toList()) {
                        ordinary(file);
                        String expected = change.tree.get(target.relativize(file).toString());
                        String actual = Files.isDirectory(file) ? "directory" : HttpTransport.digest(file, "SHA-256");
                        if (!actual.equals(expected)) {
                            throw new IOException("恢复目标含未记录或已修改的文件，已保留：" + file);
                        }
                    }
                }
            }
        } else {
            if (Files.exists(target)) {
                ordinary(target);
                String hash = HttpTransport.digest(target, "SHA-256");
                if (!hash.equals(change.before) && !change.after.contains(hash)) {
                    throw new IOException("恢复目标已被修改，未覆盖：" + target);
                }
            }
            if (change.backup != null) {
                Path backup = JsonFiles.safeResolve(directory, change.backup);
                ordinary(backup);
                if (!HttpTransport.digest(backup, "SHA-256").equals(change.before)) {
                    throw new IOException("恢复备份校验失败：" + backup);
                }
            }
        }
    }

    private void restore(Change change) throws IOException {
        Path target = checked(Path.of(change.target));
        validate(change);
        if (change.tree != null) {
            removePrivate(target);
            if (change.keepRoot) {
                Files.createDirectories(target);
            }
        } else if (change.backup != null) {
            if (Files.exists(target) && HttpTransport.digest(target, "SHA-256").equals(change.before)) {
                return; // The in-process rollback may already have restored it, including after disk-full errors.
            }
            JsonFiles.atomicWrite(target, Files.readAllBytes(JsonFiles.safeResolve(directory, change.backup)));
        } else {
            Files.deleteIfExists(target);
        }
    }

    private Path checked(Path path) throws IOException {
        path = path.toAbsolutePath().normalize();
        for (Path parent = path; parent != null; parent = parent.getParent()) {
            if (Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
                ordinary(parent);
            }
        }
        Path target = GameRootMigration.physical(path);
        if (target.getParent() == null || state.roots.stream().map(Path::of).noneMatch(target::startsWith)) {
            throw new IOException("恢复目标超出操作目录：" + target);
        }
        return target;
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void ordinary(Path path) throws IOException {
        int flags = com.sun.jna.platform.win32.Kernel32.INSTANCE.GetFileAttributes(path.toString());
        if (flags == com.sun.jna.platform.win32.WinBase.INVALID_FILE_ATTRIBUTES
            || (flags & com.sun.jna.platform.win32.WinNT.FILE_ATTRIBUTE_REPARSE_POINT) != 0) {
            throw new IOException("恢复路径是链接或无法读取：" + path);
        }
    }

    private static void removePrivate(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (path.toAbsolutePath().getParent() == null) {
            throw new IOException("拒绝清理磁盘根目录");
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                ordinary(directory);
                return FileVisitResult.CONTINUE;
            }

            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                ordinary(file);
                var dos = Files.getFileAttributeView(file, java.nio.file.attribute.DosFileAttributeView.class);
                if (dos != null && dos.readAttributes().isReadOnly()) {
                    dos.setReadOnly(false);
                }
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                if (error != null) {
                    throw error;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
