/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.browser.platform;

import ca.weblite.webview.swing.WebViewComponent;
import com.skcraft.launcher.util.SharedLocale;
import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.PointerByReference;
import lombok.extern.java.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

/**
 * Linux WebKitGTK browser configuration.
 */
@Log
public final class LinuxBrowserPlatform extends BrowserPlatform {

    private static final String WEBKIT_DISABLE_COMPOSITING_MODE =
            "WEBKIT_DISABLE_COMPOSITING_MODE";
    private static final String WEBKIT_DISABLE_DMABUF_RENDERER =
            "WEBKIT_DISABLE_DMABUF_RENDERER";
    private static final String EGL_LOG_LEVEL = "EGL_LOG_LEVEL";
    private static final String WEBKITGTK_LDCONFIG_MARKER = "libwebkit2gtk";

    LinuxBrowserPlatform(String osArch) {
        super(osArch);
    }

    @Override
    public boolean isHostRuntimeAvailable() {
        Process process = null;
        try {
            process = new ProcessBuilder("ldconfig", "-p")
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.toLowerCase(Locale.ROOT).contains(WEBKITGTK_LDCONFIG_MARKER)) {
                        return true;
                    }
                }
            }
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                log.warning("Timed out probing WebKitGTK via ldconfig; deferring to create()");
                return true;
            }
            return false;
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // Defer to WebViewComponent.create() rather than a false unavailable panel.
            log.log(Level.WARNING, "Unable to probe WebKitGTK runtime", e);
            return true;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    @Override
    public boolean canInstallDependency() {
        return true;
    }

    @Override
    public String dependencyInstallConfirmMessage() {
        return SharedLocale.tr("news.panel.install.linux.confirm");
    }

    @Override
    public DependencyInstallTask createDependencyInstallTask() throws IOException {
        return new WebKitGtkInstallTask(PackageCommand.detect());
    }

    @Override
    public void configureEnvironment(Path baseDir) throws IOException {
        setEnvironmentDefault(WEBKIT_DISABLE_COMPOSITING_MODE, "1");
        setEnvironmentDefault(WEBKIT_DISABLE_DMABUF_RENDERER, "1");
        setEnvironmentDefault(EGL_LOG_LEVEL, "fatal");
        try {
            GtkLogFilter.install();
        } catch (LinkageError | RuntimeException e) {
            log.log(Level.FINE, "Unable to install GTK diagnostic filter", e);
        }
        log.info("Configured Linux WebKit environment for weblite");
    }

    @Override
    public void configureSwing() {
        // Weblite is lightweight on Linux; keep Swing's native popup policy.
    }

    @Override
    public void openExternalUrl(URI url) throws IOException {
        String xdgOpen = new File("/usr/bin/xdg-open").canExecute()
                ? "/usr/bin/xdg-open"
                : "xdg-open";
        ProcessBuilder builder = new ProcessBuilder(xdgOpen, url.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        cleanHostEnvironment(builder).start();
    }

    /**
     * Remove AppImage/runtime library overrides before launching a host executable.
     */
    static ProcessBuilder cleanHostEnvironment(ProcessBuilder builder) {
        builder.environment().remove("LD_LIBRARY_PATH");
        builder.environment().remove("LD_PRELOAD");
        builder.environment().remove("QT_PLUGIN_PATH");
        builder.environment().remove("GTK_PATH");
        builder.environment().remove("GIO_MODULE_DIR");
        builder.environment().remove("GCONV_PATH");
        builder.environment().remove("GI_TYPELIB_PATH");
        return builder;
    }

    @Override
    public boolean applyPreferredColorScheme(
            WebViewComponent component, boolean darkTheme) {
        if (component == null) {
            return false;
        }

        CompletableFuture<Boolean> applied = new CompletableFuture<>();
        try {
            // Weblite's Linux dispatch runs on its GTK pump thread. WebKitGTK
            // observes this GtkSettings property and forwards changes to the
            // web process, updating prefers-color-scheme without navigation.
            component.dispatch(() -> {
                try {
                    Pointer settings = Gtk3.INSTANCE.gtk_settings_get_default();
                    if (settings == null) {
                        applied.complete(false);
                        return;
                    }
                    applyGtkColorScheme(settings, darkTheme);
                    applied.complete(true);
                } catch (LinkageError | RuntimeException e) {
                    applied.completeExceptionally(e);
                }
            });

            boolean updated = applied.get(2, TimeUnit.SECONDS);
            if (updated) {
                log.info("WebKitGTK prefers-color-scheme updated to "
                        + (darkTheme ? "dark" : "light"));
            }
            return updated;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException | LinkageError | RuntimeException e) {
            log.log(Level.INFO, "Unable to update WebKitGTK color scheme", e);
            return false;
        }
    }

    private static void applyGtkColorScheme(Pointer settings, boolean darkTheme) {
        if (!darkTheme) {
            Pointer environmentTheme = LibC.INSTANCE.getenv("GTK_THEME");
            if (environmentTheme != null) {
                String current = environmentTheme.getString(0);
                String light = withoutDarkVariant(current);
                if (!light.equals(current)
                        && LibC.INSTANCE.setenv("GTK_THEME", light, 1) != 0) {
                    throw new IllegalStateException("Unable to update GTK_THEME");
                }
            }

            String currentTheme = getObjectString(settings, "gtk-theme-name");
            String lightTheme = withoutDarkVariant(currentTheme);
            if (!lightTheme.equals(currentTheme)) {
                GObject.INSTANCE.g_object_set(
                        settings, "gtk-theme-name", lightTheme, Pointer.NULL);
            }
        }

        // Set this last so its notify signal observes the normalized theme name.
        GObject.INSTANCE.g_object_set(
                settings,
                "gtk-application-prefer-dark-theme",
                darkTheme ? 1 : 0,
                Pointer.NULL);
    }

    private static String getObjectString(Pointer object, String property) {
        PointerByReference value = new PointerByReference();
        GObject.INSTANCE.g_object_get(object, property, value, Pointer.NULL);
        Pointer string = value.getValue();
        if (string == null) {
            return "";
        }
        try {
            return string.getString(0);
        } finally {
            GLibMemory.INSTANCE.g_free(string);
        }
    }

    private static String withoutDarkVariant(String theme) {
        String result = theme == null ? "" : theme;
        while (true) {
            String lower = result.toLowerCase(Locale.ROOT);
            if (lower.endsWith("-dark") || lower.endsWith(":dark")) {
                result = result.substring(0, result.length() - 5);
            } else {
                return result;
            }
        }
    }

    @Override
    public String runtimePlatformKey() {
        return runtimePlatformKey("linux");
    }

    private static final class WebKitGtkInstallTask extends DependencyInstallTask {

        private final PackageCommand command;

        private WebKitGtkInstallTask(PackageCommand command) {
            this.command = command;
        }

        @Override
        public Void call() throws Exception {
            try {
                int exitCode;
                if (command.passwordlessSudo) {
                    setStatus(command.installingStatus());
                    log.info("Installing browser dependencies with passwordless sudo");
                    exitCode = run(command.sudoCommand(), false);
                } else if (command.hasElevator()) {
                    setStatus(command.passwordStatus());
                    log.info("Installing browser dependencies with native GUI askpass");
                    exitCode = run(command.elevatedCommand(), true);
                    if (exitCode != 0 && command.hasTerminal()) {
                        appendDetailLog("");
                        appendDetailLog("Native authentication failed; retrying in a terminal...");
                        setStatus(command.passwordStatus());
                        log.info("Native GUI elevation failed; retrying in terminal");
                        exitCode = run(command.terminalCommand(), false);
                    }
                } else {
                    setStatus(command.passwordStatus());
                    log.info("Native GUI askpass unavailable; installing browser dependencies in terminal");
                    exitCode = run(command.terminalCommand(), false);
                }

                if (exitCode != 0) {
                    String output = detailLog().trim();
                    String message = command.managerName + " exited with code " + exitCode;
                    if (!output.isEmpty()) {
                        message += "\n\n" + output;
                    }
                    message += "\n\n" + SharedLocale.tr(
                            "news.panel.install.linux.manual", command.manualCommand);
                    throw new IOException(message);
                }
                return null;
            } finally {
                command.cleanup();
            }
        }

        private int run(List<String> commandLine, boolean useAskPass)
                throws IOException, InterruptedException {
            ProcessBuilder builder = new ProcessBuilder(commandLine)
                    .redirectErrorStream(true);
            cleanHostEnvironment(builder);
            if (useAskPass) {
                command.applyAskPassEnvironment(builder);
            }

            Process process = builder.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    appendDetailLog(line);
                }
            }

            try {
                return process.waitFor();
            } catch (InterruptedException e) {
                process.destroyForcibly();
                throw e;
            }
        }
    }

    private static final class PackageCommand {

        private final String managerName;
        private final String sudo;
        private final boolean passwordlessSudo;
        private final Elevator elevator;
        private final Terminal terminal;
        private final String executable;
        private final List<String> arguments;
        private final List<String> fallbackArguments;
        private final String manualCommand;

        private PackageCommand(String managerName, String sudo, boolean passwordlessSudo,
                Elevator elevator, Terminal terminal, String executable,
                List<String> arguments, List<String> fallbackArguments, String manualCommand) {
            this.managerName = managerName;
            this.sudo = sudo;
            this.passwordlessSudo = passwordlessSudo;
            this.elevator = elevator;
            this.terminal = terminal;
            this.executable = executable;
            this.arguments = arguments;
            this.fallbackArguments = fallbackArguments;
            this.manualCommand = manualCommand;
        }

        private static PackageCommand detect() throws IOException {
            String sudo = findExecutable("sudo");
            boolean passwordlessSudo = canSudoWithoutPassword(sudo);
            Elevator elevator = passwordlessSudo ? null : Elevator.find(sudo);
            Terminal terminal = Terminal.find();
            String manager;

            if ((manager = findExecutable("apt-get")) != null) {
                return requireElevation(new PackageCommand(
                        "apt", sudo, passwordlessSudo, elevator, terminal, manager,
                        Arrays.asList("install", "-y", "libgtk-3-0", "libwebkit2gtk-4.1-0"),
                        Arrays.asList("install", "-y", "libgtk-3-0", "libwebkit2gtk-4.0-37"),
                        "sudo apt-get install -y libgtk-3-0 libwebkit2gtk-4.1-0"));
            }
            if ((manager = findExecutable("dnf")) != null) {
                return requireElevation(new PackageCommand(
                        "dnf", sudo, passwordlessSudo, elevator, terminal, manager,
                        Arrays.asList("install", "-y", "gtk3", "webkit2gtk4.1"),
                        Arrays.asList("install", "-y", "gtk3", "webkit2gtk3"),
                        "sudo dnf install -y gtk3 webkit2gtk4.1"));
            }
            if ((manager = findExecutable("yum")) != null) {
                return requireElevation(new PackageCommand(
                        "yum", sudo, passwordlessSudo, elevator, terminal, manager,
                        Arrays.asList("install", "-y", "gtk3", "webkit2gtk4.1"),
                        Arrays.asList("install", "-y", "gtk3", "webkit2gtk3"),
                        "sudo yum install -y gtk3 webkit2gtk4.1"));
            }
            if ((manager = findExecutable("pacman")) != null) {
                return requireElevation(new PackageCommand(
                        "pacman", sudo, passwordlessSudo, elevator, terminal, manager,
                        Arrays.asList("-S", "--noconfirm", "gtk3", "webkit2gtk-4.1"),
                        Collections.emptyList(),
                        "sudo pacman -S gtk3 webkit2gtk-4.1"));
            }
            if ((manager = findExecutable("zypper")) != null) {
                return requireElevation(new PackageCommand(
                        "zypper", sudo, passwordlessSudo, elevator, terminal, manager,
                        Arrays.asList("--non-interactive", "install",
                                "libgtk-3-0", "libwebkit2gtk-4_1-0"),
                        Collections.emptyList(),
                        "sudo zypper install libgtk-3-0 libwebkit2gtk-4_1-0"));
            }

            if (elevator != null) {
                elevator.cleanup();
            }
            throw new IOException(SharedLocale.tr("news.panel.install.linux.unsupported"));
        }

        private static PackageCommand requireElevation(PackageCommand command) throws IOException {
            if (command.sudo == null) {
                command.cleanup();
                throw new IOException(SharedLocale.tr(
                        "news.panel.install.linux.manual", command.manualCommand));
            }
            if (!command.passwordlessSudo
                    && command.elevator == null
                    && command.terminal == null) {
                command.cleanup();
                throw new IOException(SharedLocale.tr(
                        "news.panel.install.linux.manual", command.manualCommand));
            }
            return command;
        }

        private static boolean canSudoWithoutPassword(String sudo) {
            if (sudo == null) {
                return false;
            }
            try {
                ProcessBuilder builder = new ProcessBuilder(sudo, "-n", "true")
                        .redirectErrorStream(true);
                cleanHostEnvironment(builder);
                Process process = builder.start();
                // Drain output so a full pipe cannot stall the probe.
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    while (reader.readLine() != null) {
                        // ignore
                    }
                }
                return process.waitFor() == 0;
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.log(Level.FINE, "Unable to probe passwordless sudo", e);
                return false;
            }
        }

        private boolean hasElevator() {
            return elevator != null;
        }

        private boolean hasTerminal() {
            return terminal != null;
        }

        private List<String> sudoCommand() {
            List<String> command = new ArrayList<>();
            command.add(sudo);
            command.add("/bin/sh");
            command.add("-c");
            command.add(installShellCommand());
            return command;
        }

        private List<String> elevatedCommand() {
            return elevator.wrap(installShellCommand());
        }

        private List<String> terminalCommand() {
            String shellCommand = shellQuote(sudo)
                    + " /bin/sh -c " + shellQuote(installShellCommand())
                    + "; status=$?; echo; read -r -n 1 -s -p "
                    + shellQuote(SharedLocale.tr("news.panel.install.linux.closePrompt"))
                    + "; exit $status";
            return terminal.command(shellCommand);
        }

        private void applyAskPassEnvironment(ProcessBuilder builder) {
            elevator.applyEnvironment(builder);
        }

        private String installingStatus() {
            return SharedLocale.tr("news.panel.install.linux.installing", managerName);
        }

        private String passwordStatus() {
            return installingStatus()
                    + "\n" + SharedLocale.tr("news.panel.install.linux.passwordPrompt");
        }

        private void cleanup() {
            if (elevator != null) {
                elevator.cleanup();
            }
        }

        private String installShellCommand() {
            if (fallbackArguments.isEmpty()) {
                return shellCommand(arguments);
            }
            return shellCommand(arguments) + " || " + shellCommand(fallbackArguments);
        }

        private String shellCommand(List<String> args) {
            StringBuilder command = new StringBuilder(shellQuote(executable));
            for (String argument : args) {
                command.append(' ').append(shellQuote(argument));
            }
            return command.toString();
        }

        private static String shellQuote(String value) {
            return "'" + value.replace("'", "'\"'\"'") + "'";
        }

        private static String findExecutable(String name) {
            // Never elevate an executable found through an AppImage-modified PATH.
            for (String directory : Arrays.asList("/usr/bin", "/bin", "/usr/sbin", "/sbin")) {
                File file = new File(directory, name);
                if (file.isFile() && file.canExecute()) {
                    return file.getAbsolutePath();
                }
            }
            return null;
        }

        /**
         * Native toolkit password dialog used by sudo askpass.
         */
        private static final class Elevator {
            private final String sudo;
            private final String askPass;

            private Elevator(String sudo, String askPass) {
                this.sudo = sudo;
                this.askPass = askPass;
            }

            private static Elevator find(String sudo) throws IOException {
                if (sudo == null || !hasGraphicalDisplay()) {
                    return null;
                }
                String askPass = createToolkitAskPass();
                return askPass != null ? new Elevator(sudo, askPass) : null;
            }

            private List<String> wrap(String shellCommand) {
                List<String> command = new ArrayList<>();
                command.add(sudo);
                command.add("-A");
                command.add("/bin/sh");
                command.add("-c");
                command.add(shellCommand);
                return command;
            }

            private void applyEnvironment(ProcessBuilder builder) {
                builder.environment().put("SUDO_ASKPASS", askPass);
                builder.environment().put("SUDO_ASKPASS_REQUIRE", "force");
            }

            private void cleanup() {
                try {
                    Files.deleteIfExists(Path.of(askPass));
                } catch (IOException e) {
                    log.log(Level.FINE, "Unable to delete temporary askpass script", e);
                }
            }

            private static boolean hasGraphicalDisplay() {
                return hasEnvironmentValue("DISPLAY")
                        || hasEnvironmentValue("WAYLAND_DISPLAY");
            }

            private static boolean hasEnvironmentValue(String name) {
                String value = System.getenv(name);
                return value != null && !value.trim().isEmpty();
            }

            private static String createToolkitAskPass() throws IOException {
                String zenity = findExecutable("zenity");
                if (zenity != null) {
                    return createPasswordScript(zenity + " --password --title="
                            + shellQuote("Authentication Required"));
                }
                String kdialog = findExecutable("kdialog");
                if (kdialog != null) {
                    return createPasswordScript(kdialog + " --password "
                            + shellQuote("Authentication Required"));
                }
                return null;
            }

            private static String createPasswordScript(String commandLine) throws IOException {
                // Prefer /tmp: WSL trees under /mnt/c are often mounted noexec.
                Path tempDir = Path.of("/tmp");
                Path script = Files.isDirectory(tempDir)
                        ? Files.createTempFile(tempDir, "skcraft-askpass-", ".sh")
                        : Files.createTempFile("skcraft-askpass-", ".sh");
                Files.writeString(script,
                        "#!/bin/sh\nexec " + commandLine + "\n",
                        StandardCharsets.UTF_8);
                Set<PosixFilePermission> permissions = EnumSet.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                        PosixFilePermission.GROUP_READ,
                        PosixFilePermission.GROUP_EXECUTE,
                        PosixFilePermission.OTHERS_READ,
                        PosixFilePermission.OTHERS_EXECUTE);
                try {
                    Files.setPosixFilePermissions(script, permissions);
                } catch (UnsupportedOperationException e) {
                    if (!script.toFile().setExecutable(true)) {
                        Files.deleteIfExists(script);
                        throw new IOException("Unable to make askpass script executable");
                    }
                }
                return script.toAbsolutePath().toString();
            }
        }

        private enum Terminal {
            XFCE("xfce4-terminal", "--disable-server", "--execute"),
            GNOME("gnome-terminal", "--wait", "--"),
            KONSOLE("konsole", "-e"),
            XTERM("xterm", "-e"),
            X_TERMINAL_EMULATOR("x-terminal-emulator", "-e");

            private final String executableName;
            private final List<String> arguments;

            Terminal(String executableName, String... arguments) {
                this.executableName = executableName;
                this.arguments = Arrays.asList(arguments);
            }

            private static Terminal find() {
                for (Terminal terminal : values()) {
                    if (findExecutable(terminal.executableName) != null) {
                        return terminal;
                    }
                }
                return null;
            }

            private List<String> command(String shellCommand) {
                List<String> command = new ArrayList<>();
                command.add(findExecutable(executableName));
                command.addAll(arguments);
                command.add("/bin/bash");
                command.add("-lc");
                command.add(shellCommand);
                return command;
            }
        }
    }

    private static void setEnvironmentDefault(String name, String value)
            throws IOException {
        if (LibC.INSTANCE.setenv(name, value, 0) != 0) {
            throw new IOException("Unable to set " + name);
        }
    }

    /**
     * Suppresses only known harmless diagnostics from Weblite's offscreen GTK
     * renderer and forwards every other GLib message to the default writer.
     */
    private static final class GtkLogFilter {

        private static final int G_LOG_WRITER_HANDLED = 1;
        private static final String MISSING_FOCUS_DEVICE =
                "Event with type 12 not holding a GdkDevice.";
        private static final String DRAW_BEFORE_ALLOCATION =
                "gtk_widget_draw: assertion '!widget->priv->alloc_needed' failed";
        private static final LogWriterFunction LOG_WRITER =
                GtkLogFilter::writeLog;

        private static boolean installed;

        private GtkLogFilter() {
        }

        private static synchronized void install() {
            if (installed) {
                return;
            }
            GLib.INSTANCE.g_log_set_writer_func(LOG_WRITER, null, null);
            installed = true;
        }

        private static int writeLog(
                int level, Pointer fields, NativeLong fieldCount, Pointer userData) {
            String domain = null;
            String message = null;
            GLogField field = new GLogField();
            int fieldSize = field.size();
            for (int i = 0; i < fieldCount.intValue(); i++) {
                GLogField value = new GLogField(
                        fields.share((long) i * fieldSize));
                if (value.value == null) {
                    continue;
                }
                if ("GLIB_DOMAIN".equals(value.key)) {
                    domain = value.value.getString(0);
                } else if ("MESSAGE".equals(value.key)) {
                    message = value.value.getString(0);
                }
            }

            if (isHarmlessWebliteDiagnostic(domain, message)) {
                return G_LOG_WRITER_HANDLED;
            }
            return GLib.INSTANCE.g_log_writer_default(
                    level, fields, fieldCount, userData);
        }

        private static boolean isHarmlessWebliteDiagnostic(
                String domain, String message) {
            return ("Gdk".equals(domain)
                    && message != null
                    && message.startsWith(MISSING_FOCUS_DEVICE))
                    || ("Gtk".equals(domain)
                    && DRAW_BEFORE_ALLOCATION.equals(message));
        }

        public static final class GLogField extends Structure {

            public String key;
            public Pointer value;
            public NativeLong length;

            public GLogField() {
            }

            public GLogField(Pointer pointer) {
                super(pointer);
                read();
            }

            @Override
            protected List<String> getFieldOrder() {
                return Arrays.asList("key", "value", "length");
            }
        }

        private interface LogWriterFunction extends Callback {

            int invoke(
                    int level, Pointer fields, NativeLong fieldCount, Pointer userData);
        }

        private interface GLib extends Library {

            GLib INSTANCE = Native.load("glib-2.0", GLib.class);

            void g_log_set_writer_func(
                    LogWriterFunction function, Pointer userData, Pointer destroy);

            int g_log_writer_default(
                    int level, Pointer fields, NativeLong fieldCount, Pointer userData);
        }
    }

    private interface LibC extends Library {

        LibC INSTANCE = Native.load("c", LibC.class);

        Pointer getenv(String name);

        int setenv(String name, String value, int overwrite);
    }

    private interface Gtk3 extends Library {

        Gtk3 INSTANCE = Native.load("gtk-3", Gtk3.class);

        Pointer gtk_settings_get_default();
    }

    private interface GObject extends Library {

        GObject INSTANCE = Native.load("gobject-2.0", GObject.class);

        void g_object_set(
                Pointer object, String firstPropertyName, Object... arguments);

        void g_object_get(
                Pointer object, String firstPropertyName, Object... arguments);
    }

    private interface GLibMemory extends Library {

        GLibMemory INSTANCE = Native.load("glib-2.0", GLibMemory.class);

        void g_free(Pointer memory);
    }
}
