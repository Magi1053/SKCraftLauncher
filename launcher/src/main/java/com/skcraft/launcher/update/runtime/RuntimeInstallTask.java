package com.skcraft.launcher.update.runtime;

import com.skcraft.concurrency.DefaultProgress;
import com.skcraft.concurrency.ProgressFilter;
import com.skcraft.concurrency.ProgressObservable;
import com.skcraft.launcher.Launcher;
import com.skcraft.launcher.LauncherException;
import com.skcraft.launcher.install.Installer;
import com.skcraft.launcher.launch.runtime.JavaRuntime;
import com.skcraft.launcher.model.minecraft.JavaVersion;
import com.skcraft.launcher.util.SharedLocale;

import java.util.Optional;
import java.util.concurrent.Callable;

public class RuntimeInstallTask implements Callable<JavaRuntime>, ProgressObservable {
    private final Launcher launcher;
    private final JavaVersion javaVersion;
    private final Installer installer;

    private ProgressObservable progress = new DefaultProgress(-1, SharedLocale.tr("runtime.preparing"));

    public RuntimeInstallTask(Launcher launcher, JavaVersion javaVersion) {
        this.launcher = launcher;
        this.javaVersion = javaVersion;
        this.installer = new Installer(launcher.getInstallerDir(), launcher.getDownloadThreads());
    }

    @Override
    public JavaRuntime call() throws Exception {
        progress = new DefaultProgress(-1, SharedLocale.tr("runtime.collecting"));
        launcher.getRuntimeManager().install(installer, launcher.propUrl("runtimeManifestUrl"), javaVersion);

        progress = ProgressFilter.between(installer.getDownloader(), 0, 0.98);
        installer.download();

        progress = ProgressFilter.between(installer, 0.98, 1);
        installer.execute(launcher);
        installer.executeLate(launcher);

        Optional<JavaRuntime> runtime = launcher.getRuntimeManager().getRuntime(javaVersion);
        if (!runtime.isPresent()) {
            throw new LauncherException("Game Runtime install failed",
                    SharedLocale.tr("runtime.installFailed", javaVersion.getComponent()));
        }

        return runtime.get();
    }

    @Override
    public double getProgress() {
        return progress.getProgress();
    }

    @Override
    public String getStatus() {
        return progress.getStatus();
    }
}
