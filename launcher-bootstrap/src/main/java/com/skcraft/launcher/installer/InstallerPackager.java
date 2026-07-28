package com.skcraft.launcher.installer;

import com.skcraft.launcher.installer.platform.DockerLinuxInstallerPackager;
import com.skcraft.launcher.installer.platform.LinuxInstallerPackager;
import com.skcraft.launcher.installer.platform.MacInstallerPackager;
import com.skcraft.launcher.installer.platform.WindowsInstallerPackager;
import com.skcraft.launcher.installer.platform.WslLinuxInstallerPackager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public final class InstallerPackager {

    private InstallerPackager() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            throw new IllegalArgumentException("Usage: InstallerPackager <windows|linux|mac|wsl-linux|docker-linux> ...");
        }

        String mode = args[0].toLowerCase(Locale.ROOT);
        Platform packager = switch (mode) {
            case "windows" -> new WindowsInstallerPackager();
            case "linux" -> new LinuxInstallerPackager();
            case "mac" -> new MacInstallerPackager();
            case "wsl-linux" -> new WslLinuxInstallerPackager();
            case "docker-linux" -> new DockerLinuxInstallerPackager();
            default -> throw new IllegalArgumentException("Unsupported mode: " + mode);
        };
        packager.run(Arrays.copyOfRange(args, 1, args.length));
    }

    public abstract static class Platform {
        protected static final String APP_DIR = "app";
        protected static final String RUNTIME_DIR = "runtime";
        protected static final String LAUNCHER_DIR = "launcher";
        protected static final String DATA_SUBDIR = "bootstrap";

        protected Platform() {
        }

        public abstract void run(String[] args) throws Exception;

        protected Path createBundledAppInput(Path appImageDir, String prefix) throws IOException {
            Path inputDir = Files.createTempDirectory(prefix);
            copyDirectory(appImageDir.resolve(APP_DIR), inputDir);
            copyDirectory(appImageDir.resolve(DATA_SUBDIR), inputDir.resolve(DATA_SUBDIR));
            return inputDir;
        }

        protected void ensureAppImageReady(Path appImageDir) {
            ensureExists(appImageDir.resolve(RUNTIME_DIR), "Missing app image runtime");
            ensureExists(appImageDir.resolve(APP_DIR).resolve("launcher-bootstrap.jar"), "Missing app image jar");
            ensureExists(appImageDir.resolve(DATA_SUBDIR).resolve(LAUNCHER_DIR), "Missing bundled launcher directory");
            ensureExists(appImageDir.resolve("launcher-bootstrap"), "Missing launcher shell wrapper");
        }

        protected Path prepareJpackageResourceDir(Path projectDir, String platform, Map<String, String> replacements)
                throws IOException {
            Path sourceDir = projectDir.resolve("installer").resolve(platform).resolve("jpackage-resources");
            Path targetDir = projectDir.resolve("build/tmp/jpackage-resources").resolve(platform);

            ensureExists(sourceDir, "Missing jpackage resource directory");
            deleteDirectory(targetDir);
            Files.walkFileTree(sourceDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Path rel = sourceDir.relativize(dir);
                    Files.createDirectories(targetDir.resolve(rel));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path rel = sourceDir.relativize(file);
                    Path target = targetDir.resolve(rel);
                    String text = Files.readString(file, StandardCharsets.UTF_8);
                    for (Map.Entry<String, String> entry : replacements.entrySet()) {
                        text = text.replace(entry.getKey(), entry.getValue());
                    }
                    Files.writeString(target, text, StandardCharsets.UTF_8);
                    if (target.getFileName().toString().startsWith("post")) {
                        setExecutable(target);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            return targetDir;
        }

        protected Properties loadInstallerProperties(Path projectDir) throws IOException {
            Path installerProperties = projectDir.resolve("installer/installer.properties");
            Properties properties = new Properties();
            try (InputStream in = Files.newInputStream(installerProperties)) {
                properties.load(in);
            }
            return properties;
        }

        protected String getBootstrapPropertyWithFallback(Path projectDir, String key, String fallbackKey)
                throws IOException {
            Path propertiesFile = projectDir.resolve("src/main/resources/com/skcraft/launcher/bootstrap.properties");
            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(propertiesFile)) {
                properties.load(input);
            }
            String value = properties.getProperty(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
            value = properties.getProperty(fallbackKey);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("Missing bootstrap property '" + fallbackKey + "' in " + propertiesFile);
            }
            return value.trim();
        }

        protected List<String> append(List<String> base, String... extra) {
            List<String> merged = new ArrayList<>(base);
            for (String s : extra) {
                merged.add(s);
            }
            return merged;
        }

        /**
         * Nested Linux Gradle should reuse host-built jars/classes and only rebuild
         * Linux-specific packaging (jlink runtime, AppImage, deb).
         */
        protected String remoteLinuxPackageGradleArgs(String version, boolean buildDeb, String displayAppName,
                String linuxInstallDirName) {
            return ":launcher-bootstrap:packageLinux"
                    + " -Pversion=" + shellSingleQuote(version)
                    + " -PbuildDeb=" + shellSingleQuote(buildDeb ? "true" : "false")
                    + " -PappName=" + shellSingleQuote(displayAppName)
                    + " -PinstallDirName=" + shellSingleQuote(linuxInstallDirName)
                    + " -x :launcher:generateWebliteChecksums"
                    + " -x :launcher:generateEffectiveLombokConfig"
                    + " -x :launcher:generateTestEffectiveLombokConfig"
                    + " -x :launcher:compileJava"
                    + " -x :launcher:processResources"
                    + " -x :launcher:classes"
                    + " -x :launcher:shadowJar"
                    + " -x :launcher-bootstrap:generateEffectiveLombokConfig"
                    + " -x :launcher-bootstrap:compileJava"
                    + " -x :launcher-bootstrap:processResources"
                    + " -x :launcher-bootstrap:classes"
                    + " -x :launcher-bootstrap:shadowJar";
        }

        protected String shellSingleQuote(String value) {
            return "'" + value.replace("'", "'\"'\"'") + "'";
        }

        /**
         * Installer artifact file name. Platform is implied by the output folder
         * ({@code installer/windows|linux|macos}), so names are just
         * {@code <packageAppName><extension>} (e.g. {@code .AppImage}, {@code .dmg},
         * {@code  Setup.exe}).
         */
        protected String installerFileName(String displayAppName, String extension) {
            return normalizePackageAppName(displayAppName) + extension;
        }

        protected void renamePackagedFile(Path outputDir, String fromName, String toName) throws IOException {
            if (fromName.equals(toName)) {
                return;
            }
            Path from = outputDir.resolve(fromName);
            Path to = outputDir.resolve(toName);
            ensureExists(from, "Expected packaged file missing");
            Files.deleteIfExists(to);
            Files.move(from, to);
        }

        protected boolean parseBooleanFlag(String raw) {
            if (raw == null) {
                return false;
            }
            String value = raw.trim().toLowerCase(Locale.ROOT);
            return value.equals("1")
                    || value.equals("true")
                    || value.equals("yes")
                    || value.equals("on");
        }

        protected String normalizePackageAppName(String appName) {
            if (appName == null || appName.isBlank()) {
                throw new IllegalArgumentException("Package app name must not be blank.");
            }
            return appName.trim();
        }

        protected String normalizeInstallDirName(String installDirName) {
            if (installDirName == null) {
                throw new IllegalArgumentException("Install dir name must not be blank.");
            }
            String trimmed = installDirName.trim();
            if (trimmed.isBlank()) {
                throw new IllegalArgumentException("Install dir name must not be blank.");
            }
            return trimmed;
        }

        protected String findOnPath(String executable) {
            String path = System.getenv("PATH");
            if (path == null || path.isBlank()) {
                return null;
            }
            for (String entry : path.split(java.io.File.pathSeparator)) {
                if (entry == null || entry.isBlank()) {
                    continue;
                }
                Path candidate = Paths.get(entry).resolve(executable);
                if (Files.isExecutable(candidate)) {
                    return candidate.toString();
                }
            }
            return null;
        }

        protected void ensureExists(Path path, String message) {
            if (!Files.exists(path)) {
                throw new IllegalStateException(message + ": " + path);
            }
        }

        protected void runCommand(List<String> command, Path workingDirectory, Map<String, String> extraEnv)
                throws Exception {
            ProcessBuilder builder = new ProcessBuilder(command);
            if (workingDirectory != null) {
                builder.directory(workingDirectory.toFile());
            }
            builder.inheritIO();
            builder.environment().putAll(extraEnv);
            Process process = builder.start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("Command failed (" + exitCode + "): " + String.join(" ", command));
            }
        }

        protected String runAndCapture(List<String> command, Path workingDirectory, Map<String, String> extraEnv)
                throws Exception {
            ProcessBuilder builder = new ProcessBuilder(command);
            if (workingDirectory != null) {
                builder.directory(workingDirectory.toFile());
            }
            builder.environment().putAll(extraEnv);
            Process process = builder.start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            copy(process.getInputStream(), out);
            copy(process.getErrorStream(), err);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                String errorText = err.toString(StandardCharsets.UTF_8);
                if (!errorText.isBlank()) {
                    System.err.print(errorText);
                }
                throw new IllegalStateException("Command failed (" + exitCode + "): " + String.join(" ", command));
            }
            return out.toString(StandardCharsets.UTF_8);
        }

        protected void copy(InputStream in, OutputStream out) throws IOException {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
        }

        protected void copyDirectory(Path source, Path target) throws IOException {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Path rel = source.relativize(dir);
                    Files.createDirectories(target.resolve(rel));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path rel = source.relativize(file);
                    Files.copy(file, target.resolve(rel), StandardCopyOption.REPLACE_EXISTING);
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        protected void deleteDirectory(Path dir) throws IOException {
            if (!Files.exists(dir)) {
                return;
            }
            try (var paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }

        protected void setExecutable(Path file) throws IOException {
            try {
                Set<PosixFilePermission> perms = EnumSet.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                        PosixFilePermission.GROUP_READ,
                        PosixFilePermission.GROUP_EXECUTE,
                        PosixFilePermission.OTHERS_READ,
                        PosixFilePermission.OTHERS_EXECUTE);
                Files.setPosixFilePermissions(file, perms);
            } catch (UnsupportedOperationException ignored) {
                file.toFile().setExecutable(true, false);
            }
        }
    }
}
