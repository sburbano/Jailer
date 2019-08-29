/*
 * Copyright 2007 - 2019 Ralf Wisser.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.sf.jailer.ui;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JRadioButton;
import javax.swing.JTextField;

/**
 * Persists settings of formular fields.
 * 
 * @author Ralf Wisser
 */
public class Settings  {

	/**
	 * The formular fields.
	 */
	private final Map<String, JComponent> fields;

	/**
	 * The name of the file holding the settings.
	 */
	private final String FILENAME;
	
	/**
	 * <code>true</code> iff no settings-file exists.
	 */
	public final boolean isNew;
	
	/**
	 * Holds field values for each setting.
	 */
	private Map<String, Map<String, String>> settings = new TreeMap<String, Map<String,String>>();
	
	public String currentSetting = null;
	
	/**
	 * Constructor.
	 * 
	 * @param fields the formular fields
	 */
	@SuppressWarnings("unchecked")
	public Settings(String fileName, Map<String, JComponent> fields) {
		this.FILENAME = fileName;
		this.fields = fields;
		boolean isNew = true;
		if (new File(FILENAME).exists()) {
			try {
				ObjectInputStream in = new ObjectInputStream(new FileInputStream(FILENAME));
				settings = (Map<String, Map<String, String>>) in.readObject();
				currentSetting = (String) in.readObject();
				in.close();
				isNew = false;
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		this.isNew = isNew;
	}

	/**
	 * Gets names of all settings.
	 * 
	 * @return names of all settings
	 */
	public Object[] getSettingNames() {
		return settings.keySet().toArray();
	}

	/**
	 * Saves a setting.
	 * 
	 * @param name the name of the setting
	 * @param settingsContextSecondaryKey 
	 */
	public void save(String name, String settingsContextSecondaryKey) {
		if (name != null && name.trim().length() > 0) {
			name = name.trim() + ":" + settingsContextSecondaryKey;
			Map<String, String> setting = new HashMap<String, String>();
			for (Map.Entry<String, JComponent> entry: fields.entrySet()) {
				if (entry.getValue() instanceof JTextField) {
					setting.put(entry.getKey(), ((JTextField) entry.getValue()).getText());
				} else if (entry.getValue() instanceof JCheckBox) {
					if (((JCheckBox) entry.getValue()).isEnabled()) {
						setting.put(entry.getKey(), Boolean.valueOf(((JCheckBox) entry.getValue()).isSelected()).toString());
					}
				} else if (entry.getValue() instanceof JRadioButton) {
					if (((JRadioButton) entry.getValue()).isEnabled()) {
						setting.put(entry.getKey(), Boolean.valueOf(((JRadioButton) entry.getValue()).isSelected()).toString());
					}
				} else if (entry.getValue() instanceof JComboBox) {
					Object sItem = ((JComboBox) entry.getValue()).getSelectedItem();
					if (sItem != null) {
						setting.put(entry.getKey(), sItem.toString());
					}
				}
			}
			settings.put(name.trim(), setting);
			currentSetting = name;
			try {
				File file = new File(FILENAME);
				file.delete();
			} catch (Exception e) {
				e.printStackTrace();
			}
			try {
				File file = new File(FILENAME);
				ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(file));
				out.writeObject(settings);
				out.writeObject(currentSetting);
				out.close();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * Restores a setting.
	 * 
	 * @param name the name of the setting
	 * @param settingsContextSecondaryKey 
	 */
	public void restore(String name, String settingsContextSecondaryKey) {
		if (name != null && name.trim().length() > 0) {
			Map<String, String> setting = settings.get(name.trim() + ":" + settingsContextSecondaryKey);
			if (setting == null) {
				setting = settings.get(name.trim());
			}
			if (setting != null) {
				for (Map.Entry<String, JComponent> entry: fields.entrySet()) {
					if (entry.getValue() instanceof JTextField && setting.containsKey(entry.getKey())) {
						((JTextField) entry.getValue()).setText(setting.get(entry.getKey()));
					} else if (entry.getValue() instanceof JCheckBox && setting.containsKey(entry.getKey())) {
						if (((JCheckBox) entry.getValue()).isEnabled()) {
							((JCheckBox) entry.getValue()).setSelected(Boolean.valueOf(setting.get(entry.getKey())));
						}
					} else if (entry.getValue() instanceof JRadioButton && setting.containsKey(entry.getKey())) {
						if (((JRadioButton) entry.getValue()).isEnabled()) {
							((JRadioButton) entry.getValue()).setSelected(Boolean.valueOf(setting.get(entry.getKey())));
						}
					} else if (entry.getValue() instanceof JComboBox && setting.containsKey(entry.getKey())) {
						((JComboBox) entry.getValue()).setSelectedItem(setting.get(entry.getKey()));
					}
				}
			}
		}
	}
	
}
