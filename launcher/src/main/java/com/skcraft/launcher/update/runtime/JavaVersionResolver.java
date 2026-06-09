package com.skcraft.launcher.update.runtime;

import com.google.common.base.Strings;
import com.skcraft.launcher.Instance;
import com.skcraft.launcher.Launcher;
import com.skcraft.launcher.model.minecraft.JavaVersion;
import com.skcraft.launcher.model.minecraft.ReleaseList;
import com.skcraft.launcher.model.minecraft.Version;
import com.skcraft.launcher.model.minecraft.VersionManifest;
import com.skcraft.launcher.model.modpack.Manifest;
import com.skcraft.launcher.persistence.Persistence;
import com.skcraft.launcher.util.HttpRequest;
import lombok.extern.java.Log;

import java.io.File;
import java.net.URL;
import java.util.logging.Level;

import static com.skcraft.launcher.util.HttpRequest.url;

@Log
public final class JavaVersionResolver {
    private static final String LEGACY_COMPONENT = "jre-legacy";
    private static final int LEGACY_MAJOR_VERSION = 8;

    private JavaVersionResolver() {
    }

    public static JavaVersion resolve(Launcher launcher, Instance instance, VersionManifest version) {
        if (version != null && version.getJavaVersion() != null) {
            return version.getJavaVersion();
        }

        String gameVersion = resolveGameVersion(instance, version, readModpackManifest(instance));
        JavaVersion mojangJava = fetchFromMojang(launcher, gameVersion);
        if (mojangJava != null) {
            return mojangJava;
        }

        return legacyDefault();
    }

    public static JavaVersion legacyDefault() {
        JavaVersion javaVersion = new JavaVersion();
        javaVersion.setComponent(LEGACY_COMPONENT);
        javaVersion.setMajorVersion(LEGACY_MAJOR_VERSION);
        return javaVersion;
    }

    private static JavaVersion fetchFromMojang(Launcher launcher, String gameVersion) {
        if (launcher == null || Strings.isNullOrEmpty(gameVersion)) {
            return null;
        }

        try {
            URL versionManifestUrl = launcher.propUrl("versionManifestUrl");
            ReleaseList releases = HttpRequest.get(versionManifestUrl)
                    .execute()
                    .expectResponseCode(200)
                    .returnContent()
                    .asJson(ReleaseList.class);

            Version release = releases != null ? releases.find(gameVersion) : null;
            if (release == null || Strings.isNullOrEmpty(release.getUrl())) {
                return null;
            }

            VersionManifest versionManifest = HttpRequest.get(url(release.getUrl()))
                    .execute()
                    .expectResponseCode(200)
                    .returnContent()
                    .asJson(VersionManifest.class);

            return versionManifest != null ? versionManifest.getJavaVersion() : null;
        } catch (Exception e) {
            log.log(Level.FINE, "Failed to resolve Java version from Mojang for " + gameVersion, e);
            return null;
        }
    }

    private static String resolveGameVersion(Instance instance, VersionManifest version, Manifest modpackManifest) {
        if (version != null && !Strings.isNullOrEmpty(version.getId())) {
            return version.getId();
        }

        if (modpackManifest != null && !Strings.isNullOrEmpty(modpackManifest.getGameVersion())) {
            return modpackManifest.getGameVersion();
        }

        return null;
    }

    private static Manifest readModpackManifest(Instance instance) {
        if (instance == null) {
            return null;
        }

        File manifestPath = instance.getManifestPath();
        if (!manifestPath.isFile()) {
            return null;
        }

        return Persistence.read(manifestPath, Manifest.class, true);
    }
}
