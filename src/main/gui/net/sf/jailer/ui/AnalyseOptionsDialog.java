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

import java.awt.CardLayout;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.swing.DefaultComboBoxModel;

import net.sf.jailer.ExecutionContext;
import net.sf.jailer.datamodel.DataModel;
import net.sf.jailer.ui.util.ConcurrentTaskControl;
import net.sf.jailer.ui.util.UISettings;
import net.sf.jailer.util.CancellationHandler;
import net.sf.jailer.util.CsvFile;
import net.sf.jailer.util.CsvFile.Line;
import net.sf.jailer.util.CsvFile.LineFilter;

/**
 * Dialog for setting database analysing options.
 * 
 * @author Ralf Wisser
 */
public class AnalyseOptionsDialog extends javax.swing.JDialog {

	/**
	 * The selected schema;
	 */
	private String selectedSchema;

	// number of current model elements
	private int numTables = 0;
	private int numAssociations = 0;
	private int numManTables = 0;
	private int numManAssociations = 0;

	@SuppressWarnings("serial")
	private final ConcurrentTaskControl concurrentTaskControl = new ConcurrentTaskControl(
			this, "Retrieving schema names...") {

		@Override
		protected void onError(Throwable error) {
			UIUtil.showException(this, "Error", error);
			AnalyseOptionsDialog.this.setVisible(false);
			AnalyseOptionsDialog.this.dispose();
		}

		@Override
		protected void onCancellation() {
			AnalyseOptionsDialog.this.setVisible(false);
			AnalyseOptionsDialog.this.dispose();
		}

	};

	private final Object LOCK = new Object();

	/**
	 * true if user clicks OK button.
	 */
	private boolean ok;

	/** Creates new form AnalyseOptionsDialog 
	 */
	public AnalyseOptionsDialog(java.awt.Frame parent, DataModel dataModel, ExecutionContext executionContext) throws Exception {
		super(parent, true);
		initComponents();

        jPanel4.add(concurrentTaskControl, "cctc");
        ((CardLayout) jPanel4.getLayout()).show(jPanel4, "cctc");
        
		AutoCompletion.enable(schemaComboBox);
		
		List<Line> tables = new CsvFile(new File(DataModel.getTablesFile(executionContext))).getLines();
		for (Line table: tables) {
			++numTables;
			if (isManuallyEditedTable(table)) {
				++numManTables;
			}
		}
		List<Line> assocs = new CsvFile(new File(DataModel.getAssociationsFile(executionContext))).getLines();
		for (Line assoc: assocs) {
			++numAssociations;
			if (isManuallyEditedAssoc(assoc)) {
				++numManAssociations;
			}
		}
		
		removeCurrentAssociationsCheckBox.setText(removeCurrentAssociationsCheckBox.getText().replace("0", Integer.toString(numAssociations)));
		removeCurrentTablesCheckBox.setText(removeCurrentTablesCheckBox.getText().replace("0", Integer.toString(numTables)));
		keepManAssociationsCheckBox.setText(keepManAssociationsCheckBox.getText().replace("0", Integer.toString(numManAssociations)));
		keepManTablesCheckBox.setText(keepManTablesCheckBox.getText().replace("0", Integer.toString(numManTables)));
		
		removeCurrentAssociationsCheckBox.setEnabled(numAssociations > 0);
		removeCurrentTablesCheckBox.setEnabled(numTables > 0);
		
		pack();
		setLocation(parent.getLocation().x + parent.getSize().width/2 - getPreferredSize().width/2,
				parent.getLocation().y + parent.getSize().height/2 - getPreferredSize().height/2);
		UIUtil.initPeer();
	}

	public boolean isRemoving() {
		return removeCurrentAssociationsCheckBox.isSelected() || removeCurrentTablesCheckBox.isSelected();
	}
	
	public LineFilter getAssociationLineFilter() {
		final boolean remove = removeCurrentAssociationsCheckBox.isSelected();
		final boolean keep = keepManAssociationsCheckBox.isSelected();
		return new LineFilter() {
			@Override
			public boolean accept(Line line) {
				if (!remove) {
					return true;
				}
				if (keep && isManuallyEditedAssoc(line)) {
					return true;
				}
				return false;
			}
		};
	}
	
	public LineFilter getTableLineFilter() {
		final boolean remove = removeCurrentTablesCheckBox.isSelected();
		final boolean keep = keepManTablesCheckBox.isSelected();
		return new LineFilter() {
			@Override
			public boolean accept(Line line) {
				if (!remove) {
					return true;
				}
				if (keep && isManuallyEditedTable(line)) {
					return true;
				}
				return false;
			}
		};
	}
	
	private static boolean isManuallyEditedAssoc(Line assoc) {
		return DataModelEditor.DATA_MODEL_EDITOR_AUTHOR.equals(assoc.cells.get(6));
	}

	private static boolean isManuallyEditedTable(Line table) {
		int size = table.cells.size();
		for (int i = 0; i < size; ++i) {
			if ("".equals(table.cells.get(i))) {
				return DataModelEditor.DATA_MODEL_EDITOR_AUTHOR.equals(table.cells.get(i+1));
			}
		}
		return false;
	}

	public void setInitiallyWithViews(boolean withViews) {
		analyseViews.setSelected(withViews);
	}

	public void setInitiallyWithSynonyms(boolean withSynonyms) {
		analyseSynonyms.setSelected(withSynonyms);
	}

	public boolean edit(DbConnectionDialog dbConnectionDialog, boolean[] isDefaultSchema, String currentUser) throws Exception {
		return edit(dbConnectionDialog, null, isDefaultSchema, currentUser);
	}

	public boolean edit(final DbConnectionDialog dbConnectionDialog, final String initiallySelectedSchema, final boolean[] isDefaultSchema, final String currentUser) throws Exception {
		final String[] defaultSchemaF = new String[1];
		final List<String> schemas;
		synchronized (LOCK) {
			schemas = new ArrayList<String>();
		}
		concurrentTaskControl.start(new ConcurrentTaskControl.Task() {
			@Override
			public void run() throws Throwable {
				CancellationHandler.reset(null);
				List<String> dbSchemas = dbConnectionDialog.getDBSchemas(defaultSchemaF);
				synchronized (LOCK) {
					schemas.addAll(dbSchemas);
				}

				UIUtil.invokeLater(new Runnable() {
					@Override
					public void run() {
						String defaultSchema;
						synchronized (LOCK) {
							defaultSchema = defaultSchemaF[0];
							isDefaultSchema[0] = false;
							if (schemas.size() == 1) {
								if (schemas.get(0).equalsIgnoreCase(currentUser)) {
									isDefaultSchema[0] = true;
								}
							}
							if (schemas.isEmpty()) {
								isDefaultSchema[0] = true;
								schemaLabel.setVisible(false);
								schemaComboBox.setVisible(false);
							} else {
								DefaultComboBoxModel model = new DefaultComboBoxModel(schemas.toArray());
								schemaComboBox.setModel(model);
								if (initiallySelectedSchema != null) {
									schemaComboBox.setSelectedItem(initiallySelectedSchema);
								} else if (defaultSchema != null) {
									schemaComboBox.setSelectedItem(defaultSchema);
								}
							}

					        ((CardLayout) jPanel4.getLayout()).show(jPanel4, "main");
							okButton.grabFocus();
						}
					}
				});
			}
		});

		ok = false;
		
		pack();
		setVisible(true);

		synchronized (LOCK) {
			String defaultSchema = defaultSchemaF[0];
			if (ok) {
				selectedSchema = null;
				if (!schemas.isEmpty()) {
					if (schemaComboBox.getSelectedItem() instanceof String) {
						selectedSchema = (String) schemaComboBox.getSelectedItem();
					}
				}
				if (selectedSchema != null && selectedSchema.equalsIgnoreCase(defaultSchema)) {
					isDefaultSchema[0] = true;
				}
			}
			if (ok) {
				++UISettings.s7;
			}
		}
		return ok;
	}
	
	public String getSelectedSchema() {
		return selectedSchema;
	}
	
	public void appendAnalyseCLIOptions(List<String> args) {
		if (analyseAlias.isSelected()) {
			args.add("-analyse-alias");
		}
		if (analyseSynonyms.isSelected()) {
			args.add("-analyse-synonym");
		}
		if (analyseViews.isSelected()) {
			args.add("-analyse-view");
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

        jPanel4 = new javax.swing.JPanel();
        jPanel5 = new javax.swing.JPanel();
        jPanel2 = new javax.swing.JPanel();
        removeCurrentTablesCheckBox = new javax.swing.JCheckBox();
        jLabel1 = new javax.swing.JLabel();
        keepManTablesCheckBox = new javax.swing.JCheckBox();
        removeCurrentAssociationsCheckBox = new javax.swing.JCheckBox();
        keepManAssociationsCheckBox = new javax.swing.JCheckBox();
        jPanel1 = new javax.swing.JPanel();
        okButton = new javax.swing.JButton();
        cancelButton = new javax.swing.JButton();
        schemaLabel = new javax.swing.JLabel();
        schemaComboBox = new JComboBox();
        jPanel3 = new javax.swing.JPanel();
        analyseAlias = new javax.swing.JCheckBox();
        analyseSynonyms = new javax.swing.JCheckBox();
        jLabel2 = new javax.swing.JLabel();
        analyseViews = new javax.swing.JCheckBox();

        setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        setTitle("Analyze Database");
        getContentPane().setLayout(new java.awt.GridBagLayout());

        jPanel4.setLayout(new java.awt.CardLayout());

        jPanel5.setLayout(new java.awt.GridBagLayout());

        jPanel2.setBorder(javax.swing.BorderFactory.createTitledBorder(new javax.swing.border.SoftBevelBorder(javax.swing.border.BevelBorder.RAISED), "Preparation"));
        jPanel2.setLayout(new java.awt.GridBagLayout());

        removeCurrentTablesCheckBox.setText("Remove current tables (0)");
        removeCurrentTablesCheckBox.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                removeCurrentTablesCheckBoxActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 2;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(8, 8, 0, 8);
        jPanel2.add(removeCurrentTablesCheckBox, gridBagConstraints);

        jLabel1.setText("   ");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 8;
        jPanel2.add(jLabel1, gridBagConstraints);

        keepManTablesCheckBox.setSelected(true);
        keepManTablesCheckBox.setText("Keep manually entered tables (0)");
        keepManTablesCheckBox.setEnabled(false);
        keepManTablesCheckBox.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                keepManTablesCheckBoxActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 3;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(0, 24, 0, 8);
        jPanel2.add(keepManTablesCheckBox, gridBagConstraints);

        removeCurrentAssociationsCheckBox.setText("Remove current associations (0)");
        removeCurrentAssociationsCheckBox.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                removeCurrentAssociationsCheckBoxActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 4;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(8, 8, 0, 8);
        jPanel2.add(removeCurrentAssociationsCheckBox, gridBagConstraints);

        keepManAssociationsCheckBox.setSelected(true);
        keepManAssociationsCheckBox.setText("Keep manually entered associations (0)");
        keepManAssociationsCheckBox.setEnabled(false);
        keepManAssociationsCheckBox.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                keepManAssociationsCheckBoxActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 5;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(0, 24, 0, 8);
        jPanel2.add(keepManAssociationsCheckBox, gridBagConstraints);

        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.insets = new java.awt.Insets(8, 0, 8, 0);
        jPanel5.add(jPanel2, gridBagConstraints);

        jPanel1.setLayout(new java.awt.GridBagLayout());

        okButton.setText(" Ok ");
        okButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                okButtonActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.EAST;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(0, 0, 0, 4);
        jPanel1.add(okButton, gridBagConstraints);

        cancelButton.setText(" Cancel ");
        cancelButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                cancelButtonActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 2;
        gridBagConstraints.gridy = 1;
        jPanel1.add(cancelButton, gridBagConstraints);

        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 10;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.insets = new java.awt.Insets(8, 0, 4, 0);
        jPanel5.add(jPanel1, gridBagConstraints);

        schemaLabel.setText(" Analyze schema ");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 9;
        jPanel5.add(schemaLabel, gridBagConstraints);

        schemaComboBox.setModel(new javax.swing.DefaultComboBoxModel(new String[] { "Item 1", "Item 2", "Item 3", "Item 4" }));
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 2;
        gridBagConstraints.gridy = 9;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        jPanel5.add(schemaComboBox, gridBagConstraints);

        jPanel3.setBorder(javax.swing.BorderFactory.createTitledBorder(new javax.swing.border.SoftBevelBorder(javax.swing.border.BevelBorder.RAISED), "Analyse tables and ..."));
        jPanel3.setLayout(new java.awt.GridBagLayout());

        analyseAlias.setText("Aliases");
        analyseAlias.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                analyseAliasActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 2;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(8, 8, 0, 8);
        jPanel3.add(analyseAlias, gridBagConstraints);

        analyseSynonyms.setText("Synonyms");
        analyseSynonyms.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                analyseSynonymsActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 3;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(8, 8, 0, 8);
        jPanel3.add(analyseSynonyms, gridBagConstraints);

        jLabel2.setText("   ");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 8;
        jPanel3.add(jLabel2, gridBagConstraints);

        analyseViews.setText("Views");
        analyseViews.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                analyseViewsActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 4;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(8, 8, 0, 8);
        jPanel3.add(analyseViews, gridBagConstraints);

        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 2;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.insets = new java.awt.Insets(0, 0, 8, 0);
        jPanel5.add(jPanel3, gridBagConstraints);

        jPanel4.add(jPanel5, "main");

        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.fill = java.awt.GridBagConstraints.BOTH;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.weighty = 1.0;
        getContentPane().add(jPanel4, gridBagConstraints);

        pack();
    }// </editor-fold>//GEN-END:initComponents

	private void removeCurrentTablesCheckBoxActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_removeCurrentTablesCheckBoxActionPerformed
		   keepManTablesCheckBox.setEnabled(removeCurrentTablesCheckBox.isSelected());
	}//GEN-LAST:event_removeCurrentTablesCheckBoxActionPerformed

	private void keepManTablesCheckBoxActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_keepManTablesCheckBoxActionPerformed
	}//GEN-LAST:event_keepManTablesCheckBoxActionPerformed

	private void removeCurrentAssociationsCheckBoxActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_removeCurrentAssociationsCheckBoxActionPerformed
		   keepManAssociationsCheckBox.setEnabled(removeCurrentAssociationsCheckBox.isSelected());
	}//GEN-LAST:event_removeCurrentAssociationsCheckBoxActionPerformed

	private void keepManAssociationsCheckBoxActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_keepManAssociationsCheckBoxActionPerformed
	}//GEN-LAST:event_keepManAssociationsCheckBoxActionPerformed

	private void okButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_okButtonActionPerformed
		ok = true;
		setVisible(false);
		dispose();
	}//GEN-LAST:event_okButtonActionPerformed

	private void cancelButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_cancelButtonActionPerformed
		ok = false;
		setVisible(false);
		dispose();
	}//GEN-LAST:event_cancelButtonActionPerformed

	private void analyseAliasActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_analyseAliasActionPerformed
	   
	}//GEN-LAST:event_analyseAliasActionPerformed

	private void analyseViewsActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_analyseViewsActionPerformed
	   
	}//GEN-LAST:event_analyseViewsActionPerformed

	private void analyseSynonymsActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_analyseSynonymsActionPerformed
	   
	}//GEN-LAST:event_analyseSynonymsActionPerformed

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JCheckBox analyseAlias;
    private javax.swing.JCheckBox analyseSynonyms;
    private javax.swing.JCheckBox analyseViews;
    private javax.swing.JButton cancelButton;
    private javax.swing.JLabel jLabel1;
    private javax.swing.JLabel jLabel2;
    private javax.swing.JPanel jPanel1;
    private javax.swing.JPanel jPanel2;
    private javax.swing.JPanel jPanel3;
    private javax.swing.JPanel jPanel4;
    private javax.swing.JPanel jPanel5;
    private javax.swing.JCheckBox keepManAssociationsCheckBox;
    private javax.swing.JCheckBox keepManTablesCheckBox;
    private javax.swing.JButton okButton;
    private javax.swing.JCheckBox removeCurrentAssociationsCheckBox;
    private javax.swing.JCheckBox removeCurrentTablesCheckBox;
    private JComboBox schemaComboBox;
    private javax.swing.JLabel schemaLabel;
    // End of variables declaration//GEN-END:variables

	private static final long serialVersionUID = 7293743969854047598L;

}
