/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.creator.dialog;

import com.google.common.base.Strings;
import com.jidesoft.swing.SearchableUtils;
import com.jidesoft.swing.TableSearchable;
import com.skcraft.launcher.Launcher;
import com.skcraft.launcher.LauncherUtils;
import com.skcraft.launcher.builder.BuilderConfig;
import com.skcraft.launcher.builder.FeaturePattern;
import com.skcraft.launcher.builder.FnPatternList;
import com.skcraft.launcher.creator.Creator;
import com.skcraft.launcher.creator.model.swing.FeaturePatternTableModel;
import com.skcraft.launcher.model.minecraft.JavaVersion;
import com.skcraft.launcher.model.minecraft.ReleaseList;
import com.skcraft.launcher.model.minecraft.Version;
import com.skcraft.launcher.model.minecraft.runtime.RuntimeInfo;
import com.skcraft.launcher.model.minecraft.runtime.RuntimeList;
import com.skcraft.launcher.model.minecraft.runtime.RuntimePlatform;
import com.skcraft.launcher.model.modpack.LaunchModifier;
import com.skcraft.launcher.swing.GroupedComboBox;
import com.skcraft.launcher.swing.SwingHelper;
import com.skcraft.launcher.swing.TextFieldPopupMenu;
import com.skcraft.launcher.util.Environment;
import com.skcraft.launcher.util.HttpRequest;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BuilderConfigDialog extends JDialog {

    private static final Pattern MAJOR_VERSION_PATTERN = Pattern.compile("^(\\d+\\.\\d+)");
    private static final String OTHER_MAJOR_GROUP = "Other";

    private final JTextField nameText = new JTextField(20);
    private final JTextField titleText = new JTextField(30);
    private final JComboBox<Object> gameVersionBox = new JComboBox<>();
    private final JComboBox<RuntimeChoice> javaVersionBox = new JComboBox<>();
    private final JSpinner minMemorySpinner = new JSpinner(new SpinnerNumberModel(0, 0, 65536, 128));
    private final JSpinner maxMemorySpinner = new JSpinner(new SpinnerNumberModel(0, 0, 65536, 128));
    private final JTextArea launchFlagsArea = new JTextArea(10, 40);
    private final JTextArea userFilesIncludeArea = new JTextArea(15, 40);
    private final JTextArea userFilesExcludeArea = new JTextArea(8, 40);
    private final FeaturePatternTable featuresTable = new FeaturePatternTable();
    private FeaturePatternTableModel featuresModel;

    private final BuilderConfig config;
    private boolean saved = false;

    public BuilderConfigDialog(Window parent, BuilderConfig config) {
        super(parent, "Modpack Properties", ModalityType.DOCUMENT_MODAL);

        this.config = config;

        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        initComponents();
        setResizable(false);
        pack();
        setLocationRelativeTo(parent);

        copyFrom();

        nameText.requestFocus();
    }

    private void initComponents() {
        nameText.setComponentPopupMenu(TextFieldPopupMenu.INSTANCE);
        titleText.setComponentPopupMenu(TextFieldPopupMenu.INSTANCE);
        launchFlagsArea.setComponentPopupMenu(TextFieldPopupMenu.INSTANCE);
        userFilesIncludeArea.setComponentPopupMenu(TextFieldPopupMenu.INSTANCE);

        gameVersionBox.setEditable(true);
        gameVersionBox.setRenderer(new GameVersionRenderer());
        Component editorComponent = gameVersionBox.getEditor().getEditorComponent();
        if (editorComponent instanceof JTextField) {
            ((JTextField) editorComponent).setComponentPopupMenu(TextFieldPopupMenu.INSTANCE);
        }

        launchFlagsArea.setFont(nameText.getFont());
        userFilesIncludeArea.setFont(nameText.getFont());
        userFilesExcludeArea.setFont(nameText.getFont());

        JTabbedPane tabbedPane = new JTabbedPane();

        JPanel container = new JPanel();
        container.setLayout(new MigLayout("fill, insets dialog"));

        tabbedPane.addTab("Modpack", null, createMainPanel());
        tabbedPane.addTab("Launch", null, createLaunchPanel());
        tabbedPane.addTab("User Files", null, createUserFilesPanel());
        tabbedPane.addTab("Optional Features", null, createFeaturesPanel());

        container.add(tabbedPane, "span, grow, gapbottom unrel");

        JButton saveButton = new JButton("Save");
        JButton cancelButton = new JButton("Cancel");

        container.add(saveButton, "tag ok, span, split 2, sizegroup bttn");
        container.add(cancelButton, "tag cancel, sizegroup bttn");

        getRootPane().setDefaultButton(saveButton);
        getRootPane().registerKeyboardAction(event -> cancelButton.doClick(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        add(container, BorderLayout.CENTER);

        saveButton.addActionListener(e -> {
            if (nameText.getText().trim().isEmpty()) {
                SwingHelper.showErrorDialog(BuilderConfigDialog.this, "The 'Name' field cannot be empty.", "Input Error");
                return;
            }

            if (getGameVersionText().isEmpty()) {
                SwingHelper.showErrorDialog(BuilderConfigDialog.this,
                        "The 'Game Version' field must be a Minecraft version.", "Input Error");
                return;
            }

            RuntimeChoice choice = getSelectedRuntimeChoice();
            if (choice != null && !choice.isDefault() && choice.majorVersion <= 0) {
                SwingHelper.showErrorDialog(BuilderConfigDialog.this, "The selected Java version is not usable.", "Input Error");
                return;
            }

            copyTo();
            saved = true;
            dispose();
        });

        cancelButton.addActionListener(e -> dispose());

        TableSearchable tableSearchable = SearchableUtils.installSearchable(featuresTable);
        tableSearchable.setMainIndex(-1);
    }

    private JPanel createMainPanel() {
        JPanel container = new JPanel();
        SwingHelper.removeOpaqueness(container);
        container.setLayout(new MigLayout("insets dialog"));

        container.add(new JLabel("Name:"));
        container.add(nameText, "span");

        container.add(new JLabel("Title:"));
        container.add(titleText, "span");

        container.add(new JLabel("Game Version:"));
        container.add(gameVersionBox, "span, growx, w 220!");

        container.add(new JLabel("Java Version:"));
        container.add(javaVersionBox, "span");

        return container;
    }

    private JPanel createLaunchPanel() {
        JPanel container = new JPanel();
        SwingHelper.removeOpaqueness(container);
        container.setLayout(new MigLayout("insets dialog"));

        container.add(new JLabel("Minimum Memory (Xms, MB):"));
        container.add(minMemorySpinner, "wrap");

        container.add(new JLabel("Maximum Memory (Xmx, MB):"));
        container.add(maxMemorySpinner, "wrap, gapbottom unrel");

        SwingHelper.enableSpinnerMouseWheel(minMemorySpinner, maxMemorySpinner);
        SwingHelper.linkMinMaxSpinners(minMemorySpinner, maxMemorySpinner);

        container.add(new JLabel("Launch Flags:"), "wrap");
        container.add(SwingHelper.wrapScrollPane(launchFlagsArea), "span");

        return container;
    }

    private JPanel createUserFilesPanel() {
        JPanel container = new JPanel();
        SwingHelper.removeOpaqueness(container);
        container.setLayout(new MigLayout("insets dialog"));

        container.add(new JLabel("Include Patterns:"), "wrap");
        container.add(SwingHelper.wrapScrollPane(userFilesIncludeArea), "span, gapbottom unrel");

        container.add(new JLabel("Exclude Patterns:"), "wrap");
        container.add(SwingHelper.wrapScrollPane(userFilesExcludeArea), "span");

        return container;
    }

    private JPanel createFeaturesPanel() {
        JPanel container = new JPanel();
        SwingHelper.removeOpaqueness(container);
        container.setLayout(new MigLayout("fill, insets dialog"));

        JButton newButton = new JButton("New...");
        JButton editButton = new JButton("Edit...");
        JButton deleteButton = new JButton("Delete...");
        JButton moveUpButton = new JButton(SwingHelper.createIcon(Creator.class, "move_up.png"));
        JButton moveDownButton = new JButton(SwingHelper.createIcon(Creator.class, "move_down.png"));
        moveUpButton.setToolTipText("Move Up");
        moveDownButton.setToolTipText("Move Down");
        moveUpButton.setEnabled(false);
        moveDownButton.setEnabled(false);

        container.add(newButton, "span, split 5, sizegroup bttn");
        container.add(editButton, "sizegroup bttn");
        container.add(deleteButton, "sizegroup bttn");
        container.add(moveUpButton, "w 26!, h 26!");
        container.add(moveDownButton, "w 26!, h 26!");

        container.add(SwingHelper.wrapScrollPane(featuresTable), "grow, w 10:100:null, gaptop 10");

        Runnable updateMoveButtons = () -> {
            if (featuresModel == null) {
                moveUpButton.setEnabled(false);
                moveDownButton.setEnabled(false);
                return;
            }
            int index = getSelectedFeatureIndex();
            int rowCount = featuresModel.getRowCount();
            moveUpButton.setEnabled(index > 0);
            moveDownButton.setEnabled(index > -1 && index < rowCount - 1);
        };

        featuresTable.getSelectionModel().addListSelectionListener(e -> updateMoveButtons.run());

        newButton.addActionListener(e -> {
            FeaturePattern pattern = new FeaturePattern();
            if (FeaturePatternDialog.showEditor(BuilderConfigDialog.this, pattern)) {
                featuresModel.addFeature(pattern);
                selectFeatureByModelIndex(featuresModel.getRowCount() - 1);
                updateMoveButtons.run();
            }
        });

        editButton.addActionListener(e -> {
            int index = getSelectedFeatureIndex();
            if (index > -1) {
                FeaturePattern pattern = featuresModel.getFeature(index);
                FeaturePatternDialog.showEditor(BuilderConfigDialog.this, pattern);
                featuresModel.fireTableDataChanged();
                selectFeatureByModelIndex(index);
                updateMoveButtons.run();
            } else {
                SwingHelper.showErrorDialog(BuilderConfigDialog.this, "Select a feature first.", "No Selection");
            }
        });

        deleteButton.addActionListener(e -> {
            int index = getSelectedFeatureIndex();
            if (index > -1) {
                FeaturePattern pattern = featuresModel.getFeature(index);
                if (SwingHelper.confirmDialog(BuilderConfigDialog.this,
                        "Are you sure that you want to delete '" + pattern.getFeature().getName() + "'?", "Delete")) {
                    featuresModel.removeFeature(index);
                    selectFeatureByModelIndex(Math.min(index, featuresModel.getRowCount() - 1));
                    updateMoveButtons.run();
                }
            } else {
                SwingHelper.showErrorDialog(BuilderConfigDialog.this, "Select a feature first.", "No Selection");
            }
        });

        moveUpButton.addActionListener(e -> {
            int index = getSelectedFeatureIndex();
            if (index > 0) {
                featuresModel.moveFeatureUp(index);
                selectFeatureByModelIndex(index - 1);
                updateMoveButtons.run();
            }
        });

        moveDownButton.addActionListener(e -> {
            int index = getSelectedFeatureIndex();
            if (index > -1 && index < featuresModel.getRowCount() - 1) {
                featuresModel.moveFeatureDown(index);
                selectFeatureByModelIndex(index + 1);
                updateMoveButtons.run();
            }
        });

        return container;
    }

    private int getSelectedFeatureIndex() {
        int viewRow = featuresTable.getSelectedRow();
        if (viewRow < 0) {
            return -1;
        }
        return featuresTable.convertRowIndexToModel(viewRow);
    }

    private void selectFeatureByModelIndex(int modelIndex) {
        if (modelIndex < 0 || modelIndex >= featuresModel.getRowCount()) {
            featuresTable.clearSelection();
            return;
        }

        int viewIndex = featuresTable.convertRowIndexToView(modelIndex);
        if (viewIndex < 0) {
            featuresTable.clearSelection();
            return;
        }

        featuresTable.setRowSelectionInterval(viewIndex, viewIndex);
        featuresTable.scrollRectToVisible(featuresTable.getCellRect(viewIndex, 0, true));
    }

    private void copyFrom() {
        SwingHelper.setTextAndResetCaret(nameText, config.getName());
        SwingHelper.setTextAndResetCaret(titleText, config.getTitle());
        loadGameVersions(config.getGameVersion());
        JavaVersion javaVersion = config.getJavaVersion();
        loadRuntimeChoices(javaVersion);
        selectRuntimeChoice(javaVersion);
        minMemorySpinner.setValue(config.getLaunchModifier().getMinMemory());
        maxMemorySpinner.setValue(config.getLaunchModifier().getMaxMemory());
        SwingHelper.setTextAndResetCaret(launchFlagsArea,
                SwingHelper.listToLines(config.getLaunchModifier().getFlags()));
        SwingHelper.setTextAndResetCaret(userFilesIncludeArea,
                SwingHelper.listToLines(config.getUserFiles().getInclude()));
        SwingHelper.setTextAndResetCaret(userFilesExcludeArea,
                SwingHelper.listToLines(config.getUserFiles().getExclude()));
        featuresModel = new FeaturePatternTableModel(config.getFeatures());
        featuresTable.setModel(featuresModel);
    }

    private void copyTo() {
        config.setName(nameText.getText().trim());
        config.setTitle(Strings.emptyToNull(titleText.getText().trim()));
        config.setGameVersion(getGameVersionText());
        RuntimeChoice choice = getSelectedRuntimeChoice();
        String component = choice != null ? Strings.emptyToNull(choice.component) : null;
        if (component == null) {
            config.setJavaVersion(null);
        } else {
            JavaVersion javaVersion = new JavaVersion();
            javaVersion.setComponent(component);
            javaVersion.setMajorVersion(choice.majorVersion);
            config.setJavaVersion(javaVersion);
        }

        LaunchModifier launchModifier = config.getLaunchModifier();
        FnPatternList userFiles = config.getUserFiles();

        launchModifier.setMinMemory((int) minMemorySpinner.getValue());
        launchModifier.setMaxMemory((int) maxMemorySpinner.getValue());
        launchModifier.setFlags(SwingHelper.linesToList(launchFlagsArea.getText()));
        userFiles.setInclude(SwingHelper.linesToList(userFilesIncludeArea.getText()));
        userFiles.setExclude(SwingHelper.linesToList(userFilesExcludeArea.getText()));
    }

    public static boolean showEditor(Window window, BuilderConfig config) {
        BuilderConfigDialog dialog = new BuilderConfigDialog(window, config);
        dialog.setVisible(true);
        return dialog.saved;
    }

    private String getGameVersionText() {
        Component editorComponent = gameVersionBox.getEditor().getEditorComponent();
        if (editorComponent instanceof JTextField) {
            return ((JTextField) editorComponent).getText().trim();
        }
        Object selected = gameVersionBox.getSelectedItem();
        if (selected instanceof GameVersionOption) {
            return ((GameVersionOption) selected).id;
        }
        return Strings.nullToEmpty(selected != null ? selected.toString() : "").trim();
    }

    private void setGameVersionEditorText(String text) {
        Component editorComponent = gameVersionBox.getEditor().getEditorComponent();
        if (editorComponent instanceof JTextField) {
            ((JTextField) editorComponent).setText(Strings.nullToEmpty(text));
        }
    }

    private void selectGameVersion(String version) {
        String selected = Strings.nullToEmpty(version).trim();
        if (selected.isEmpty()) {
            gameVersionBox.setSelectedItem(null);
            setGameVersionEditorText("");
            return;
        }

        ComboBoxModel<Object> model = gameVersionBox.getModel();
        for (int i = 0; i < model.getSize(); i++) {
            Object element = model.getElementAt(i);
            if (element instanceof GameVersionOption
                    && selected.equals(((GameVersionOption) element).id)) {
                gameVersionBox.setSelectedItem(element);
                setGameVersionEditorText(selected);
                return;
            }
        }

        gameVersionBox.setSelectedItem(null);
        setGameVersionEditorText(selected);
    }

    private void loadGameVersions(String selectedVersion) {
        GroupedComboBox.Model model = new GroupedComboBox.Model();

        try {
            List<Version> versions = fetchGameVersions();
            model = buildGroupedGameVersionModel(versions, selectedVersion);
        } catch (Exception e) {
            SwingHelper.showErrorDialog(this,
                    "Failed to load Minecraft versions from Mojang. Existing values can still be preserved.",
                    "Game Versions", e);
            String selected = Strings.emptyToNull(Strings.nullToEmpty(selectedVersion).trim());
            if (selected != null) {
                model.addGroup(majorGroupForId(selected));
                model.addElement(new GameVersionOption(selected, null));
            }
        }

        gameVersionBox.setModel(model);
        selectGameVersion(selectedVersion);
    }

    private static GroupedComboBox.Model buildGroupedGameVersionModel(
            List<Version> versions, String selectedVersion) {
        GroupedComboBox.Model model = new GroupedComboBox.Model();
        if (versions == null) {
            versions = Collections.emptyList();
        }

        List<Version> dated = new ArrayList<>(versions);
        Map<Version, Integer> originalIndex = new HashMap<>();
        for (int i = 0; i < dated.size(); i++) {
            originalIndex.put(dated.get(i), i);
        }

        Comparator<Version> descending = (a, b) -> compareByReleaseDate(a, b, originalIndex, false);

        List<Version> newestFirst = new ArrayList<>();
        for (Version version : dated) {
            String id = version.getId();
            // Skip week-format / other unversioned snapshots (e.g. 24w14a); keep 1.21-pre1, etc.
            if (Strings.isNullOrEmpty(id) || parseMajorGroup(id) == null) {
                continue;
            }
            newestFirst.add(version);
        }
        newestFirst.sort(descending);

        String lastMajor = null;
        for (Version version : newestFirst) {
            String id = version.getId();
            String major = parseMajorGroup(id);
            if (major == null) {
                continue;
            }
            if (!major.equals(lastMajor)) {
                model.addGroup(major);
                lastMajor = major;
            }
            model.addElement(new GameVersionOption(id, version.getType()));
        }

        String selected = Strings.emptyToNull(Strings.nullToEmpty(selectedVersion).trim());
        if (selected != null && !containsGameVersion(model, selected)) {
            insertCustomGameVersion(model, selected);
        }

        return model;
    }

    private static void insertCustomGameVersion(GroupedComboBox.Model model, String selected) {
        String major = majorGroupForId(selected);
        int groupIndex = -1;
        int insertIndex = model.getSize();

        for (int i = 0; i < model.getSize(); i++) {
            Object element = model.getElementAt(i);
            if (element instanceof GroupedComboBox.Group) {
                if (groupIndex >= 0) {
                    insertIndex = i;
                    break;
                }
                if (major.equals(((GroupedComboBox.Group) element).getLabel())) {
                    groupIndex = i;
                }
            }
        }

        if (groupIndex < 0) {
            model.addGroup(major);
            model.addElement(new GameVersionOption(selected, null));
            return;
        }

        model.insertElementAt(new GameVersionOption(selected, null), insertIndex);
    }

    private static String majorGroupForId(String id) {
        String parsed = parseMajorGroup(id);
        return parsed != null ? parsed : OTHER_MAJOR_GROUP;
    }

    private static String parseMajorGroup(String id) {
        Matcher matcher = MAJOR_VERSION_PATTERN.matcher(id);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private static boolean containsGameVersion(ComboBoxModel<Object> model, String versionId) {
        for (int i = 0; i < model.getSize(); i++) {
            Object element = model.getElementAt(i);
            if (element instanceof GameVersionOption
                    && versionId.equals(((GameVersionOption) element).id)) {
                return true;
            }
        }
        return false;
    }

    private static List<Version> fetchGameVersions() throws IOException, InterruptedException {
        Properties launcherProperties = LauncherUtils.loadProperties(
                Launcher.class, "launcher.properties", "com.skcraft.launcher.propertiesFile");
        String versionManifestUrl = launcherProperties.getProperty("versionManifestUrl");

        ReleaseList releases = HttpRequest.get(HttpRequest.url(versionManifestUrl))
                .execute()
                .expectResponseCode(200)
                .returnContent()
                .asJson(ReleaseList.class);

        List<Version> versions = releases.getVersions();
        return versions != null ? versions : Collections.emptyList();
    }

    private static int compareByReleaseDate(Version a, Version b, Map<Version, Integer> originalIndex,
                                            boolean ascending) {
        long timeA = parseVersionTime(a);
        long timeB = parseVersionTime(b);
        boolean datedA = timeA != Long.MIN_VALUE;
        boolean datedB = timeB != Long.MIN_VALUE;

        if (datedA && datedB) {
            int cmp = Long.compare(timeA, timeB);
            return ascending ? cmp : -cmp;
        }
        // Undated entries always follow dated ones; preserve manifest order among themselves.
        if (datedA) {
            return -1;
        }
        if (datedB) {
            return 1;
        }

        int indexA = originalIndex.getOrDefault(a, 0);
        int indexB = originalIndex.getOrDefault(b, 0);
        return Integer.compare(indexA, indexB);
    }

    private static long parseVersionTime(Version version) {
        String timestamp = Strings.emptyToNull(version.getReleaseTime());
        if (timestamp == null) {
            timestamp = Strings.emptyToNull(version.getTime());
        }
        if (timestamp == null) {
            return Long.MIN_VALUE;
        }

        try {
            return OffsetDateTime.parse(timestamp).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
        }

        try {
            return Instant.parse(timestamp).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            return Long.MIN_VALUE;
        }
    }

    private void selectRuntimeChoice(JavaVersion selectedVersion) {
        if (selectedVersion == null) {
            javaVersionBox.setSelectedIndex(0);
            return;
        }

        String component = selectedVersion.getComponent();
        ComboBoxModel<RuntimeChoice> model = javaVersionBox.getModel();
        for (int i = 0; i < model.getSize(); i++) {
            RuntimeChoice choice = model.getElementAt(i);
            if (Strings.nullToEmpty(component).equals(choice.component)) {
                javaVersionBox.setSelectedItem(choice);
                return;
            }
        }

        for (int i = 0; i < model.getSize(); i++) {
            RuntimeChoice choice = model.getElementAt(i);
            if (!choice.isDefault() && selectedVersion.getMajorVersion() == choice.majorVersion) {
                javaVersionBox.setSelectedItem(choice);
                return;
            }
        }

        javaVersionBox.setSelectedIndex(0);
    }

    private RuntimeChoice getSelectedRuntimeChoice() {
        return (RuntimeChoice) javaVersionBox.getSelectedItem();
    }

    private void loadRuntimeChoices(JavaVersion selectedVersion) {
        DefaultComboBoxModel<RuntimeChoice> model = new DefaultComboBoxModel<>();
        model.addElement(RuntimeChoice.useDefault());

        try {
            List<RuntimeChoice> runtimeChoices = fetchRuntimeChoices();
            for (RuntimeChoice choice : runtimeChoices) {
                model.addElement(choice);
            }
        } catch (Exception e) {
            SwingHelper.showErrorDialog(this,
                    "Failed to load Java versions from Mojang. Existing values can still be preserved.",
                    "Java Versions", e);
        }

        if (selectedVersion != null && Strings.emptyToNull(selectedVersion.getComponent()) != null
                && !containsRuntimeChoice(model, selectedVersion)) {
            model.addElement(new RuntimeChoice(
                    selectedVersion.getComponent(),
                    selectedVersion.getMajorVersion(),
                    formatJavaMajorVersion(selectedVersion.getMajorVersion()) + " (saved override)"));
        }

        javaVersionBox.setModel(model);
    }

    private static boolean containsRuntimeChoice(ComboBoxModel<RuntimeChoice> model, JavaVersion selectedVersion) {
        for (int i = 0; i < model.getSize(); i++) {
            RuntimeChoice choice = model.getElementAt(i);
            if (selectedVersion.getComponent().equals(choice.component)
                    || selectedVersion.getMajorVersion() == choice.majorVersion) {
                return true;
            }
        }
        return false;
    }

    private static List<RuntimeChoice> fetchRuntimeChoices() throws IOException, InterruptedException {
        Properties creatorProperties = LauncherUtils.loadProperties(
                Creator.class, "creator.properties", "com.skcraft.launcher.creator.propertiesFile");
        String runtimeManifestUrl = Strings.emptyToNull(creatorProperties.getProperty("runtimeManifestUrl"));
        if (runtimeManifestUrl == null) {
            Properties launcherProperties = LauncherUtils.loadProperties(
                    Launcher.class, "launcher.properties", "com.skcraft.launcher.propertiesFile");
            runtimeManifestUrl = launcherProperties.getProperty("runtimeManifestUrl");
        }

        RuntimePlatform platform = RuntimePlatform.from(Environment.getInstance());
        RuntimeList runtimes = HttpRequest.get(HttpRequest.url(runtimeManifestUrl))
                .execute()
                .expectResponseCode(200)
                .returnContent()
                .asJson(RuntimeList.class);

        Map<Integer, RuntimeChoice> choicesByMajorVersion = new TreeMap<>();
        if (platform == null || runtimes.getRuntimesByPlatform() == null) {
            return new ArrayList<>();
        }

        Map<String, List<RuntimeInfo>> platformRuntimes = runtimes.getRuntimesByPlatform().get(platform.getId());
        if (platformRuntimes == null) {
            return new ArrayList<>();
        }

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

            RuntimeChoice choice = new RuntimeChoice(component, majorVersion, formatJavaMajorVersion(majorVersion));
            RuntimeChoice current = choicesByMajorVersion.get(majorVersion);
            if (current == null || isPreferredRuntimeChoice(choice, current)) {
                choicesByMajorVersion.put(majorVersion, choice);
            }
        }

        return new ArrayList<>(choicesByMajorVersion.values());
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

    private static boolean isPreferredRuntimeChoice(RuntimeChoice candidate, RuntimeChoice current) {
        int candidatePriority = getRuntimeComponentPriority(candidate.component);
        int currentPriority = getRuntimeComponentPriority(current.component);
        if (candidatePriority != currentPriority) {
            return candidatePriority < currentPriority;
        }

        return candidate.component.compareTo(current.component) > 0;
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

    private static String formatJavaMajorVersion(int majorVersion) {
        return majorVersion == 8 ? "1.8" : String.valueOf(majorVersion);
    }

    private static final class GameVersionOption {
        private final String id;
        private final String type;

        private GameVersionOption(String id, String type) {
            this.id = id;
            this.type = type;
        }

        @Override
        public String toString() {
            return id;
        }
    }

    private static final class GameVersionRenderer implements ListCellRenderer<Object> {
        private final JPanel panel = new JPanel(new BorderLayout(8, 0));
        private final JLabel nameLabel = new JLabel();
        private final JLabel typeLabel = new JLabel();

        private GameVersionRenderer() {
            panel.setOpaque(true);
            nameLabel.setOpaque(false);
            typeLabel.setOpaque(false);
            panel.add(nameLabel, BorderLayout.WEST);
            panel.add(typeLabel, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            boolean inPopup = index >= 0;
            boolean isGroup = value instanceof GroupedComboBox.Group;

            Color background;
            Color foreground;
            if (!isGroup && isSelected) {
                background = list.getSelectionBackground();
                foreground = list.getSelectionForeground();
            } else {
                background = list.getBackground();
                foreground = list.getForeground();
            }
            panel.setBackground(background);
            nameLabel.setForeground(foreground);
            typeLabel.setForeground(foreground);

            if (value == null) {
                nameLabel.setText("");
                typeLabel.setText("");
                typeLabel.setVisible(false);
                panel.setBorder(BorderFactory.createEmptyBorder());
                return panel;
            }

            if (isGroup) {
                nameLabel.setText(((GroupedComboBox.Group) value).getLabel());
                nameLabel.setFont(list.getFont().deriveFont(Font.BOLD));
                Color disabled = UIManager.getColor("Label.disabledForeground");
                if (disabled != null) {
                    nameLabel.setForeground(disabled);
                }
                typeLabel.setText("");
                typeLabel.setVisible(false);
                if (inPopup && index > 0) {
                    Color separator = UIManager.getColor("Separator.foreground");
                    if (separator == null) {
                        separator = UIManager.getColor("Component.borderColor");
                    }
                    Border line = separator != null
                            ? BorderFactory.createMatteBorder(1, 0, 0, 0, separator)
                            : BorderFactory.createEmptyBorder();
                    panel.setBorder(BorderFactory.createCompoundBorder(
                            BorderFactory.createEmptyBorder(6, 0, 0, 0),
                            BorderFactory.createCompoundBorder(line,
                                    BorderFactory.createEmptyBorder(2, 8, 2, 8))));
                } else if (inPopup) {
                    panel.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
                } else {
                    panel.setBorder(BorderFactory.createEmptyBorder());
                }
                return panel;
            }

            GameVersionOption option = (GameVersionOption) value;
            nameLabel.setText(option.id);
            nameLabel.setFont(list.getFont().deriveFont(Font.PLAIN));
            String typeText = formatVersionType(option.type);
            typeLabel.setText(typeText);
            typeLabel.setVisible(inPopup && !typeText.isEmpty());
            typeLabel.setFont(list.getFont().deriveFont(Font.PLAIN));
            if (!isSelected) {
                Color muted = UIManager.getColor("Label.disabledForeground");
                if (muted != null) {
                    typeLabel.setForeground(muted);
                }
            }
            panel.setBorder(inPopup
                    ? BorderFactory.createEmptyBorder(2, 16, 2, 8)
                    : BorderFactory.createEmptyBorder());
            return panel;
        }
    }

    private static String formatVersionType(String type) {
        if (Strings.isNullOrEmpty(type)) {
            return "";
        }
        String normalized = type.trim().replace('_', ' ');
        if (normalized.isEmpty()) {
            return "";
        }
        StringBuilder formatted = new StringBuilder(normalized.length());
        boolean capitalizeNext = true;
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (Character.isWhitespace(c)) {
                formatted.append(c);
                capitalizeNext = true;
            } else if (capitalizeNext) {
                formatted.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                formatted.append(Character.toLowerCase(c));
            }
        }
        return formatted.toString();
    }

    private static class RuntimeChoice {
        private final String component;
        private final int majorVersion;
        private final String label;

        private RuntimeChoice(String component, int majorVersion, String label) {
            this.component = component;
            this.majorVersion = majorVersion;
            this.label = label;
        }

        private static RuntimeChoice useDefault() {
            return new RuntimeChoice("", 0, "Use Minecraft default");
        }

        private boolean isDefault() {
            return component.isEmpty();
        }

        @Override
        public String toString() {
            return label;
        }
    }

}
