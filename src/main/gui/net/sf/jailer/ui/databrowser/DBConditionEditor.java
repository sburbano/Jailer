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
package net.sf.jailer.ui.databrowser;

import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.awt.event.WindowFocusListener;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.ActionMap;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.InputMap;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.text.BadLocationException;

import org.fife.rsta.ui.EscapableDialog;

import net.sf.jailer.datamodel.Column;
import net.sf.jailer.datamodel.DataModel;
import net.sf.jailer.datamodel.Table;
import net.sf.jailer.ui.ParameterSelector;
import net.sf.jailer.ui.UIUtil;
import net.sf.jailer.ui.scrollmenu.JScrollPopupMenu;
import net.sf.jailer.ui.syntaxtextarea.BasicFormatterImpl;
import net.sf.jailer.ui.syntaxtextarea.DataModelBasedSQLCompletionProvider;
import net.sf.jailer.ui.syntaxtextarea.RSyntaxTextAreaWithSQLSyntaxStyle;
import net.sf.jailer.ui.syntaxtextarea.SQLAutoCompletion;
import net.sf.jailer.ui.syntaxtextarea.SQLCompletionProvider;
import net.sf.jailer.ui.util.SizeGrip;
import net.sf.jailer.util.SqlUtil;

/**
 * Editor for multi-line SQL conditions with parameter support.
 * 
 * @author Ralf Wisser
 */
public abstract class DBConditionEditor extends EscapableDialog {

	private boolean ok;
	private boolean escaped;
	private ParameterSelector parameterSelector;
	private DataModelBasedSQLCompletionProvider provider;

	/** Creates new form ConditionEditor */
	public DBConditionEditor(java.awt.Frame parent, DataModel dataModel) {
		super(parent, false);
		setUndecorated(true);
		initComponents();
		
		addWindowFocusListener(new WindowFocusListener() {
			@Override
			public void windowLostFocus(WindowEvent e) {
				ok = !escaped;
				setVisible(false);
			}
			@Override
			public void windowGainedFocus(WindowEvent e) {
			}
		});
		
		addComponentListener(new ComponentListener() {
			@Override
			public void componentShown(ComponentEvent e) {
			}
			@Override
			public void componentResized(ComponentEvent e) {
			}
			@Override
			public void componentMoved(ComponentEvent e) {
			}
			@Override
			public void componentHidden(ComponentEvent e) {
				if (ok && initialCondition.equals(editorPane.getText())) {
					ok = false;
				}
				consume(ok? removeSingleLineComments(editorPane.getText()).replaceAll("\\n(\\r?) *", " ").replace('\n', ' ').replace('\r', ' ') : null);
			}
		});
		
		this.editorPane = new RSyntaxTextAreaWithSQLSyntaxStyle(false, false) {
			@Override
			protected void runBlock() {
				super.runBlock();
				okButtonActionPerformed(null);
			}
		};
		JScrollPane jScrollPane2 = new JScrollPane();
		jScrollPane2.setViewportView(editorPane);
		
		JPanel corner = new SizeGrip();
		gripPanel.add(corner);

		GridBagConstraints gridBagConstraints = new java.awt.GridBagConstraints();
		gridBagConstraints.gridx = 1;
		gridBagConstraints.gridy = 1;
		gridBagConstraints.gridwidth = 100;
		gridBagConstraints.fill = java.awt.GridBagConstraints.BOTH;
		gridBagConstraints.weightx = 1.0;
		gridBagConstraints.weighty = 1.0;
		jPanel1.add(jScrollPane2, gridBagConstraints);
		jScrollPane2.setViewportView(editorPane);
		
		if (dataModel != null) {
			try {
				provider = new DataModelBasedSQLCompletionProvider(null, dataModel);
				provider.setDefaultClause(SQLCompletionProvider.Clause.WHERE);
				sqlAutoCompletion = new SQLAutoCompletion(provider, editorPane);
			} catch (SQLException e) {
			}
		}
		
		setLocation(400, 150);
		setSize(440, 120);
		
		table1dropDown.setText(null);
		table1dropDown.setIcon(dropDownIcon);
		table1dropDown.addMouseListener(new java.awt.event.MouseAdapter() {
			@Override
			public void mousePressed(java.awt.event.MouseEvent evt) {
				openColumnDropDownBox(table1dropDown, table1alias, table1);
			}
			
			@Override
			public void mouseEntered(java.awt.event.MouseEvent evt) {
				table1dropDown.setEnabled(false);
			}
			@Override
			public void mouseExited(java.awt.event.MouseEvent evt) {
				table1dropDown.setEnabled(true);
		   }
		});
	}

	@Override
	protected void escapePressed() {
		escaped = true;
		super.escapePressed();
	}

	/**
	 * Opens a drop-down box which allows the user to select columns for restriction definitions.
	 */
	private void openColumnDropDownBox(JLabel label, String alias, Table table) {
		JPopupMenu popup = new JScrollPopupMenu();
		List<String> columns = new ArrayList<String>();
		
		for (Column c: table.getColumns()) {
			columns.add(alias + "." + c.name);
		}
		if (addPseudoColumns) {
			columns.add("");
			columns.add(alias + ".$IS_SUBJECT");
			columns.add(alias + ".$DISTANCE");
			columns.add("$IN_DELETE_MODE");
			columns.add("NOT $IN_DELETE_MODE");
		}
		
		for (final String c: columns) {
			if (c.equals("")) {
				popup.add(new JSeparator());
				continue;
			}
			JMenuItem m = new JMenuItem(c);
			m.addActionListener(new ActionListener () {
				@Override
				public void actionPerformed(ActionEvent e) {
					if (editorPane.isEnabled()) {
						if (editorPane.isEditable()) {
							editorPane.replaceSelection(c);
						}
					}
				}
			});
			popup.add(m);
		}
		UIUtil.fit(popup);
		popup.show(label, 0, label.getHeight());
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
        jPanel1 = new javax.swing.JPanel();
        jPanel2 = new javax.swing.JPanel();
        table1name = new javax.swing.JLabel();
        table1dropDown = new javax.swing.JLabel();
        toSubQueryButton = new javax.swing.JButton();
        okButton = new javax.swing.JButton();
        cancelButton = new javax.swing.JButton();
        jPanel3 = new javax.swing.JPanel();
        gripPanel = new javax.swing.JPanel();
        jLabel2 = new javax.swing.JLabel();

        setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        getContentPane().setLayout(new java.awt.GridBagLayout());

        jPanel4.setLayout(new java.awt.GridBagLayout());

        jPanel1.setLayout(new java.awt.GridBagLayout());

        jPanel2.setLayout(new java.awt.GridBagLayout());

        table1name.setFont(table1name.getFont().deriveFont(table1name.getFont().getSize()+1f));
        table1name.setText("jLabel1");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 4;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        gridBagConstraints.insets = new java.awt.Insets(0, 0, 0, 8);
        jPanel2.add(table1name, gridBagConstraints);

        table1dropDown.setFont(table1dropDown.getFont().deriveFont(table1dropDown.getFont().getSize()+1f));
        table1dropDown.setText("jLabel1");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 3;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        jPanel2.add(table1dropDown, gridBagConstraints);

        toSubQueryButton.setText("to Subquery");
        toSubQueryButton.setToolTipText("<html>Converts condition into a subquery.<br> This allows to add joins with related tables or limiting clauses etc. </html>");
        toSubQueryButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                toSubQueryButtonActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 2;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.gridheight = 2;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        gridBagConstraints.insets = new java.awt.Insets(0, 8, 0, 12);
        jPanel2.add(toSubQueryButton, gridBagConstraints);

        okButton.setText("    Ok    ");
        okButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                okButtonActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        jPanel2.add(okButton, gridBagConstraints);

        cancelButton.setText("Cancel");
        cancelButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                cancelButtonActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        gridBagConstraints.weightx = 1.0;
        jPanel2.add(cancelButton, gridBagConstraints);

        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 10;
        gridBagConstraints.gridwidth = 100;
        gridBagConstraints.fill = java.awt.GridBagConstraints.BOTH;
        jPanel1.add(jPanel2, gridBagConstraints);

        jPanel3.setBorder(javax.swing.BorderFactory.createEtchedBorder());
        jPanel3.setLayout(new java.awt.GridBagLayout());

        gripPanel.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 0, 0));
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 100;
        gridBagConstraints.gridy = 20;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.SOUTHEAST;
        jPanel3.add(gripPanel, gridBagConstraints);

        jLabel2.setText("<html>  <i>Ctrl+Space</i> for code completion. <i>Ctrl+Enter</i> for Ok. <i>Esc</i> for Cancel.</html>");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 20;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        gridBagConstraints.weightx = 1.0;
        jPanel3.add(jLabel2, gridBagConstraints);

        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 20;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(4, 0, 0, 0);
        jPanel1.add(jPanel3, gridBagConstraints);

        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.fill = java.awt.GridBagConstraints.BOTH;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.weighty = 1.0;
        jPanel4.add(jPanel1, gridBagConstraints);

        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.fill = java.awt.GridBagConstraints.BOTH;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.weighty = 1.0;
        getContentPane().add(jPanel4, gridBagConstraints);

        pack();
    }// </editor-fold>//GEN-END:initComponents

	private void okButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_okButtonActionPerformed
		ok = true;
		setVisible(false);
	}//GEN-LAST:event_okButtonActionPerformed

    private void toSubQueryButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_toSubQueryButtonActionPerformed
        if (table1alias != null && table1 != null) {
        	String condition = editorPane.getText();
        	String subAlias = table1alias + "_SUB";
    		
        	if ("T".equalsIgnoreCase(table1alias)) {
        		condition = SqlUtil.replaceAlias(condition, subAlias);
        	} else if ("A".equalsIgnoreCase(table1alias)) {
        		condition = SqlUtil.replaceAliases(condition, subAlias, "B");
        	} else {
        		return;
        	}
        	StringBuilder prefix = new StringBuilder();
        	StringBuilder suffix = new StringBuilder();
        	StringBuilder pkCond = new StringBuilder();
        	
        	for (Column pk: table1.primaryKey.getColumns()) {
        		if (pkCond.length() > 0) {
        			pkCond.append(" and ");
        		}
        		pkCond.append(subAlias + "." + pk.name + "=" + table1alias + "." + pk.name);
        	}
        	
        	if (table1.primaryKey.getColumns().size() == 1) {
        		prefix.append(table1alias + "." + table1.primaryKey.getColumns().get(0).name + " in (\n    Select " + subAlias + "." + table1.primaryKey.getColumns().get(0).name + "\n    From " + table1.getName() + " " + subAlias + " \n    Where\n        ");
        		suffix.append("\n)");
        	} else {
        		prefix.append("exists(\n    Select 1\n    From " + table1.getName() + " " + subAlias + " \n    Where (\n        ");
        		suffix.append("\n        ) and " + pkCond + ")");
        	}
        	editorPane.beginAtomicEdit();
        	editorPane.setText(prefix + condition + suffix);
        	editorPane.setCaretPosition(prefix.length() + condition.length());
        	editorPane.endAtomicEdit();
        	editorPane.grabFocus();
        	toSubQueryButton.setEnabled(false);
        }
    }//GEN-LAST:event_toSubQueryButtonActionPerformed

    private void cancelButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_cancelButtonActionPerformed
        escaped = true;
        setVisible(false);
    }//GEN-LAST:event_cancelButtonActionPerformed

	private Table table1, table2;
	private String table1alias, table2alias;
	private boolean addPseudoColumns;
	private String initialCondition;
	
	/**
	 * Edits a given condition.
	 * 
	 * @param condition the condition
	 * @return new condition or <code>null</code>, if user canceled the editor
	 */
	public void edit(String condition, String table1label, String table1alias, Table table1, String table2label, String table2alias, Table table2, boolean addPseudoColumns, boolean addConvertSubqueryButton) {
		if (Pattern.compile("\\bselect\\b", Pattern.CASE_INSENSITIVE|Pattern.DOTALL).matcher(condition).find()) {
			condition = new BasicFormatterImpl().format(condition);
		}
		this.table1 = table1;
		this.table2 = table2;
		this.table1alias = table1alias;
		this.table2alias = table2alias;
		this.addPseudoColumns = addPseudoColumns;
		if (table1 != null) {
			this.table1name.setText("  " + table1.getName());
			this.table1name.setVisible(true);
			this.table1dropDown.setVisible(true);
		} else {
			this.table1name.setVisible(false);
			this.table1dropDown.setVisible(false);
		}
		toSubQueryButton.setVisible(addConvertSubqueryButton);
		toSubQueryButton.setEnabled(true);
		if (table1 != null && (table1.primaryKey == null || table1.primaryKey.getColumns() == null|| table1.primaryKey.getColumns().isEmpty())) {
			toSubQueryButton.setEnabled(false);
		}
		if (Pattern.compile("(exists|in)\\s*\\(\\s*select", Pattern.CASE_INSENSITIVE|Pattern.DOTALL).matcher(condition).find()) {
			toSubQueryButton.setEnabled(false);
		}
		ok = false;
		escaped = false;
		editorPane.setText(condition);
		editorPane.setCaretPosition(0);
		editorPane.discardAllEdits();

		if (parameterSelector != null) {
			parameterSelector.updateParameters();
		}
		if (provider != null) {
			provider.removeAliases();
			if (table1 != null) {
				provider.addAlias(table1alias, table1);
			}
			if (table2 != null) {
				provider.addAlias(table2alias, table2);
			}
		}
		UIUtil.invokeLater(new Runnable() {
			@Override
			public void run() {
				editorPane.grabFocus();
			}
		});
		initialCondition = condition;
		setVisible(true);
	}

	/**
	 * Removes single line comments.
	 * 
	 * @param statement
	 *            the statement
	 * 
	 * @return statement the statement without comments and literals
	 */
	private String removeSingleLineComments(String statement) {
		Pattern pattern = Pattern.compile("('(?:[^']*'))|(/\\*.*?\\*/)|(\\-\\-.*?(?=\n|$))", Pattern.DOTALL);
		Matcher matcher = pattern.matcher(statement);
		boolean result = matcher.find();
		StringBuffer sb = new StringBuffer();
		if (result) {
			do {
				if (matcher.group(3) == null) {
					matcher.appendReplacement(sb, "$0");
					result = matcher.find();
					continue;
				}
				int l = matcher.group(0).length();
				matcher.appendReplacement(sb, "");
				if (matcher.group(1) != null) {
					l -= 2;
					sb.append("'");
				}
				while (l > 0) {
					--l;
					sb.append(' ');
				}
				if (matcher.group(1) != null) {
					sb.append("'");
				}
				result = matcher.find();
			} while (result);
		}
		matcher.appendTail(sb);
		return sb.toString();
	}

	public void setLocationAndFit(Point pos) {
		setLocation(pos);
		UIUtil.fit(this);
        try {
            // Get the size of the screen
            Dimension dim = Toolkit.getDefaultToolkit().getScreenSize();
            int hd = getY() - (dim.height - 80);
            if (hd > 0) {
                setLocation(getX(), Math.max(getY() - hd, 0));
            }
        } catch (Throwable t) {
            // ignore
        }
	}

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton cancelButton;
    private javax.swing.JPanel gripPanel;
    private javax.swing.JLabel jLabel2;
    private javax.swing.JPanel jPanel1;
    private javax.swing.JPanel jPanel2;
    private javax.swing.JPanel jPanel3;
    private javax.swing.JPanel jPanel4;
    private javax.swing.JButton okButton;
    protected javax.swing.JLabel table1dropDown;
    protected javax.swing.JLabel table1name;
    private javax.swing.JButton toSubQueryButton;
    // End of variables declaration//GEN-END:variables
	
	private Icon dropDownIcon;
	{
		String dir = "/net/sf/jailer/ui/resource";
		
		// load images
		try {
			dropDownIcon = new ImageIcon(getClass().getResource(dir + "/dropdown.png"));
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	public final RSyntaxTextAreaWithSQLSyntaxStyle editorPane;
	private SQLAutoCompletion sqlAutoCompletion;

	public static void initialObserve(final JTextField textfield, final Runnable open) {
		InputMap im = textfield.getInputMap();
		@SuppressWarnings("serial")
		Action a = new AbstractAction() {
			boolean done = false;
			@Override
			public void actionPerformed(ActionEvent e) {
				if (!done) {
					done = true;
					open.run();
				}
			}
		};
		KeyStroke ks = KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK);
		im.put(ks, a);
		ActionMap am = textfield.getActionMap();
		am.put(a, a);
	}

	public void observe(final JTextField textfield, final Runnable open) {
		InputMap im = textfield.getInputMap();
		@SuppressWarnings("serial")
		Action a = new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				doCompletion(textfield, open);
			}
		};
		KeyStroke ks = KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK);
		im.put(ks, a);
		ActionMap am = textfield.getActionMap();
		am.put(a, a);
	}
	
	public void doCompletion(final JTextField textfield, final Runnable open) {
		String origText = textfield.getText();
		String caretMarker;
		for (int suffix = 0; ; suffix++) {
			caretMarker = "CARET" + suffix;
			if (!origText.contains(caretMarker)) {
				break;
			}
		}
		try {
			textfield.getDocument().insertString(textfield.getCaretPosition(), caretMarker, null);
		} catch (BadLocationException e1) {
			e1.printStackTrace();
		}
		open.run();
		textfield.setText(origText);
		String text = editorPane.getText();
		int i = text.indexOf(caretMarker);
		if (i >= 0) {
			editorPane.setText(text.substring(0, i) + text.substring(i + caretMarker.length()));
			editorPane.setCaretPosition(i);
		}
		UIUtil.invokeLater(1, new Runnable() {
			@Override
			public void run() {
				sqlAutoCompletion.doCompletion();
			}
		});
	}

	protected abstract void consume(String cond);

	private static final long serialVersionUID = -5169934807182707970L;

}
