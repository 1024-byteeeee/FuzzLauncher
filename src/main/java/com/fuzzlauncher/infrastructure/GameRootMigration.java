package com.fuzzlauncher.infrastructure;

import com.fuzzlauncher.application.TaskContext;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** A verified, reversible copy until settings commit; source deletion only follows that commit. */
public final class GameRootMigration implements AutoCloseable {
  private final Path source;
  private final Path target;
  private final Path workspace;
  private final boolean targetExisted;
  private final List<Path> entries = new ArrayList<>();
  private boolean published;
  private boolean committed;

  private GameRootMigration(Path source, Path target, Path workspace, boolean targetExisted) {
    this.source = source;
    this.target = target;
    this.workspace = workspace;
    this.targetExisted = targetExisted;
  }

  public static boolean sameDirectory(Path first, Path second) throws IOException {
    return first.toAbsolutePath().normalize().equals(second.toAbsolutePath().normalize())
        || (Files.exists(first) && Files.exists(second) && Files.isSameFile(first, second));
  }

  /**
   * Resolves existing ancestors too, preventing aliases/junctions from bypassing nesting checks.
   */
  public static Path physical(Path path) throws IOException {
    path = path.toAbsolutePath().normalize();
    Path existing = path;
    while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS))
      existing = existing.getParent();
    if (existing == null) throw new IOException("找不到目录所在磁盘或上级目录：" + path);
    return existing.toRealPath().resolve(existing.relativize(path)).normalize();
  }

  public static GameRootMigration prepare(
      Path source, Path target, List<Path> protectedPaths, TaskContext task) throws IOException {
    if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) requireOrdinary(source);
    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) requireOrdinary(target);
    source = physical(source);
    target = physical(target);
    if (source.getParent() == null
        || target.getParent() == null
        || source.startsWith(target)
        || target.startsWith(source)) throw new IOException("迁移目录不能相同、互相包含或使用磁盘根目录");
    for (Path protectedPath : protectedPaths)
      if (physical(protectedPath).startsWith(source))
        throw new IOException("游戏根目录包含启动器程序或配置数据，不能整体迁移，请先整理为独立游戏目录");
    if (Files.exists(source) && !Files.isDirectory(source)) throw new IOException("原游戏根目录不是文件夹");
    boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
    if (exists) {
      requireOrdinary(target);
      if (!Files.isDirectory(target)) throw new IOException("迁移目标不是文件夹");
      try (var children = Files.list(target)) {
        if (children.findAny().isPresent()) throw new IOException("迁移目标目录必须为空，避免覆盖已有文件");
      }
    }
    Files.createDirectories(target.getParent());
    Path workspace = Files.createTempDirectory(target.getParent(), ".fuzz-migration-");
    GameRootMigration migration = new GameRootMigration(source, target, workspace, exists);
    try {
      migration.copy(task);
      task.checkCancelled();
      if (exists) Files.delete(target); // Only an empty target may be replaced.
      Files.move(workspace.resolve("content"), target);
      migration.published = true;
      Files.delete(workspace);
      return migration;
    } catch (IOException | RuntimeException ex) {
      try {
        migration.close();
      } catch (IOException cleanup) {
        ex.addSuppressed(cleanup);
      }
      throw ex;
    }
  }

  private void copy(TaskContext task) throws IOException {
    Path staged = Files.createDirectory(workspace.resolve("content"));
    if (!Files.exists(source)) return;
    Files.walkFileTree(
        source,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs)
              throws IOException {
            task.checkCancelled();
            requireOrdinaryPath(source, directory);
            Path relative = source.relativize(directory);
            entries.add(relative);
            if (!directory.equals(source)) Files.createDirectory(staged.resolve(relative));
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            task.checkCancelled();
            requireOrdinaryPath(source, file);
            Path relative = source.relativize(file);
            task.report("正在迁移 " + relative, -1);
            Files.copy(file, staged.resolve(relative), StandardCopyOption.COPY_ATTRIBUTES);
            task.checkCancelled();
            if (Files.mismatch(file, staged.resolve(relative)) != -1)
              throw new IOException("文件复制校验失败：" + relative);
            entries.add(relative);
            return FileVisitResult.CONTINUE;
          }
        });
    // Detect new/deleted files or writes during the copy before publishing any destination data.
    task.report("正在校验迁移文件", -1);
    verify(task, staged);
  }

  private void verify(TaskContext task, Path copied) throws IOException {
    Set<Path> expected = new HashSet<>(entries);
    Files.walkFileTree(
        source,
        new SimpleFileVisitor<>() {
          private void check(Path path, boolean directory) throws IOException {
            task.checkCancelled();
            requireOrdinaryPath(source, path);
            Path relative = source.relativize(path);
            if (!expected.remove(relative)
                || (!directory && Files.mismatch(path, copied.resolve(relative)) != -1))
              throw new IOException("源目录在迁移过程中发生变化，请重试：" + relative);
          }

          @Override
          public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs)
              throws IOException {
            check(path, true);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path path, BasicFileAttributes attrs)
              throws IOException {
            check(path, false);
            return FileVisitResult.CONTINUE;
          }
        });
    if (!expected.isEmpty()) throw new IOException("源目录在迁移过程中有文件被删除，请重试");
  }

  /** Once configuration is durable, the verified destination becomes authoritative. */
  public void commit() {
    committed = true;
  }

  public void cleanSource() throws IOException {
    if (!committed || !published) throw new IllegalStateException("Migration has not committed");
    if (!Files.exists(source)) return;
    // Only delete inventoried files still equal to their verified destination counterparts.
    for (Path relative : entries.reversed()) {
      Path file = source.resolve(relative);
      requireOrdinaryPath(source, file);
      requireOrdinaryPath(target, target.resolve(relative));
      if (!Files.isDirectory(file) && Files.mismatch(file, target.resolve(relative)) != -1)
        throw new IOException("旧目录文件已发生变化，已保留：" + file);
      Files.delete(file); // Non-empty directories fail rather than deleting newly added files.
    }
  }

  private static void requireOrdinary(Path path) throws IOException {
    int flags = com.sun.jna.platform.win32.Kernel32.INSTANCE.GetFileAttributes(path.toString());
    if (flags == com.sun.jna.platform.win32.WinBase.INVALID_FILE_ATTRIBUTES
        || (flags & com.sun.jna.platform.win32.WinNT.FILE_ATTRIBUTE_REPARSE_POINT) != 0)
      throw new IOException("无法迁移链接或读取文件属性：" + path);
  }

  private static void requireOrdinaryPath(Path root, Path path) throws IOException {
    for (Path current = path;
        current != null && current.startsWith(root);
        current = current.getParent()) requireOrdinary(current);
  }

  private static void removePrivateTree(Path root) throws IOException {
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs)
              throws IOException {
            requireOrdinary(directory);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            requireOrdinary(file);
            var dos =
                Files.getFileAttributeView(
                    file, java.nio.file.attribute.DosFileAttributeView.class);
            if (dos != null && dos.readAttributes().isReadOnly()) dos.setReadOnly(false);
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path directory, IOException error)
              throws IOException {
            if (error != null) throw error;
            Files.delete(directory);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  @Override
  public void close() throws IOException {
    if (committed) return;
    if (published && !committed) removePrivateTree(target);
    removePrivateTree(workspace);
    if (!committed && targetExisted && !Files.exists(target)) Files.createDirectory(target);
  }
}
