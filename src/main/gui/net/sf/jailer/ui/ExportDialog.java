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

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import net.sf.jailer.ExecutionContext;
import net.sf.jailer.configuration.DBMS;
import net.sf.jailer.database.BasicDataSource;
import net.sf.jailer.database.Session;
import net.sf.jailer.database.SqlException;
import net.sf.jailer.database.WorkingTableScope;
import net.sf.jailer.datamodel.Association;
import net.sf.jailer.datamodel.Column;
import net.sf.jailer.datamodel.DataModel;
import net.sf.jailer.datamodel.PrimaryKeyFactory;
import net.sf.jailer.datamodel.Table;
import net.sf.jailer.ddl.DDLCreator;
import net.sf.jailer.extractionmodel.ExtractionModel.AdditionalSubject;
import net.sf.jailer.modelbuilder.JDBCMetaDataBasedModelElementFinder;
import net.sf.jailer.subsetting.ScriptFormat;
import net.sf.jailer.util.CancellationHandler;
import net.sf.jailer.util.CsvFile;
import net.sf.jailer.util.Quoting;

/**
 * Data Export Dialog.
 *
 * @author Ralf Wisser
 */
@SuppressWarnings("serial")
public abstract class ExportDialog extends javax.swing.JDialog {

	/**
	 * true iff ok-button was clicked.
	 */
	boolean isOk = false;
	
	/**
	 * Xml/Sql switch.
	 */
	public final ScriptFormat scriptFormat;
	
	/**
	 * Restricted data model.
	 */
	private final DataModel dataModel;
	
	/**
	 * Previous subject condition.
	 */
	private static String previousSubjectCondition;

	/**
	 * Previous initial subject condition.
	 */
	private static String previousInitialSubjectCondition;
	
	/**
	 * Display name for default schema.
	 */
	private static String DEFAULT_SCHEMA = "<default>";
	
	/**
	 * Schema mapping fields.
	 */
	private Map<String, JTextField> schemaMappingFields = new HashMap<String, JTextField>();
	
	/**
	 * Labels of schema mapping fields.
	 */
	private Map<String, JLabel> schemaMappingLabels = new HashMap<String, JLabel>();
	
	/**
	 * Source-schema mapping fields.
	 */
	private Map<String, JTextField> sourceSchemaMappingFields = new HashMap<String, JTextField>();
	
	/**
	 * The form field setting.
	 */
	private Settings theSettings;
	
	/**
	 * The subject table.
	 */
	private final Table subject;
	private final List<AdditionalSubject> additionalSubjects;
	
	private ParameterEditor parameterEditor;
	private final List<String> initialArgs;
	private final String user;
	private final String password;
	private final String subjectCondition;
	private final String settingsContext;
	private final String settingsContextSecondaryKey;
	private final DBMS sourceDBMS;
	private final DbConnectionDialog dbConnectionDialog;

	private String[] schemaComboboxModel;

	private static boolean lastConfirmInsert = false;
	private final String extractionModelFileName;
	private final String jmFile;
	private final String tmpFileName;
	private final ExecutionContext executionContext;

	/** Creates new form DbConnectionDialog 
	 * @param showCmd 
	 * @param jmFile 
	 * @param tmpFileName 
	 * @param args */
	public ExportDialog(java.awt.Frame parent, final DataModel dataModel, final Table subject, String subjectCondition, List<AdditionalSubject> additionalSubjects, Session session, List<String> initialArgs, String user, String password, boolean showCmd, DbConnectionDialog dbConnectionDialog, String extractionModelFileName, String jmFile, String tmpFileName, ExecutionContext executionContext) {
		super(parent, true);
		this.executionContext = executionContext;
		this.extractionModelFileName = extractionModelFileName;
		this.jmFile = jmFile;
		this.tmpFileName = tmpFileName;
		this.subjectCondition = subjectCondition;
		this.dataModel = dataModel;
		this.subject = subject;
		this.initialArgs = new ArrayList<String>(initialArgs);
		this.user = user;
		this.password = password;
		this.settingsContext = session.dbUrl; 
		this.settingsContextSecondaryKey = session.getSchema();
		this.sourceDBMS = session.dbms;
		this.dbConnectionDialog = dbConnectionDialog;
		this.additionalSubjects = additionalSubjects;
		
		try {
			UIUtil.setWaitCursor(parent);

			initComponents();
			
			explain.setVisible(false);
			
			if (jScrollPane2.getHorizontalScrollBar() != null) {
	        	jScrollPane2.getHorizontalScrollBar().setUnitIncrement(16);
	        }
	        if (jScrollPane2.getVerticalScrollBar() != null) {
	        	jScrollPane2.getVerticalScrollBar().setUnitIncrement(16);
	        }
			
	        CancellationHandler.reset(null);
	
			if (!showCmd) {
				commandLinePanel.setVisible(false);
			}
			
			List<String> allSchemas = new ArrayList<String>(JDBCMetaDataBasedModelElementFinder.getSchemas(session, session.getSchema()));
			allSchemas.addAll(JDBCMetaDataBasedModelElementFinder.getCatalogsWithSchemas(session));
			String defaultSchema = JDBCMetaDataBasedModelElementFinder.getDefaultSchema(session, session.getSchema());
			initWorkingTableSchemaBox(session, allSchemas, defaultSchema);
			initIFMTableSchemaBox(session, allSchemas, defaultSchema);
	
			try {
				JTextField c = (JTextField) workingTableSchemaComboBox.getEditor().getEditorComponent();
				c.getDocument().addDocumentListener(new DocumentListener() {
					@Override
					public void removeUpdate(DocumentEvent e) {
						update();
					}
					@Override
					public void insertUpdate(DocumentEvent e) {
						update();
					}
					@Override
					public void changedUpdate(DocumentEvent e) {
						update();
					}
					private void update() {
						updateCLIArea();				
					}
				});
			} catch (ClassCastException e) {
				// ignore
			}
	
			parameterEditor = new ParameterEditor(parent);
			GridBagConstraints gridBagConstraints = new java.awt.GridBagConstraints();
			gridBagConstraints.gridx = 0;
			gridBagConstraints.gridy = 0;
			gridBagConstraints.fill = GridBagConstraints.BOTH;
			gridBagConstraints.weightx = 1.0;
			gridBagConstraints.weighty = 1.0;
			gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
			parameterPanel.add(parameterEditor.createPane(dataModel.getParameters(subjectCondition, additionalSubjects)), gridBagConstraints);
			
			ScriptFormat theScriptFormat = ScriptFormat.SQL;
			try {
				theScriptFormat = ScriptFormat.valueOf(dataModel.getExportModus());
			} catch (Exception e) {
			}
			
			scriptFormat = theScriptFormat;
			toLabel.setText(scriptFormat.getDisplayName());
			
			setModal(true);
			setLocation(100, 60);
			Map<String, JComponent> fields = new HashMap<String, JComponent>();
			fields.put("insert" + scriptFormat.name(), insert);
			fields.put("threads", threads);
			fields.put("rowsPerThread", rowsPerThread);
			fields.put("unicode", unicode);
			fields.put("sorted", sortedCheckBox);
			fields.put("insertIncrementally", insertIncrementally);
			fields.put("independentWorkingTables", independentWorkingTables);
			fields.put("isolationLevel", isolationLevelComboBox);
			fields.put("upsertCheckbox", upsertCheckbox);
			fields.put("scopeLocal", scopeLocal);
			fields.put("scopeGlobal", scopeGlobal);
			fields.put("scopeSession", scopeSession);
			fields.put("orderByPK", orderByPKCheckbox);
			fields.put("useRowIds", useRowIds);
			for (Map.Entry<String, JTextField> e: parameterEditor.textfieldsPerParameter.entrySet()) {
				fields.put("$" + e.getKey(), e.getValue());
			}
			
			try {
				JTextField c;
				c = (JTextField) workingTableSchemaComboBox.getEditor().getEditorComponent();
				fields.put("workingTableSchema", c);
			} catch (ClassCastException e) {
				// ignore
			}
			try {
				JTextField c;
				c = (JTextField) iFMTableSchemaComboBox.getEditor().getEditorComponent();
				fields.put("iFMTableSchema", c);
			} catch (ClassCastException e) {
				// ignore
			}
			
			confirmInsert.setSelected(lastConfirmInsert);
			if (scriptFormat == ScriptFormat.INTRA_DATABASE) {
				exportLabel.setText(" Receipt*");
				jLabel3.setVisible(false);
				delete.setVisible(false);
				browseDeleteButton.setVisible(false);
			} else {
				confirmInsert.setVisible(false);
			}
			
			orderByPKCheckbox.setEnabled(ScriptFormat.SQL.equals(scriptFormat));
			orderByPKCheckbox.setVisible(ScriptFormat.SQL.equals(scriptFormat));
			
			sortedCheckBox.setEnabled(ScriptFormat.SQL.equals(scriptFormat) || ScriptFormat.INTRA_DATABASE.equals(scriptFormat) || ScriptFormat.DBUNIT_FLAT_XML.equals(scriptFormat) || ScriptFormat.LIQUIBASE_XML.equals(scriptFormat));
			sortedCheckBox.setSelected(true);
			upsertCheckbox.setEnabled(ScriptFormat.SQL.equals(scriptFormat) || ScriptFormat.INTRA_DATABASE.equals(scriptFormat));
			rowsPerThread.setEnabled(ScriptFormat.SQL.equals(scriptFormat));
			insertIncrementally.setEnabled(session.dbms.getLimitTransactionSize().getLimit() != 0);

			Map<JTextField, String> defaults = new HashMap<JTextField, String>();

			if (ScriptFormat.INTRA_DATABASE.equals(scriptFormat)) {
				jLabel8.setVisible(false);
				jPanel8.setVisible(false);
			}
			
			if ((!ScriptFormat.SQL.equals(scriptFormat)) && (!ScriptFormat.INTRA_DATABASE.equals(scriptFormat)) && (!ScriptFormat.DBUNIT_FLAT_XML.equals(scriptFormat)) && !ScriptFormat.LIQUIBASE_XML.equals(scriptFormat)) {
				schemaMappingPanel.setVisible(false);
			} else {
				schemaMappingPanel.setVisible(true);
				initSchemaMapping(dataModel, fields, defaults);
			}
			initSourceSchemaMapping(dataModel, fields, defaults);
			initIsolationLevel(session);
			initScopeButtons(session);

			useRowIds.setSelected(false);
			if (session.dbms.getRowidName() == null) {
				useRowIds.setSelected(true);
				useRowIds.setVisible(false);
			}

			theSettings = new Settings(Environment.newFile(".exportdata.ui").getPath(), fields);

			theSettings.restore(settingsContext, settingsContextSecondaryKey);
			for (JTextField field: defaults.keySet()) {
				if (field.getText().length() == 0) {
					field.setText(defaults.get(field));
				}
			}

			insert.setText(UIUtil.correctFileSeparator(insert.getText()));

			if (scriptFormat == ScriptFormat.INTRA_DATABASE && insert.getText().trim().length() == 0) {
				insert.setText("receipt.txt");
			}
			
			if (scriptFormat == ScriptFormat.INTRA_DATABASE) {
				for (Map.Entry<String, JTextField> e: schemaMappingFields.entrySet()) {
					if (e.getKey().equals(e.getValue().getText())) {
						e.getValue().setText("");
					}
				}
			}
			
			if (threads.getText().length() == 0) {
				threads.setText("4");
			}
			if (rowsPerThread.getText().length() == 0) {
				rowsPerThread.setText("50");
			}
			
			if (additionalSubjects.isEmpty()) {
				additSubsLabel.setVisible(false);
				additSubsLabelTitel.setVisible(false);
			} else {
				StringBuilder sb = new StringBuilder();
				int ll = 0;
				for (AdditionalSubject as: additionalSubjects) {
					if (sb.length() > 0) {
						sb.append(", ");
					}
					sb.append(as.getSubject().getName());
					ll += as.getSubject().getName().length();
					if (ll > 120) {
						ll = 0;
						sb.append("\n");
					}
				}
				final int MAX = 60;
				if (sb.length() > MAX) {
					additSubsLabel.setToolTipText(UIUtil.toHTML(sb.toString(), 0));
					additSubsLabel.setText(sb.toString().substring(0, MAX) + "...");
				} else {
					additSubsLabel.setText(sb.toString());
				}
			}
			
			subjectTable.setText(subject.getName());
			if (subjectCondition.equals(previousInitialSubjectCondition)) {
				where.setText((previousSubjectCondition));
			} else {
				where.setText((subjectCondition));
			}
			
			browseInsertButton.setIcon(loadIcon);
			browseDeleteButton.setIcon(loadIcon);
			
			if (parameterEditor.firstTextField != null) {
				parameterEditor.firstTextField.grabFocus();
			}
			
			DocumentListener dl = new DocumentListener() {
				@Override
				public void removeUpdate(DocumentEvent e) {
					updateCLIArea();
				}
				@Override
				public void insertUpdate(DocumentEvent e) {
					updateCLIArea();
				}
				@Override
				public void changedUpdate(DocumentEvent e) {
					updateCLIArea();
				}
			};
			ActionListener al = new ActionListener() {
				@Override
				public void actionPerformed(ActionEvent arg0) {
					workingTableSchemaComboBox.setEnabled(!scopeLocal.isSelected());
					updateCLIArea();
				}
			};
			where.getDocument().addDocumentListener(dl);
			insert.getDocument().addDocumentListener(dl);
			delete.getDocument().addDocumentListener(dl);
			threads.getDocument().addDocumentListener(dl);
			rowsPerThread.getDocument().addDocumentListener(dl);
			upsertCheckbox.addActionListener(al);
			checkPKs.addActionListener(al);
			insertIncrementally.addActionListener(al);
			independentWorkingTables.addActionListener(al);
			transactional.addActionListener(al);
			explain.addActionListener(al);
			unicode.addActionListener(al);
			sortedCheckBox.addActionListener(al);
			orderByPKCheckbox.addActionListener(al);
			scopeGlobal.addActionListener(al);
			scopeSession.addActionListener(al);
			scopeLocal.addActionListener(al);
			for (JTextField field: parameterEditor.textfieldsPerParameter.values()) {
				field.getDocument().addDocumentListener(dl);
			}
		
			Dimension preferredSize = where.getPreferredSize();
			preferredSize.width = 10;
			where.setPreferredSize(preferredSize);
			
			final ConditionEditor subjectConditionEditor = new ConditionEditor(null, null, dataModel, null);
			subjectConditionEditor.setTitle("Subject condition");
			openWhereEditor.setIcon(conditionEditorIcon);
			openWhereEditor.setText(null);
			openWhereEditor.addMouseListener(new java.awt.event.MouseAdapter() {
				@Override
				public void mouseReleased(MouseEvent e) {
					mouseClicked(e);
				}
				@Override
				public void mouseClicked(java.awt.event.MouseEvent evt) {
					String cond = subjectConditionEditor.edit(where.getText(), "Subject", "T", subject, null, null, null, false, true);
					if (cond != null) {
						if (!where.getText().equals((cond))) {
							where.setText((cond));
						}
						openWhereEditor.setIcon(conditionEditorSelectedIcon);
					}
				}
				
				@Override
				public void mouseEntered(java.awt.event.MouseEvent evt) {
					openWhereEditor.setIcon(conditionEditorSelectedIcon);
				}
				@Override
				public void mouseExited(java.awt.event.MouseEvent evt) {
					openWhereEditor.setIcon(conditionEditorIcon);
			   }
			});
	
			initTargetDBMS(session);
			
			pack();
			updateCLIArea();
			setSize(Math.max(Math.min(getSize().width, 900), 580), getSize().height);
			placeholder.setVisible(false);
			placeholder1.setVisible(false);
			UIUtil.initPeer();
			UIUtil.fit(this);
		} finally {
			UIUtil.resetWaitCursor(parent);
		}
		setVisible(true);
		try {
			if (initScopeButtonThread != null) {
				initScopeButtonThread.join();
			}
		} catch (InterruptedException e1) {
		}
		initScopeButtonThread = null;
		if (isOk) {
			previousInitialSubjectCondition = subjectCondition;
			previousSubjectCondition = where.getText();
		}
	}

	@SuppressWarnings({ "unchecked" })
	private void initIsolationLevel(Session session) {
		final Map<String, String> levels = new TreeMap<String, String>();
		levels.put(String.valueOf(Connection.TRANSACTION_READ_COMMITTED), "Read committed");
		levels.put(String.valueOf(Connection.TRANSACTION_READ_UNCOMMITTED), "Read uncommitted");
		levels.put(String.valueOf(Connection.TRANSACTION_REPEATABLE_READ), "Repeatable read");
		levels.put(String.valueOf(Connection.TRANSACTION_SERIALIZABLE), "Serializable");
		int dLevel = Connection.TRANSACTION_NONE;
		try {
			dLevel = session.getConnection().getTransactionIsolation();
		} catch (SQLException e) {
			// ignore
		}
		String dd = levels.get(String.valueOf(dLevel));
		if (dd == null) {
			dd = "None";
		}
		levels.put(String.valueOf(Connection.TRANSACTION_NONE), "Default (" + dd + ")");
		isolationLevelComboBox.setModel(new DefaultComboBoxModel<String>(levels.keySet().toArray(new String[0])));
		isolationLevelComboBox.setRenderer(new DefaultListCellRenderer() {
			@SuppressWarnings("rawtypes")
			@Override
			public Component getListCellRendererComponent(JList list,
					Object value, int index, boolean isSelected,
					boolean cellHasFocus) {
				return super.getListCellRendererComponent(list, levels.get(value), index, isSelected, cellHasFocus);
			}
		});
		isolationLevelComboBox.setSelectedItem(String.valueOf(Connection.TRANSACTION_READ_UNCOMMITTED));
		isolationLevelComboBox.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent arg0) {
				updateCLIArea();				
			}
		});
	}

	@SuppressWarnings({ "unchecked" })
	private void initTargetDBMS(Session session) {
		if (scriptFormat == ScriptFormat.SQL) {
			targetDBMSComboBox.setModel(new DefaultComboBoxModel<DBMS>(DBMS.values()));
			targetDBMSComboBox.setRenderer(new DefaultListCellRenderer() {
				@SuppressWarnings("rawtypes")
				@Override
				public Component getListCellRendererComponent(JList list,
						Object value, int index, boolean isSelected,
						boolean cellHasFocus) {
					return super.getListCellRendererComponent(list,
							value instanceof DBMS? ((DBMS) value).getDisplayName() : value, index, isSelected,
							cellHasFocus);
				}
			});
			targetDBMSComboBox.setSelectedItem(sourceDBMS);
			targetDBMSComboBox.addActionListener(new ActionListener() {
				@Override
				public void actionPerformed(ActionEvent arg0) {
					updateCLIArea();				
				}
			});
			targetDBMSComboBox.setMaximumRowCount(20);
		} else {
			targetDBMSLabel.setVisible(false);
			targetDBMSComboBox.setVisible(false);
		}
	}

	@SuppressWarnings("unchecked")
	private void initIFMTableSchemaBox(Session session, List<String> allSchemas, String defaultSchema) {
		boolean hasImportFilter = false;
		for (Table table: dataModel.getTables()) {
			for (Column column: table.getColumns()) {
				if (column.getFilter() != null && !column.getFilter().isApplyAtExport()) {
					hasImportFilter  = true;
					break;
				}
			}
			if (hasImportFilter) {
				break;
			}
		}
		if (!hasImportFilter) {
			iFMTPanel.setVisible(false);
			iFMTableSchemaComboBox.setVisible(false);
			return;
		}
		List<String> schemas = new ArrayList<String>();
		schemas.add(DEFAULT_SCHEMA);
		schemas.addAll(allSchemas);
		schemas.remove(defaultSchema);
		quoteSchemas(schemas, session);
		String[] ifmComboboxModel = schemas.toArray(new String[0]);
		iFMTableSchemaComboBox.setModel(new DefaultComboBoxModel(ifmComboboxModel));
		iFMTableSchemaComboBox.setSelectedItem(DEFAULT_SCHEMA);
		iFMTableSchemaComboBox.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent arg0) {
				updateCLIArea();
			}
		});
		try {
			JTextField c = (JTextField) iFMTableSchemaComboBox.getEditor().getEditorComponent();
			c.getDocument().addDocumentListener(new DocumentListener() {
				@Override
				public void removeUpdate(DocumentEvent e) {
					updateCLIArea();
				}
				@Override
				public void insertUpdate(DocumentEvent e) {
					updateCLIArea();
				}
				@Override
				public void changedUpdate(DocumentEvent e) {
					updateCLIArea();
				}
			});
		} catch (ClassCastException e) {
			// ignore
		}
	}
	
	@SuppressWarnings("unchecked")
	private void initWorkingTableSchemaBox(Session session, List<String> allSchemas, String defaultSchema) {
		List<String> schemas = new ArrayList<String>();
		schemas.add(DEFAULT_SCHEMA);
		schemas.addAll(allSchemas);
		schemas.remove(defaultSchema);
		quoteSchemas(schemas, session);
		schemaComboboxModel = schemas.toArray(new String[0]);
		workingTableSchemaComboBox.setModel(new DefaultComboBoxModel(schemaComboboxModel));
		workingTableSchemaComboBox.setSelectedItem(DEFAULT_SCHEMA);
		workingTableSchemaComboBox.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent arg0) {
				updateCLIArea();				
			}
		});
	}

	private void quoteSchemas(List<String> schemas, Session session) {
		List<String> result = new ArrayList<String>();
		Quoting quoting;
		try {
			quoting = new Quoting(session);
		} catch (SQLException e) {
			e.printStackTrace();
			return;
		}
		for (String schema: schemas) {
			if (DEFAULT_SCHEMA.equals(schema)) {
				result.add(schema);
			} else {
				int iDot = schema.indexOf('.');
				if (iDot > 0) {
					String catalog = schema.substring(0, iDot);
					result.add(quoting.quote(catalog) + "." + quoting.quote(schema.substring(iDot + 1)));
				} else {
					result.add(quoting.quote(schema));
				}
			}
		}
		schemas.clear();
		schemas.addAll(result);
	}

	private void updateCLIArea() {
		explain.setEnabled(!scopeLocal.isSelected());

		List<String> args = new ArrayList<String>(initialArgs);
		if (args.size() > 0) {
			if (extractionModelFileName != null) {
				args.set(0, extractionModelFileName);
			} else {
				args.set(0, "<extraction model>");
			}
		}
		fillCLIArgs(args);
		String cmd = "sh jailer.sh";
		if (System.getProperty("os.name", "").toLowerCase().startsWith("windows")) {
			cmd = "jailer.bat";
		}
		String cli = cmd + UIUtil.createCLIArgumentString(user, password, args, executionContext);
		cliArea.setText(cli);
		cliArea.setCaretPosition(0);
		jScrollPane1.getViewport().setViewPosition(new Point(0,0));
	}
	
	private Thread initScopeButtonThread;
	private boolean sessionLocalIsAvailable = false;

	private Set<String> targetSchemaSet = new TreeSet<String>();

	private void initScopeButtons(final Session session) {
		DBMS configuration = session.dbms;
		sessionLocalIsAvailable = configuration.getSessionTemporaryTableManager() != null;

		scopeGlobal.setEnabled(true);
		scopeSession.setEnabled(sessionLocalIsAvailable);
		jButton1.setEnabled(true);
		scopeGlobal.setSelected(true);

		updateCLIArea();
	}

	/**
	 * Initializes the schema mapping panel.
	 * 
	 * @param dataModel the data model
	 * @param fields to put newly created text fields into
	 * @param defaults to put default values for newly created text fields into
	 */
	private void initSchemaMapping(DataModel dataModel, Map<String, JComponent> fields, Map<JTextField, String> defaults) {
		Set<String> distinctSchemas = new HashSet<String>();
		
		Set<String> relevantSchemas = getRelevantSchemas(true);
		for (Table table: dataModel.getTables()) {
			String schema = table.getOriginalSchema(DEFAULT_SCHEMA);
			if (relevantSchemas.contains(schema.equals(DEFAULT_SCHEMA)? "" : schema)) {
				distinctSchemas.add(schema);
			}
		}
		
		List<String> sortedSchemaList = new ArrayList<String>(distinctSchemas);
		Collections.sort(sortedSchemaList);
		
		boolean simplified = sortedSchemaList.size() == 1;
		if (simplified) {
			schemaMappingPanel.setVisible(false);
		}
		
		int y = 0;
		for (String schema: sortedSchemaList) {
			JLabel a = new JLabel(schema + " into ");
			java.awt.GridBagConstraints gridBagConstraints = new java.awt.GridBagConstraints();
			gridBagConstraints.gridx = 1;
			gridBagConstraints.gridy = y;
			gridBagConstraints.anchor = java.awt.GridBagConstraints.EAST;
			if (simplified) {
				a.setText(" Target schema ");
				gridBagConstraints.gridx = 0;
				gridBagConstraints.gridy = 80;
				gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
				jPanel1.add(a, gridBagConstraints);
			} else {
				schemaMappingPanel.add(a, gridBagConstraints);
			}
			JComboBox cb = new JComboBox();
			cb.setMaximumRowCount(20);
			JComponent ccb = cb;
			cb.setModel(new DefaultComboBoxModel(schemaComboboxModel)); 
			cb.setEditable(true);
			cb.setSelectedItem(schema);
			JTextField c;
			try {
				c = (JTextField) cb.getEditor().getEditorComponent();
			} catch (ClassCastException e) {
				c = new JTextField(schema);
				ccb = c;
			}
			c.getDocument().addDocumentListener(new DocumentListener() {
				@Override
				public void removeUpdate(DocumentEvent e) {
					updateCLIArea();
				}
				@Override
				public void insertUpdate(DocumentEvent e) {
					updateCLIArea();
				}
				@Override
				public void changedUpdate(DocumentEvent e) {
					updateCLIArea();
				}
			});
			fields.put("schema-" + schema, c);
			defaults.put(c, schema);
			schemaMappingFields.put(schema, c);
			schemaMappingLabels.put(schema, a);
			gridBagConstraints = new java.awt.GridBagConstraints();
			gridBagConstraints.gridx = 3;
			gridBagConstraints.gridy = y;
			gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
			if (simplified) {
				gridBagConstraints.gridx = 1;
				gridBagConstraints.gridy = 80;
				gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
				gridBagConstraints.fill = java.awt.GridBagConstraints.NONE;
				jPanel1.add(ccb, gridBagConstraints);
			} else {
				schemaMappingPanel.add(ccb, gridBagConstraints);
			}
			y++;
		}
	}
	
	/**
	 * Initializes the source schema mapping panel.
	 * 
	 * @param dataModel the data model
	 * @param fields to put newly created text fields into
	 * @param defaults to put default values for newly created text fields into
	 */
	private void initSourceSchemaMapping(DataModel dataModel, Map<String, JComponent> fields, Map<JTextField, String> defaults) {
		Set<String> distinctSchemas = new HashSet<String>();
		
		Set<String> relevantSchemas = getRelevantSchemas(true);
		for (Table table: dataModel.getTables()) {
			String schema = table.getOriginalSchema(DEFAULT_SCHEMA);
			if (relevantSchemas.contains(schema.equals(DEFAULT_SCHEMA)? "" : schema)) {
				distinctSchemas.add(schema);
			}
		}
		
		List<String> sortedSchemaList = new ArrayList<String>(distinctSchemas);
		Collections.sort(sortedSchemaList);
		
		boolean simplified = sortedSchemaList.size() == 1;
		if (simplified) {
			sourceSchemaMappingPanel.setVisible(false);
		}
		
		int y = 0;
		for (String schema: sortedSchemaList) {
			JLabel b = new JLabel(" instead of ");
			java.awt.GridBagConstraints gridBagConstraints = new java.awt.GridBagConstraints();
			gridBagConstraints.gridx = 2;
			gridBagConstraints.gridy = y;
			if (simplified) {
				b.setText(" Source schema ");
				gridBagConstraints.gridx = 0;
				gridBagConstraints.gridy = 82;
				gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
				jPanel1.add(b, gridBagConstraints);
				gridBagConstraints = new java.awt.GridBagConstraints();
				gridBagConstraints.gridx = 0;
				gridBagConstraints.gridy = 83;
				jPanel1.add(new JLabel(" "), gridBagConstraints);
			} else {
				sourceSchemaMappingPanel.add(b, gridBagConstraints);
			}
			JComboBox cb = new JComboBox();
			cb.setMaximumRowCount(20);
			JComponent ccb = cb;
			cb.setModel(new DefaultComboBoxModel(schemaComboboxModel)); 
			cb.setEditable(true);
			cb.setSelectedItem(schema);
			JTextField c;
			try {
				c = (JTextField) cb.getEditor().getEditorComponent();
			} catch (ClassCastException e) {
				c = new JTextField(schema);
				ccb = c;
			}
			c.getDocument().addDocumentListener(new DocumentListener() {
				@Override
				public void removeUpdate(DocumentEvent e) {
					updateCLIArea();
				}
				@Override
				public void insertUpdate(DocumentEvent e) {
					updateCLIArea();
				}
				@Override
				public void changedUpdate(DocumentEvent e) {
					updateCLIArea();
				}
			});
			// fields.put("srcschema-" + schema, c);
			defaults.put(c, schema);
			sourceSchemaMappingFields.put(schema, c);
			gridBagConstraints = new java.awt.GridBagConstraints();
			gridBagConstraints.gridx = 1;
			gridBagConstraints.gridy = y;
			gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
			if (simplified) {
				gridBagConstraints.gridx = 1;
				gridBagConstraints.gridy = 82;
				gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
				gridBagConstraints.fill = java.awt.GridBagConstraints.NONE;
				jPanel1.add(ccb, gridBagConstraints);
			} else {
				sourceSchemaMappingPanel.add(ccb, gridBagConstraints);
			}
			JLabel a = new JLabel(schema);
			gridBagConstraints = new java.awt.GridBagConstraints();
			gridBagConstraints.gridx = 3;
			gridBagConstraints.gridy = y;
			gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
			sourceSchemaMappingPanel.add(a, gridBagConstraints);
			y++;
		}
	}
	
	/** This method is called from within the constructor to
	 * initialize the form.
	 * WARNING: Do NOT modify this code. The content of this method is
	 * always regenerated by the Form Editor.
	 */
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {
        java.awt.GridBagConstraints gridBagConstraints;

        buttonGroup1 = new javax.swing.ButtonGroup();
        jScrollPane2 = new javax.swing.JScrollPane();
        jPanel6 = new javax.swing.JPanel();
        jPanel1 = new javax.swing.JPanel();
        sourceSchemaMappingPanel = new javax.swing.JPanel();
        jLabel18 = new javax.swing.JLabel();
        jLabel19 = new javax.swing.JLabel();
        jLabel20 = new javax.swing.JLabel();
        schemaMappingPanel = new javax.swing.JPanel();
        jLabel13 = new javax.swing.JLabel();
        jLabel14 = new javax.swing.JLabel();
        jLabel15 = new javax.swing.JLabel();
        where = new javax.swing.JTextField();
        exportLabel = new javax.swing.JLabel();
        jLabel3 = new javax.swing.JLabel();
        jLabel5 = new javax.swing.JLabel();
        jLabel6 = new javax.swing.JLabel();
        insert = new javax.swing.JTextField();
        delete = new javax.swing.JTextField();
        threads = new javax.swing.JTextField();
        rowsPerThread = new javax.swing.JTextField();
        upsertCheckbox = new javax.swing.JCheckBox();
        explain = new javax.swing.JCheckBox();
        placeholder = new javax.swing.JLabel();
        jLabel4 = new javax.swing.JLabel();
        jLabel8 = new javax.swing.JLabel();
        jLabel7 = new javax.swing.JLabel();
        jLabel11 = new javax.swing.JLabel();
        jPanel4 = new javax.swing.JPanel();
        subjectTable = new javax.swing.JLabel();
        jLabel12 = new javax.swing.JLabel();
        jLabel16 = new javax.swing.JLabel();
        jPanel8 = new javax.swing.JPanel();
        scopeSession = new javax.swing.JRadioButton();
        scopeGlobal = new javax.swing.JRadioButton();
        scopeLocal = new javax.swing.JRadioButton();
        jLabel1 = new javax.swing.JLabel();
        jLabel26 = new javax.swing.JLabel();
        jLabel27 = new javax.swing.JLabel();
        jLabel9 = new javax.swing.JLabel();
        jLabel21 = new javax.swing.JLabel();
        parameterPanel = new javax.swing.JPanel();
        commandLinePanel = new javax.swing.JPanel();
        jLabel22 = new javax.swing.JLabel();
        jScrollPane1 = new javax.swing.JScrollPane();
        cliArea = new javax.swing.JTextArea();
        jLabel23 = new javax.swing.JLabel();
        jLabel24 = new javax.swing.JLabel();
        jLabel25 = new javax.swing.JLabel();
        copyButton = new javax.swing.JButton();
        placeholder1 = new javax.swing.JLabel();
        sortedCheckBox = new javax.swing.JCheckBox();
        unicode = new javax.swing.JCheckBox();
        openWhereEditor = new javax.swing.JLabel();
        additSubsLabel = new javax.swing.JLabel();
        additSubsLabelTitel = new javax.swing.JLabel();
        useRowIds = new javax.swing.JCheckBox();
        jLabel10 = new javax.swing.JLabel();
        workingTableSchemaComboBox = new javax.swing.JComboBox();
        confirmInsert = new javax.swing.JCheckBox();
        jLabel17 = new javax.swing.JLabel();
        toLabel = new javax.swing.JLabel();
        targetDBMSComboBox = new javax.swing.JComboBox();
        targetDBMSLabel = new javax.swing.JLabel();
        iFMTableSchemaComboBox = new javax.swing.JComboBox();
        iFMTPanel = new javax.swing.JPanel();
        jLabel29 = new javax.swing.JLabel();
        jLabel30 = new javax.swing.JLabel();
        browseInsertButton = new javax.swing.JButton();
        browseDeleteButton = new javax.swing.JButton();
        checkPKs = new javax.swing.JCheckBox();
        insertIncrementally = new javax.swing.JCheckBox();
        targetDBMSLabel1 = new javax.swing.JLabel();
        jPanel3 = new javax.swing.JPanel();
        isolationLevelComboBox = new javax.swing.JComboBox();
        iLHintLabel = new javax.swing.JLabel();
        transactional = new javax.swing.JCheckBox();
        independentWorkingTables = new javax.swing.JCheckBox();
        orderByPKCheckbox = new javax.swing.JCheckBox();
        jPanel7 = new javax.swing.JPanel();
        jPanel2 = new javax.swing.JPanel();
        jButton1 = new javax.swing.JButton();
        jLabel2 = new javax.swing.JLabel();
        cancelButton = new javax.swing.JButton();

        setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        setTitle("Data Export"); // NOI18N
        getContentPane().setLayout(new java.awt.GridBagLayout());

        jScrollPane2.setBorder(javax.swing.BorderFactory.createEmptyBorder(1, 1, 1, 1));
        jScrollPane2.setHorizontalScrollBarPolicy(javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        jPanel6.setBorder(javax.swing.BorderFactory.createEmptyBorder(1, 1, 1, 1));
        jPanel6.setLayout(new java.awt.GridBagLayout());

        jPanel1.setBorder(javax.swing.BorderFactory.createEmptyBorder(1, 1, 1, 1));
        jPanel1.setLayout(new java.awt.GridBagLayout());

        sourceSchemaMappingPanel.setLayout(new java.awt.GridBagLayout());

        jLabel18.setText(" Read from schema "); // NOI18N
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 0;
        sourceSchemaMappingPanel.add(jLabel18, gridBagConstraints);

        jLabel19.setText(" "); // NOI18N
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 4;
        gridBagConstraints.gridy = 0;
        gridBagConstraints.weightx = 1.0;
        sourceSchemaMappingPanel.add(jLabel19, gridBagConstraints);

        jLabel20.setText("                          "); // NOI18N
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 200;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        sourceSchemaMappingPanel.add(jLabel20, gridBagConstraints);

        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 82;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.fill = java.awt.GridBagConstraints.BOTH;
        jPanel1.add(sourceSchemaMappingPanel, gridBagConstraints);

        schemaMappingPanel.setLayout(new java.awt.GridBagLayout());

        jLabel13.setText(" Insert rows from schema "); // NOI18N
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 0;
        schemaMappingPanel.add(jLabel13, gridBagConstraints);

        jLabel14.setText(" "); // NOI18N
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 4;
        gridBagConstraints.gridy = 0;
        gridBagConstraints.weightx = 1.0;
        schemaMappingPanel.add(jLabel14, gridBagConstraints);

        jLabel15.setText("                          "); // NOI18N
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 3;
        gridBagConstraints.gridy = 200;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        schemaMappingPanel.add(jLabel15, gridBagConstraints);

        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 80;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.fill = java.awt.GridBagConstraints.BOTH;
        jPanel1.add(schemaMappingPanel, gridBagConstraints);

        where.setMaximumSize(new java.awt.Dimension(300, 2147483647));
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 18;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.fill = java.awt.GridBagConstraints.BOTH;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(0, 0, 4, 0);
        jPanel1.add(where, gridBagConstraints);

        exportLabel.setText(" Into*"); // NOI18N
        exportLabel.setToolTipText("add '.zip' or '.gz' extension for compressed files");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 30;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        jPanel1.add(exportLabel, gridBagConstraints);

        jLabel3.setText(" Generate delete-script* "); // NOI18N
        jLabel3.setToolTipText("add '.zip' or '.gz' extension for compressed files");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 34;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        jPanel1.add(jLabel3, gridBagConstraints);

        jLabel5.setText(" "); // NOI18N
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 45;
        jPanel1.add(jLabel5, gridBagConstraints);

        jLabel6.setText(" Parallel threads "); // NOI18N
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 54;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        jPanel1.add(jLabel6, gridBagConstraints);

        insert.setToolTipText("add '.zip' or '.gz' extension for compressed files");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 30;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.fill = java.awt.GridBagConstraints.BOTH;
        gridBagConstraints.insets = new java.awt.Insets(0, 0, 1, 0);
        jPanel1.add(insert, gridBagConstraints);

        delete.setToolTipText("add '.zip' or '.gz' extension for compressed files");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 34;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.fill = java.awt.GridBagConstraints.BOTH;
        gridBagConstraints.insets = new java.awt.Insets(0, 0, 1, 0);
        jPanel1.add(delete, gridBagConstraints);

        threads.setMinimumSize(new java.awt.Dimension(44, 19));
        threads.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent evt) {
                threadsFocusLost(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 54;
        gridBagConstraints.ipadx = 30;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        jPanel1.add(threads, gridBagConstraints);
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 55;
        gridBagConstraints.ipadx = 30;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        jPanel1.add(rowsPerThread, gridBagConstraints);

        upsertCheckbox.setText("upsert-statements (overwrite) for all rows"); // NOI18N
        upsertCheckbox.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 0, 0));
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 38;
        gridBagConstraints.fill = java.awt.GridBagConstraints.BOTH;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(2, 0, 2, 0);
        jPanel1.add(upsertCheckbox, gridBagConstraints);

        explain.setText("explain"); // NOI18N
        explain.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 0, 0));
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 47;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.insets = new java.awt.Insets(2, 0, 2, 0);
        jPanel1.add(explain, gridBagConstraints);

        placeholder.setText(" "); // NOI18N
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 2;
        gridBagConstraints.gridy = 0;
        gridBagConstraints.weightx = 1.0;
        jPanel1.add(placeholder, gridBagConstraints);

        jLabel4.setText(" "); // NOI18N
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 10;
        gridBagConstraints.gridy = 30;
        jPanel1.add(jLabel4, gridBagConstraints);

        jLabel8.setText(" Working table scope"); // NOI18N
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 56;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.NORTHWEST;
        gridBagConstraints.insets = new java.awt.Insets(12, 0, 0, 0);
        jPanel1.add(jLabel8, gridBagConstraints);

        jLabel7.setText(" Export from"); // NOI18N
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 15;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        gridBagConstraints.insets = new java.awt.Insets(0, 0, 4, 0);
        jPanel1.add(jLabel7, gridBagConstraints);

        jLabel11.setText(" Where"); // NOI18N
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 18;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        gridBagConstraints.insets = new java.awt.Insets(0, 0, 4, 0);
        jPanel1.add(jLabel11, gridBagConstraints);

        jPanel4.setLayout(new java.awt.GridBagLayout());

        subjectTable.setFont(subjectTable.getFont().deriveFont(subjectTable.getFont().getStyle() | java.awt.Font.BOLD));
        subjectTable.setText("jLabel11"); // NOI18N
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 1;
        jPanel4.add(subjectTable, gridBagConstraints);

        jLabel12.setFont(jLabel12.getFont().deriveFont((jLabel12.getFont().getStyle() | java.awt.Font.ITALIC) & ~java.awt.Font.BOLD));
        jLabel12.setText("  as T"); // NOI18N
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 2;
        gridBagConstraints.gridy = 1;
        jPanel4.add(jLabel12, gridBagConstraints);

        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 15;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        gridBagConstraints.insets = new java.awt.Insets(0, 0, 4, 0);
        jPanel1.add(jPanel4, gridBagConstraints);

        jLabel16.setText(" Rows per statement "); // NOI18N
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 55;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        jPanel1.add(jLabel16, gridBagConstraints);

        jPanel8.setLayout(new java.awt.GridBagLayout());

        buttonGroup1.add(scopeSession);
        scopeSession.setText("temporary tables    "); // NOI18N
        scopeSession.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                scopeSessionActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 58;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        jPanel8.add(scopeSession, gridBagConstraints);

        buttonGroup1.add(scopeGlobal);
        scopeGlobal.setText("global tables"); // NOI18N
        scopeGlobal.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                scopeGlobalActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 56;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        jPanel8.add(scopeGlobal, gridBagConstraints);

        buttonGroup1.add(scopeLocal);
        scopeLocal.setText("local database");
        scopeLocal.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                scopeLocalActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 55;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        jPanel8.add(scopeLocal, gridBagConstraints);

        jLabel1.setForeground(new java.awt.Color(128, 128, 128));
        jLabel1.setText("  (best for single-threaded performance)");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 2;
        gridBagConstraints.gridy = 58;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        jPanel8.add(jLabel1, gridBagConstraints);

        jLabel26.setForeground(new java.awt.Color(128, 128, 128));
        jLabel26.setText("  (best for multi-threaded performance)");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 2;
        gridBagConstraints.gridy = 56;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        jPanel8.add(jLabel26, gridBagConstraints);

        jLabel27.setForeground(new java.awt.Color(128, 128, 128));
        jLabel27.setText("  (no update-privilege required)");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 2;
        gridBagConstraints.gridy = 55;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        jPanel8.add(jLabel27, gridBagConstraints);

        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 56;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.insets = new java.awt.Insets(12, 0, 0, 0);
        jPanel1.add(jPanel8, gridBagConstraints);

        jLabel9.setText("           "); // NOI18N
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 58;
        jPanel1.add(jLabel9, gridBagConstraints);

        jLabel21.setText(" With"); // NOI18N
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 24;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.NORTHWEST;
        gridBagConstraints.insets = new java.awt.Insets(4, 0, 0, 0);
        jPanel1.add(jLabel21, gridBagConstraints);

        parameterPanel.setLayout(new java.awt.GridBagLayout());
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 24;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.fill = java.awt.GridBagConstraints.BOTH;
        gridBagConstraints.weightx = 1.0;
        jPanel1.add(parameterPanel, gridBagConstraints);

        commandLinePanel.setLayout(new java.awt.GridBagLayout());

        jLabel22.setText(" Command line"); // NOI18N
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 0;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        commandLinePanel.add(jLabel22, gridBagConstraints);

        jScrollPane1.setHorizontalScrollBarPolicy(javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        cliArea.setEditable(false);
        cliArea.setColumns(20);
        cliArea.setLineWrap(true);
        cliArea.setWrapStyleWord(true);
        cliArea.setMaximumSize(new java.awt.Dimension(300, 2147483647));
        jScrollPane1.setViewportView(cliArea);

        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.gridheight = 3;
        gridBagConstraints.fill = java.awt.GridBagConstraints.BOTH;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.weighty = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(0, 8, 0, 0);
        commandLinePanel.add(jScrollPane1, gridBagConstraints);

        jLabel23.setText(" "); // NOI18N
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 1;
        commandLinePanel.add(jLabel23, gridBagConstraints);

        jLabel24.setText(" "); // NOI18N
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 2;
        commandLinePanel.add(jLabel24, gridBagConstraints);

        jLabel25.setText(" "); // NOI18N
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 3;
        commandLinePanel.add(jLabel25, gridBagConstraints);

        copyButton.setText("Copy to Clipboard"); // NOI18N
        copyButton.setToolTipText("Copy to Clipboard"); // NOI18N
        copyButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                copyButtonActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 4;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        gridBagConstraints.insets = new java.awt.Insets(0, 8, 0, 0);
        commandLinePanel.add(copyButton, gridBagConstraints);

        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 85;
        gridBagConstraints.gridwidth = 4;
        gridBagConstraints.fill = java.awt.GridBagConstraints.BOTH;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.weighty = 1.0;
        jPanel1.add(commandLinePanel, gridBagConstraints);

        placeholder1.setText(" "); // NOI18N
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 2;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.weightx = 1.0;
        jPanel1.add(placeholder1, gridBagConstraints);

        sortedCheckBox.setText("sort topologically");
        sortedCheckBox.setToolTipText("sort exported rows according to dependencies");
        sortedCheckBox.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 0, 0));
        sortedCheckBox.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                sortedCheckBoxActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 37;
        gridBagConstraints.fill = java.awt.GridBagConstraints.BOTH;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(2, 0, 2, 0);
        jPanel1.add(sortedCheckBox, gridBagConstraints);

        unicode.setText("UTF-8 encoding"); // NOI18N
        unicode.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 0, 0));
        unicode.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                unicodeActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 45;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.insets = new java.awt.Insets(2, 0, 2, 0);
        jPanel1.add(unicode, gridBagConstraints);

        openWhereEditor.setText("jLabel28");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 18;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.EAST;
        gridBagConstraints.insets = new java.awt.Insets(0, 0, 4, 0);
        jPanel1.add(openWhereEditor, gridBagConstraints);

        additSubsLabel.setText(" "); // NOI18N
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 20;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        jPanel1.add(additSubsLabel, gridBagConstraints);

        additSubsLabelTitel.setText(" Additional Subjects"); // NOI18N
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 20;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        jPanel1.add(additSubsLabelTitel, gridBagConstraints);

        useRowIds.setText("use \"ROWID\" column"); // NOI18N
        useRowIds.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 0, 0));
        useRowIds.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                useRowIdsActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 60;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.insets = new java.awt.Insets(8, 0, 4, 0);
        jPanel1.add(useRowIds, gridBagConstraints);

        jLabel10.setText(" Working table schema "); // NOI18N
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 57;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        gridBagConstraints.insets = new java.awt.Insets(4, 0, 0, 0);
        jPanel1.add(jLabel10, gridBagConstraints);

        workingTableSchemaComboBox.setEditable(true);
        workingTableSchemaComboBox.setMaximumRowCount(20);
        workingTableSchemaComboBox.setModel(new javax.swing.DefaultComboBoxModel(new String[] { "Item 1", "Item 2", "Item 3", "Item 4" }));
        workingTableSchemaComboBox.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                workingTableSchemaComboBoxActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 57;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        gridBagConstraints.insets = new java.awt.Insets(4, 0, 0, 0);
        jPanel1.add(workingTableSchemaComboBox, gridBagConstraints);

        confirmInsert.setText("ask for permission to insert into target schema"); // NOI18N
        confirmInsert.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 0, 0));
        confirmInsert.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                confirmInsertActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 46;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.insets = new java.awt.Insets(2, 0, 2, 0);
        jPanel1.add(confirmInsert, gridBagConstraints);

        jLabel17.setText(" To"); // NOI18N
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 19;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        gridBagConstraints.insets = new java.awt.Insets(0, 0, 4, 0);
        jPanel1.add(jLabel17, gridBagConstraints);

        toLabel.setText(" To"); // NOI18N
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 19;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        gridBagConstraints.insets = new java.awt.Insets(0, 0, 4, 0);
        jPanel1.add(toLabel, gridBagConstraints);

        targetDBMSComboBox.setModel(new javax.swing.DefaultComboBoxModel(new String[] { "Item 1", "Item 2", "Item 3", "Item 4" }));
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 35;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        jPanel1.add(targetDBMSComboBox, gridBagConstraints);

        targetDBMSLabel.setText(" Target DBMS"); // NOI18N
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 35;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        jPanel1.add(targetDBMSLabel, gridBagConstraints);

        iFMTableSchemaComboBox.setEditable(true);
        iFMTableSchemaComboBox.setMaximumRowCount(20);
        iFMTableSchemaComboBox.setModel(new javax.swing.DefaultComboBoxModel(new String[] { "Item 1", "Item 2", "Item 3", "Item 4" }));
        iFMTableSchemaComboBox.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                iFMTableSchemaComboBoxActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 59;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.NORTHWEST;
        jPanel1.add(iFMTableSchemaComboBox, gridBagConstraints);

        iFMTPanel.setLayout(new java.awt.GridBagLayout());

        jLabel29.setText(" Import filter-"); // NOI18N
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        iFMTPanel.add(jLabel29, gridBagConstraints);

        jLabel30.setText(" mapping table schema "); // NOI18N
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 2;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        iFMTPanel.add(jLabel30, gridBagConstraints);

        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 59;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.insets = new java.awt.Insets(0, 0, 4, 0);
        jPanel1.add(iFMTPanel, gridBagConstraints);

        browseInsertButton.setText(" Browse..");
        browseInsertButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                browseInsertButtonActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 3;
        gridBagConstraints.gridy = 30;
        jPanel1.add(browseInsertButton, gridBagConstraints);

        browseDeleteButton.setText(" Browse..");
        browseDeleteButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                browseDeleteButtonActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 3;
        gridBagConstraints.gridy = 34;
        jPanel1.add(browseDeleteButton, gridBagConstraints);

        checkPKs.setText("check primary keys"); // NOI18N
        checkPKs.setToolTipText("<html>Check the validity of the primary keys of all relevant tables. <br>Reports an error if a primary key is ambiguous or contains a null. </html>");
        checkPKs.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 0, 0));
        checkPKs.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                checkPKsActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 53;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.insets = new java.awt.Insets(2, 0, 8, 0);
        jPanel1.add(checkPKs, gridBagConstraints);

        insertIncrementally.setText("limit transaction size"); // NOI18N
        insertIncrementally.setToolTipText("<html>Collects the rows using multiple insert operations with a limited number of rows per operation.<br>Use this option if otherwise the transactions become too big.</html>");
        insertIncrementally.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 0, 0));
        insertIncrementally.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                insertIncrementallyActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 48;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.insets = new java.awt.Insets(2, 0, 2, 0);
        jPanel1.add(insertIncrementally, gridBagConstraints);

        targetDBMSLabel1.setText(" Isolation level"); // NOI18N
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 36;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        gridBagConstraints.insets = new java.awt.Insets(0, 0, 4, 0);
        jPanel1.add(targetDBMSLabel1, gridBagConstraints);

        jPanel3.setLayout(new java.awt.GridBagLayout());

        isolationLevelComboBox.setModel(new javax.swing.DefaultComboBoxModel(new String[] { "Item 1", "Item 2", "Item 3", "Item 4" }));
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        gridBagConstraints.insets = new java.awt.Insets(0, 0, 4, 0);
        jPanel3.add(isolationLevelComboBox, gridBagConstraints);

        iLHintLabel.setForeground(new java.awt.Color(128, 128, 128));
        iLHintLabel.setText("  (for all collection and export transactions)");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 2;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(0, 0, 4, 0);
        jPanel3.add(iLHintLabel, gridBagConstraints);

        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 36;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        jPanel1.add(jPanel3, gridBagConstraints);

        transactional.setText("transactional"); // NOI18N
        transactional.setToolTipText("Perform export in a single transaction");
        transactional.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 0, 0));
        transactional.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                transactionalActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 52;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.insets = new java.awt.Insets(2, 0, 2, 0);
        jPanel1.add(transactional, gridBagConstraints);

        independentWorkingTables.setText("independent working tables"); // NOI18N
        independentWorkingTables.setToolTipText("<html>Create working tables that are independent of the extraction model. <br>(Potentially less efficient)</html>");
        independentWorkingTables.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 0, 0));
        independentWorkingTables.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                independentWorkingTablesActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 49;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.insets = new java.awt.Insets(2, 0, 2, 0);
        jPanel1.add(independentWorkingTables, gridBagConstraints);

        orderByPKCheckbox.setText("order by primary key"); // NOI18N
        orderByPKCheckbox.setToolTipText("<html>Orders the exported rows according to the primary key. This makes the result script deterministic.<br>\n<b>Please take into account that this can affect the performance.</b></html>\n");
        orderByPKCheckbox.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 0, 0));
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 39;
        gridBagConstraints.fill = java.awt.GridBagConstraints.BOTH;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(2, 0, 2, 0);
        jPanel1.add(orderByPKCheckbox, gridBagConstraints);

        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 0;
        gridBagConstraints.fill = java.awt.GridBagConstraints.BOTH;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.weighty = 1.0;
        jPanel6.add(jPanel1, gridBagConstraints);

        jScrollPane2.setViewportView(jPanel6);

        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 0;
        gridBagConstraints.fill = java.awt.GridBagConstraints.BOTH;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.weighty = 1.0;
        getContentPane().add(jScrollPane2, gridBagConstraints);

        jPanel7.setBorder(javax.swing.BorderFactory.createEmptyBorder(1, 1, 1, 1));
        jPanel7.setLayout(new java.awt.GridBagLayout());

        jPanel2.setLayout(new java.awt.GridBagLayout());

        jButton1.setText("Export Data"); // NOI18N
        jButton1.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton1ActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 0;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.EAST;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.weighty = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(4, 4, 0, 6);
        jPanel2.add(jButton1, gridBagConstraints);

        jLabel2.setFont(jLabel2.getFont().deriveFont(jLabel2.getFont().getStyle() & ~java.awt.Font.BOLD));
        jLabel2.setText(" *  add '.zip' or '.gz' extension for compressed files"); // NOI18N
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 0;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        jPanel2.add(jLabel2, gridBagConstraints);

        cancelButton.setText(" Cancel ");
        cancelButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                cancelButtonActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 2;
        gridBagConstraints.gridy = 0;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.EAST;
        gridBagConstraints.weighty = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(4, 0, 0, 6);
        jPanel2.add(cancelButton, gridBagConstraints);

        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 100;
        gridBagConstraints.gridwidth = 4;
        gridBagConstraints.fill = java.awt.GridBagConstraints.BOTH;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.weighty = 1.0;
        jPanel7.add(jPanel2, gridBagConstraints);

        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.fill = java.awt.GridBagConstraints.BOTH;
        getContentPane().add(jPanel7, gridBagConstraints);

        pack();
    }// </editor-fold>//GEN-END:initComponents

	private void jButton1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton1ActionPerformed
		if (scriptFormat != ScriptFormat.INTRA_DATABASE) {
			for (JTextField f: schemaMappingFields.values()) {
				if (f.getText().trim().length() == 0) {
					f.setText(DEFAULT_SCHEMA);
				}
			}
		}
		for (JTextField f: sourceSchemaMappingFields.values()) {
			if (f.getText().trim().length() == 0) {
				f.setText(DEFAULT_SCHEMA);
			}
		}
		theSettings.save(settingsContext, settingsContextSecondaryKey);

		boolean err = false;
		if (insert.getText().trim().length() == 0 && (!delete.isVisible() || delete.getText().trim().length() == 0)) {
			exportLabel.setForeground(Color.RED);
			err = true;
		}
		if (scriptFormat == ScriptFormat.INTRA_DATABASE) {
			for (Map.Entry<String, JTextField> e: schemaMappingFields.entrySet()) {
				if (e.getValue().getText().trim().length() == 0) {
					JLabel label = schemaMappingLabels.get(e.getKey());
					if (label != null) {
						label.setForeground(Color.RED);
					}
					err = true;
				}
			}
		}
		if (err) {
			JOptionPane.showMessageDialog(this, "Unfilled mandatory fields", "Error", JOptionPane.ERROR_MESSAGE);
		} else {
			if (useRowIds.isVisible() && !useRowIds.isSelected()) {
				if (!checkForPKs()) {
					return;
				}
			}
			UIUtil.setWaitCursor(this);
			boolean cwt;
			try {
				cwt = createWorkingTables();
			} finally {
				UIUtil.resetWaitCursor(this);
			}
			if (cwt) {
				isOk = true;
				lastConfirmInsert = confirmInsert.isSelected();
				setVisible(false);
			}
		}
	}//GEN-LAST:event_jButton1ActionPerformed

	protected abstract boolean checkForPKs();

	private boolean createWorkingTables() {
		List<String> ddlArgs = new ArrayList<String>();
		ddlArgs.add("create-ddl");
		dbConnectionDialog.addDbArgs(ddlArgs);
		ddlArgs.add(jmFile);
		if (isIndependentWorkingTablesSelected()) {
			ddlArgs.add("-independent-working-tables");
			String delFile = getDeleteFileName();
			if (delFile != null) {
				ddlArgs.add("-d");
				ddlArgs.add(delFile);
			}
		}
		if (!isUseRowId()) {
			ddlArgs.add("-no-rowid");
		}
		if (getWorkingTableSchema() != null) {
			ddlArgs.add("-working-table-schema");
			ddlArgs.add(getWorkingTableSchema());
		}
		ExecutionContext cDDLExecutionContext = new ExecutionContext(executionContext);
		cDDLExecutionContext.setIndependentWorkingTables(isIndependentWorkingTablesSelected());
		cDDLExecutionContext.setNoRowid(!isUseRowId());
		
		DDLCreator ddlCreator = new DDLCreator(cDDLExecutionContext);
		BasicDataSource dataSource;
		String hint = 
				"Possible solutions:\n" +
				"  - choose working table scope \"local database\"\n" +
				"  - choose another working table schema\n" +
				"  - execute the Jailer-DDL manually (jailer_ddl.sql)\n";
		try {
			if (!cDDLExecutionContext.isIndependentWorkingTables()) {
				PrimaryKeyFactory.createUPKScope(tmpFileName != null? tmpFileName : jmFile, cDDLExecutionContext);
			}
			dataSource = UIUtil.createBasicDataSource(this, ddlArgs.get(1), ddlArgs.get(2), ddlArgs.get(3), ddlArgs.get(4), 0, dbConnectionDialog.currentJarURLs());
			String tableInConflict = getTemporaryTableScope().equals(WorkingTableScope.GLOBAL)? UIUtil.getDDLTableInConflict(ddlCreator, ExportDialog.this, dataSource, dataSource.dbms) : null;
			if (tableInConflict != null && getTemporaryTableScope().equals(WorkingTableScope.GLOBAL)) {
				JOptionPane.showMessageDialog(this, "Can't drop table '" + tableInConflict + "' as it is not created by Jailer.\nDrop or rename this table first.", "Error", JOptionPane.ERROR_MESSAGE);
			} else {
				if (!getTemporaryTableScope().equals(WorkingTableScope.GLOBAL) || UIUtil.isDDLUptodate(ddlCreator, ExportDialog.this, dataSource, dataSource.dbms, isUseRowId(), getWorkingTableSchema())) {
					return true;
				} else {
					try {
						DDLCreator.uPKWasTooLong = false;
						boolean result = UIUtil.runJailer(this, ddlArgs, false,
							false, false, true,
							null, dbConnectionDialog.getUser(), dbConnectionDialog.getPassword(), null,
							null, false, false, true, false, true, executionContext);
						if (DDLCreator.uPKWasTooLong) {
							JOptionPane.showMessageDialog(
									this,
									"Universal Primary Key is too big to be indexed.\nPerformance could suffer.\n\n"
									+ "Solution: Try to reduce the number of primary key columns in the tables.",
									"Universal Primaray Key is big", JOptionPane.INFORMATION_MESSAGE);
						}
						return result;
					} catch (Exception e) {
						Throwable cause = e;
						while (cause != null && !(cause instanceof SqlException) && cause.getCause() != null && cause.getCause() != cause) {
							cause = cause.getCause();
						}
						if (cause instanceof SqlException) {
							SqlException sqlEx = (SqlException) cause;
							if (sqlEx.getInsufficientPrivileges()) {
								String message = "Insufficient privileges to create working-tables!\n" + hint;
								if (scriptFormat == ScriptFormat.INTRA_DATABASE) {
									JOptionPane.showOptionDialog(this, message, "Insufficient privileges", JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE, null, new Object[] { "Cancel" }, null);
								} else if (0 == JOptionPane.showOptionDialog(this, message, "Insufficient privileges", JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE, null, new Object[] { "Use local database", "Cancel" }, null)) {
									scopeLocal.setSelected(true);
									return true;
								}							
							} else {
								UIUtil.showException(this, "Error", new SqlException("Automatic creation of working-tables failed!\n" + hint + "\n\nCause: " + sqlEx.message + "", sqlEx.sqlStatement, null));
							}
						} else {
							UIUtil.showException(this, "Error", e);
						}
					}
				}
			}
		} catch (Exception e) {
			UIUtil.showException(this, "Error", e);
		}
		return false;
	}

	private void scopeGlobalActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_scopeGlobalActionPerformed
	}//GEN-LAST:event_scopeGlobalActionPerformed

	private void copyButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_copyButtonActionPerformed
		cliArea.selectAll();
		cliArea.copy();
		updateCLIArea();
	}//GEN-LAST:event_copyButtonActionPerformed

	private void sortedCheckBoxActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_sortedCheckBoxActionPerformed
	}//GEN-LAST:event_sortedCheckBoxActionPerformed

	private void scopeSessionActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_scopeSessionActionPerformed
	}//GEN-LAST:event_scopeSessionActionPerformed

	private void unicodeActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_unicodeActionPerformed
	}//GEN-LAST:event_unicodeActionPerformed

	private void cancelButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_cancelButtonActionPerformed
		dispose();
	}//GEN-LAST:event_cancelButtonActionPerformed

	private void threadsFocusLost(java.awt.event.FocusEvent evt) {//GEN-FIRST:event_threadsFocusLost
		String text = threads.getText().trim();
		if (text.length() > 0) {
			try {
				int n = Integer.parseInt(text);
				if (n > 10000) {
					threads.setText("10000");
				}
			} catch (NumberFormatException e) {
				threads.setText("");
			}
		}
	}//GEN-LAST:event_threadsFocusLost

	private void useRowIdsActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_useRowIdsActionPerformed
		updateCLIArea();
	}//GEN-LAST:event_useRowIdsActionPerformed

	private void workingTableSchemaComboBoxActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_workingTableSchemaComboBoxActionPerformed
	}//GEN-LAST:event_workingTableSchemaComboBoxActionPerformed

	private void scopeLocalActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_scopeLocalActionPerformed
	}//GEN-LAST:event_scopeLocalActionPerformed

	private void confirmInsertActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_confirmInsertActionPerformed
	}//GEN-LAST:event_confirmInsertActionPerformed

	private void iFMTableSchemaComboBoxActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_iFMTableSchemaComboBoxActionPerformed
	}//GEN-LAST:event_iFMTableSchemaComboBoxActionPerformed

	private void browseInsertButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_browseInsertButtonActionPerformed
		String fn = UIUtil.choseFile(null, ".", scriptFormat.getFileChooserTitle(), scriptFormat.getFileExtension(), ExportDialog.this, true, false);
		if (fn != null) {
			insert.setText(fn);
		}
	}//GEN-LAST:event_browseInsertButtonActionPerformed

	private void browseDeleteButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_browseDeleteButtonActionPerformed
		String fn = UIUtil.choseFile(null, ".", "SQL Delete Script", ".sql", ExportDialog.this, true, false);
		if (fn != null) {
			delete.setText(fn);
		}
	}//GEN-LAST:event_browseDeleteButtonActionPerformed

    private void checkPKsActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_checkPKsActionPerformed
    }//GEN-LAST:event_checkPKsActionPerformed

    private void insertIncrementallyActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_insertIncrementallyActionPerformed
    }//GEN-LAST:event_insertIncrementallyActionPerformed

    private void transactionalActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_transactionalActionPerformed
        iLHintLabel.setVisible(!transactional.isSelected());
    }//GEN-LAST:event_transactionalActionPerformed

    private void independentWorkingTablesActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_independentWorkingTablesActionPerformed
    }//GEN-LAST:event_independentWorkingTablesActionPerformed
	
	public boolean isOk() {
		return isOk;
	}

	/**
	 * Gets name of delete-script, or <code>null</code>, if there is no delete-script.
	 */
	public String getDeleteFileName() {
		if (delete.isVisible() && delete.getText().trim().length() > 0) {
			return toFileName(delete.getText().trim());
		}
		return null;
	}
	
	public boolean isIndependentWorkingTablesSelected() {
		return independentWorkingTables.isSelected();
	}

	public boolean insertScripFileNameFieldIsEmpty() {
		return insert.getText().trim().length() == 0;
	}
	
	/**
	 * Fills field content into cli-args.
	 * 
	 * @param args the argument-list to fill
	 */
	public void fillCLIArgs(List<String> args) {
		if (insert.getText().trim().length() > 0) {
			args.add(0, "export");
			args.add("-e");
			args.add(toFileName(insert.getText()));
		} else {
			args.add(0, "delete");
		}
		if (delete.isVisible() && delete.getText().trim().length() > 0) {
			args.add("-d");
			args.add(toFileName(delete.getText().trim()));
		}
		if (checkPKs.isSelected()) {
			args.add("-check-primary-keys");
		}
		if (insertIncrementally.isSelected()) {
			args.add("-limit-transaction-size");
		}
		if (isIndependentWorkingTablesSelected()) {
			args.add("-independent-working-tables");
		}
		if (transactional.isSelected()) {
			args.add("-transactional");
		}
		Object isolationLevel = isolationLevelComboBox.getSelectedItem();
		if (isolationLevel != null && !String.valueOf(Connection.TRANSACTION_NONE).equals(isolationLevel)) {
			args.add("-isolation-level");
			args.add(isolationLevel.toString());
		}
		if (explain.isSelected()) {
			args.add("-explain");
		}
		if (unicode.isSelected()) {
			args.add("-UTF8");
		}
		if (upsertCheckbox.isSelected()) {
			args.add("-upsert-only");
		}
		if (!sortedCheckBox.isSelected()) {
			args.add("-no-sorting");
		}
		if (orderByPKCheckbox.isSelected()) {
			args.add("-order-by-pk");
		}
		if (!useRowIds.isSelected() && useRowIds.isVisible()) {
			args.add("-no-rowid");
		}
		if (scriptFormat == ScriptFormat.SQL) {
			Object selectedItem = targetDBMSComboBox.getSelectedItem();
			if (selectedItem instanceof DBMS) {
				DBMS targetDBMS = (DBMS) selectedItem;
				if (targetDBMS != null && targetDBMS != sourceDBMS) {
					args.add("-target-dbms");
					args.add(targetDBMS.getId());
				}
			}
		}
		try {
			int nt = Integer.parseInt(threads.getText().trim());
			if (nt > 0) {
				args.add("-threads");
				args.add("" + nt);
			}
		} catch (Exception e) {
		}
		try {
			int nt = Integer.parseInt(rowsPerThread.getText().trim());
			if (nt > 0) {
				args.add("-entities");
				args.add("" + nt);
			}
		} catch (Exception e) {
		}
		
		if (!where.getText().equals(subjectCondition)) {
			args.add("-where");
			args.add((where.getText()).replace('\n', ' ').replace('\r', ' '));
		}

		args.add("-format");
		args.add(scriptFormat.toString());
		if (ScriptFormat.XML.equals(scriptFormat)) {
			args.add("-xml");
			args.add("-xml-root");
			args.add(dataModel.getXmlSettings().rootTag);
			args.add("-xml-date");
			args.add(dataModel.getXmlSettings().datePattern);
			args.add("-xml-timestamp");
			args.add(dataModel.getXmlSettings().timestampPattern);
		}
		
		targetSchemaSet.clear();
		StringBuilder schemaMapping = new StringBuilder();
		for (String schema: schemaMappingFields.keySet()) {
			String to = schemaMappingFields.get(schema).getText().trim();
			targetSchemaSet.add(to);
			if (to.equals(DEFAULT_SCHEMA)) {
				to = "";
			}
			if (schemaMapping.length() > 0) {
				schemaMapping.append(",");
			}
			schemaMapping.append((schema.equals(DEFAULT_SCHEMA)? "" : schema) + "=" + to);
		}
		if (schemaMapping.length() > 0) {
			args.add("-schemamapping");
			args.add(schemaMapping.toString());
		}
		
		StringBuilder parameter = new StringBuilder();
		for (String p: parameterEditor.textfieldsPerParameter.keySet()) {
			String v = parameterEditor.textfieldsPerParameter.get(p).getText().trim();
			if (parameter.length() > 0) {
				parameter.append(";");
			}
			parameter.append(p + "=" + CsvFile.encodeCell(v));
		}
		if (parameter.length() > 0) {
			args.add("-parameters");
			args.add(parameter.toString());
		}
		
		StringBuilder sourceSchemaMapping = new StringBuilder();
		for (String schema: sourceSchemaMappingFields.keySet()) {
			String to = sourceSchemaMappingFields.get(schema).getText().trim();
			if (to.equals(DEFAULT_SCHEMA)) {
				to = "";
			}
			if (sourceSchemaMapping.length() > 0) {
				sourceSchemaMapping.append(",");
			}
			sourceSchemaMapping.append((schema.equals(DEFAULT_SCHEMA)? "" : schema) + "=" + to);
		}
		if (sourceSchemaMapping.length() > 0) {
			args.add("-source-schemamapping");
			args.add(sourceSchemaMapping.toString());
		}

		args.add("-scope");
		args.add(getTemporaryTableScope().toString());

		String schema = (String) workingTableSchemaComboBox.getEditor().getItem();
		if (schema != null && schema.length() > 0 && !schema.equals(DEFAULT_SCHEMA)) {
			args.add("-working-table-schema");
			args.add(schema);
		}
		
		if (iFMTableSchemaComboBox.isVisible()) {
			try {
				JTextField c = (JTextField) iFMTableSchemaComboBox.getEditor().getEditorComponent();
				String ifmItem = c.getText().trim();
				if (ifmItem != null && !"".equals(ifmItem) && !DEFAULT_SCHEMA.equals(ifmItem)) {
					args.add("-import-filter-mapping-table-schema");
					args.add(ifmItem.toString());
				}
			} catch (ClassCastException e) {
				// ignore
			}
		}
	}

	private String toFileName(String f) {
		if (!new File(f).isAbsolute()) {
			return Environment.newFile(f).getPath();
		}
		return f;
	}

	private Set<String> getRelevantSchemas(boolean withDelete) {
		Set<Table> closure = closureOfSubjects();
		
		if (withDelete) {
			Set<Table> border = new HashSet<Table>();
			for (Table table: closure) {
				for (Association a: table.associations) {
					if (!a.reversalAssociation.isIgnored()) {
						border.add(a.destination);
					}
				}
			}
			closure.addAll(border);
		}
		Set<String> relevantSchemas = new HashSet<String>();
		for (Table table: closure) {
			relevantSchemas.add(table.getOriginalSchema(""));
		}
		return relevantSchemas;
	}

	private Set<Table> closureOfSubjects() {
		Set<Table> subjects = new HashSet<Table>();
		subjects.add(subject);
		if (additionalSubjects != null) {
			for (AdditionalSubject as: additionalSubjects) {
				subjects.add(as.getSubject());
			}
		}
		
		Set<Table> closure = new HashSet<Table>();
		
		for (Table subject: subjects) {
			Set<Table> toCheck = new HashSet<Table>(subject.closure(closure, true));
			closure.addAll(toCheck);
		}
		return closure;
	}

	public Set<String> getTargetSchemaSet() {
		return targetSchemaSet;
	}

	public boolean getConfirmExport() {
		return confirmInsert.isSelected();
	}

	public boolean isUseRowId() {
		return useRowIds.isSelected();
	}
	
	public String getWorkingTableSchema() {
		String schema = (String) workingTableSchemaComboBox.getEditor().getItem();
		if (schema.length() > 0 && !schema.equals(DEFAULT_SCHEMA)) {
			return schema;
		}
		return null;
	}

	public WorkingTableScope getTemporaryTableScope() {
		if (scopeLocal.isSelected()) {
			return WorkingTableScope.LOCAL_DATABASE;
		}
		if (scopeSession.isSelected()) {
			return WorkingTableScope.SESSION_LOCAL;
		}
//    	if (scopeTransaction.isSelected()) {
//    		return TemporaryTableScope.TRANSACTION_LOCAL;
//    	}
		return WorkingTableScope.GLOBAL;
	}

	public boolean hasDeleteScript() {
		return delete.isVisible() && delete.getText().trim().length() > 0;
	}
	
    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JLabel additSubsLabel;
    private javax.swing.JLabel additSubsLabelTitel;
    private javax.swing.JButton browseDeleteButton;
    private javax.swing.JButton browseInsertButton;
    private javax.swing.ButtonGroup buttonGroup1;
    private javax.swing.JButton cancelButton;
    public javax.swing.JCheckBox checkPKs;
    private javax.swing.JTextArea cliArea;
    public javax.swing.JPanel commandLinePanel;
    public javax.swing.JCheckBox confirmInsert;
    private javax.swing.JButton copyButton;
    private javax.swing.JTextField delete;
    public javax.swing.JCheckBox explain;
    private javax.swing.JLabel exportLabel;
    private javax.swing.JPanel iFMTPanel;
    private javax.swing.JComboBox iFMTableSchemaComboBox;
    private javax.swing.JLabel iLHintLabel;
    public javax.swing.JCheckBox independentWorkingTables;
    private javax.swing.JTextField insert;
    public javax.swing.JCheckBox insertIncrementally;
    private javax.swing.JComboBox isolationLevelComboBox;
    private javax.swing.JButton jButton1;
    private javax.swing.JLabel jLabel1;
    private javax.swing.JLabel jLabel10;
    private javax.swing.JLabel jLabel11;
    private javax.swing.JLabel jLabel12;
    private javax.swing.JLabel jLabel13;
    private javax.swing.JLabel jLabel14;
    private javax.swing.JLabel jLabel15;
    private javax.swing.JLabel jLabel16;
    private javax.swing.JLabel jLabel17;
    private javax.swing.JLabel jLabel18;
    private javax.swing.JLabel jLabel19;
    private javax.swing.JLabel jLabel2;
    private javax.swing.JLabel jLabel20;
    private javax.swing.JLabel jLabel21;
    private javax.swing.JLabel jLabel22;
    private javax.swing.JLabel jLabel23;
    private javax.swing.JLabel jLabel24;
    private javax.swing.JLabel jLabel25;
    private javax.swing.JLabel jLabel26;
    private javax.swing.JLabel jLabel27;
    private javax.swing.JLabel jLabel29;
    private javax.swing.JLabel jLabel3;
    private javax.swing.JLabel jLabel30;
    private javax.swing.JLabel jLabel4;
    private javax.swing.JLabel jLabel5;
    private javax.swing.JLabel jLabel6;
    private javax.swing.JLabel jLabel7;
    private javax.swing.JLabel jLabel8;
    private javax.swing.JLabel jLabel9;
    private javax.swing.JPanel jPanel1;
    private javax.swing.JPanel jPanel2;
    private javax.swing.JPanel jPanel3;
    private javax.swing.JPanel jPanel4;
    private javax.swing.JPanel jPanel6;
    private javax.swing.JPanel jPanel7;
    private javax.swing.JPanel jPanel8;
    private javax.swing.JScrollPane jScrollPane1;
    private javax.swing.JScrollPane jScrollPane2;
    private javax.swing.JLabel openWhereEditor;
    private javax.swing.JCheckBox orderByPKCheckbox;
    private javax.swing.JPanel parameterPanel;
    private javax.swing.JLabel placeholder;
    private javax.swing.JLabel placeholder1;
    private javax.swing.JTextField rowsPerThread;
    private javax.swing.JPanel schemaMappingPanel;
    private javax.swing.JRadioButton scopeGlobal;
    private javax.swing.JRadioButton scopeLocal;
    private javax.swing.JRadioButton scopeSession;
    private javax.swing.JCheckBox sortedCheckBox;
    public javax.swing.JPanel sourceSchemaMappingPanel;
    private javax.swing.JLabel subjectTable;
    private javax.swing.JComboBox targetDBMSComboBox;
    private javax.swing.JLabel targetDBMSLabel;
    private javax.swing.JLabel targetDBMSLabel1;
    private javax.swing.JTextField threads;
    private javax.swing.JLabel toLabel;
    public javax.swing.JCheckBox transactional;
    public javax.swing.JCheckBox unicode;
    private javax.swing.JCheckBox upsertCheckbox;
    public javax.swing.JCheckBox useRowIds;
    private javax.swing.JTextField where;
    private javax.swing.JComboBox workingTableSchemaComboBox;
    // End of variables declaration//GEN-END:variables
	
	private Icon loadIcon;
	private Icon conditionEditorIcon;
	private Icon conditionEditorSelectedIcon;
	{
		String dir = "/net/sf/jailer/ui/resource";
		
		// load images
		try {
			loadIcon = new ImageIcon(getClass().getResource(dir + "/load.png"));
		} catch (Exception e) {
			e.printStackTrace();
		}
		try {
			conditionEditorIcon = new ImageIcon(getClass().getResource(dir + "/edit.png"));
		} catch (Exception e) {
			e.printStackTrace();
		}
		try {
			conditionEditorSelectedIcon = new ImageIcon(getClass().getResource(dir + "/edit_s.png"));
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

}
