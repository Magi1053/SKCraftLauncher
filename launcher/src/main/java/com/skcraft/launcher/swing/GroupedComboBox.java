package com.skcraft.launcher.swing;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.UIManager;
import javax.swing.border.Border;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.util.function.Function;

public final class GroupedComboBox {

	private GroupedComboBox() {
	}

	public static final class Group {
		private final String label;

		public Group(String label) {
			this.label = label;
		}

		public String getLabel() {
			return label;
		}
	}

	public static class Model extends DefaultComboBoxModel<Object> {
		public void addGroup(String label) {
			addElement(new Group(label));
		}

		@Override
		public void setSelectedItem(Object item) {
			if (item instanceof Group) {
				return;
			}
			super.setSelectedItem(item);
		}
	}

	public static boolean isOption(Object item) {
		return !(item instanceof Group);
	}

	public static JComboBox<Object> create(Model model, Function<Object, String> labelFormatter) {
		JComboBox<Object> combo = new JComboBox<>(model);
		combo.setRenderer(new Renderer(labelFormatter));
		return combo;
	}

	private static final class Renderer extends DefaultListCellRenderer {
		private final Function<Object, String> labelFormatter;

		private Renderer(Function<Object, String> labelFormatter) {
			this.labelFormatter = labelFormatter;
		}

		@Override
		public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected,
				boolean cellHasFocus) {
			boolean inPopup = index >= 0;
			boolean isGroup = value instanceof Group;
			super.getListCellRendererComponent(list, value, index, isGroup ? false : isSelected,
					isGroup ? false : cellHasFocus);

			if (isGroup) {
				setText(((Group) value).getLabel());
				setFont(list.getFont().deriveFont(Font.BOLD));
				if (inPopup && index > 0) {
					Color separator = UIManager.getColor("Separator.foreground");
					if (separator == null) {
						separator = UIManager.getColor("Component.borderColor");
					}
					Border line = separator != null
							? BorderFactory.createMatteBorder(1, 0, 0, 0, separator)
							: BorderFactory.createEmptyBorder();
					setBorder(BorderFactory.createCompoundBorder(
							BorderFactory.createEmptyBorder(6, 0, 0, 0),
							BorderFactory.createCompoundBorder(line, BorderFactory.createEmptyBorder(2, 8, 0, 8))));
				} else {
					setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
				}
				return this;
			}

			setFont(list.getFont());
			setText(labelFormatter.apply(value));
			setBorder(inPopup ? BorderFactory.createEmptyBorder(0, 16, 0, 8) : BorderFactory.createEmptyBorder());
			return this;
		}
	}
}
