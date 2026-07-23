package com.skcraft.launcher.dialog;

import com.skcraft.launcher.Instance;
import com.skcraft.launcher.InstanceSettings;
import com.skcraft.launcher.Launcher;
import com.skcraft.launcher.launch.JavaProcessBuilder;
import com.skcraft.launcher.launch.MemoryRequirements;
import com.skcraft.launcher.launch.MemorySettings;
import com.skcraft.launcher.launch.runtime.AddJavaRuntime;
import com.skcraft.launcher.launch.runtime.JavaRuntime;
import com.skcraft.launcher.launch.runtime.JavaRuntimeFinder;
import com.skcraft.launcher.model.minecraft.JavaVersion;
import com.skcraft.launcher.model.minecraft.VersionManifest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skcraft.launcher.model.modpack.LaunchModifier;
import com.skcraft.launcher.persistence.Persistence;
import com.skcraft.launcher.swing.FormPanel;
import com.skcraft.launcher.swing.GroupedComboBox;
import com.skcraft.launcher.swing.LinedBoxPanel;
import com.skcraft.launcher.swing.SwingHelper;
import com.skcraft.launcher.update.runtime.JavaVersionResolver;
import com.skcraft.launcher.update.runtime.ManagedRuntimeOption;
import com.skcraft.launcher.model.minecraft.runtime.RuntimePlatform;
import com.skcraft.launcher.util.Environment;
import com.skcraft.launcher.util.SharedLocale;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileFilter;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public class InstanceSettingsDialog extends JDialog {
	private static final Logger log = Logger.getLogger(InstanceSettingsDialog.class.getName());
	private static final ObjectMapper VERSION_MAPPER = new ObjectMapper();
	private static final int MAX_CONFIGURABLE_MEMORY = 65536;

	private final Launcher launcher;
	private final Instance instance;
	private final InstanceSettings settings;

	private final LinedBoxPanel formsPanel = new LinedBoxPanel(false);
	private final FormPanel memorySettingsPanel = new FormPanel();
	private final JSpinner minMemorySpinner = new JSpinner();
	private final JSpinner maxMemorySpinner = new JSpinner();

	private final FormPanel runtimePanel = new FormPanel();
	private JComboBox<Object> javaRuntimeBox;
	private final JTextArea userJavaArgsText = new JTextArea(4, 30);
	private final JScrollPane userJavaArgsScroll = new JScrollPane(userJavaArgsText);
	private final JCheckBox modpackJvmArgsCheck = new JCheckBox(
			SharedLocale.tr("instance.options.useModpackJvmArguments"));
	private final JTextArea combinedJavaArgsText = new JTextArea(3, 30);

	private final LinedBoxPanel buttonsPanel = new LinedBoxPanel(true);
	private final JButton okButton = new JButton(SharedLocale.tr("button.save"));
	private final JButton cancelButton = new JButton(SharedLocale.tr("button.cancel"));

	private boolean saved = false;

	public InstanceSettingsDialog(Window owner, Launcher launcher, Instance instance) {
		super(owner);
		this.launcher = launcher;
		this.instance = instance;
		this.settings = getOrCreateSettings(instance);

		setTitle(instance.getTitle() + " - " + SharedLocale.tr("instance.options.title"));
		setModalityType(DEFAULT_MODALITY_TYPE);
		initComponents();
		setSize(new Dimension(480, 600));
		setLocationRelativeTo(owner);
	}

	private static InstanceSettings getOrCreateSettings(Instance instance) {
		if (instance.getSettings() == null) {
			instance.setSettings(new InstanceSettings());
		}
		return instance.getSettings();
	}

	private void initComponents() {
		initJavaRuntimeBox();
		initMemorySpinners();
		initLayout();
		initActions();

		updateComponents();
	}

	private void initMemorySpinners() {
		int minMemoryCap = getMinimumConfigurableMemory();
		minMemorySpinner
				.setModel(new SpinnerNumberModel(minMemoryCap,
						minMemoryCap, MAX_CONFIGURABLE_MEMORY, 128));
		maxMemorySpinner
				.setModel(new SpinnerNumberModel(Math.max(MemorySettings.DEFAULT_MAX_MEMORY, minMemoryCap),
						minMemoryCap, MAX_CONFIGURABLE_MEMORY, 128));
		SwingHelper.enableSpinnerMouseWheel(minMemorySpinner, maxMemorySpinner);
		SwingHelper.linkMinMaxSpinners(minMemorySpinner, maxMemorySpinner);
	}

	private int getMinimumConfigurableMemory() {
		LaunchModifier modifier = instance.getLaunchModifier();
		int requiredMinMemory = modifier != null ? modifier.getMinMemory() : 0;
		return Math.max(MemorySettings.DEFAULT_MIN_MEMORY, requiredMinMemory);
	}

	private void initJavaRuntimeBox() {
		javaRuntimeBox = GroupedComboBox.create(createRuntimeModel(Collections.emptyList()),
				this::formatRuntimeOptionLabel);
		loadManagedRuntimesAsync();
	}

	private GroupedComboBox.Model createRuntimeModel(List<ManagedRuntimeOption> managedOptions) {
		GroupedComboBox.Model model = new GroupedComboBox.Model();
		model.addElement(null);

		Set<JavaRuntime> managedDirs = new HashSet<>();
		for (ManagedRuntimeOption option : managedOptions) {
			if (option.getRuntime() != null) {
				managedDirs.add(option.getRuntime());
			}
		}

		model.addGroup(SharedLocale.tr("instance.options.runtimeGroupManaged"));
		for (ManagedRuntimeOption option : managedOptions) {
			model.addElement(option);
		}
		if (settings.getManagedRuntimeComponent() != null
				&& !containsManagedComponent(model, settings.getManagedRuntimeComponent())) {
			model.addElement(createSavedManagedRuntimeOption(settings.getManagedRuntimeComponent()));
		}

		Set<JavaRuntime> systemRuntimes = new HashSet<>(JavaRuntimeFinder.getAvailableRuntimes());
		systemRuntimes.removeAll(managedDirs);
		model.addGroup(SharedLocale.tr("instance.options.runtimeGroupSystem"));
		for (JavaRuntime javaRuntime : systemRuntimes.stream().sorted().toArray(JavaRuntime[]::new)) {
			model.addElement(javaRuntime);
		}
		if (settings.getRuntime() != null && !containsRuntime(model, settings.getRuntime())) {
			model.addElement(settings.getRuntime());
		}
		model.addElement(AddJavaRuntime.ADD_RUNTIME_SENTINEL);

		return model;
	}

	private void loadManagedRuntimesAsync() {
		new SwingWorker<List<ManagedRuntimeOption>, Void>() {
			@Override
			protected List<ManagedRuntimeOption> doInBackground() throws Exception {
				return launcher.getRuntimeManager().fetchManagedRuntimes(launcher.propUrl("runtimeManifestUrl"));
			}

			@Override
			protected void done() {
				try {
					Object selected = javaRuntimeBox.getSelectedItem();
					GroupedComboBox.Model model = createRuntimeModel(get());
					javaRuntimeBox.setModel(model);
					javaRuntimeBox.setSelectedItem(findMatchingRuntimeSelection(model, selected));
				} catch (Exception e) {
					SwingHelper.showErrorDialog(InstanceSettingsDialog.this,
							SharedLocale.tr("instance.options.managedRuntimeLoadFailed"),
							SharedLocale.tr("instance.options.managedRuntimeLoadFailedTitle"), e);
				}
			}
		}.execute();
	}

	private Object findMatchingRuntimeSelection(ComboBoxModel<Object> model, Object selection) {
		if (selection == null) {
			return null;
		}

		for (int i = 0; i < model.getSize(); i++) {
			Object element = model.getElementAt(i);
			if (selection instanceof ManagedRuntimeOption && element instanceof ManagedRuntimeOption) {
				String component = ((ManagedRuntimeOption) selection).getComponent();
				if (component.equals(((ManagedRuntimeOption) element).getComponent())) {
					return element;
				}
			} else if (selection instanceof JavaRuntime && selection.equals(element)) {
				return element;
			}
		}

		return selection;
	}

	private String formatRuntimeOptionLabel(Object value) {
		if (value instanceof GroupedComboBox.Group) {
			return ((GroupedComboBox.Group) value).getLabel();
		}
		if (value == null) {
			return getAutomaticRuntimeLabel();
		}
		if (value instanceof ManagedRuntimeOption) {
			return ((ManagedRuntimeOption) value).getDisplayLabel();
		}
		if (value instanceof AddJavaRuntime) {
			return value.toString();
		}
		if (value instanceof JavaRuntime) {
			return formatSystemRuntimeLabel((JavaRuntime) value);
		}
		return String.valueOf(value);
	}

	private String getAutomaticRuntimeLabel() {
		JavaVersion requiredVersion = readRequiredJavaVersion();
		if (requiredVersion != null && requiredVersion.getMajorVersion() > 0) {
			return SharedLocale.tr("instance.options.automaticRuntimeWithVersion",
					ManagedRuntimeOption.formatJavaMajorVersion(requiredVersion.getMajorVersion()));
		}
		return SharedLocale.tr("instance.options.automaticRuntime");
	}

	private JavaVersion readRequiredJavaVersion() {
		if (!instance.getVersionPath().isFile()) {
			return null;
		}

		try {
			VersionManifest version = VERSION_MAPPER.readValue(instance.getVersionPath(), VersionManifest.class);
			return JavaVersionResolver.resolve(launcher, instance, version);
		} catch (Exception ignored) {
			return null;
		}
	}

	private static String formatSystemRuntimeLabel(JavaRuntime runtime) {
		String version = runtime.getVersion() != null ? runtime.getVersion() : "unknown";
		return ManagedRuntimeOption.formatLabel(version, runtime.is64Bit(), runtime.getDir().getAbsolutePath());
	}

	private static boolean containsRuntime(ComboBoxModel<Object> model, JavaRuntime runtime) {
		for (int i = 0; i < model.getSize(); i++) {
			Object element = model.getElementAt(i);
			if (!GroupedComboBox.isOption(element)) {
				continue;
			}
			if (element instanceof JavaRuntime && runtime.equals(element)) {
				return true;
			}
		}
		return false;
	}

	private ManagedRuntimeOption createSavedManagedRuntimeOption(String component) {
		JavaVersion javaVersion = new JavaVersion();
		javaVersion.setComponent(component);
		return launcher.getRuntimeManager().getRuntime(javaVersion)
				.map(runtime -> new ManagedRuntimeOption(
						component, runtime.getMajorVersion(), runtime.getVersion(), runtime.is64Bit(), true, runtime))
				.orElseGet(() -> {
					RuntimePlatform platform = RuntimePlatform.from(Environment.getInstance());
					boolean is64Bit = platform != null && platform.is64Bit();
					return new ManagedRuntimeOption(component, 0, null, is64Bit, false, null);
				});
	}

	private static boolean containsManagedComponent(ComboBoxModel<Object> model, String component) {
		for (int i = 0; i < model.getSize(); i++) {
			Object element = model.getElementAt(i);
			if (!GroupedComboBox.isOption(element)) {
				continue;
			}
			if (element instanceof ManagedRuntimeOption
					&& component.equals(((ManagedRuntimeOption) element).getComponent())) {
				return true;
			}
		}
		return false;
	}

	private static int indexOfAddRuntimeSentinel(ComboBoxModel<Object> model) {
		for (int i = 0; i < model.getSize(); i++) {
			if (model.getElementAt(i) == AddJavaRuntime.ADD_RUNTIME_SENTINEL) {
				return i;
			}
		}
		return model.getSize();
	}

	private void initLayout() {
		memorySettingsPanel.addRow(new JLabel(SharedLocale.tr("options.jvmRuntime")), javaRuntimeBox);
		memorySettingsPanel.addRow(new JLabel(SharedLocale.tr("options.minMemory")), minMemorySpinner);
		memorySettingsPanel.addRow(new JLabel(SharedLocale.tr("options.maxMemory")), maxMemorySpinner);

		runtimePanel.addRow(new JLabel(SharedLocale.tr("instance.options.userJvmArguments")));
		runtimePanel.addRow(userJavaArgsScroll);
		runtimePanel.addRow(modpackJvmArgsCheck);
		runtimePanel.addRow(new JLabel(SharedLocale.tr("instance.options.combinedJvmArguments")));
		runtimePanel.addRow(combinedJavaArgsText);

		okButton.setMargin(new Insets(0, 10, 0, 10));
		buttonsPanel.addGlue();
		buttonsPanel.addElement(okButton);
		buttonsPanel.addElement(cancelButton);
		SwingHelper.alignButtonSizes(okButton, cancelButton);

		okButton.addActionListener(e -> {
			if (save()) {
				dispose();
			}
		});

		cancelButton.addActionListener(e -> dispose());

		formsPanel.addElement(memorySettingsPanel);
		formsPanel.addElement(runtimePanel);

		add(formsPanel, BorderLayout.NORTH);
		add(buttonsPanel, BorderLayout.SOUTH);

		initJvmArgsInputs();
	}

	private void initJvmArgsInputs() {
		userJavaArgsText.setLineWrap(true);
		userJavaArgsText.setWrapStyleWord(true);

		initEffectiveArgsPreview();
	}

	private void initEffectiveArgsPreview() {
		combinedJavaArgsText.setEditable(false);
		combinedJavaArgsText.setFocusable(false);
		combinedJavaArgsText.setLineWrap(true);
		combinedJavaArgsText.setWrapStyleWord(true);
		combinedJavaArgsText.setFont(UIManager.getFont("Label.font"));
		combinedJavaArgsText.setOpaque(false);
		combinedJavaArgsText.setBorder(BorderFactory.createEmptyBorder());
	}

	private void initActions() {
		userJavaArgsText.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				updateCombinedJvmArgs();
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				updateCombinedJvmArgs();
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				updateCombinedJvmArgs();
			}
		});
		minMemorySpinner.addChangeListener(e -> updateCombinedJvmArgs());
		maxMemorySpinner.addChangeListener(e -> updateCombinedJvmArgs());
		modpackJvmArgsCheck.addActionListener(e -> updateCombinedJvmArgs());

		javaRuntimeBox.addActionListener(e -> {
			if (javaRuntimeBox.getSelectedItem() == AddJavaRuntime.ADD_RUNTIME_SENTINEL) {
				javaRuntimeBox.setSelectedItem(getCurrentRuntimeSelection());
				javaRuntimeBox.setPopupVisible(false);

				JFileChooser chooser = new JFileChooser();
				chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
				chooser.setFileFilter(new JavaRuntimeFileFilter());
				chooser.setDialogTitle("Choose a Java executable");

				int result = chooser.showOpenDialog(this);
				if (result == JFileChooser.APPROVE_OPTION) {
					JavaRuntime runtime = JavaRuntimeFinder
							.getRuntimeFromPath(chooser.getSelectedFile().getAbsolutePath());

					MutableComboBoxModel<Object> comboModel = (MutableComboBoxModel<Object>) javaRuntimeBox.getModel();
					comboModel.insertElementAt(runtime, indexOfAddRuntimeSentinel(comboModel));
					javaRuntimeBox.setSelectedItem(runtime);
				}
			}
		});
	}

	private void updateComponents() {
		MemorySettings.Resolved memory = settings.getMemorySettings() == null
				? MemorySettings.resolve(instance)
				: MemorySettings.normalize(
						settings.getMemorySettings().getMinMemory(),
						settings.getMemorySettings().getMaxMemory());
		int minMemory = Math.max(getMinimumConfigurableMemory(), memory.getMinMemory());
		minMemorySpinner.setValue(minMemory);
		maxMemorySpinner.setValue(Math.max(minMemory, memory.getMaxMemory()));
		javaRuntimeBox.setSelectedItem(getCurrentRuntimeSelection());
		userJavaArgsText.setText(settings.getCustomJvmArgs() != null ? settings.getCustomJvmArgs() : "");
		modpackJvmArgsCheck.setSelected(settings.isModpackJvmArgsEnabled());
		userJavaArgsText.setCaretPosition(0);
		updateCombinedJvmArgs();
	}

	private void updateCombinedJvmArgs() {
		combinedJavaArgsText.setText(getCombinedJvmArgsText());
		combinedJavaArgsText.setCaretPosition(0);
	}

	private String getCombinedJvmArgsText() {
		List<String> args = new ArrayList<>();
		int minMemory = getEffectiveMinMemory();
		int maxMemory = getEffectiveMaxMemory(minMemory);

		String userArgs = userJavaArgsText.getText();
		if (userArgs != null && !userArgs.trim().isEmpty()) {
			args.addAll(JavaProcessBuilder.splitArgs(userArgs));
		}

		List<String> flags = instance.getLaunchModifier() != null
				? instance.getLaunchModifier().getFlags()
				: null;
		if (modpackJvmArgsCheck.isSelected() && flags != null) {
			args.addAll(flags);
		}

		args.add("-Xms" + minMemory + "M");
		args.add("-Xmx" + maxMemory + "M");

		return String.join(" ", args);
	}

	private int getEffectiveMinMemory() {
		int minMemory = (int) minMemorySpinner.getValue();
		return Math.max(getMinimumConfigurableMemory(), minMemory);
	}

	private int getEffectiveMaxMemory(int minMemory) {
		int maxMemory = (int) maxMemorySpinner.getValue();
		return Math.max(minMemory, maxMemory);
	}

	private Object getCurrentRuntimeSelection() {
		if (settings.getManagedRuntimeComponent() != null) {
			ComboBoxModel<Object> model = javaRuntimeBox.getModel();
			for (int i = 0; i < model.getSize(); i++) {
				Object element = model.getElementAt(i);
				if (element instanceof ManagedRuntimeOption
						&& settings.getManagedRuntimeComponent()
								.equals(((ManagedRuntimeOption) element).getComponent())) {
					return element;
				}
			}
		}

		return settings.getRuntime();
	}

	private boolean save() {
		int maxMemory = getEffectiveMaxMemory(getEffectiveMinMemory());
		int systemCap = MemoryRequirements.getPhysicalMemoryCapMb();
		if (maxMemory > systemCap) {
			SwingHelper.showErrorDialog(this,
					SharedLocale.tr("runner.instanceMemoryExceedsSystem",
							instance.getTitle(),
							MemorySettings.formatMemoryGb(maxMemory),
							MemorySettings.formatMemoryGb(systemCap)),
					SharedLocale.tr("launcher.insufficientSystemMemoryTitle"));
			return false;
		}

		MemorySettings memorySettings = settings.getMemorySettings();
		if (memorySettings == null) {
			memorySettings = new MemorySettings();
			settings.setMemorySettings(memorySettings);
		}

		int minMemory = getEffectiveMinMemory();
		memorySettings.setMinMemory(minMemory);
		memorySettings.setMaxMemory(getEffectiveMaxMemory(minMemory));
		Object selectedRuntime = javaRuntimeBox.getSelectedItem();
		if (selectedRuntime == null) {
			settings.setRuntime(null);
			settings.setManagedRuntimeComponent(null);
		} else if (selectedRuntime instanceof ManagedRuntimeOption) {
			settings.setRuntime(null);
			settings.setManagedRuntimeComponent(((ManagedRuntimeOption) selectedRuntime).getComponent());
		} else if (selectedRuntime instanceof JavaRuntime) {
			settings.setRuntime((JavaRuntime) selectedRuntime);
			settings.setManagedRuntimeComponent(null);
		}
		String customJvmArgs = userJavaArgsText.getText().trim();
		settings.setCustomJvmArgs(customJvmArgs.isEmpty() ? null : customJvmArgs);
		settings.setModpackJvmArgsEnabled(modpackJvmArgsCheck.isSelected());

		saved = true;
		return true;
	}

	public static boolean open(Window parent, Launcher launcher, Instance instance) {
		if (!instance.isLocal()) {
			return false;
		}

		InstanceSettingsDialog dialog;
		try {
			dialog = new InstanceSettingsDialog(parent, launcher, instance);
			dialog.setVisible(true);
		} catch (Throwable t) {
			log.log(Level.WARNING, "Failed to open instance settings for " + instance.getName(), t);
			SwingHelper.showErrorDialog(parent,
					SharedLocale.tr("errors.genericError"),
					SharedLocale.tr("instance.options.title"), t);
			return false;
		}

		if (dialog.saved) {
			Persistence.commitAndForget(instance);
		}

		return dialog.saved;
	}

	static class JavaRuntimeFileFilter extends FileFilter {
		@Override
		public boolean accept(File f) {
			return f.isDirectory() || f.getName().startsWith("java") && f.canExecute();
		}

		@Override
		public String getDescription() {
			return "Game Runtime executables";
		}
	}
}
