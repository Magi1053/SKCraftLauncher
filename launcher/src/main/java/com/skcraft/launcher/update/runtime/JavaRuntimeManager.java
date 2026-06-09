package com.skcraft.launcher.update.runtime;

import com.skcraft.launcher.Launcher;
import com.skcraft.launcher.LauncherException;
import com.skcraft.launcher.install.*;
import com.skcraft.launcher.launch.runtime.JavaRuntime;
import com.skcraft.launcher.model.minecraft.JavaVersion;
import com.skcraft.launcher.model.minecraft.runtime.*;
import com.skcraft.launcher.persistence.Persistence;
import com.skcraft.launcher.util.Environment;
import com.skcraft.launcher.util.HttpRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static com.skcraft.launcher.util.HttpRequest.url;
import static com.skcraft.launcher.util.SharedLocale.tr;

@Log
@RequiredArgsConstructor
public class JavaRuntimeManager {
    private final File runtimesDir;
    private final Environment environment = Environment.getInstance();

    public List<ManagedRuntimeOption> fetchManagedRuntimes(URL manifestUrl) throws IOException, InterruptedException {
        RuntimePlatform platform = RuntimePlatform.from(environment);
        if (platform == null) {
            return Collections.emptyList();
        }

        RuntimeList availableRuntimes = HttpRequest.get(manifestUrl)
                .execute()
                .expectResponseCode(200)
                .returnContent()
                .asJson(RuntimeList.class);

        if (availableRuntimes.getRuntimesByPlatform() == null) {
            return Collections.emptyList();
        }

        Map<String, List<RuntimeInfo>> platformRuntimes = availableRuntimes.getRuntimesByPlatform().get(platform.getId());
        if (platformRuntimes == null) {
            return Collections.emptyList();
        }

        Map<Integer, ManagedRuntimeOption> optionsByMajorVersion = new TreeMap<>(Comparator.reverseOrder());
        for (Map.Entry<String, List<RuntimeInfo>> entry : platformRuntimes.entrySet()) {
            String component = entry.getKey();
            if (!isRuntimeComponent(component) || entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }

            RuntimeInfo info = entry.getValue().get(0);
            String version = info.getVersion() != null ? info.getVersion().getName() : null;
            int majorVersion = detectMajorVersion(version);
            if (majorVersion <= 0) {
                continue;
            }

            ManagedRuntimeOption candidate = createManagedRuntimeOption(component, majorVersion, version);
            ManagedRuntimeOption current = optionsByMajorVersion.get(majorVersion);
            if (current == null || isPreferredRuntimeChoice(candidate, current)) {
                optionsByMajorVersion.put(majorVersion, candidate);
            }
        }

        return new ArrayList<>(optionsByMajorVersion.values());
    }

    private ManagedRuntimeOption createManagedRuntimeOption(String component, int majorVersion, String version) {
        JavaVersion javaVersion = new JavaVersion();
        javaVersion.setComponent(component);
        Optional<JavaRuntime> installed = getRuntime(javaVersion);
        if (installed.isPresent()) {
            JavaRuntime runtime = installed.get();
            runtime.setMinecraftBundled(true);
            String displayVersion = runtime.getVersion() != null ? runtime.getVersion() : version;
            return new ManagedRuntimeOption(component, majorVersion, displayVersion, runtime.is64Bit(), true, runtime);
        }

        RuntimePlatform platform = RuntimePlatform.from(environment);
        boolean is64Bit = platform != null && platform.is64Bit();
        return new ManagedRuntimeOption(component, majorVersion, version, is64Bit, false, null);
    }

    private static boolean isPreferredRuntimeChoice(ManagedRuntimeOption candidate, ManagedRuntimeOption current) {
        int candidatePriority = getRuntimeComponentPriority(candidate.getComponent());
        int currentPriority = getRuntimeComponentPriority(current.getComponent());
        if (candidatePriority != currentPriority) {
            return candidatePriority < currentPriority;
        }

        return candidate.getComponent().compareTo(current.getComponent()) > 0;
    }

    private static int getRuntimeComponentPriority(String component) {
        if ("jre-legacy".equals(component)) {
            return 0;
        }

        if (component.contains("snapshot")) {
            return 2;
        }

        return 1;
    }

    private static boolean isRuntimeComponent(String component) {
        return "jre-legacy".equals(component) || component.startsWith("java-runtime-");
    }

    private static int detectMajorVersion(String version) {
        if (version == null || version.isEmpty()) {
            return 0;
        }

        if (version.startsWith("1.") && version.length() > 2) {
            return parseLeadingInt(version.substring(2));
        }

        return parseLeadingInt(version);
    }

    private static int parseLeadingInt(String text) {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isDigit(c)) {
                break;
            }
            value.append(c);
        }

        if (value.length() == 0) {
            return 0;
        }

        return Integer.parseInt(value.toString());
    }

    public Optional<JavaRuntime> getRuntime(JavaVersion version) {
        RuntimePlatform platform = RuntimePlatform.from(environment);
        RuntimeData data = readRuntimeData(platform, version);

        if (platform == null || data == null || !platform.getId().equals(data.getPlatform())) {
            return Optional.empty();
        }

        File javaHome = getJavaHome(getRuntimeDir(platform, version), data);
        if (!hasJavaExecutable(javaHome)) {
            return Optional.empty();
        }

        return Optional.of(new JavaRuntime(javaHome.getAbsoluteFile(), data.getVersion(), data.is64Bit()));
    }

    public void install(Installer installer, URL manifestUrl, JavaVersion version) throws Exception {
        RuntimePlatform platform = RuntimePlatform.from(environment);
        if (platform == null) {
            throw new LauncherException("Unsupported Game Runtime platform",
                    tr("runtime.unsupportedPlatform", environment.getPlatform(), environment.getArch()));
        }

        RuntimeList availableRuntimes = HttpRequest.get(manifestUrl)
                .execute()
                .expectResponseCode(200)
                .returnContent()
                .asJson(RuntimeList.class);

        RuntimeInfo info = availableRuntimes.getRuntime(platform, version);
        if (info == null || info.getManifest() == null) {
            throw new LauncherException("No Game Runtime available",
                    tr("runtime.unavailable", version.getComponent(), platform.getId()));
        }

        File destination = getRuntimeDir(platform, version);
        RuntimeData currentData = readRuntimeData(platform, version);
        boolean manifestChanged = currentData == null
                || currentData.getManifestSha1() == null
                || !currentData.getManifestSha1().equals(info.getManifest().getSha1());

        RuntimeManifest manifest = HttpRequest.get(url(info.getManifest().getUrl()))
                .execute()
                .expectResponseCode(200)
                .returnContent()
                .asJson(RuntimeManifest.class);

        queueRuntimeFiles(installer, destination, manifest, manifestChanged);
        queueRuntimeData(installer, destination, platform, version, info);
    }

    private void queueRuntimeFiles(Installer installer, File destination, RuntimeManifest manifest,
                                   boolean manifestChanged) throws Exception {
        if (manifest.getFiles() == null) {
            return;
        }

        for (Map.Entry<String, RuntimeManifestEntry> entry : manifest.getFiles().entrySet()) {
            String filename = entry.getKey();
            RuntimeManifestEntry runtimeEntry = entry.getValue();
            File target = resolveRuntimeFile(destination, filename);

            if (runtimeEntry instanceof RuntimeManifestEntry.File) {
                RuntimeManifestEntry.File file = (RuntimeManifestEntry.File) runtimeEntry;
                DownloadInfo download = file.getDownloads() != null ? file.getDownloads().get(Format.RAW) : null;
                if (download == null || download.getUrl() == null) {
                    continue;
                }

                if (manifestChanged || !target.isFile()) {
                    File tempFile = installer.getDownloader().download(
                            url(download.getUrl()), "", download.getSize(), filename);
                    installer.queue(new FileMover(tempFile, target));

                    if (download.getSha1() != null) {
                        installer.queue(new FileVerify(target, filename, download.getSha1()));
                    }

                    if (file.isExecutable()) {
                        installer.queue(new FileSetExecutable(target));
                    }
                }
            } else if (runtimeEntry instanceof RuntimeManifestEntry.Directory) {
                if (!target.isDirectory()) {
                    installer.queue(new CreateFolder(target));
                }
            } else if (runtimeEntry instanceof RuntimeManifestEntry.Link) {
                if (manifestChanged || !target.exists()) {
                    RuntimeManifestEntry.Link link = (RuntimeManifestEntry.Link) runtimeEntry;
                    installer.queue(new CreateLink(target, Paths.get(link.getTarget())));
                }
            }
        }
    }

    private void queueRuntimeData(Installer installer, File destination, RuntimePlatform platform,
                                  JavaVersion version, RuntimeInfo info) {
        installer.queueLate(new InstallTask() {
            @Override
            public void execute(Launcher launcher) throws Exception {
                RuntimeData data = new RuntimeData();
                data.setComponent(version.getComponent());
                data.setPlatform(platform.getId());
                data.setVersion(info.getVersion().getName());
                data.setManifestSha1(info.getManifest().getSha1());
                data.setJavaHomePath(getJavaHomePath(platform));
                data.set64Bit(platform.is64Bit());
                Persistence.write(new File(destination, "runtime.json"), data);
            }

            @Override
            public double getProgress() {
                return -1;
            }

            @Override
            public String getStatus() {
                return tr("installer.adhoc.writingRuntimeData");
            }
        });
    }

    private RuntimeData readRuntimeData(RuntimePlatform platform, JavaVersion version) {
        if (platform == null || version == null || version.getComponent() == null) {
            return null;
        }

        return Persistence.read(new File(getRuntimeDir(platform, version), "runtime.json"), RuntimeData.class, true);
    }

    private File getRuntimeDir(RuntimePlatform platform, JavaVersion version) {
        return new File(new File(runtimesDir, platform.getId()), version.getComponent());
    }

    private static File getJavaHome(File runtimeDir, RuntimeData data) {
        String javaHomePath = data.getJavaHomePath() != null ? data.getJavaHomePath() : ".";
        return new File(runtimeDir, javaHomePath);
    }

    private static String getJavaHomePath(RuntimePlatform platform) {
        switch (platform) {
            case MAC_OS:
            case MAC_OS_ARM64:
                return "jre.bundle/Contents/Home";
            default:
                return ".";
        }
    }

    private static boolean hasJavaExecutable(File javaHome) {
        File bin = new File(javaHome, "bin");
        return new File(bin, "java").isFile() || new File(bin, "java.exe").isFile();
    }

    private static File resolveRuntimeFile(File root, String filename) throws IOException, LauncherException {
        File target = new File(root, filename);
        String rootPath = root.getCanonicalPath();
        String targetPath = target.getCanonicalPath();

        if (!targetPath.equals(rootPath) && !targetPath.startsWith(rootPath + File.separator)) {
            throw new LauncherException("Invalid Game Runtime path",
                    tr("runtime.invalidPath", filename));
        }

        return target;
    }
}
