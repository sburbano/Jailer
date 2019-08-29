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
package net.sf.jailer.ui.databrowser.sqlconsole;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTabbedPane;
import javax.swing.RowSorter;
import javax.swing.RowSorter.SortKey;
import javax.swing.SwingUtilities;
import javax.swing.event.CaretEvent;
import javax.swing.event.CaretListener;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.TableModel;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.Segment;

import org.apache.log4j.Logger;
import org.fife.ui.rtextarea.RTextScrollPane;

import net.sf.jailer.ExecutionContext;
import net.sf.jailer.configuration.Configuration;
import net.sf.jailer.configuration.DBMS;
import net.sf.jailer.database.Session;
import net.sf.jailer.datamodel.Association;
import net.sf.jailer.datamodel.DataModel;
import net.sf.jailer.datamodel.Table;
import net.sf.jailer.modelbuilder.MemorizedResultSet;
import net.sf.jailer.ui.DbConnectionDialog;
import net.sf.jailer.ui.Environment;
import net.sf.jailer.ui.JComboBox;
import net.sf.jailer.ui.QueryBuilderDialog;
import net.sf.jailer.ui.QueryBuilderDialog.Relationship;
import net.sf.jailer.ui.UIUtil;
import net.sf.jailer.ui.associationproposer.AssociationProposerView;
import net.sf.jailer.ui.databrowser.BrowserContentPane;
import net.sf.jailer.ui.databrowser.BrowserContentPane.LoadJob;
import net.sf.jailer.ui.databrowser.BrowserContentPane.RowsClosure;
import net.sf.jailer.ui.databrowser.DataBrowser;
import net.sf.jailer.ui.databrowser.Desktop;
import net.sf.jailer.ui.databrowser.Desktop.RowBrowser;
import net.sf.jailer.ui.databrowser.Reference;
import net.sf.jailer.ui.databrowser.Row;
import net.sf.jailer.ui.databrowser.metadata.MDSchema;
import net.sf.jailer.ui.databrowser.metadata.MDTable;
import net.sf.jailer.ui.databrowser.metadata.MetaDataDetailsPanel;
import net.sf.jailer.ui.databrowser.metadata.MetaDataPanel;
import net.sf.jailer.ui.databrowser.metadata.MetaDataPanel.OutlineInfo;
import net.sf.jailer.ui.databrowser.metadata.MetaDataSource;
import net.sf.jailer.ui.databrowser.metadata.ResultSetRenderer;
import net.sf.jailer.ui.syntaxtextarea.RSyntaxTextAreaWithSQLSyntaxStyle;
import net.sf.jailer.ui.syntaxtextarea.SQLAutoCompletion;
import net.sf.jailer.ui.syntaxtextarea.SQLCompletionProvider;
import net.sf.jailer.ui.util.SmallButton;
import net.sf.jailer.ui.util.UISettings;
import net.sf.jailer.util.CancellationException;
import net.sf.jailer.util.CancellationHandler;
import net.sf.jailer.util.CsvFile;
import net.sf.jailer.util.Pair;

/**
 * SQL Console.
 *
 * @author Ralf Wisser
 */
@SuppressWarnings("serial")
public abstract class SQLConsole extends javax.swing.JPanel {

	/**
	 * The logger.
	 */
	private static final Logger logger = Logger.getLogger(MetaDataDetailsPanel.class);

    private static final int MAX_TAB_COUNT = 8;
    private static final int MAX_HISTORY_SIZE = 100;
    
    private Session session;
    MetaDataSource metaDataSource;
    private RSyntaxTextAreaWithSQLSyntaxStyle editorPane;
	private final MetaDataBasedSQLCompletionProvider provider;
    private final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<Runnable>();
    private final Reference<DataModel> datamodel;
    private final ExecutionContext executionContext;
    private final List<String> history = new ArrayList<String>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean updatingStatus = new AtomicBoolean(false);
    private final ImageIcon scaledCancelIcon;
    private final ImageIcon scaledExplainIcon;
    private final SQLPlusSupport sqlPlusSupport = new SQLPlusSupport();
	private File file;
	private JMenuItem menuItemToggle;
	private JMenuItem menuItemSubstituteVariables;
	private JMenuItem menuItemAnalyse;
	private int initialTabbedPaneSelection = 0;
	private List<? extends SortKey> initialSortKeys = null;
	private String initialSortKeysSql = null;
	private int initialTabbedPaneSelectionLoc = -1;

	private final String IGNORED_STATEMENTS = "(\\s*/\\s*)";
	
	/**
	 * Stops the consumer thread.
	 */
    private final Runnable STOP = new Runnable() {
		@Override
		public void run() {
			// nothing to do
		}
	};

    /**
     * Creates new form SQLConsole
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public SQLConsole(Session session, MetaDataSource metaDataSource, Reference<DataModel> datamodel, ExecutionContext executionContext) throws SQLException {
        this.session = session;
        this.metaDataSource = metaDataSource;
        this.datamodel = datamodel;
        this.executionContext = executionContext;
        initComponents();
        initMenuItems();
        historyComboBox.setMaximumRowCount(25);
        GridBagConstraints gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 2;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(0, 0, 0, 16);
        jPanel5.add(historyComboBox, gridBagConstraints);
        
        this.editorPane = new RSyntaxTextAreaWithSQLSyntaxStyle(true, true) {
			@Override
			protected boolean canExplain() {
				return SQLConsole.this.canExplain();
			}
            @Override
            protected void runBlock() {
                executeSelectedStatements(false, null, true);
            }
            @Override
            protected void explainBlock() {
                executeSelectedStatements(true, null, true);
            }
            @Override
            protected void runAll() {
                executeAllStatements();
            }
            @Override
            public void updateMenuItemState() {
                updateMenuItemState(!running.get(), !running.get());
            }
            @Override
            protected void selectTable(MDTable mdTable) {
                SQLConsole.this.selectTable(mdTable);
            }
            @Override
        	protected void updateMenuItems(boolean isTextSelected) {
            	SQLConsole.this.updateMenuItems(isTextSelected);
        	}
            @Override
            protected MDTable getSelectedTable() {		
                Document doc = getDocument();

                int dot = getCaretPosition();
                int start = getLineStartOffsetOfCurrentLine();
                int lineEndOffsetOfCurrentLine = getLineEndOffsetOfCurrentLine();
                int len = lineEndOffsetOfCurrentLine - start;
                Segment seg = new Segment();
                try {
                    doc.getText(start, len, seg);
                } catch (BadLocationException ble) {
                    logger.info("error", ble);
                    return null;
                }

                start = dot - getLineStartOffsetOfCurrentLine();
                String segment = seg.toString();
                char ch = start < segment.length()? segment.charAt(start) : ' ';
                while (start<segment.length() && (Character.isLetterOrDigit(ch) || ch == '"' || ch == '`'|| ch == '_')) {
                    start++;
                    ch = start < segment.length()? segment.charAt(start) : ' ';
                }
                
                String line = seg.toString().substring(0, start);

                String reIdentifier = "(?:[\"][^\"]+[\"])|(?:[`][^`]+[`])|(?:['][^']+['])|(?:[\\w]+)";

                Pattern pattern = Pattern.compile("(?:(" + reIdentifier + ")\\s*\\.\\s*)?(" + reIdentifier + ")$");
                Matcher matcher = pattern.matcher(line);
                if (matcher.find()) {
                    MDSchema schema;
                    if (matcher.group(1) != null) {
                        schema = SQLConsole.this.metaDataSource.find(matcher.group(1));
                        if (schema == null) {
                            return null;
                        }
                    } else {
                        schema = SQLConsole.this.metaDataSource.getDefaultSchema();
                    }
                    return schema.find(matcher.group(2));
                }
                return null;
            }

        	@Override
        	protected void appendPopupMenu(JPopupMenu menu) {
        		menu.addSeparator();
        		menu.add(menuItemToggle);
        		menu.add(menuItemSubstituteVariables);
        		menu.addSeparator();
        		menu.add(menuItemAnalyse);
        	}

        };

        historyComboBox.setRenderer(new DefaultListCellRenderer() {
             @Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                 if (index == 0) {
                     value = null;
                 }
                 Object shortValue = value;
                 if (value instanceof String) {
                     shortValue = shortSQL((String) value, 120);
                 }
                 Component c = super.getListCellRendererComponent(list, shortValue, index, isSelected, cellHasFocus);
                 if (c instanceof JLabel) {
                     if (value instanceof String && value.toString().length() > 0) {
                         ((JLabel) c).setToolTipText(UIUtil.toHTML(value.toString(), 100));
                     } else {
                         ((JLabel) c).setToolTipText(null);
                     }
                 }
                 return c;
             }
        });
        restoreHistory();
        
        provider = new MetaDataBasedSQLCompletionProvider(session, metaDataSource) {
            @Override
			protected String prepareStatementForAliasAnalysis(String statement) {
            	return sqlPlusSupport.replaceVariables(statement, null);
            }
        };
        new SQLAutoCompletion(provider, editorPane);

        RTextScrollPane jScrollPane = new RTextScrollPane();
        jScrollPane.setViewportView(editorPane);
        editorPane.setGutter(jScrollPane.getGutter());
        consoleContainerPanel.add(jScrollPane);
        jScrollPane.setLineNumbersEnabled(true);
        jScrollPane.setIconRowHeaderEnabled(true);

        runSQLButton.setAction(editorPane.runBlock);
        runnAllButton.setAction(editorPane.runAll);
        explainButton.setAction(editorPane.explain);

        runSQLButton.setText("Run");
        runnAllButton.setText("Run all");

        runSQLButton.setIcon(UIUtil.scaleIcon(this, runIcon));
        runnAllButton.setIcon(UIUtil.scaleIcon(this, runAllIcon));
        runSQLButton.setToolTipText(runSQLButton.getText() + " - Ctrl-Enter");
        runnAllButton.setToolTipText(runnAllButton.getText() + " - Alt-Enter");
                
        scaledCancelIcon = UIUtil.scaleIcon(this, cancelIcon);
        cancelButton.setIcon(scaledCancelIcon);
        
        scaledExplainIcon = UIUtil.scaleIcon(this, explainIcon);
        explainButton.setIcon(scaledExplainIcon);
        
        limitComboBox.setModel(new DefaultComboBoxModel(DataBrowser.ROW_LIMITS));
        limitComboBox.setSelectedItem(1000);
        
        resetStatus();
        statusLabel.setVisible(true);
        
        editorPane.addCaretListener(new CaretListener() {
            @Override
            public void caretUpdate(CaretEvent e) {
                updateOutline(false);
            }
        });

        initialContentSize = editorPane.getDocument().getLength();
        initialContentHash = editorPane.getText().hashCode();
        editorPane.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void removeUpdate(DocumentEvent e) {
				checkDirty();
			}
			@Override
			public void insertUpdate(DocumentEvent e) {
				checkDirty();
			}
			@Override
			public void changedUpdate(DocumentEvent e) {
				checkDirty();
			}
		});
        
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
            	Runnable action = null;
            	do {
                    try {
                    	action = queue.take();
                        action.run();
                    } catch (Throwable t) {
                        logger.info("error", t);
                    }
                } while (action != STOP);
            }
        }, "console-thread-" + (threadNum++));
        thread.setDaemon(true);
        thread.start();
    }

	protected void initMenuItems() {
		JMenuItem item = new JMenuItem("Toggle Line Continuation");
		item.setEnabled(false);
		menuItemToggle = item;
		item.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				toggleLineContinuation();
			}
		});
		item.setToolTipText(
				"<html>Adds (or remove) line-continuation-character ('\\') <br>" +
				" to each line terminated by ';' <br>"
				+ "(allowing you to execute PL/SQL code)");
		item = new JMenuItem("Substitute Variables");
		item.setEnabled(false);
		menuItemSubstituteVariables = item;
		item.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				substituteVariables();
			}
		});
		item.setToolTipText(
				"<html>Substitutes variables <i>(&amp;VAR[.])</i> with corresponding values. <br><br>\n" + 
				"<b>Statements:</b>\n" + 
				"<table>\n" + 
				"<tr><td><b>&nbsp;DEFINE VAR=\"VALUE\"</b></td><td>&nbsp;&nbsp;&nbsp;</td><td>Assigns a value to variable VAR</td></tr>\n" + 
				"<tr><td><b>&nbsp;DEFINE</b></td><td></td><td>Lists all variables</td></tr>\n" + 
				"<tr><td><b>&nbsp;&amp;VAR</b>&nbsp;&nbsp;or&nbsp;&nbsp;<b>&nbsp;&amp;VAR.</b></td><td></td><td>Variable substitution (inside other statements)</td></tr>\n" + 
				"</table>");
		item = new JMenuItem("Analyze SQL");
		item.setEnabled(false);
		menuItemAnalyse = item;
		item.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				analyzeSQL();
			}
		});
		item.setToolTipText("Analyzes selected SQL and proposes association definitions.");
	}

	private void updateMenuItems(boolean isTextSelected) {
    	if (menuItemToggle != null) {
    		menuItemToggle.setEnabled(isTextSelected);
    	}
    	if (menuItemSubstituteVariables != null) {
    		menuItemSubstituteVariables.setEnabled(isTextSelected);
    	}
    	if (menuItemAnalyse != null) {
    		menuItemAnalyse.setEnabled(isTextSelected);
    	}
	}

	private static int threadNum = 1;

    private boolean canExplain() {
		return metaDataSource.getSession().dbms.getExplainQuery() != null && !metaDataSource.getSession().dbms.getExplainQuery().isEmpty();
	}

    private AtomicBoolean pending = new AtomicBoolean(false);
    private AtomicBoolean stopped = new AtomicBoolean(false);
    private String prevSql = null;
    private int prevCaretPos;

    /**
     * Update of outline of statement under caret and history after switching from another console to this.
     */
    public void update() {
    	updateOutline(false);
    	restoreHistory();
    }
    
    /**
     * Update of outline of statement under caret.
     */
    private void updateOutline(boolean checkPrevSql) {
        if (!pending.get()) {
            Pair<Integer, Integer> loc = editorPane.getCurrentStatementLocation(true, true, null, false);
            String sql = editorPane.getText(loc.a, loc.b, true);
            if (sql.trim().isEmpty()) {
                loc = editorPane.getCurrentStatementLocation(true, true, null, true);
                sql = editorPane.getText(loc.a, loc.b, true);
            }
            if (checkPrevSql && sql.equals(prevSql) && editorPane.getCaretPosition() == prevCaretPos) {
                return;
            }
            prevSql = sql;
            prevCaretPos = editorPane.getCaretPosition();
            
            try {
                updateOutline(sql, editorPane.getLineStartOffset(loc.a));
            } catch (Exception e1) {
                logger.info("error", e1);
                return;
            }
            
            if (sql.length() > 20000) {
                stopped.set(false);
                pending.set(true);
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException e) {
                        }
                        pending.set(false);
                        if (!stopped.get()) {
                            UIUtil.invokeLater(new Runnable() {
                                @Override
                                public void run() {
                                    updateOutline(true);
                                }
                            });
                        }
                    }
                }).start();
            } else {
                stopped.set(true);
            }
        }
    }

    protected String shortSQL(String sql, int maxLength) {
        sql = sql.trim().replaceAll("\\s+", " ");
        if (sql.length() > maxLength) {
            sql = sql.replaceFirst("^(?is)(\\bselect\\b).........*?(\\bfrom\\b)(.*)$", "$1 ... $2$3");
        }
        if (sql.length() > maxLength) {
            sql = sql.substring(0, maxLength) + "...";
        }
        return sql;
    }

    private void resetStatus() {
        statusLabel.setVisible(false);
        cancelButton.setEnabled(false);
    }

    /**
     * Executes a block of SQL statements (each statement separated by a ';' at the end of the line).
     * 
     * @param sqlBlock the sql block
     * @param location location of the block in the console
     * @param emptyLineSeparatesStatements 
     * @param locFragmentOffset location of statement fragment, if any
     * @param explain 
     * @param tabContentPanel the panel to show result (option)
     */
    protected void executeSQLBlock(final String sqlBlock, final Pair<Integer, Integer> location, final boolean emptyLineSeparatesStatements, final Pair<Integer, Integer> locFragmentOffset, final boolean explain, final TabContentPanel tabContentPanel) {
        if (!running.get()) {
            int lineStartOffset = -1;
            try {
                if (location != null) {
                    lineStartOffset = editorPane.getLineStartOffset(location.a);
                }
            } catch (BadLocationException e) {
                lineStartOffset = -1;
            }
            disableLastErrorTab();
            final Pair<Integer, Integer> caretDotMark = new Pair<Integer, Integer>(editorPane.getCaret().getDot(), editorPane.getCaret().getMark());
            final int finalLineStartOffset = lineStartOffset;
            queue.add(new Runnable() {
                @Override
                public void run() {
                    running.set(true);
                    updatingStatus.set(false);
                    UIUtil.invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            editorPane.updateMenuItemState();
                        }
                    });
                    Status status = new Status();
                    status.location = location;
                    status.linesExecuted = 0;
                    status.linesExecuting = 0;
                    status.running = true;
                    int lineStartOffset = finalLineStartOffset;
                    try {
                        Pattern pattern;
                        if (emptyLineSeparatesStatements) {
                            pattern = Pattern.compile("(?:(;\\s*(\\n\\r?|$))|(\\n\\r?([ \\t\\r]*\\n\\r?)+))", Pattern.DOTALL);
                        } else {
                            pattern = Pattern.compile("(?:(;\\s*(\\n\\r?|$)))", Pattern.DOTALL);
                        }
                        
                        Matcher matcher = pattern.matcher(sqlBlock);
                        boolean result = matcher.find();
                        StringBuffer sb = new StringBuffer();
                        if (result || locFragmentOffset != null) {
                            do {
                                String sql;
                                String pureSql;
                                if (locFragmentOffset != null) {
                                    sql = sqlBlock;
                                    pureSql = sqlBlock;
                                } else {
                                    sb.setLength(0);
                                    matcher.appendReplacement(sb, "");
                                    pureSql = sb.toString();
                                    sb.append(matcher.group());
                                    sql = sb.toString();
                                }
                                status.linesExecuting += countLines(pureSql);
                                if (sql.trim().length() > 0) {
                                    executeSQL(pureSql, status, lineStartOffset, explain, tabContentPanel, caretDotMark);
                                    if (status.failed) {
                                        if (locFragmentOffset != null) {
                                            if (status.errorPositionIsKnown) {
                                                try {
                                                    status.errorPosition += locFragmentOffset.a - editorPane.getLineStartOffset(editorPane.getLineOfOffset(locFragmentOffset.a));
                                                } catch (BadLocationException e) {
                                                    logger.info("error", e);
                                                }
                                            }
                                        }
                                        break;
                                    }
                                }
                                if (lineStartOffset >= 0) {
                                    lineStartOffset += sql.length();
                                }
                                status.linesExecuted += countLines(sql) - 1;
                                if (locFragmentOffset != null) {
                                    pattern = Pattern.compile("(\\n\\s*)$", Pattern.DOTALL);
                                    matcher = pattern.matcher(sql);
                                    if (!matcher.find()) {
                                        status.linesExecuted++;
                                    }
                                    break;
                                }
                                String terminator = matcher.group(1);
                                if (terminator != null && !terminator.contains("\n")) { // ';' without nl
                                    status.linesExecuted++;
                                }
                                status.linesExecuting = status.linesExecuted;
                                result = matcher.find();
                            } while (result);
                        }
                        if (!status.failed && locFragmentOffset == null) {
                            sb.setLength(0);
                            matcher.appendTail(sb);
                            String sbToString = sb.toString();
                            String sql = sbToString;
                            if (sql.trim().length() > 0) {
                                status.linesExecuting += countLines(sql);
                                executeSQL(sql, status, lineStartOffset, explain, tabContentPanel, caretDotMark);
                                if (!status.failed) {
                                    status.linesExecuted = status.linesExecuting;
                                }
                            }
                        }
                        if (status.numStatements <= 7) {
                            storeHistory();
                        } else {
                            restoreHistory();
                        }
                    } finally {
                        status.running = false;
                        running.set(false);
                        status.updateView(true);
                        UIUtil.invokeLater(new Runnable() {
                            @Override
                            public void run() {
                                editorPane.updateMenuItemState(true, false);
                            }
                        });
                    }
                }

                private int countLines(String line) {
                    int lines = 1;
                    for (int i = 0; i < line.length(); ++i) {
                        if (line.charAt(i) == '\n') {
                            ++lines;
                        }
                    }
                    return lines;
                }
            });
        }
    }

    /**
     * Executes a single SQL statment.
     * 
     * @param sql the statement
     * @param status the status to update
     * @param statementStartOffset 
     * @param explain 
     * @param origTabContentPanel the panel to show result (option)
     */
    private void executeSQL(final String sql, final Status status, int statementStartOffset, final boolean explain, final TabContentPanel origTabContentPanel, final Pair<Integer, Integer> caretDotMark) {
        Statement statement = null;
        ResultSet resultSet = null;
        final Status localStatus = new Status();
        String sqlStatement = null;
        String stmtId = null;
        TreeMap<Integer, Integer> positionOffsets = new TreeMap<Integer, Integer>();
        Connection resetAutoCommitConnection = null;
		try {
	        Connection connection = session.getConnection();
	        if (!explain && session.dbms.equals(DBMS.POSTGRESQL)) {
	            if (connection.getAutoCommit()) {
	            	connection.setAutoCommit(false);
	            	resetAutoCommitConnection = connection;
	            }
	        }
	        status.numStatements++;
            localStatus.numStatements++;
            UISettings.s3++;
            status.updateView(false);
            statement = connection.createStatement();
			if (session.dbms != null) {
				if (session.dbms.getFetchSize() != null) {
					statement.setFetchSize(session.dbms.getFetchSize());
				}
			}
			CancellationHandler.reset(SQLConsole.this);
            CancellationHandler.begin(statement, SQLConsole.this);
            long startTime = System.currentTimeMillis();
            sqlStatement = 
            		sql
            		.replaceFirst("(?is)(;\\s*)+$", "")
            		.replaceAll("((?:(?:;(?: |\\t|\\r)*?(?:--[^\\n]*)?))) ?\\\\([ \\t\\r]*\\n)", "$1$2")
            		.replaceAll("((?:\\n(?: |\\t|\\r)*?)) ?\\\\([ \\t\\r]*)(?=\\n)", "$1");
			sqlStatement = sqlPlusSupport.replaceVariables(sqlStatement, positionOffsets);
	        status.statement = sqlStatement;
	        boolean loadButtonIsVisible = true;
            boolean hasResultSet;
            boolean hasUpdateCount = true;
            ResultSet sqlPlusResultSet = null;
            if (explain) {
            	if (session.dbms.getExplainCreateExplainTable() != null) {
            		Statement createStatement = connection.createStatement();
            		try {
            			createStatement.execute(session.dbms.getExplainCreateExplainTable());
            		} catch (Exception e) {
        				// ignore
        			} finally {
            			try {
            				createStatement.close();
            			} catch (Exception e) {
            				// ignore
            			}
            		}
            	}
            	synchronized (this) {
            		stmtId = "Jailer" + (nextPlanID++ % 8);
				}
            	if (session.dbms.getExplainPrepare() != null && !session.dbms.getExplainPrepare().isEmpty()) {
                	statement.execute(String.format(session.dbms.getExplainPrepare(), sqlStatement, stmtId));
                	statement.close();
            	}
                statement = connection.createStatement();
            	hasResultSet = statement.execute(String.format(session.dbms.getExplainQuery(), sqlStatement, stmtId));
            } else {
            	sqlPlusResultSet = sqlPlusSupport.executeSQLPLusQuery(sqlStatement);
            	if (sqlPlusResultSet != null) {
            		hasResultSet = true;
            		loadButtonIsVisible = false;
            	} else if (sqlPlusSupport.executeSQLPLusStatement(sqlStatement)) {
            		hasUpdateCount = false;
            		hasResultSet = false;
            	} else if (sqlStatement.matches(IGNORED_STATEMENTS)) {
            		hasUpdateCount = false;
            		hasResultSet = false;
            	} else {
            		hasResultSet = executeStatementWithLimit(statement, sqlStatement, session);
            	}
            }
            if (hasResultSet) {
                resultSet = sqlPlusResultSet != null? sqlPlusResultSet : statement.getResultSet();
                ResultSetMetaData metaData = resultSet.getMetaData();
                final String columnLabels[] = new String[metaData.getColumnCount()];
                for (int i = 0; i < metaData.getColumnCount(); ++i) {
                	columnLabels[i] = metaData.getColumnLabel(i + 1);
                }
                ResultSet theMetaDataResultSet = null;
                String resultSetType = null;
                try {
                    resultSetType = TabContentPanel.toType(metaData, session, executionContext);
                	theMetaDataResultSet = TabContentPanel.toMetaDataResultSet(metaData, session, executionContext);
				} catch (Throwable e1) {
					logger.info("error", e1);
				}
                final ResultSet metaDataResultSet = theMetaDataResultSet;
                final String finalResultSetType = resultSetType;
				final Integer limit = (Integer) limitComboBox.getSelectedItem();
                List<Table> nfResultTypes = explain || sqlPlusResultSet != null? null : QueryTypeAnalyser.getType(sqlStatement, metaDataSource);
                Table resultType = null;
                if (nfResultTypes != null && !nfResultTypes.isEmpty()) {
                    if (nfResultTypes.size() == 1) {
                        resultType = nfResultTypes.get(0);
                    }
					int columnCount = metaData.getColumnCount();
                    for (Table table: nfResultTypes) {
                        while (table.getColumns().size() < columnCount) {
                            table.getColumns().add(new net.sf.jailer.datamodel.Column(null, "", 0, -1));
                        }
                    }
                }
                if (resultType != null) {
                	if (resultType.getColumns().size() > metaData.getColumnCount()) {
                		// stale meta data
                		resultType = null;
                		nfResultTypes = null;
                	}
                }
                final List<Table> resultTypes = nfResultTypes;
                final MemorizedResultSet metaDataDetails = new MemorizedResultSet(resultSet, limit, session, SQLConsole.this) {
            		@Override
                	protected Object convertCellContent(Object object) {
            			Object lobRender = BrowserContentPane.toLobRender(object);
                		if (lobRender != null) {
                			return lobRender;
                		}
                		return object;
            		}

            		@Override
            		protected void prepareHook(ResultSetMetaData rmd) throws SQLException {
            			sqlPlusSupport.prepareColumnSubstitution(rmd);
            		}

            		@Override
            		protected void readRowHook(ResultSet resultSet) throws SQLException {
                        try {
                        	sqlPlusSupport.substituteColumns(resultSet);
                        } catch (SQLException e) {
                        	// ignore
                        }
            		}
                };
                resultSet.close();
                long now = System.currentTimeMillis();
                status.hasSelected = true;
                status.timeInMS += (now - startTime);
                localStatus.timeInMS += (now - startTime);
                status.numRowsRead += metaDataDetails.getSize();
                localStatus.numRowsRead += metaDataDetails.getSize();
                localStatus.hasSelected = true;
                if (metaDataDetails.getSize() > limit) {
                    status.limitExceeded = true;
                    localStatus.limitExceeded = true;
                }
                status.updateView(false);
            	status.statement = sqlStatement;
                final String finalSqlStatement = sqlStatement;
                final Table finalResultType = resultType;
                final boolean finalLoadButtonIsVisible = loadButtonIsVisible;
                
                UIUtil.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        final BrowserContentPane rb = new ResultContentPane(datamodel.get(), finalResultType, "", session, null,
                                null, null, new RowsClosure(), false, false, executionContext);
                        if (resultTypes != null && resultTypes.size() > 1) {
                            rb.setResultSetType(resultTypes);
                        }
                        rb.setAlternativeColumnLabels(columnLabels);
                        rb.setTableFilterEnabled(metaDataDetails.getSize() > 1 && metaDataDetails.getSize() <= limit);
                        rb.setStatementForReloading(finalSqlStatement);
                        metaDataDetails.reset();
                        LoadJob loadJob = rb.newLoadJob(metaDataDetails, limit);
                        loadJob.run();
                        JComponent rTabContainer = rb.getRowsTableContainer();
                        metaDataDetails.reset();
						JComponent metaDataRenderer = null;
		                try {
							metaDataRenderer = new ResultSetRenderer(metaDataResultSet, null, datamodel.get(), session, executionContext);
						} catch (Throwable e1) {
							logger.info("error", e1);
						}
						final TabContentPanel tabContentPanel = 
                        		new TabContentPanel(rb.rowsCount, 
                        				metaDataRenderer,
                        				finalResultSetType,
                        				explain,
                        				origTabContentPanel == null? null : origTabContentPanel.shimPanel,
                        				caretDotMark);
                        tabContentPanel.contentPanel.add(rTabContainer);
                        rb.sortColumnsCheckBox.setVisible(true);
                        rb.sortColumnsPanel.setVisible(false);
                        tabContentPanel.controlsPanel1.add(rb.sortColumnsCheckBox);
                        rb.sortColumnsCheckBox.addActionListener(new java.awt.event.ActionListener() {
                            @Override
							public void actionPerformed(java.awt.event.ActionEvent evt) {
                            	UIUtil.invokeLater(new Runnable() {
									@Override
									public void run() {
		                            	updateColumnsAndTextView(rb, tabContentPanel);
									}
                            	});
                            }
                        });
                        final JButton loadButton = new JButton(rb.loadButton.getText(), rb.loadButton.getIcon());
                        tabContentPanel.loadButton = loadButton;
                        loadButton.addActionListener(new ActionListener() {
							@Override
							public void actionPerformed(ActionEvent e) {
								tabContentPanel.loadingPanel.setVisible(true);
								tabContentPanel.repaint();
								loadButton.setEnabled(false);
								initialSortKeysSql = sql;
								try {
									initialSortKeys = rb.rowsTable.getRowSorter().getSortKeys();
								} catch (Exception e2) {
									initialSortKeys = null;
								}
								reload(tabContentPanel, sql);
							}
						});
                        tabContentPanel.cancelLoadButton.addActionListener(new ActionListener() {
							@Override
							public void actionPerformed(ActionEvent e) {
								CancellationHandler.cancel(SQLConsole.this);
								tabContentPanel.loadingPanel.setVisible(false);
								tabContentPanel.repaint();
								loadButton.setEnabled(true);
							}
						});
						tabContentPanel.controlsPanel1.add(loadButton);
                        loadButton.setVisible(finalLoadButtonIsVisible);
                        loadButton.setIcon(UIUtil.scaleIcon(SQLConsole.this, runIcon));
                        rb.setOnReloadAction(new Runnable() {
							@Override
							public void run() {
								updateColumnsAndTextView(rb, tabContentPanel);
							}
						});
                        String sqlE = sql.trim();
                        if (explain) {
                        	sqlE = "Explain Plan for " + sqlE;
                        }
                        String stmt = sqlE;
                        
                        tabContentPanel.statementLabel.setToolTipText(UIUtil.toHTML(sqlE, 100));
                        if (stmt.length() > 200) {
                            stmt = stmt.substring(0, 200) + "...";
                        }
                        tabContentPanel.statementLabel.setText(stmt.replaceAll("\\s+", " "));
                        rTabContainer = tabContentPanel.shimPanel;
                        final int MAXLENGTH = 30;
                        String title = shortSQL(sqlE, MAXLENGTH);
                        final int loc = status != null && status.location != null? status.location.a : -1;
                        if (initialTabbedPaneSelection >= 0 && initialTabbedPaneSelectionLoc == loc) {
                        	if (initialTabbedPaneSelection < tabContentPanel.tabbedPane.getTabCount()) {
                        		tabContentPanel.tabbedPane.setSelectedIndex(initialTabbedPaneSelection);
                        	}
                        }
                		try {
                    		if (initialSortKeys != null && initialSortKeysSql != null && initialSortKeysSql.equals(sql)) {
                    			rb.getRowsTable().getRowSorter().setSortKeys(initialSortKeys);
                    		}
                		} catch (Exception e) {
                			// ignore
                		}
                		tabContentPanel.tabbedPane.addChangeListener(new ChangeListener() {
							@Override
							public void stateChanged(ChangeEvent e) {
								initialTabbedPaneSelection = tabContentPanel.tabbedPane.getSelectedIndex();
								initialTabbedPaneSelectionLoc = loc;
								updateColumnsAndTextView(rb, tabContentPanel);
							}
						});
                        removeLastErrorTab();
                        
                        if (origTabContentPanel == null) {
	                        jTabbedPane1.add(rTabContainer);
	                        jTabbedPane1.setTabComponentAt(jTabbedPane1.indexOfComponent(rTabContainer), getTitlePanel(jTabbedPane1, rTabContainer, title));
	
	                        if (jTabbedPane1.getTabCount() > MAX_TAB_COUNT) {
	                            jTabbedPane1.remove(0);
	                        }
	                        jTabbedPane1.setSelectedIndex(jTabbedPane1.getTabCount() - 1);
                        }
                        rb.resetRowsTableContainer();
                        jTabbedPane1.repaint();
                    }

                    private ColumnsTable columnsTable; 
                    
					public void updateColumnsAndTextView(final BrowserContentPane rb,
							final TabContentPanel tabContentPanel) {
						String tableSortAndFilterState = "";
						RowSorter<? extends TableModel> sorter = rb.rowsTable.getRowSorter();
						if (sorter.getModelRowCount() > sorter.getViewRowCount()) {
							tableSortAndFilterState = "Filtered";
						}
						List<? extends SortKey> skeys = sorter.getSortKeys();
						if (!skeys.isEmpty()) {
							for (int i = 0; i < rb.rowsTable.getColumnCount(); ++i) {
								if (rb.rowsTable.getColumnModel().getColumn(i).getModelIndex() == skeys.get(0).getColumn()) {
									tableSortAndFilterState = tableSortAndFilterState + (tableSortAndFilterState.isEmpty()? "Sorted" : " and sorted")
											+ " by \"" + rb.rowsTable.getColumnName(i) + "\"";
									break;
								}
							}
						}
						if (tabContentPanel.tabbedPane.getSelectedComponent() == tabContentPanel.columnsPanel || tabContentPanel.tabbedPane.getSelectedComponent() == tabContentPanel.contentPanel) {
							Point vp = null;
							if (columnsTable != null) {
								if (tabContentPanel.columnsScrollPane.getViewport() != null) {
									vp = tabContentPanel.columnsScrollPane.getViewport().getViewPosition();
								}
							}
							columnsTable = new ColumnsTable(rb);
							tabContentPanel.columnsScrollPane.setViewportView(columnsTable);
							if (vp != null) {
								if (tabContentPanel.columnsScrollPane.getViewport() != null) {
									tabContentPanel.columnsScrollPane.getViewport().setViewPosition(vp);
								}
							}
							tabContentPanel.columnsSortedStateLabel.setText("  " + tableSortAndFilterState);
							tabContentPanel.columnsSortedStateLabel.setVisible(!tableSortAndFilterState.isEmpty());
						}
						if (tabContentPanel.tabbedPane.getSelectedComponent() == tabContentPanel.textTabPanel) {
							tabContentPanel.updateTextView(rb.rowsTable);
							tabContentPanel.textSortedStateLabel.setText("  " + tableSortAndFilterState);
							tabContentPanel.textSortedStateLabel.setVisible(!tableSortAndFilterState.isEmpty());
						}
					}
                });
            } else {
                status.timeInMS += (System.currentTimeMillis() - startTime);
                int updateCount = !hasUpdateCount? 0 : statement.getUpdateCount();
                if (updateCount >= 0) {
                    status.numRowsUpdated += updateCount;
                }
                status.updateView(false);
                status.hasUpdated = true;
                if (updateCount != 0) {
                    setDataHasChanged(true);
                }
                if (isDDLStatement(sql)) {
                    status.withDDL = true;
                }
                UIUtil.invokeLater(new Runnable() {
					@Override
					public void run() {
		            	removeLastErrorTab();
		            	if (origTabContentPanel != null) {
			            	origTabContentPanel.loadingPanel.setVisible(false);
				            origTabContentPanel.repaint();
				            if (origTabContentPanel.loadButton != null) {
				            	origTabContentPanel.loadButton.setEnabled(true);
				            }
			            }
					}
				});
            }
            CancellationHandler.end(statement, SQLConsole.this);
            statement.close();
            if (!explain) {
            	appendHistory(sql);
            }
        } catch (Throwable error) {
            try {
                CancellationHandler.checkForCancellation(SQLConsole.this);
            } catch (CancellationException e) {
                error = e;
            }
            if (statement != null) {
                try {
                    statement.close();
                } catch (SQLException e) {
                }
            }
            if (resultSet != null) {
                try {
                    resultSet.close();
                } catch (SQLException e) {
                }
            }
            if (!isCommentOnly(sqlStatement)) {
                if (error instanceof SQLException && sqlStatement != null && statementStartOffset >= 0) {
                    int pos = retrieveErrorPos(sqlStatement, error.getMessage());
                    if (pos >= 0) {
                        Entry<Integer, Integer> floor = positionOffsets.floorEntry(pos);
                        int positionOffset;
                        if (floor == null) {
                        	positionOffset = 0;
                        } else {
                        	positionOffset = floor.getValue();
                        }
						status.errorPosition = statementStartOffset + pos + positionOffset;
						status.origErrorPosition = pos;
                        status.errorPositionIsKnown = true;
                    } else {
                        status.errorPosition = statementStartOffset;
                        status.errorPositionIsKnown = false;
                    }
                }
                status.failed = true;
                status.error = error;
            }
            if (error instanceof CancellationException) {
                CancellationHandler.reset(SQLConsole.this);
                queue.clear();
            }
            status.updateView(false);
            final Throwable finalError = error;
            UIUtil.invokeLater(new Runnable() {
				@Override
				public void run() {
		            if (origTabContentPanel != null) {
		            	origTabContentPanel.loadingPanel.setVisible(false);
			            origTabContentPanel.repaint();
			            if (origTabContentPanel.loadButton != null) {
			            	origTabContentPanel.loadButton.setEnabled(true);
			            }
		            }
		            if (!(finalError instanceof SQLException || finalError instanceof CancellationException)) {
		            	UIUtil.showException(SQLConsole.this, "Error", finalError);
		            }
				}
			});
        } finally {
            if (explain && session.dbms.getExplainCleanup() != null && !session.dbms.getExplainCleanup().isEmpty()) {
            	if (session.dbms.getExplainPrepare() != null) {
                    try {
                    	statement = session.getConnection().createStatement();
						statement.execute(String.format(session.dbms.getExplainCleanup(), sqlStatement, stmtId));
	                	statement.close();
					} catch (SQLException e) {
	                    logger.info("error", e);
					}
            	}
            }
            if (resetAutoCommitConnection != null) {
            	try {
					resetAutoCommitConnection.setAutoCommit(true);
				} catch (SQLException e) {
					// ignore
				}
            }
        }
    }

	private boolean executeStatementWithLimit(Statement statement, String sqlStatement, Session session) throws SQLException {
		if (DBMS.MySQL.equals(session.dbms)) {
			try {
				int limit = 1 + (Integer) limitComboBox.getSelectedItem();
				Pattern pattern = Pattern.compile(".*\\blimit\\b\\s*([0-9]+)(\\s|\\))*$", Pattern.DOTALL|Pattern.CASE_INSENSITIVE);
				Matcher matcher = pattern.matcher(sqlStatement);
				if (matcher.matches()) {
					if (Integer.parseInt(matcher.group(1)) < limit) {
						return statement.execute(sqlStatement);
					}
				}
				return statement.execute("(" + sqlStatement + "\n) limit " + limit);
			} catch (Throwable e) {
				return statement.execute(sqlStatement);
			}
		}
		return statement.execute(sqlStatement);
	}

	public void setCaretPosition(int position) {
    	if (editorPane.getDocument().getLength() >= position) {
    		try {
    			int l = editorPane.getLineOfOffset(position);
    			editorPane.setCaretPosition(position);
    			int lineHeight = editorPane.getLineHeight();
    			editorPane.scrollRectToVisible(new Rectangle(0, Math.max(0, l - 2) * lineHeight, 1, 4 * lineHeight));
    		} catch (Exception e) {
    			// ignore
    		}
    		editorPane.setCaretPosition(position);
    		grabFocus();
    	}
    }

    private void updateOutline(String sql, int startPosition) {
    	sql = sql.replaceFirst(";\\s*$", "");
        final int MAX_CONTEXT_LENGTH = 80;
        final int MAX_TOOLTIP_LENGTH = 100;
        List<OutlineInfo> outlineInfos = new ArrayList<OutlineInfo>();
        TreeMap<Integer,Integer> offsets = new TreeMap<Integer,Integer>();
        sql = sqlPlusSupport.replaceVariables(sql, offsets);
		provider.findAliases(SQLCompletionProvider.removeCommentsAndLiterals(sql), null, outlineInfos);
        adjustLevels(outlineInfos);
        List<OutlineInfo> relocatedOutlineInfos = new ArrayList<OutlineInfo>();
        int indexOfInfoAtCaret = -1;
        int caretPos = editorPane.getCaretPosition();
        OutlineInfo predInfo = null;
        for (int i = 0; i < outlineInfos.size(); ++i) {
            OutlineInfo info = outlineInfos.get(i);
            Entry<Integer, Integer> floor = offsets.floorEntry(info.position);
            if (floor != null) {
            	info.origPosition = info.position + floor.getValue();
            }
        }
        for (int i = 0; i < outlineInfos.size(); ++i) {
            OutlineInfo info = outlineInfos.get(i);
        	if (info.isBegin || info.isEnd) {
        		continue;
        	}
            int pos = info.position;
            if (pos + startPosition <= caretPos || indexOfInfoAtCaret < 0) {
                indexOfInfoAtCaret = relocatedOutlineInfos.size();
            }
			OutlineInfo rlInfo = new OutlineInfo(info.mdTable, info.alias, info.level, pos + startPosition, info.scopeDescriptor);
            rlInfo.isCTE = info.isCTE;
            rlInfo.rowCount = info.rowCount;
            if (info.withContext) {
                int cStart = info.contextPosition;
                int cEnd = sql.length();
                int ttEnd = sql.length();
                if (i < outlineInfos.size() - 1) {
                    cEnd = Math.min(cEnd, outlineInfos.get(i + 1).position);
                    ttEnd = Math.min(ttEnd, outlineInfos.get(i + 1).position);
                }
                if (info.contextEnd > 0) {
                    cEnd = Math.min(cEnd, info.contextEnd);
                    ttEnd = Math.min(ttEnd, info.contextEnd);
                }
                if (cStart < cEnd) {
	                String context = sql.substring(cStart, cEnd).trim().replaceAll("\\s+", " ");
	                if (context.length() > MAX_CONTEXT_LENGTH) {
	                    context = context.substring(0, MAX_CONTEXT_LENGTH) + "...";
	                }
	                rlInfo.context = UIUtil.toHTML(context, 0);
	                rlInfo.tooltip = UIUtil.toHTML(sql.substring(info.position, ttEnd), MAX_TOOLTIP_LENGTH);
                }
            }
            rlInfo.withSeparator = info.withSeparator || predInfo != null && predInfo.level + (predInfo.mdTable != null? 1 : 0) == info.level && i > 0 && outlineInfos.get(i - 1).isBegin;
            relocatedOutlineInfos.add(rlInfo);
            predInfo = info;
        }
        indexOfInfoAtCaret -= simplifyOutline(relocatedOutlineInfos, indexOfInfoAtCaret);
        setOutlineTables(relocatedOutlineInfos, indexOfInfoAtCaret);
    }

    private int simplifyOutline(List<OutlineInfo> outlineInfos, int indexOfInfoAtCaret) {
		// "From <single table>"
    	int caretOffset = 0;
    	List<OutlineInfo> toRemove = new ArrayList<OutlineInfo>();
    	for (int i = 1; i < outlineInfos.size(); ++i) {
    		OutlineInfo info = outlineInfos.get(i);
    		OutlineInfo pred = outlineInfos.get(i - 1);
    		if (info.mdTable != null && "From".equalsIgnoreCase(pred.scopeDescriptor)) {
    			if (info.level == pred.level) {
    				OutlineInfo succ = null;
    				if (i + 1 < outlineInfos.size()) {
    					succ = outlineInfos.get(i + 1);
    				}
    				if (succ == null || (succ.level != info.level || succ.mdTable == null)) {
    					info.scopeDescriptor = pred.scopeDescriptor;
    					info.position = pred.position;
    					info.origPosition = pred.origPosition;
    					toRemove.add(pred);
    					if (i - 1 < indexOfInfoAtCaret) {
    						++caretOffset;
    					}
    				}
    			}
    		}
    	}
    	outlineInfos.removeAll(toRemove);
    	return caretOffset;
	}

	private void adjustLevels(List<OutlineInfo> outlineInfos) {
    	if (outlineInfos.size() > 0) {
    		int lastLevel = outlineInfos.get(outlineInfos.size() - 1).level;
    		for (int level = lastLevel - 1; level >= 0; --level) {
    			OutlineInfo info = new OutlineInfo(null, null, level, 0, "");
    			info.isEnd = true;
				outlineInfos.add(info);
    		}
    	}
		Map<Integer, Integer> lastBegin = new HashMap<Integer, Integer>();
		List<Pair<Integer, Integer>> pairs = new ArrayList<Pair<Integer, Integer>>();
		Map<Integer, Pair<Integer, Integer>> pairsPerEnd = new HashMap<Integer, Pair<Integer, Integer>>();
		for (int i = 0; i < outlineInfos.size(); ++i) {
            OutlineInfo info = outlineInfos.get(i);
            if (info.isBegin) {
            	lastBegin.put(info.level, i);
            } else if (info.isEnd) {
            	if (lastBegin.containsKey(info.level)) {
            		Pair<Integer, Integer> pair = new Pair<Integer, Integer>(lastBegin.get(info.level), i);
					pairs.add(pair);
					pairsPerEnd.put(i, pair);
            	}
            }
		}
		for (int i = pairs.size() - 1; i > 0; --i) {
            Pair<Integer, Integer> thePair = pairs.get(i);
			boolean ok = false;
			int findA = thePair.a + 1;
            Pair<Integer, Integer> pair = pairsPerEnd.get(thePair.b - 1);;
            while (pair != null) {
    			if (findA == pair.a) {
    				ok = true;
    				break;
    			}
    			if (findA > pair.a) {
    				break;
    			}
    			Pair<Integer, Integer> predPair = pairsPerEnd.get(pair.a - 1);
            	if (predPair != null && pair.a - 1 != predPair.b) {
            		break;
            	}
        		pair = predPair;
            }
            
            if (ok) {
        		for (int j = thePair.a; j < thePair.b; ++j) {
        			outlineInfos.get(j).level--; 
        		}
			}
		}
	}

	private boolean isDDLStatement(String sql) {
        return sql.trim().matches("^(?is)\\b(drop|create|alter|rename)\\b.*");
    }

    protected abstract void refreshMetaData();
    protected abstract void selectTable(MDTable mdTable);
    protected abstract void setOutlineTables(List<OutlineInfo> outlineTables, int indexOfInfoAtCaret);
    protected abstract JFrame getOwner();
    protected abstract void openDataModelEditor(boolean merge);
    
    private boolean dataHasChanged = false;
    
    public synchronized void setDataHasChanged(boolean b) {
        dataHasChanged = b;		
    }

    public synchronized boolean getDataHasChanged() {
        return dataHasChanged;		
    }

    /**
     * Resets the console.
     * 
     * @param session
     *            new session
     * @param metaDataSource
     *            new meta data source
     */
    public void reset(Session session, MetaDataSource metaDataSource) throws SQLException {
        this.session = session;
        this.metaDataSource = metaDataSource;
        provider.reset(session, metaDataSource);
        editorPane.forceCaretEvent();
    }

    private class Status {
        public int origErrorPosition;
		public int errorPosition = -1;
        public boolean errorPositionIsKnown = false;
        protected int linesExecuting;
        protected int linesExecuted;
        public boolean withDDL;
        boolean failed;
        boolean running;
        boolean limitExceeded;
        int numRowsRead;
        int numRowsUpdated;
        int numStatements;
        boolean hasSelected = false;
        boolean hasUpdated = false;
        long timeInMS;
        Throwable error;
        String statement;
        Pair<Integer, Integer> location;

        private synchronized void updateView(boolean force) {
            if (force || !updatingStatus.get()) {
                updatingStatus.set(true);
                
                UIUtil.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            synchronized (Status.this) {
                                Font font = new JLabel("X").getFont();
                                statusLabel.setFont(new Font(font.getName(), font.getStyle(), (font.getSize() * 14) / 10));
                                statusLabel.setVisible(false);
                                cancelButton.setEnabled(false);
                                if (!failed) {
                                    cancelButton.setEnabled(running);
                                    statusLabel.setVisible(true);
                                    statusLabel.setForeground(running? new Color(0, 100, 0) : Color.BLACK);
                                    statusLabel.setText(getText());
                                } else {
                                	statusLabel.setVisible(true);
                                    if (error instanceof CancellationException) {
                                        statusLabel.setForeground(Color.RED);
                                        statusLabel.setText("Cancelled");
                                        removeLastErrorTab();
                                    } else if (error instanceof SQLException) {
                                        String pos = "";
                                        int errorLine = -1;
                                        try {
                                            errorLine = editorPane.getLineOfOffset(errorPosition);
                                            if (errorPositionIsKnown) {
                                                int col = errorPosition - editorPane.getLineStartOffset(errorLine) + 1;
                                                pos = "Error at line " + (errorLine + 1) + ", column " + col + ": ";
                                            }
                                            setCaretPosition(errorPosition);
                                        } catch (BadLocationException e) {
                                        }
                                        if (errorLine >= 0) {
                                            editorPane.setLineTrackingIcon(errorLine, scaledCancelIcon);
                                        }
                                        showError(pos + error.getMessage(), statement, origErrorPosition);
                                    } else {
                                        StringWriter sw = new StringWriter();
                                        PrintWriter pw = new PrintWriter(sw);
                                        error.printStackTrace(pw);
                                        String sStackTrace = sw.toString(); // stack trace as a string
                                        showError(sStackTrace, statement, origErrorPosition);
                                    }
                                }
                            }
                            Color failedColor = new Color(255, 200, 200);
                            Color okColor = new Color(210, 255, 210);
                            Color pendingColor = new Color(235, 235, 255);
                            Color runningColor = new Color(255, 249, 200);
                            if (location != null) {
                                editorPane.removeAllLineHighlights();
                                editorPane.setHighlightCurrentLine(false);
                                try {
                                    for (int i = location.a; i <= location.b; ++i) {
                                        Color hl;
                                        if (i < linesExecuted + location.a) {
                                            hl = okColor;
                                        } else if (i >= linesExecuting + location.a) {
                                            hl = pendingColor;
                                        } else {
                                            if (failed) {
                                                hl = failedColor;
                                            } else if (running){
                                                hl = runningColor;
                                            } else {
                                                hl = pendingColor;
                                            }
                                        }
                                        editorPane.addLineHighlight(i, hl);
                                    }
                                } catch (BadLocationException e) {
                                }
                            }
                            
                            jPanel2.repaint();
                            
                            if (withDDL && !running) {
                                withDDL = false;
                                refreshMetaData();
                            }
                        } finally {
                            updatingStatus.set(false);
                        }
                    }
                });
            }
        }

		private String getText() {
            String text = "";
            if (running) {
                text = "Running... ";
                if (numStatements <= 1 && numRowsRead == 0 && numRowsUpdated == 0) {
                    return text;
                }
            }
            if (numStatements > 1) {
                text += numStatements + " Statements. ";
            }
            if (hasSelected) {
                text += (limitExceeded? numRowsRead - 1 : numRowsRead) + " rows read";
                if (limitExceeded) {
                    text += " (limit exceeded)";
                }
                text += ". ";
            }
            if (hasUpdated) {
                text += numRowsUpdated + " rows updated. ";
            }
            return text + "Elapsed time: " + (timeInMS / 1000.0) + " sec";
        }
    }
    
    private void showError(String errorMessage, String statement, int errorPosition) {
    	statusLabel.setVisible(true);
    	statusLabel.setForeground(Color.RED);
    	statusLabel.setText("Error");
    	
    	removeLastErrorTab();
    	
    	JComponent rTabContainer = new ErrorPanel(errorMessage, statement, errorPosition);
		jTabbedPane1.add(rTabContainer);
        jTabbedPane1.setTabComponentAt(jTabbedPane1.indexOfComponent(rTabContainer), getTitlePanel(jTabbedPane1, rTabContainer, "Error"));

        if (jTabbedPane1.getTabCount() > MAX_TAB_COUNT) {
            jTabbedPane1.remove(0);
        }
        jTabbedPane1.setSelectedIndex(jTabbedPane1.getTabCount() - 1);
        jTabbedPane1.repaint();
    }
    
    private void removeLastErrorTab() {
		if (jTabbedPane1.getTabCount() > 0) {
			if (jTabbedPane1.getTabComponentAt(jTabbedPane1.getTabCount() - 1) instanceof TitelPanel) {
				if (((TitelPanel) jTabbedPane1.getTabComponentAt(jTabbedPane1.getTabCount() - 1)).rTabContainer instanceof ErrorPanel) {
					jTabbedPane1.removeTabAt(jTabbedPane1.getTabCount() - 1);
				}
			}
		}
	}
    
    private void disableLastErrorTab() {
		if (jTabbedPane1.getTabCount() > 0) {
			if (jTabbedPane1.getTabComponentAt(jTabbedPane1.getTabCount() - 1) instanceof TitelPanel) {
				if (((TitelPanel) jTabbedPane1.getTabComponentAt(jTabbedPane1.getTabCount() - 1)).rTabContainer instanceof ErrorPanel) {
					((ErrorPanel)((TitelPanel) jTabbedPane1.getTabComponentAt(jTabbedPane1.getTabCount() - 1)).rTabContainer).doDisable();
						
				}
			}
		}
	}

    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    // <editor-fold defaultstate="collapsed" desc="Generated
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {
        java.awt.GridBagConstraints gridBagConstraints;

        jPanel1 = new javax.swing.JPanel();
        jSplitPane2 = new javax.swing.JSplitPane();
        jPanel2 = new javax.swing.JPanel();
        consoleContainerPanel = new javax.swing.JPanel();
        jPanel5 = new javax.swing.JPanel();
        jLabel1 = new javax.swing.JLabel();
        limitComboBox = new javax.swing.JComboBox();
        cancelButton = new javax.swing.JButton();
        runSQLButton = new javax.swing.JButton();
        runnAllButton = new javax.swing.JButton();
        explainButton = new javax.swing.JButton();
        jPanel6 = new javax.swing.JPanel();
        statusLabel = new javax.swing.JLabel();
        dummyLabel = new javax.swing.JLabel();
        jPanel3 = new javax.swing.JPanel();
        jTabbedPane1 = new javax.swing.JTabbedPane();
        jLabel2 = new javax.swing.JLabel();

        setLayout(new java.awt.GridBagLayout());

        jPanel1.setLayout(new java.awt.GridBagLayout());

        jSplitPane2.setOrientation(javax.swing.JSplitPane.VERTICAL_SPLIT);
        jSplitPane2.setResizeWeight(0.6);
        jSplitPane2.setContinuousLayout(true);
        jSplitPane2.setOneTouchExpandable(true);

        jPanel2.setLayout(new java.awt.GridBagLayout());

        consoleContainerPanel.setLayout(new java.awt.BorderLayout());
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.gridwidth = 4;
        gridBagConstraints.fill = java.awt.GridBagConstraints.BOTH;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.weighty = 1.0;
        jPanel2.add(consoleContainerPanel, gridBagConstraints);

        jPanel5.setLayout(new java.awt.GridBagLayout());

        jLabel1.setText("Row limit ");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 13;
        gridBagConstraints.gridy = 2;
        gridBagConstraints.insets = new java.awt.Insets(0, 4, 0, 0);
        jPanel5.add(jLabel1, gridBagConstraints);

        limitComboBox.setModel(new javax.swing.DefaultComboBoxModel(new String[] { "Item 1", "Item 2", "Item 3", "Item 4" }));
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 14;
        gridBagConstraints.gridy = 2;
        jPanel5.add(limitComboBox, gridBagConstraints);

        cancelButton.setText("Cancel");
        cancelButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                cancelButtonActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 12;
        gridBagConstraints.gridy = 2;
        jPanel5.add(cancelButton, gridBagConstraints);

        runSQLButton.setText("Run");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 2;
        jPanel5.add(runSQLButton, gridBagConstraints);

        runnAllButton.setText("Run all");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 2;
        gridBagConstraints.gridy = 2;
        jPanel5.add(runnAllButton, gridBagConstraints);

        explainButton.setText("Explain");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 3;
        gridBagConstraints.gridy = 2;
        gridBagConstraints.insets = new java.awt.Insets(0, 0, 0, 8);
        jPanel5.add(explainButton, gridBagConstraints);

        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 0;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.SOUTH;
        gridBagConstraints.weightx = 1.0;
        jPanel2.add(jPanel5, gridBagConstraints);

        jPanel6.setLayout(new java.awt.GridBagLayout());

        statusLabel.setForeground(java.awt.Color.gray);
        statusLabel.setText(" ctrl-space for code completion");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.gridheight = 20;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.weighty = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(0, 8, 0, 0);
        jPanel6.add(statusLabel, gridBagConstraints);

        dummyLabel.setForeground(java.awt.Color.gray);
        dummyLabel.setText(" ");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.gridheight = 20;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.SOUTH;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.weighty = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(8, 8, 8, 0);
        jPanel6.add(dummyLabel, gridBagConstraints);

        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 2;
        gridBagConstraints.gridwidth = 4;
        gridBagConstraints.fill = java.awt.GridBagConstraints.BOTH;
        jPanel2.add(jPanel6, gridBagConstraints);

        jSplitPane2.setLeftComponent(jPanel2);

        jPanel3.setLayout(new java.awt.GridBagLayout());

        jTabbedPane1.setTabLayoutPolicy(javax.swing.JTabbedPane.SCROLL_TAB_LAYOUT);
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.fill = java.awt.GridBagConstraints.BOTH;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.weighty = 1.0;
        jPanel3.add(jTabbedPane1, gridBagConstraints);

        jLabel2.setText(" ");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.SOUTHWEST;
        jPanel3.add(jLabel2, gridBagConstraints);

        jSplitPane2.setRightComponent(jPanel3);

        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.fill = java.awt.GridBagConstraints.BOTH;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.weighty = 1.0;
        jPanel1.add(jSplitPane2, gridBagConstraints);

        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.fill = java.awt.GridBagConstraints.BOTH;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.weighty = 1.0;
        add(jPanel1, gridBagConstraints);
    }// </editor-fold>//GEN-END:initComponents

    private void cancelButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_cancelButtonActionPerformed
        statusLabel.setText("Canceling...");
        statusLabel.setForeground(Color.RED);
    	CancellationHandler.cancel(this);
    	cancelButton.setEnabled(false);
    }//GEN-LAST:event_cancelButtonActionPerformed

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton cancelButton;
    private javax.swing.JPanel consoleContainerPanel;
    private javax.swing.JLabel dummyLabel;
    private javax.swing.JButton explainButton;
    private javax.swing.JLabel jLabel1;
    private javax.swing.JLabel jLabel2;
    private javax.swing.JPanel jPanel1;
    private javax.swing.JPanel jPanel2;
    private javax.swing.JPanel jPanel3;
    private javax.swing.JPanel jPanel5;
    private javax.swing.JPanel jPanel6;
    private javax.swing.JSplitPane jSplitPane2;
    private javax.swing.JTabbedPane jTabbedPane1;
    private javax.swing.JComboBox limitComboBox;
    private javax.swing.JButton runSQLButton;
    private javax.swing.JButton runnAllButton;
    private javax.swing.JLabel statusLabel;
    // End of variables declaration//GEN-END:variables

    class ResultContentPane extends BrowserContentPane {
        public ResultContentPane(DataModel dataModel, Table table, String condition, Session session,
                List<Row> parentRows, Association association, Frame parentFrame,
                RowsClosure rowsClosure, Boolean selectDistinct,
                boolean reload, ExecutionContext executionContext) {
            super(dataModel, table, condition, session, parentRows, association, parentFrame, 
            		rowsClosure, selectDistinct, reload, executionContext);
            noSingleRowDetailsView = true;
            rowsTableScrollPane.setWheelScrollingEnabled(true);
        }
        @Override
        protected int getReloadLimit() {
        	return (Integer) limitComboBox.getSelectedItem();
        }
        @Override
        protected void unhide() {
        }
        @Override
        protected void showInNewWindow() {
        }
        @Override
        protected void reloadDataModel() throws Exception {
        }
        @Override
        protected void openSchemaMappingDialog() {
        }
        @Override
        protected void openSchemaAnalyzer() {
        }
        @Override
        protected void onRedraw() {
            jTabbedPane1.repaint();
        }
        @Override
        protected void onHide() {
        }
        @Override
        protected void onContentChange(List<Row> rows, boolean reloadChildren) {
        }
        @Override
        protected RowBrowser navigateTo(Association association, List<Row> pRows) {
        	return null;
        }
        @Override
        protected List<RowBrowser> getTableBrowser() {
            return null;
        }
        @Override
        protected PriorityBlockingQueue<RunnableWithPriority> getRunnableQueue() {
            return Desktop.runnableQueue;
        }
        @Override
        protected QueryBuilderDialog getQueryBuilderDialog() {
            return null;
        }
        @Override
        protected RowBrowser getParentBrowser() {
            return null;
        }
        @Override
        protected JFrame getOwner() {
            Window owner = SwingUtilities.getWindowAncestor(SQLConsole.this);
            if (owner instanceof JFrame) {
                return (JFrame) owner;
            }
            return null;
        }
        @Override
        protected double getLayoutFactor() {
            return 0;
        }
        @Override
        protected DbConnectionDialog getDbConnectionDialog() {
            return null;
        }
        @Override
        protected List<RowBrowser> getChildBrowsers() {
            return new ArrayList<RowBrowser>();
        }
        @Override
        protected void findClosure(Row row, Set<Pair<BrowserContentPane, Row>> closure, boolean forward) {
        }
        @Override
        protected void findClosure(Row row) {
        }
        @Override
        protected Relationship createQBRelations(boolean withParents) {
            return null;
        }
        @Override
        protected List<Relationship> createQBChildrenRelations(RowBrowser tabu, boolean all) {
            return null;
        }
        @Override
        protected void collectPositions(Map<String, Map<String, double[]>> positions) {
        }
        @Override
        protected void close() {
        }
        @Override
        protected void beforeReload() {
        }
        @Override
        protected void appendLayout() {
        }
        @Override
        protected void adjustClosure(BrowserContentPane tabu, BrowserContentPane thisOne) {
        }
        @Override
        protected void addRowToRowLink(Row pRow, Row exRow) {
        }
        @Override
        protected boolean renderRowAsPK(Row theRow) {
            return false;
        }
        @Override
        protected MetaDataSource getMetaDataSource() {
            return null;
        }
        @Override
        protected SQLConsole getSqlConsole(boolean switchToConsole) {
            return SQLConsole.this;
        }
		@Override
		protected void deselectChildrenIfNeededWithoutReload() {
		}
    };

    private class TitelPanel extends JPanel {
    	public final JComponent rTabContainer;
    	
    	public TitelPanel(final JTabbedPane tabbedPane, final JComponent rTabContainer, String title) {
    		super(new FlowLayout(FlowLayout.LEFT, 0, 0));
    		this.rTabContainer = rTabContainer;
    		setOpaque(false);
    		JLabel titleLbl = new JLabel(title);
    		titleLbl.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 5));
    		add(titleLbl);
    		SmallButton closeButton = new SmallButton(closeIcon) {
    			@Override
    			protected void onClick() {
    				tabbedPane.remove(rTabContainer);
    			}
    		};
    		add(closeButton);
    	}
    }

    private JPanel getTitlePanel(final JTabbedPane tabbedPane, final JComponent rTabContainer, String title) {
        return new TitelPanel(tabbedPane, rTabContainer, title);
    }

    @Override
	public void grabFocus() {
        editorPane.grabFocus();
    }

    private Icon closeIcon;
    {
        String dir = "/net/sf/jailer/ui/resource";
        
        // load images
        try {
            closeIcon = new ImageIcon(getClass().getResource(dir + "/Close-16-1.png"));
        } catch (Exception e) {
            logger.info("error", e);
        }
    }

    /**
     * Appends a statement and eventually executes it.
     * 
     * @param sql the statement
     * @param execute execute the statement?
     * @param useLineContinuation 
     * @param tabContentPanel 
     */
    public void appendStatement(String sql, boolean execute) {
    	appendStatement(sql, execute, null, false);
    }

    /**
     * Appends a statement and eventually executes it.
     * 
     * @param sql the statement
     * @param execute execute the statement?
     * @param tabContentPanel the panel to show result (option)
     */
    public void appendStatement(String sql, boolean execute, TabContentPanel tabContentPanel, boolean useLineContinuation) {
        if (!findAndSetCaretPosition(sql, tabContentPanel)) {
	        String pre = "";
	        int lineCount = editorPane.getLineCount();
	        if (lineCount > 0 && editorPane.getDocument().getLength() > 0) {
	            pre = "\n";
	            if (editorPane.getText(lineCount - 1, lineCount - 1, true).trim().length() > 0) {
	                pre += "\n";
	            }
	        }
	        if (useLineContinuation) {
	        	sql = addLineContinuation(sql);
	        } else {
	        	sql = sql.replaceAll("\\n\\s*\\n", "\n").trim();
	        }
	    	if (!sql.endsWith(";") && !sql.endsWith(" \\")) {
	            sql += ";";
	        }
	        editorPane.append(pre + sql.replace("\r", "") + "\n");
	        setCaretPosition(editorPane.getDocument().getLength());
    	}
        if (!running.get()) {
            resetStatus();
        }
        if (execute) {
            executeSelectedStatements(false, tabContentPanel, true);
        }
    }

    /**
     * Searches a statement and sets caret position s.t. current statement equals the given one.
     */
    private boolean findAndSetCaretPosition(String statement, TabContentPanel tabContentPanel) {
        statement = statement.replaceFirst("(;\\s*)+$", "").trim();
        
        if (tabContentPanel != null && tabContentPanel.caretDotMark.a != tabContentPanel.caretDotMark.b) {
	    	try {
	    		try {
					editorPane.setCaretPosition(0);
	    		} catch (Exception e) {
					// ignore
				}
	    		editorPane.setCaretPosition(tabContentPanel.caretDotMark.a);
		    	editorPane.moveCaretPosition(tabContentPanel.caretDotMark.b);
				String existingStatement = executeSelectedStatements(false, tabContentPanel, false);
				if (existingStatement.replaceFirst("(;\\s*)+$", "").trim().equals(statement)) {
					return true;
				}
	    	} catch (/*IllegalArgument*/ Exception e) {
	    		// ignore
	    	}
        }
        
        String[] statementLines = statement.split("\\s*\\n\\s*");
    	int lineCount = editorPane.getLineCount();
	    for (int lineNr = 0; lineNr < lineCount; ++lineNr) {
	    	if (lineNr + statementLines.length <= lineCount) {
	    		boolean found = true;
		    	for (int i = 0; i < statementLines.length; ++i) {
		    		String line = getLineContent(lineNr + i);
		    		line = line.trim().replaceFirst("(;\\s*)+$", "");
		    		if (!line.equals(statementLines[i])) {
		    			found = false;
		    			break;
		    		}
		    	}
		    	if (found) {
		    		try {
						editorPane.setCaretPosition(0);
		    		} catch (Exception e) {
						// ignore
					}
		    		try {
						editorPane.setCaretPosition(editorPane.getLineStartOffset(lineNr));
						String existingStatement = executeSelectedStatements(false, tabContentPanel, false).replaceFirst("(;\\s*)+$", "").trim();
						if (existingStatement.equals(statement)) {
							return true;
						}
		    		} catch (Exception e) {
						return false;
					}
		    	}
	    	}
	    }
	    
	    return false;
    }

    private String getLineContent(int line) {
    	try {
    		Segment txt = new Segment();
    		int sOff = editorPane.getLineStartOffset(line);
            editorPane.getDocument().getText(sOff, editorPane.getLineEndOffset(line) - sOff, txt);
            return txt.toString();
        } catch (BadLocationException e) {
            return "";
        }
    }
    
	private String executeSelectedStatements(boolean explain, TabContentPanel tabContentPanel, boolean execute) {
        try {
        	String sql;
	        Pair<Integer, Integer> loc = null;
	        Pair<Integer, Integer> locFragmentOffset = null;
	        Pair<Pair<Integer, Integer>, Pair<Integer, Integer>> locFragment = editorPane.getCurrentStatementFragmentLocation();
	        if (locFragment != null) {
	            loc = locFragment.a;
	            locFragmentOffset = locFragment.b;
	            sql = editorPane.getDocument().getText(locFragmentOffset.a, locFragmentOffset.b - locFragmentOffset.a);
	            if (loc.a < loc.b) {
	                Pattern pattern = Pattern.compile("(\\n\\s*)$", Pattern.DOTALL);
	                Matcher matcher = pattern.matcher(sql);
	                if (matcher.find()) {
	                    loc = new Pair<Integer, Integer>(loc.a, loc.b - 1);
	                }
	            }
	        } else {
	            loc = editorPane.getCurrentStatementLocation(null);
	            sql = editorPane.getText(loc.a, loc.b, true);
	        }
	        if (loc != null && execute) {
	            executeSQLBlock(sql, loc, editorPane.getCaret().getDot() == editorPane.getCaret().getMark(), locFragmentOffset, explain, tabContentPanel);
	        }
	        return sql;
        } catch (BadLocationException e) {
            e.printStackTrace();
            return "";
        }
    }

    private void executeAllStatements() {
        if (editorPane.getLineCount() > 0) {
            int start = 0;
            
            try {
                while (start < editorPane.getLineCount()) {
                    Segment txt = new Segment();
                    int sOff = editorPane.getLineStartOffset(start);
                    editorPane.getDocument().getText(sOff, editorPane.getLineEndOffset(start) - sOff, txt);
                    String sLine = txt.toString().trim();
                    if (sLine.length() > 0) {
                        break;
                    }
                    ++start;
                }
            } catch (BadLocationException e) {
                // ignore
            }
            Pair<Integer, Integer> loc = new Pair<Integer, Integer>(start, editorPane.getLineCount() - 1);
            executeSQLBlock(editorPane.getText(loc.a, loc.b, true), loc, false, null, false, null);
        }
    }

    private boolean isCommentOnly(String statement) {
    	if (statement == null) {
    		return false;
    	}
        Pattern pattern = Pattern.compile("('([^']*'))|(/\\*.*?\\*/)|(\\-\\-.*?(\n|$))", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(statement);
        boolean result = matcher.find();
        StringBuffer sb = new StringBuffer();
        if (result) {
            do {
                if (matcher.group(1) == null) {
                    matcher.appendReplacement(sb, "");
                } else {
                    matcher.appendReplacement(sb, "$0");
                }
                result = matcher.find();
            } while (result);
        }
        matcher.appendTail(sb);
        return sb.toString().trim().isEmpty();
    }

    private final String HISTORY_FILE = ".history";
    private final String LF = System.getProperty("line.separator", "\n");
    private final JComboBox historyComboBox = new JComboBox() {
        @Override
        public java.awt.Dimension getMinimumSize() {
            Dimension s = super.getMinimumSize();
            return new Dimension(Math.min(80, s.width), s.height);
        }
    };

    private synchronized void restoreHistory() {
        try {
            File file = Environment.newFile(HISTORY_FILE);
            if (file.exists()) {
                BufferedReader in = new BufferedReader(new FileReader(file));
                history.clear();
                String line;
                while ((line = in.readLine()) != null) {
                    String[] lines = CsvFile.decodeLine(line.trim());
                    if (lines.length > 0 && !lines[0].isEmpty()) {
                        history.add(lines[0]);
                    }
                }
                in.close();
            }
        } catch (Exception e) {
            logger.info("error", e);
        }
        UIUtil.invokeLater(new Runnable() {
			@Override
			public void run() {
		        historyComboBox.setModel(historyComboboxModel());
			}
		});
    }

    private synchronized void storeHistory() {
        try {
            File file = Environment.newFile(HISTORY_FILE);
            FileWriter out = new FileWriter(file);
            for (String sql: history) {
                out.write(CsvFile.encodeCell(sql) + LF);
            }
            out.close();
        } catch (Exception e) {
            logger.info("error", e);
        }
        UIUtil.invokeLater(new Runnable() {
			@Override
			public void run() {
				historyComboBox.setModel(historyComboboxModel());
			}
		});
    }

    private final ItemListener historyCBItemListener = new ItemListener() {
        @Override
        public void itemStateChanged(ItemEvent e) {
            if (e.getStateChange() == ItemEvent.SELECTED && e.getItem() != null && historyComboBox.getSelectedIndex() > 0) {
                appendStatement(e.getItem().toString(), false, null, true);
                historyComboBox.setSelectedIndex(0);
            }
        }
    };
    
    private DefaultComboBoxModel historyComboboxModel() {
        ArrayList<String> model = new ArrayList<String>(history);
        if (!model.isEmpty()) {
            model.add(0, new String(model.get(0) + " "));
        }
        historyComboBox.removeItemListener(historyCBItemListener);
        DefaultComboBoxModel cbModel = new DefaultComboBoxModel(model.toArray());
        historyComboBox.addItemListener(historyCBItemListener);
        return cbModel;
    }

    private synchronized void appendHistory(String sql) {
        sql = sql.trim();
        if (!sql.isEmpty() && sql.length() < 100000) {
            history.remove(sql);
            history.add(0, sql);
            if (history.size() > MAX_HISTORY_SIZE) {
                history.remove(history.size() - 1);
            }
        }
    }

    private int retrieveErrorPos(String sqlStatement, CharSequence errorMessage) {
        if (DBMS.ORACLE.equals(session.dbms)) {
            String statement = "declare "
                    + "l_theCursor integer default dbms_sql.open_cursor;" + "begin      " + "    begin     "
                    + "      dbms_sql.parse(  l_theCursor, ?, dbms_sql.native );"
                    + "      exception when others then ? := dbms_sql.last_error_position;" + "    end;"
                    + "    dbms_sql.close_cursor( l_theCursor );" + "end;";

            CallableStatement cStmt = null;
            try {
                Connection connection = session.getConnection();
                cStmt = connection.prepareCall(statement);
                cStmt.registerOutParameter(2, Types.INTEGER);
                cStmt.setString(1, sqlStatement);

                cStmt.execute();

                return cStmt.getInt(2);
            } catch (Exception e) {
                logger.info("error", e);
            } finally {
                if (cStmt != null) {
                    try {
                        cStmt.close();
                    } catch (SQLException e) {
                    }
                }
            }
        } else if (DBMS.POSTGRESQL.equals(session.dbms)) {
            Pattern pattern = Pattern.compile("\\n\\s*Position: ([0-9]+)");
            Matcher matcher = pattern.matcher(errorMessage);
            if (matcher.find()) {
                return Integer.parseInt(matcher.group(1)) - 1;
            }
        }
        return -1;
    }

    private void toggleLineContinuation() {
		String currentStatement = editorPane.getCurrentStatement(false);
		String newStatement;
		Pattern p = Pattern.compile("(?:(?:;( |\\t|\\r)*(?:--[^\\n]*)?)|(?:\\n( |\\t|\\r)*)) ?\\\\( |\\t|\\r)*\\n", Pattern.DOTALL);
        Matcher m = p.matcher(currentStatement);
        if (m.find()) {
			newStatement = currentStatement.replaceAll("((?:(?:;(?: |\\t|\\r)*?(?:--[^\\n]*)?))) ?\\\\([ \\t\\r]*\\n)", "$1$2");
			newStatement = newStatement.replaceAll("((?:\\n(?: |\\t|\\r)*?)) ?\\\\([ \\t\\r]*)(?=\\n)", "$1$2");
		} else {
			newStatement = addLineContinuation(currentStatement);
		}
		if (!currentStatement.equals(newStatement)) {
			editorPane.replaceCurrentStatement(newStatement, false);
		}
	}

	private String addLineContinuation(String statement) {
		String newStatement = statement;
		newStatement = newStatement.replace("\r", "");
		newStatement = newStatement.replaceAll("((?:(?:;(?: |\\t|\\r)*(?:--[^\\n]*)?)))(\\n(\\r)?)", "$1 \\\\$2");
		newStatement = newStatement.replaceAll("((?:(?:\\n(?: |\\t|\\r)*)))(?=\\n)", "$1 \\\\");
		newStatement = newStatement.replaceAll("\\\\(\\s*)$", "$1");
		return newStatement;
	}

    private void analyzeSQL() {
		String currentStatement = editorPane.getCurrentStatement(false);
		File tempFile = Configuration.getInstance().createTempFile();
		try {
			BufferedWriter out = new BufferedWriter(new FileWriter(tempFile));
			Pair<Integer, Integer> loc = editorPane.getCurrentStatementLocation(false, false, null, false);
			if (loc != null) {
				for (int i = 0; i < loc.a; ++i) {
					out.write("\n");
				}
			}
			out.write(currentStatement);
			out.close();
			AssociationProposerView associationProposer = new AssociationProposerView(getOwner(), datamodel.get(), tempFile, executionContext);
			if (associationProposer.isAccepted()) {
				openDataModelEditor(true);
			}
		} catch (Exception e) {
			UIUtil.showException(this, "Error", e);
		}
		tempFile.delete();
    }

	private void substituteVariables() {
		String currentStatement = editorPane.getCurrentStatement(true);
		String newStatement = sqlPlusSupport.replaceVariables(currentStatement, null);
		if (!currentStatement.equals(newStatement)) {
			editorPane.replaceCurrentStatement(newStatement, true);
		}
	}

	/**
	 * @return <code>true</code> iff console editor is empty
	 */
	public boolean isEmpty() {
		return editorPane.getDocument().getLength() == 0;
	}
	
	/**
	 * Sets the row limit.
	 * 
	 * @param limit the row limit
	 */
	public void setRowLimit(Integer limit) {
		limitComboBox.setSelectedItem(limit);
	}

	private int initialContentSize;
	private int initialContentHash;
	private boolean dirty;
	
	/**
	 * Loads content from file.
	 * 
	 * @param file the file
	 */
	public void loadFromFile(File file) throws IOException {
		this.file = file;
		if (file.exists()) {
			BufferedReader in = new BufferedReader(new FileReader(file));
			StringBuilder sb = new StringBuilder();
			int c;
	        while ((c = in.read()) != -1) {
	        	if (c != '\r') {
	        		sb.append((char) c);
	        	}
	        }
	        in.close();
	        editorPane.setText(sb.toString());
	        dirty = false;
		} else {
			editorPane.setText("");
	        dirty = true;
		}
        initialContentSize = editorPane.getDocument().getLength();
        initialContentHash = editorPane.getText().hashCode();
        consoleContainerPanel.setVisible(false);
        UIUtil.invokeLater(new Runnable() {
			@Override
			public void run() {
		        consoleContainerPanel.setVisible(true);
		        grabFocus();
			}
		});
        setCaretPosition(0);
        onContentStateChange(file, dirty);
	}

	/**
	 * Stores content to file if console is dirty or new file is given.
	 * 
	 * @param newFile the file or <code>null</code> to store into assigned file
	 */
	public void storeToFile(File newFile) throws IOException {
		if (newFile != null || file != null && dirty) {
			if (newFile != null) {
				file = newFile;
			}
			PrintWriter out = new PrintWriter(new BufferedWriter(new FileWriter(file)));
			int lines = editorPane.getLineCount();
			for (int line = 0; line < lines; ++line) {
				out.print(getLineContent(line).replace("\r", "").replace("\n", ""));
				if (line < lines - 1) {
					out.println();
				}
			}
			out.close();
	        initialContentSize = editorPane.getDocument().getLength();
	        initialContentHash = editorPane.getText().hashCode();
	        dirty = false;
	        onContentStateChange(file, dirty);
		}
	}

	private void checkDirty() {
		boolean newState = false;
        if (initialContentSize != editorPane.getDocument().getLength()) {
        	newState = true;
        } else {
        	newState = initialContentHash != editorPane.getText().hashCode();
        }
        if (newState != dirty) {
        	dirty = newState;
            onContentStateChange(file, dirty);
        }
	}

	/**
	 * Reloads tab content
	 * 
	 * @param tabContentPanel to be reloaded
	 * @param sql statement to be executed again
	 */
	private void reload(TabContentPanel tabContentPanel, String sql) {
		try {
			UIUtil.setWaitCursor(tabContentPanel);
			appendStatement(sql.replaceFirst("(;\\s*)+$", ""), true, tabContentPanel, true);
		} finally {
			UIUtil.resetWaitCursor(tabContentPanel);
		}
	}

	/**
	 * Gets the file associated with this console.
	 * 
	 * @return file associated with this console or <code>null</code>, if the console is not assigned to a file
	 */
	public File getFile() {
		return file;
	}

	public boolean isDirty() {
		return dirty;
	}

	/**
	 * Closes this console.
	 */
	public void close() {
    	CancellationHandler.cancel(this);
    	queue.add(STOP);
	}

	/**
	 * Gets the editor pane.
	 * 
	 * @return the editor pane
	 */
    public RSyntaxTextAreaWithSQLSyntaxStyle getEditorPane() {
		return editorPane;
	}

	protected abstract void onContentStateChange(File file, boolean dirty);

	static private ImageIcon runIcon;
    static private ImageIcon runAllIcon;
    static private ImageIcon cancelIcon;
    static private ImageIcon explainIcon;

    private int nextPlanID = 0;
    
    static {
        String dir = "/net/sf/jailer/ui/resource";
        
        // load images
        try {
            runIcon = new ImageIcon(MetaDataPanel.class.getResource(dir + "/run.png"));
            runAllIcon = new ImageIcon(MetaDataPanel.class.getResource(dir + "/runall.png"));
            cancelIcon = new ImageIcon(MetaDataPanel.class.getResource(dir + "/Cancel.png"));
            explainIcon = new ImageIcon(MetaDataPanel.class.getResource(dir + "/explain.png"));
        } catch (Exception e) {
            logger.info("error", e);
        }
    }

}
