package com.skcraft.launcher.dialog;

import com.skcraft.launcher.Instance;
import com.skcraft.launcher.InstanceSettings;
import com.skcraft.launcher.dialog.component.BetterComboBox;
import com.skcraft.launcher.launch.JavaProcessBuilder;
import com.skcraft.launcher.launch.MemorySettings;
import com.skcraft.launcher.launch.runtime.AddJavaRuntime;
import com.skcraft.launcher.launch.runtime.JavaRuntime;
import com.skcraft.launcher.launch.runtime.JavaRuntimeFinder;
import com.skcraft.launcher.persistence.Persistence;
import com.skcraft.launcher.swing.FormPanel;
import com.skcraft.launcher.swing.LinedBoxPanel;
import com.skcraft.launcher.util.SharedLocale;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileFilter;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class InstanceSettingsDialog extends JDialog {
	private final Instance instance;
	private final InstanceSettings settings;

	private final LinedBoxPanel formsPanel = new LinedBoxPanel(false);
	private final FormPanel memorySettingsPanel = new FormPanel();
	private final JSpinner minMemorySpinner = new JSpinner();
	private final JSpinner maxMemorySpinner = new JSpinner();

	private final FormPanel runtimePanel = new FormPanel();
	private final JComboBox<JavaRuntime> javaRuntimeBox = new BetterComboBox<>();
	private final JTextArea userJavaArgsText = new JTextArea(4, 30);
	private final JScrollPane userJavaArgsScroll = new JScrollPane(userJavaArgsText);
	private final JCheckBox modpackJvmArgsCheck = new JCheckBox(SharedLocale.tr("instance.options.useModpackJvmArguments"));
	private final JTextArea combinedJavaArgsText = new JTextArea(3, 30);

	private final LinedBoxPanel buttonsPanel = new LinedBoxPanel(true);
	private final JButton okButton = new JButton(SharedLocale.tr("button.save"));
	private final JButton cancelButton = new JButton(SharedLocale.tr("button.cancel"));

	private boolean saved = false;

	public InstanceSettingsDialog(Window owner, Instance instance) {
		super(owner);
		this.instance = instance;
		this.settings = instance.getSettings();

		setTitle(SharedLocale.tr("instance.options.title"));
		setModalityType(DEFAULT_MODALITY_TYPE);
		initComponents();
		setSize(new Dimension(480, 600));
		setLocationRelativeTo(owner);
	}

	private void initComponents() {
		initJavaRuntimeBox();
		initLayout();
		initActions();

		updateComponents();
	}

	private void initJavaRuntimeBox() {
		JavaRuntime[] javaRuntimes = JavaRuntimeFinder.getAvailableRuntimes().toArray(new JavaRuntime[0]);
		DefaultComboBoxModel<JavaRuntime> model = new DefaultComboBoxModel<>();
		model.addElement(null);
		for (JavaRuntime javaRuntime : javaRuntimes) {
			model.addElement(javaRuntime);
		}
		if (settings.getRuntime() != null && Arrays.stream(javaRuntimes).noneMatch(r -> r.equals(settings.getRuntime()))) {
			model.insertElementAt(settings.getRuntime(), 1);
		}
		model.addElement(AddJavaRuntime.ADD_RUNTIME_SENTINEL);

		javaRuntimeBox.setModel(model);
		javaRuntimeBox.setRenderer(new DefaultListCellRenderer() {
			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
				super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
				if (value == null) {
					setText(SharedLocale.tr("instance.options.automaticJava"));
				}
				return this;
			}
		});
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

		okButton.addActionListener(e -> {
			save();
			dispose();
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
				javaRuntimeBox.setSelectedItem(settings.getRuntime());
				javaRuntimeBox.setPopupVisible(false);

				JFileChooser chooser = new JFileChooser();
				chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
				chooser.setFileFilter(new JavaRuntimeFileFilter());
				chooser.setDialogTitle("Choose a Java executable");

				int result = chooser.showOpenDialog(this);
				if (result == JFileChooser.APPROVE_OPTION) {
					JavaRuntime runtime = JavaRuntimeFinder.getRuntimeFromPath(chooser.getSelectedFile().getAbsolutePath());

					MutableComboBoxModel<JavaRuntime> comboModel = (MutableComboBoxModel<JavaRuntime>) javaRuntimeBox.getModel();
					comboModel.insertElementAt(runtime, 1);
					javaRuntimeBox.setSelectedItem(runtime);
				}
			}
		});
	}

	private void updateComponents() {
		MemorySettings.Resolved resolved = MemorySettings.resolve(instance);
		if (settings.getMemorySettings() == null) {
			minMemorySpinner.setValue(resolved.getMinMemory());
			maxMemorySpinner.setValue(resolved.getMaxMemory());
		} else {
			minMemorySpinner.setValue(settings.getMemorySettings().getMinMemory());
			maxMemorySpinner.setValue(settings.getMemorySettings().getMaxMemory());
		}
		javaRuntimeBox.setSelectedItem(settings.getRuntime());
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
		return minMemory > 0 ? minMemory : 1024;
	}

	private int getEffectiveMaxMemory(int minMemory) {
		int maxMemory = (int) maxMemorySpinner.getValue();
		if (maxMemory <= 0) {
			maxMemory = 1024;
		}

		return Math.max(minMemory, maxMemory);
	}

	private void save() {
		MemorySettings memorySettings = settings.getMemorySettings();
		if (memorySettings == null) {
			memorySettings = new MemorySettings();
			settings.setMemorySettings(memorySettings);
		}

		memorySettings.setMinMemory((int) minMemorySpinner.getValue());
		memorySettings.setMaxMemory((int) maxMemorySpinner.getValue());
		settings.setRuntime((JavaRuntime) javaRuntimeBox.getSelectedItem());
		String customJvmArgs = userJavaArgsText.getText().trim();
		settings.setCustomJvmArgs(customJvmArgs.isEmpty() ? null : customJvmArgs);
		settings.setModpackJvmArgsEnabled(modpackJvmArgsCheck.isSelected());

		saved = true;
	}

	public static boolean open(Window parent, Instance instance) {
		InstanceSettingsDialog dialog = new InstanceSettingsDialog(parent, instance);
		dialog.setVisible(true);

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
			return "Java runtime executables";
		}
	}
}
