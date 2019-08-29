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
package net.sf.jailer.entitygraph.local;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.sf.jailer.ExecutionContext;
import net.sf.jailer.configuration.Configuration;
import net.sf.jailer.configuration.DBMS;
import net.sf.jailer.configuration.LocalDatabaseConfiguration;
import net.sf.jailer.database.InlineViewBuilder;
import net.sf.jailer.database.InlineViewStyle;
import net.sf.jailer.database.LocalDatabase;
import net.sf.jailer.database.SQLDialect;
import net.sf.jailer.database.Session;
import net.sf.jailer.database.Session.ResultSetReader;
import net.sf.jailer.database.UpdateTransformer;
import net.sf.jailer.database.WorkingTableScope;
import net.sf.jailer.datamodel.Association;
import net.sf.jailer.datamodel.Column;
import net.sf.jailer.datamodel.DataModel;
import net.sf.jailer.datamodel.PrimaryKey;
import net.sf.jailer.datamodel.PrimaryKeyFactory;
import net.sf.jailer.datamodel.RowIdSupport;
import net.sf.jailer.datamodel.Table;
import net.sf.jailer.ddl.DDLCreator;
import net.sf.jailer.entitygraph.EntityGraph;
import net.sf.jailer.util.CellContentConverter;
import net.sf.jailer.util.CsvFile;
import net.sf.jailer.util.Quoting;
import net.sf.jailer.util.SqlUtil;

/**
 * Persistent graph of entities.
 * Persists the graph in a local database.
 * 
 * @author Ralf Wisser
 */
public class LocalEntityGraph extends EntityGraph {

	/**
	 * For access to the remote database.
	 */
	private final Session remoteSession; 

	/**
	 * For access to the local database.
	 */
	private final Session localSession;

	/**
	 * Local database.
	 */
	private final LocalDatabase localDatabase;

	private InlineViewStyle localInlineViewStyle;

	private InlineViewStyle remoteInlineViewStyle; 
	
	private abstract class RemoteInlineViewBuilder extends InlineViewBuilder {

		public RemoteInlineViewBuilder(String name, String columnList) {
			this(name, columnList, false);
		}
		
		public RemoteInlineViewBuilder(String name, String columnList, boolean allUPK) {
			super(remoteInlineViewStyle, name, remoteSession, columnList.split(", *"));
			this.allUPK = allUPK;
		}
		
		private final boolean allUPK;
		
		@Override
		protected String sqlValue(ResultSet resultSet, int i) throws SQLException {
			Object value = cellContentConverter.getObject(resultSet, i);
			if (!allUPK && !isUPKColumn(columnNames[i - 1])) {
				value = cellContentConverter.toSql(value);
			} else if (value instanceof String && isNUPKColumn(columnNames[i - 1])) {
				String prefix = remoteSession.dbms.getNcharPrefix();
				if (prefix != null && value != null && !value.toString().startsWith(prefix)) {
					value = prefix + value;
				}
			}
			return (String) value;
		}

		@Override
		protected CellContentConverter createCellContentConverter() {
			return new CellContentConverter(resultSetMetaData, localSession, localSession.dbms);
		}
		
		@Override
		public void close() throws SQLException {
			if (DBMS.POSTGRESQL.equals(remoteSession.dbms) && statementBuilder.size() == 1) {
				process("(Select " + statementBuilder.getItems().get(0) + " Union Select " + statementBuilder.getItems().get(0) + ") " + name);
			} else {
				super.close();
			}
		}
		
	}

	private abstract class LocalInlineViewBuilder extends InlineViewBuilder {

		public LocalInlineViewBuilder(String name, String columnList) {
			this(name, columnList, false);
		}
		
		public LocalInlineViewBuilder(String name, String columnList, boolean allUPK) {
			super(localInlineViewStyle, name, localSession, columnList.split(", *"));
			this.allUPK = allUPK;
			this.localDBMSConfiguration = localSession.dbms;
		}
		
		private final boolean allUPK;
		private final DBMS localDBMSConfiguration;
		
		@Override
		protected String sqlValue(ResultSet resultSet, int i) throws SQLException {
			String value = cellContentConverter.toSql(cellContentConverter.getObject(resultSet, i));
			if (allUPK || isUPKColumn(columnNames[i - 1])) {
				// value = cellContentConverter.toSql(value);
				value = "'" + localDBMSConfiguration.convertToStringLiteral(value) + "'";
			}
			return value;
		}

		@Override
		protected CellContentConverter createCellContentConverter() {
			return new CellContentConverter(resultSetMetaData, remoteSession, remoteSession.dbms);
		}

	}

	private Set<String> upkColumnNames = null;
	private Set<String> nupkColumnNames = null;
	
	private synchronized boolean isUPKColumn(String columnName) {
		if (upkColumnNames == null) {
			upkColumnNames = new HashSet<String>();
			for (Column c: universalPrimaryKey.getColumns()) {
				upkColumnNames.add(c.name);
			}
		}
		return upkColumnNames.contains(columnName);
	}

	private synchronized boolean isNUPKColumn(String columnName) {
		if (nupkColumnNames == null) {
			nupkColumnNames = new HashSet<String>();
			String localNPKType = getConfiguration().getLocalNPKType();
			for (Column c: universalPrimaryKey.getColumns()) {
				if (c.type.equalsIgnoreCase(localNPKType)) {
					nupkColumnNames.add(c.name);
				}
			}
		}
		return nupkColumnNames.contains(columnName);
	}

	private static LocalDatabaseConfiguration localConfiguration = null;
	
	private static synchronized LocalDatabaseConfiguration getConfiguration() {
		if (localConfiguration == null) {
			localConfiguration = Configuration.getInstance().localEntityGraphConfiguration;
			if (localConfiguration == null) {
				localConfiguration = new LocalDatabaseConfiguration();
			}
		}
		return localConfiguration;
	}
	
	private final RowIdSupport rowIdSupport;
	private final Quoting quoting;
	
	/**
	 * Copy constructor.
	 */
	private LocalEntityGraph(int graphID, DataModel dataModel,
			Session remoteSession, Session localSession, LocalDatabase localDatabase,
			InlineViewStyle localInlineViewStyle,
			InlineViewStyle remoteInlineViewStyle, Set<String> upkColumnNames,
			PrimaryKey universalPrimaryKey, int birthdayOfSubject, Set<String> fieldProcTables,
			RowIdSupport rowIdSupport, ExecutionContext executionContext) throws SQLException {
		super(graphID, dataModel, executionContext);
		this.remoteSession = remoteSession;
		this.localSession = localSession;
		this.localDatabase = localDatabase;
		this.localInlineViewStyle = localInlineViewStyle;
		this.remoteInlineViewStyle = remoteInlineViewStyle;
		this.upkColumnNames = upkColumnNames;
		this.universalPrimaryKey = universalPrimaryKey;
		this.birthdayOfSubject = birthdayOfSubject;
		this.fieldProcTables.addAll(fieldProcTables);
		this.rowIdSupport = rowIdSupport;
		this.quoting = new Quoting(remoteSession);
	}

	/**
	 * Constructor.
	 * 
	 * @param remoteSession
	 */
	private LocalEntityGraph(int graphID, Session remoteSession, ExecutionContext executionContext) throws IOException, SQLException {
		super(graphID, new DataModel(new PrimaryKeyFactory(executionContext) {
			@Override
			public PrimaryKey createPrimaryKey(List<Column> columns, String tableName) {
				List<Column> localPK = new ArrayList<Column>(columns.size());
				for (Column c: columns) {
					if (c.type.equalsIgnoreCase("nvarchar") || c.type.equalsIgnoreCase("nchar")) {
						localPK.add(new Column(c.name, getConfiguration().getLocalNPKType(), getConfiguration().getLocalPKLength(), -1));
					} else {
						localPK.add(new Column(c.name, getConfiguration().getLocalPKType(), getConfiguration().getLocalPKLength(), -1));
					}
				}
				return super.createPrimaryKey(localPK, tableName);
			}
		}, executionContext.getSourceSchemaMapping(), executionContext), executionContext);
		this.remoteSession = remoteSession;
		this.quoting = new Quoting(remoteSession);
		this.rowIdSupport = new RowIdSupport(getDatamodel(), remoteSession.dbms, getConfiguration().getLocalPKType(), executionContext);
		this.localDatabase = createLocalDatabase(getConfiguration().getDriver(), getConfiguration().getUrlPattern(), getConfiguration().getUser(), getConfiguration().getPassword(), getConfiguration().getLib());
		this.localSession = this.localDatabase.getSession();
		this.universalPrimaryKey = rowIdSupport.getUniversalPrimaryKey();
		this.localInlineViewStyle = InlineViewStyle.forSession(localSession);
		this.remoteInlineViewStyle = InlineViewStyle.forSession(remoteSession);
		new DDLCreator(executionContext).createDDL(getDatamodel(), localSession, WorkingTableScope.GLOBAL, rowIdSupport, null);
	 
		File fieldProcTablesFile = new File("field-proc-tables.csv");
		if (fieldProcTablesFile.exists()) {
			try {
				for (CsvFile.Line line: new CsvFile(fieldProcTablesFile).getLines()) {
					fieldProcTables.add(line.cells.get(0).toLowerCase());
				}
				Session._log.info("tables with field procedures: " + fieldProcTables);
			} catch (Exception e) {
				throw new RuntimeException(e.getMessage(), e);
			}
		}
	}

	/**
	 * Creates a local database and opens a localSession for it.
	 * @param urlparameter 
	 * 
	 * @return the localSession
	 * @
	 */
	private LocalDatabase createLocalDatabase(String driverClassName, String urlPattern, String user, String password, String jarfile) throws FileNotFoundException, SQLException {
		try {
			return new LocalDatabase(driverClassName, urlPattern, user, password, jarfile);
		} catch (ClassNotFoundException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Closes the graph. Deletes the local database.
	 */
	@Override
	public void close() throws SQLException {
		localDatabase.shutDown();
	}

	/**
	 * The universal primary key.
	 */
	private final PrimaryKey universalPrimaryKey;
	
	/**
	 * Birthday of subject rows.
	 */
	private int birthdayOfSubject = 0;
	
	/**
	 * Sets birthday of subject rows.
	 * 
	 * @param birthdayOfSubject birthday of subject rows
	 */
	@Override
	public void setBirthdayOfSubject(int birthdayOfSubject) {
		this.birthdayOfSubject = birthdayOfSubject;
	}
	
	/**
	 * Creates a new entity-graph.
	 * 
	 * @param graphID the unique ID of the graph
	 * @param remoteSession for executing SQL-Statements
	 * @param universalPrimaryKey the universal primary key
	 * @return the newly created entity-graph
	 */
	public static LocalEntityGraph create(DataModel dataModel, int graphID, Session remoteSession, ExecutionContext executionContext) throws IOException, SQLException {
		LocalEntityGraph entityGraph = new LocalEntityGraph(graphID, remoteSession, executionContext);
		try {
			entityGraph.localSession.executeUpdate("Insert into " + SQLDialect.dmlTableReference(ENTITY_GRAPH, entityGraph.localSession, executionContext) + "(id, age) values (" + graphID + ", 1)");
		} catch (SQLException e) {
			throw new RuntimeException("Can't find working tables! " +
					"Run 'jailer.sh create-ddl' " +
					"and execute the DDL-script first!", e);
		}
		return entityGraph;
	}

	/**
	 * Copies an entity-graph.
	 * 
	 * @param newGraphID the unique ID of the new graph
	 * @param localSession for executing SQL-Statements
	 * @return the newly created entity-graph
	 */
	@Override
	public EntityGraph copy(int newGraphID, Session globalSession) throws SQLException {
		LocalEntityGraph entityGraph = new LocalEntityGraph(newGraphID, dataModel, remoteSession, localSession, localDatabase, localInlineViewStyle, remoteInlineViewStyle, upkColumnNames, universalPrimaryKey, birthdayOfSubject, fieldProcTables, rowIdSupport, executionContext);
		entityGraph.setBirthdayOfSubject(birthdayOfSubject);
		localSession.executeUpdate(
				"Insert into " + dmlTableReference(ENTITY, localSession) + "(r_entitygraph, " + universalPrimaryKey.columnList(null) + ", birthday, orig_birthday, type) " +
					"Select " + newGraphID + ", " + universalPrimaryKey.columnList(null) + ", birthday, birthday, type From " + dmlTableReference(ENTITY, localSession) + " Where r_entitygraph=" + graphID + "");
		return entityGraph;
	}

	/**
	 * Gets the age of the graph.
	 * 
	 * @return the age of the graph
	 */
	@Override
	public int getAge() throws SQLException {
		final int[] age = new int[1];
		age[0] = -1;
		localSession.executeQuery("Select age From " + dmlTableReference(ENTITY_GRAPH, localSession) + " Where id=" + graphID + "", new Session.ResultSetReader() {
			@Override
			public void readCurrentRow(ResultSet resultSet) throws SQLException {
				age[0] = resultSet.getInt(1);
			}
			@Override
			public void close() {
			}
		});
		return age[0];
	}

	/**
	 * Sets the age of the graph.
	 * 
	 * @param age the age of the graph
	 */
	@Override
	public void setAge(int age) throws SQLException {
		localSession.executeUpdate("Update " + dmlTableReference(ENTITY_GRAPH, localSession) + " Set age=" + age + " Where id=" + graphID + "");
	}
	
	/**
	 * Gets the number of entities in the graph.
	 * 
	 * @return the number of entities in the graph
	 */
	@Override
	public long getSize() throws SQLException {
		final int[] size = new int[1];
		size[0] = -1;
		localSession.executeQuery("Select count(*) From " + dmlTableReference(ENTITY, localSession) + " Where r_entitygraph=" + graphID + " and birthday >= 0", new Session.ResultSetReader() {
			@Override
			public void readCurrentRow(ResultSet resultSet) throws SQLException {
				size[0] = resultSet.getInt(1);
			}
			@Override
			public void close() {
			}
		});
		return size[0];
	}

	/**
	 * Deletes the graph.
	 */
	@Override
	public void delete() throws SQLException {
		if (!isTruncated) {
			localSession.executeUpdate("Delete from " + dmlTableReference(DEPENDENCY, localSession) + " Where r_entitygraph=" + graphID + "");
			localSession.executeUpdate("Delete from " + dmlTableReference(ENTITY, localSession) + " Where r_entitygraph=" + graphID + "");
			localSession.executeUpdate("Delete from " + dmlTableReference(ENTITY_GRAPH, localSession) + " Where id=" + graphID + "");
		}
	}

	/**
	 * Adds entities to the graph.
	 * 
	 * @param table the table 
	 * @param condition the condition in SQL that the entities must fulfill
	 * @param today the birthday of the new entities
	 * 
	 * @return row-count
	 */
	@Override
	public long addEntities(Table table, String condition, int today) throws SQLException {
		// checkPseudoColumns(table, condition);
		return addEntities(table, "T", condition, today);
	}
	
	/**
	 * The pseudo-columns $DISTANCE and $IS_SUBJECT are currently not supported
	 * if the "working table scope" "local database" is used. It works with
	 * "global tables" or "temporary tables".
	 */
	private void checkPseudoColumns(Table table, String condition) {
		if (condition != null) {
			if (!condition.equals(SqlUtil.resolvePseudoColumns(condition, "A", "B", 0, 0, inDeleteMode))) {
				throw new IllegalArgumentException(
						"Unsupported use of pseudo-columns in condition:\n\"" + condition + "\"\n(Table " + table.getName() + ")\n\n" +
						"When generating delete-scripts, the pseudo-columns $DISTANCE and $IS_SUBJECT are currently not supported " +
						"if the \"working table scope\" is \"local database\". It works with " +
						"\"global tables\" or \"temporary tables\".");
			}
		}
	}

	/**
	 * Resolves an association. Retrieves and adds all entities 
	 * associated with an entity born yesterday in the graph 
	 * and adds the dependencies.
	 * 
	 * @param table the table 
	 * @param association the association to resolve
	 * @param today the birthday of the new entities
	 * 
	 * @return row-count or -1, if association is ignored
	 */
	@Override
	public long resolveAssociation(final Table table, Association association, final int today) throws SQLException {
		if (association.getJoinCondition() != null) {
			final String jc = SqlUtil.resolvePseudoColumns(association.getJoinCondition(), today, birthdayOfSubject, association.reversed, inDeleteMode);
			
			final String destAlias;
			final String sourceAlias;
			if (association.reversed) {
				destAlias = "A";
				sourceAlias = "B";
			} else {
				destAlias = "B";
				sourceAlias = "A";
			}
			Integer associationExplanationIDm = 0;
			if (explain) {
				synchronized (explainIdOfAssociation) {
					associationExplanationIDm = explainIdOfAssociation.get(association);
					if (associationExplanationIDm == null) {
						associationExplanationIDm = (nextExplainID++);
						explainIdOfAssociation.put(association, associationExplanationIDm);
					}
				}
			}
			final Integer associationExplanationID = associationExplanationIDm;
			
			// ----
			
			final Table destination = association.destination;
			final String condition = "E.r_entitygraph=" + graphID + " and E.birthday = " + (today - 1) + " and E.type=" + typeName(table) + "";
			final Table source = association.source;
			
			String select;
			
			select =
			  "Select " + upkColumnList(source, "E", null) +
			  " From " + dmlTableReference(ENTITY, localSession) + " E" +
			  " Where " + condition;
			
			final long[] rc = new long[1];
			
			localSession.executeQuery(select, new RemoteInlineViewBuilder("E", upkColumnList(source, null)) {
				@Override
				protected void process(String inlineView) throws SQLException {
					String select =
							"Select distinct " + pkList(destination, destAlias) +
							" From " + inlineView + " join " + quoting.requote(source.getName()) + " " + sourceAlias + " on " + pkEqualsEntityID(source, sourceAlias, "E", "", false) +
							" join " + quoting.requote(destination.getName()) + " " + destAlias + " on (" + jc + ")";
					
					remoteSession.executeQuery(select, new LocalInlineViewBuilder(destAlias, upkColumnList(destination, null)) {
						@Override
						protected void process(String inlineView) throws SQLException {
							Map<Column, Column> match = upkMatch(destination);
							StringBuffer sb = new StringBuffer();
							for (Column column: universalPrimaryKey.getColumns()) {
								if (sb.length() > 0) {
									sb.append(" and ");
								}
								Column tableColumn = match.get(column);
								sb.append("Duplicate." + column.name);
								if (tableColumn != null) {
									sb.append("=" + destAlias + "." + column.name);
								} else {
									sb.append(" is null");
								}
							}
							
							String entityJoinCondition = sb.toString();
							String select = "Select " + graphID + " as GRAPH_ID, " + upkColumnList(destination, destAlias, null) + ", " + today + " AS BIRTHDAY, " + typeName(destination) + " AS TYPE" +
							(source == null || !explain? "" : ", " + associationExplanationID + " AS ASSOCIATION, " + typeName(source) + " AS SOURCE_TYPE, " + upkColumnList(source, "PRE_")) +
							" From " + inlineView + 
							" left join " + dmlTableReference(ENTITY, localSession) + " Duplicate on Duplicate.r_entitygraph=" + graphID + " and Duplicate.type=" + typeName(destination) + " and " +
							entityJoinCondition + 
							" Where Duplicate.type is null";
							
							String insert = "Insert into " + dmlTableReference(ENTITY, localSession) + " (r_entitygraph, " + upkColumnList(destination, null) + ", birthday, type" + (source == null || !explain? "" : ", association, PRE_TYPE, " + upkColumnList(source, "PRE_"))  + ") " + select;
							rc[0] += localSession.executeUpdate(insert);
							totalRowcount += rc[0];
						}
					});
					
				}
			});
			return rc[0];
		}
		return -1;
	}

	/**
	 * Adds entities to the graph.
	 * 
	 * @param table the table 
	 * @param condition the condition in SQL that the entities must fulfill with 'E' as alias for the entity-table
	 * @param joinedTable optional table to join with
	 * @param source optional, the source-table
	 * @param joinCondition optional condition to join with <code>joinedTable</code>
	 * @param joinWithEntity whether to join with entity-table too
	 * @param today the birthday of the new entities
	 * 
	 * @return row-count
	 */
	private long addEntities(final Table table, final String alias, String condition, final int today) throws SQLException {
		String select =
			"Select " + pkList(table, alias) +
			" From " + quoting.requote(table.getName()) + " " + alias + " Where (" + condition + ")";
		
		final long[] rc = new long[1];
		
		remoteSession.executeQuery(select, new LocalInlineViewBuilder(alias, upkColumnList(table, null)) {
			@Override
			protected void process(String inlineView) throws SQLException {
				String select = "Select " + graphID + " as GRAPH_ID, " + upkColumnList(table, alias, null) + ", " + today + " AS BIRTHDAY, " + typeName(table) + " AS TYPE" +
				" From " + inlineView;
				
				String insert = "Insert into " + dmlTableReference(ENTITY, localSession) + " (r_entitygraph, " + upkColumnList(table, null) + ", birthday, type) " + select;
				rc[0] += localSession.executeUpdate(insert);
				totalRowcount += rc[0];
			}
		});
		
		return rc[0];
	}

	/**
	 * Adds dependencies.
	 * 
	 * @param from source of dependency
	 * @param fromAlias alias for from-table
	 * @param to destination of dependency
	 * @param toAlias alias for to-table
	 * @param condition condition of dependency
	 * @param aggregationId id of aggregation association (for XML export), 0 if not applicable
	 * @param dependencyId id of dependency
	 */
	@Override
	public void addDependencies(final Table from, final String fromAlias, final Table to, final String toAlias, final String condition, final int aggregationId, final int dependencyId, boolean isAssociationReversed) throws SQLException {
		checkPseudoColumns(from, condition);
		String upkColumnList = upkColumnList(from, "E1", null);
		String select = 
				"Select " + upkColumnList + " From " + dmlTableReference(ENTITY, localSession) + " E1 " +
				" Where E1.r_entitygraph=" + graphID + 
				" and E1.type=" + typeName(from) + "";

		localSession.executeQuery(select, new RemoteInlineViewBuilder("E1", upkColumnList(from, null, null)) {
			@Override
			protected void process(String inlineView) throws SQLException {
				String upkColumnList = upkColumnList(from, "E1", null);
				String select = 
						"Select " + upkColumnList + ", " + pkList(to, toAlias) +
						" From " + inlineView + ", " +
						 quoting.requote(from.getName()) + " " + fromAlias + ", " + quoting.requote(to.getName()) + " " + toAlias +
						" Where (" + condition + ")" +
						" and " + pkEqualsEntityID(from, fromAlias, "E1", "", false);
				
				remoteSession.executeQuery(select, new LocalInlineViewBuilder("E1E2", upkColumnList(from, null, "E1") + ", " + upkColumnList(to, null, "E2"), true) {
					@Override
					protected void process(String inlineView) throws SQLException {
						// (Select 1002 E1PK0, 7934 E1PK1, '2007-01-01' E1PK2, 7934 E2PK0 Union all
						Map<Column, Column> match = upkMatch(to);
						StringBuffer sb = new StringBuffer();
						for (Column column: universalPrimaryKey.getColumns()) {
							Column tableColumn = match.get(column);
							if (tableColumn != null) {
								if (sb.length() > 0) {
									sb.append(" and ");
								}
								sb.append("E2" + "." + "" + column.name);
								sb.append("=" + "E1E2" + ".E2" + column.name);
							}
						}
						String pkEqualsEntityID = sb.toString();
						String insert = "Insert into " + dmlTableReference(DEPENDENCY, localSession) + "(r_entitygraph, assoc, depend_id, from_type, to_type, " + upkColumnList(from, "FROM_") + ", " + upkColumnList(to, "TO_") + ") " +
								"Select " + graphID + ", " + aggregationId  + ", " + dependencyId + ", " + typeName(from) + ", " + typeName(to) + ", " + upkColumnList(from, "E1E2", "E1") + ", " + upkColumnList(to, "E1E2", "E2") +
								" From " + inlineView + ", " + dmlTableReference(ENTITY, localSession) + " E2" +
								" Where E2.r_entitygraph=" + graphID + "" +
								" and E2.type=" + typeName(to) + "" +
								" and " + pkEqualsEntityID;
						
							totalRowcount += localSession.executeUpdate(insert);
					}
				});
			}
		});
	}
	
	/**
	 * Gets distinct association-ids of all edged.
	 */
	@Override
	public Set<Integer> getDistinctDependencyIDs() throws SQLException {
		String select = "Select distinct depend_id from " + dmlTableReference(DEPENDENCY, localSession) + " Where r_entitygraph=" + graphID;
		final Set<Integer> ids = new HashSet<Integer>();
		localSession.executeQuery(select, new Session.ResultSetReader() {
			@Override
			public void readCurrentRow(ResultSet resultSet) throws SQLException {
				ids.add(resultSet.getInt(1));
			}
			@Override
			public void close() {
			}
		});
		return ids;
	}

	/**
	 * Marks all entities of a given table which don't dependent on other entities,
	 * s.t. they can be read and deleted.
	 */
	@Override
	public void markIndependentEntities(Table table) throws SQLException {
		StringBuffer fromEqualsPK = new StringBuffer();
		Map<Column, Column> match = upkMatch(table);
		for (Column column: universalPrimaryKey.getColumns()) {
			if (fromEqualsPK.length() > 0) {
				fromEqualsPK.append(" and ");
			}
			if (match.get(column) != null) {
				fromEqualsPK.append("D.FROM_" + column.name + "=" + dmlTableReference(ENTITY, localSession) + "." + column.name);
			} else {
				fromEqualsPK.append("D.FROM_" + column.name + " is null and " + dmlTableReference(ENTITY, localSession) + "." + column.name + " is null");
			}
		}
		localSession.executeUpdate(
				"Update " + dmlTableReference(ENTITY, localSession) + " set birthday=0 " +
				"Where r_entitygraph=" + graphID + " and birthday>0 and " +
					   (table != null? "type=" + typeName(table) + " and " : "") +
					   "not exists (Select * from " + dmlTableReference(DEPENDENCY, localSession) + " D " +
						   "Where D.r_entitygraph=" + graphID + " and D.assoc=0 and D.from_type=" + dmlTableReference(ENTITY, localSession) + ".type and " +
								 fromEqualsPK + ")");
	}

	private Map<Column, Column> upkMatch(Table table) {
		return universalPrimaryKey.match(rowIdSupport.getPrimaryKey(getDatamodel().getTable(table.getName())));
	}

	/**
	 * Marks all rows which are not target of a dependency.
	 */
	@Override
	public void markRoots(Table table) throws SQLException {
		StringBuffer toEqualsPK = new StringBuffer();
		Map<Column, Column> match = upkMatch(table);
		for (Column column: universalPrimaryKey.getColumns()) {
			if (toEqualsPK.length() > 0) {
				toEqualsPK.append(" and ");
			}
			if (match.containsKey(column)) {
				toEqualsPK.append("D.TO_" + column.name + "=" + dmlTableReference(ENTITY, localSession) + "." + column.name);
			} else {
				toEqualsPK.append("D.TO_" + column.name + " is null and " + dmlTableReference(ENTITY, localSession) + "." + column.name + " is null");
			}
		}
		localSession.executeUpdate(
				"Update " + dmlTableReference(ENTITY, localSession) + " set birthday=0 " +
				"Where r_entitygraph=" + graphID + " and birthday>0 and type=" + typeName(table) + " and " +
					   "not exists (Select * from " + dmlTableReference(DEPENDENCY, localSession) + " D " +
						   "Where D.r_entitygraph=" +graphID + " and D.to_type=" + dmlTableReference(ENTITY, localSession) + ".type and " +
								 toEqualsPK + ")");
	}

	/**
	 * Reads all entities of a given table which are marked as independent or as roots.
	 * 
	 * @param table the table
	 * @param orderByPK if <code>true</code>, result will be ordered by primary keys
	 */
	@Override
	public void readMarkedEntities(Table table, boolean orderByPK) throws SQLException {
		Session.ResultSetReader reader = getTransformerFactory().create(table);
		readMarkedEntities(table, reader, filteredSelectionClause(table), orderByPK);
	}
	
	/**
	 * Reads all entities of a given table which are marked as independent or as roots.
	 * 
	 * @param reader for reading the result-set
	 * @param table the table
	 * @param orderByPK if <code>true</code>, result will be ordered by primary keys
	 */
	private void readMarkedEntities(final Table table, final Session.ResultSetReader reader, final String selectionSchema, final boolean orderByPK) throws SQLException {
		String orderBy = "";
		String upkColumnList = upkColumnList(table, null, "");
		if (orderByPK) {
			orderBy = " order by " + upkColumnList;
		}
		
		String select = 
				"Select " + upkColumnList + " From " + dmlTableReference(ENTITY, localSession) + " E" +
				" Where E.birthday=0 and E.r_entitygraph=" + graphID + " and E.type=" + typeName(table) + "" +
				orderBy;
		
		localSession.executeQuery(select, new RemoteInlineViewBuilder("E", upkColumnList) {
			
			@Override
			protected void process(String inlineView) throws SQLException {
				String orderBy = "";
				if (orderByPK) {
					orderBy = " order by " + rowIdSupport.getPrimaryKey(table).columnList("T.", quoting);
				}
				
				long rc = remoteSession.executeQuery(
						"Select " + selectionSchema + " From " + inlineView + " join " + quoting.requote(table.getName()) + " T on " +
						pkEqualsEntityID(table, "T", "E", "", false) +
						orderBy,
						reader);
				executionContext.getProgressListenerRegistry().fireExported(table, rc);
				addExportedCount(rc);
			}
		});
	}
	
	/**
	 * Reads all entities of a given table which are marked as independent or as roots.
	 * 
	 * @param reader for reading the result-set
	 * @param table the table
	 * @param orderByPK if <code>true</code>, result will be ordered by primary keys
	 */
	@Override
	public void readMarkedEntities(final Table table, final Session.ResultSetReader reader, final String selectionSchema, final String originalPKAliasPrefix, final boolean orderByPK) throws SQLException {
		if (originalPKAliasPrefix == null) {
			readMarkedEntities(table, reader, selectionSchema, orderByPK);
			return;
		}
		
		String orderBy = "";
		String upkColumnList = upkColumnList(table, null, "");
		if (orderByPK) {
			orderBy = " order by " + upkColumnList;
		}
		
		String select = 
				"Select " + upkColumnList + " From " + dmlTableReference(ENTITY, localSession) + " E" +
				" Where E.birthday=0 and E.r_entitygraph=" + graphID + " and E.type=" + typeName(table) + "" +
				orderBy;
		
		localSession.executeQuery(select, new RemoteInlineViewBuilder("E", upkColumnList) {
			
			@Override
			protected void process(String inlineView) throws SQLException {
				String orderBy = "";
				if (orderByPK) {
					orderBy = " order by " + rowIdSupport.getPrimaryKey(table).columnList("T.", quoting);
				}
				
				StringBuffer sb = new StringBuffer();
				StringBuffer selectOPK = new StringBuffer();
				List<Column> pkColumns = rowIdSupport.getPrimaryKey(table).getColumns();
				for (int i = 0; i < pkColumns.size(); ++i) {
					if (i > 0) {
						sb.append(", ");
						selectOPK.append(", ");
					}
					sb.append(originalPKAliasPrefix + i);
					selectOPK.append("T." + quoting.requote(pkColumns.get(i).name) + " AS " + originalPKAliasPrefix + i);
				}

				String sqlQuery = "Select " + selectionSchema + " From (" +
						"Select " + selectOPK + ", " + filteredSelectionClause(table) + " From " + inlineView + " join " + quoting.requote(table.getName()) + " T on " +
						pkEqualsEntityID(table, "T", "E", "", false) +
						") T ";
				
				long rc = remoteSession.executeQuery(
						sqlQuery + orderBy,
						reader);
				executionContext.getProgressListenerRegistry().fireExported(table, rc);
				addExportedCount(rc);
			}
		});
	}
	
	/**
	 * Unites the graph with another one and deletes the other graph.
	 * 
	 * @param graph the graph to be united with this graph
	 */
	@Override
	public void uniteWith(EntityGraph graph) throws SQLException {
		StringBuffer e1EqualsE2 = new StringBuffer();
		for (Column column: universalPrimaryKey.getColumns()) {
			if (e1EqualsE2.length() > 0) {
				e1EqualsE2.append(" and ");
			}
			e1EqualsE2.append("E1." + column.name + "=E2." + column.name);
		}
		localSession.executeUpdate("Update " + dmlTableReference(ENTITY, localSession) + " E1 " +
				"set E1.r_entitygraph=" + graphID + " " +
				"Where E1.r_entitygraph=" + graph.graphID + " " +
				"and not exists(Select * from " + dmlTableReference(ENTITY, localSession) + " E2 Where " +
				"E2.r_entitygraph=" + graphID + " and " +
				e1EqualsE2 +
				")");
		graph.delete();
	}
	
	/**
	 * Reads all entities of a given table.
	 * 
	 * @param table the table
	 * @param orderByPK if <code>true</code>, result will be ordered by primary keys
	 */
	@Override
	public void readEntities(Table table, boolean orderByPK) throws SQLException {
		Session.ResultSetReader reader = getTransformerFactory().create(table);
		readEntities(table, orderByPK, reader, true);
	}

	/**
	 * Reads all entities of a given table.
	 * 
	 * @param table the table
	 * @param orderByPK if <code>true</code>, result will be ordered by primary keys
	 */
	private long readEntities(final Table table, final boolean orderByPK, final Session.ResultSetReader reader, final boolean fireProgressEvents) throws SQLException {
		String upkColumnList = upkColumnList(table, "E", null);
		String select = 
				"Select " + upkColumnList + " From " + dmlTableReference(ENTITY, localSession) + " E " +
				" Where E.birthday>=0 and E.r_entitygraph=" + graphID + " and E.type=" + typeName(table) + "";
		if (orderByPK) {
			select += " order by " + upkColumnList;
		}
		final long[] rc = new long[1];
		
		localSession.executeQuery(select, new RemoteInlineViewBuilder("E", upkColumnList(table, null, null)) {
			
			@Override
			protected void process(String inlineView) throws SQLException {
				String sqlQuery = "Select " + filteredSelectionClause(table) + " From " + inlineView + " join " + quoting.requote(table.getName()) + " T on " +
						pkEqualsEntityID(table, "T", "E", "", false);
				long lrc;
				if (orderByPK) {
					String sqlQueryWithOrderBy = sqlQuery +
						" order by " + rowIdSupport.getPrimaryKey(table).columnList("T.", quoting);
					lrc = remoteSession.executeQuery(sqlQueryWithOrderBy, reader, sqlQuery, null, 0);
				} else {
					lrc = remoteSession.executeQuery(sqlQuery, reader);
				}
				rc[0] += lrc;
				if (fireProgressEvents) {
					executionContext.getProgressListenerRegistry().fireExported(table, lrc);
					addExportedCount(lrc);
				}
			}
		});
		return rc[0];
	}

	/**
	 * Reads some columns of all entities of a given table without using filters.
	 * 
	 * @param table the table
	 * @param columns the columns
	 * @param reader to read
	 */
	@Override
	public long readUnfilteredEntityColumns(final Table table, final List<Column> columns, final Session.ResultSetReader reader) throws SQLException {
		String upkColumnList = upkColumnList(table, "E", null);
		String select = 
				"Select distinct " + upkColumnList + " From " + dmlTableReference(ENTITY, localSession) + " E " +
				" Where E.birthday>=0 and E.r_entitygraph=" + graphID + " and E.type=" + typeName(table) + "";
		
		StringBuilder sb = new StringBuilder();
		boolean first = true;
		
		for (Column c: columns) {
			if (!first) {
				sb.append(", ");
			}
			sb.append("T." + quoting.requote(c.name));
			sb.append(" as " + quoting.requote(c.name));
			first = false;
		}
		
		final String columnList = sb.toString();
		final long[] rc = new long[1];
		
		localSession.executeQuery(select, new RemoteInlineViewBuilder("E", upkColumnList(table, null, null)) {
			
			@Override
			protected void process(String inlineView) throws SQLException {
				String sqlQuery = "Select distinct " + columnList + " From " + inlineView + " join " + quoting.requote(table.getName()) + " T on " +
						pkEqualsEntityID(table, "T", "E", "", false);
				rc[0] += remoteSession.executeQuery(sqlQuery, reader);
			}
		});
		return rc[0];
	}

	/**
	 * Updates columns of a table.
	 * 
	 * @param table the table
	 * @param columns the columns;
	 * @param reason to be written as comment
	 */
	@Override
	public void updateEntities(Table table, Set<Column> columns, OutputStreamWriter scriptFileWriter, DBMS targetConfiguration, String reason) throws SQLException {
		Session.ResultSetReader reader = new UpdateTransformer(table, columns, scriptFileWriter, executionContext.getNumberOfEntities(), getTargetSession(), targetConfiguration, importFilterManager, reason, executionContext);
		readEntities(table, false, reader, false);
	}

	/**
	 * Gets select clause for reading rows of given type
	 * with respect of the column filters.
	 * 
	 * @param table the table to read rows from
	 * @return select clause
	 */
	private String filteredSelectionClause(Table table) {
		StringBuilder sb = new StringBuilder();
		boolean first = true;
		
		for (Column c: table.getSelectionClause()) {
			if (!first) {
				sb.append(", ");
			}
			String filterExpression = null;
			if (c.getFilter() != null && c.getFilter().isApplyAtExport()) {
				filterExpression = c.getFilterExpression();
			}
			if (filterExpression != null) {
				if (filterExpression.trim().toLowerCase().startsWith("select")) {
					sb.append("(" + filterExpression + ")");
				} else {
					sb.append(filterExpression);
				}
			} else {
				sb.append("T." + quoting.requote(c.name));
			}
			sb.append(" as " + quoting.requote(c.name));
			first = false;
		}
		
		return sb.toString();
	}

	/**
	 * Deletes all entities which are marked as independent.
	 */
	@Override
	public void deleteIndependentEntities(Table table) throws SQLException {
		StringBuffer fromEqualsPK = new StringBuffer();
		StringBuffer toEqualsPK = new StringBuffer();
		Map<Column, Column> match = upkMatch(table);
		for (Column column: universalPrimaryKey.getColumns()) {
			if (fromEqualsPK.length() > 0) {
				fromEqualsPK.append(" and ");
			}
			if (match.containsKey(column)) {
				fromEqualsPK.append(dmlTableReference(DEPENDENCY, localSession) + ".FROM_" + column.name + "=" + column.name);
			} else {
				fromEqualsPK.append(dmlTableReference(DEPENDENCY, localSession) + ".FROM_" + column.name + " is null and " + column.name + " is null");
			}
			if (toEqualsPK.length() > 0) {
				toEqualsPK.append(" and ");
			}
			if (match.containsKey(column)) {
				toEqualsPK.append(dmlTableReference(DEPENDENCY, localSession) + ".TO_" + column.name + "=" + column.name);
			} else {
				toEqualsPK.append(dmlTableReference(DEPENDENCY, localSession) + ".TO_" + column.name + " is null and " + column.name + " is null");
			}
		}
		localSession.executeUpdate(
				"Delete From " + dmlTableReference(DEPENDENCY, localSession) + " " +
				"Where " + dmlTableReference(DEPENDENCY, localSession) + ".r_entitygraph=" + graphID + " and assoc=0 and from_type=" + typeName(table) + " and " + 
					  "exists (Select * from " + dmlTableReference(ENTITY, localSession) + " E Where " + 
						  "E.r_entitygraph=" + graphID + " and " +
						  fromEqualsPK + " and " + dmlTableReference(DEPENDENCY, localSession) + ".from_type=E.type and " +
						  "E.birthday=0)");
		localSession.executeUpdate(
				"Delete From " + dmlTableReference(DEPENDENCY, localSession) + " " +
				"Where " + dmlTableReference(DEPENDENCY, localSession) + ".r_entitygraph=" + graphID + " and assoc=0 and to_type=" + typeName(table) + " and " +
					  "exists (Select * from " + dmlTableReference(ENTITY, localSession) + " E Where " + 
						  "E.r_entitygraph=" + graphID + " and " +
						  toEqualsPK + " and " + dmlTableReference(DEPENDENCY, localSession) + ".to_type=E.type and " +
						  "E.birthday=0)");
		localSession.executeUpdate(
				"Delete From " + dmlTableReference(ENTITY, localSession) + " " +
				"Where r_entitygraph=" + graphID + " and type=" + typeName(table) + " and " +
					   "birthday=0");
	}
	
	/**
	 * Deletes all entities from a given table.
	 */
	@Override
	public long deleteEntities(Table table) throws SQLException {
		return localSession.executeUpdate(
				"Delete From " + dmlTableReference(ENTITY, localSession) + " " +
				"Where r_entitygraph=" + graphID + " and " +
					   "type=" + typeName(table));
	}

	/**
	 * Counts the entities of a given table in this graph.
	 * 
	 * @param table the table
	 * @return the number of entities from table in this graph
	 */
	@Override
	public long countEntities(Table table) throws SQLException {
		final long[] count = new long[1];
		localSession.executeQuery(
				"Select count(*) from " + dmlTableReference(ENTITY, localSession) + " E " +
				"Where E.birthday>=0 and E.r_entitygraph=" + graphID + " and E.type=" + typeName(table) + "",
				new Session.AbstractResultSetReader() {
					@Override
					public void readCurrentRow(ResultSet resultSet) throws SQLException {
						count[0] = resultSet.getLong(1);
					}
				});
		return count[0];
	}

	/**
	 * Removes all entities from this graph which are associated with an entity
	 * outside the graph.
	 * 
	 * @param deletedEntitiesAreMarked if true, consider entity as deleted if its birthday is negative
	 * @param association the association
	 * @return number of removed entities
	 */
	@Override
	public long removeAssociatedDestinations(final Association association, final boolean deletedEntitiesAreMarked) throws SQLException {
		final String jc = association.getJoinCondition();
		checkPseudoColumns(association.source, jc);
		if (jc != null) {
			final String destAlias;
			final String sourceAlias;
			if (association.reversed) {
				destAlias = "A";
				sourceAlias = "B";
			} else {
				destAlias = "B";
				sourceAlias = "A";
			}
			final int setId = getNextSetId();
			
			final long[] rc = new long[1];

			String selectEB = 
					"Select " + upkColumnList(association.destination, "EB", "") + " from " + dmlTableReference(ENTITY, localSession) + " EB " +
					"Where " + (deletedEntitiesAreMarked? "EB.birthday>=0 and " : "") +
					"EB.r_entitygraph=" + graphID + " and EB.type=" + typeName(association.destination) + " ";
		
			localSession.executeQuery(selectEB, new RemoteInlineViewBuilder("EB", upkColumnList(association.destination, ""), true) {
				
				@Override
				protected void process(String inlineView) throws SQLException {
				
					String sep = upkMatch(association.source).isEmpty()? "" : ", ";
					
					String selectSource =
							"Select distinct " + upkColumnList(association.destination, "EB", "") + sep + pkList(association.source, sourceAlias, "") + " from " + inlineView + " " +
									"join " + quoting.requote(association.destination.getName()) + " " + destAlias + " on "+ pkEqualsEntityID(association.destination, destAlias, "EB", "", false) + " " +
									"join " + quoting.requote(association.source.getName()) + " " + sourceAlias + " " + " on " + jc;

					remoteSession.executeQuery(selectSource, new LocalInlineViewBuilder("EBA", upkColumnList(association.destination, null, "EB") + sep + upkColumnList(association.source, "A"), true) {
						
						@Override
						protected void process(String inlineView) throws SQLException {
							Map<Column, Column> match = upkMatch(association.source);
							StringBuffer eBAEqualsEA = new StringBuffer();
							for (Column column: universalPrimaryKey.getColumns()) {
								Column tableColumn = match.get(column);
								if (tableColumn != null) {
									if (eBAEqualsEA.length() > 0) {
										eBAEqualsEA.append(" and ");
									}
									eBAEqualsEA.append("EBA.A" + column.name);
									eBAEqualsEA.append("=EA." + column.name);
								}
							}
							
							String selectEB =
									"Select distinct " + setId + ", " + typeName(association.destination) + ", " + upkColumnList(association.destination, "EBA", "EB") + 
									" from " + inlineView + (deletedEntitiesAreMarked? " join " : " left join ") + dmlTableReference(ENTITY, localSession) + " EA" + 
									" on EA.r_entitygraph=" + graphID + " and EA.type=" + typeName(association.source) + "" +
									(eBAEqualsEA.length() > 0? " and " + eBAEqualsEA : "") +
									" Where " + (deletedEntitiesAreMarked? "EA.birthday=-1" : "EA.type is null");
									
							String remove = "Insert into " + dmlTableReference(ENTITY_SET_ELEMENT, localSession) + 
									"(set_id, type, " + upkColumnList(association.destination, null, "") + ") " +
									selectEB;
							
							long rcl = localSession.executeUpdate(remove);
							totalRowcount += rcl;
							if (rcl > 0) {
								match = upkMatch(association.destination);
								StringBuffer sEqualsE = new StringBuffer();
								StringBuffer sEqualsEWoAlias = new StringBuffer();
								for (Column column: universalPrimaryKey.getColumns()) {
									if (sEqualsE.length() > 0) {
										sEqualsE.append(" and ");
									}
									if (sEqualsEWoAlias.length() > 0) {
										sEqualsEWoAlias.append(" and ");
									}
									if (match.containsKey(column)) {
										sEqualsE.append("S." + column.name + "=E." + column.name);
										sEqualsEWoAlias.append("S." + column.name + "=" + dmlTableReference(ENTITY, localSession) + "." + column.name);
									} else {
										sEqualsE.append("S." + column.name + " is null and E." + column.name + " is null");
										sEqualsEWoAlias.append("S." + column.name + " is null and " + dmlTableReference(ENTITY, localSession) + "." + column.name + " is null");
									}
								}
								remove = "Update " + dmlTableReference(ENTITY, localSession) + " E set E.birthday=-1 Where E.r_entitygraph=" + graphID + " and E.type=" + typeName(association.destination) + " " +
										  "and exists (Select * from " + dmlTableReference(ENTITY_SET_ELEMENT, localSession) + " S where S.set_id=" + setId + " and E.type=S.type and " + sEqualsE + ") " +
										  "and E.birthday<>-1";
								boolean silent = localSession.getSilent();
								try {
									localSession.setSilent(true);
									long r = localSession.executeUpdate(remove);
									rc[0] += r;
								} catch (SQLException e) {
									// postgreSQL
									Session._log.debug("failed, retry without alias (" + e.getMessage() + ")");
									remove = "Update " + dmlTableReference(ENTITY, localSession) + " set birthday=-1 Where " + dmlTableReference(ENTITY, localSession) + ".r_entitygraph=" + graphID + " and " + dmlTableReference(ENTITY, localSession) + ".type=" + typeName(association.destination) + " " +
									"and exists (Select * from " + dmlTableReference(ENTITY_SET_ELEMENT, localSession) + " S where S.set_id=" + setId + " and " + dmlTableReference(ENTITY, localSession) + ".type=S.type and " + sEqualsEWoAlias + ") " +
									"and " + dmlTableReference(ENTITY, localSession) + ".birthday<>-1";
									rc[0] += localSession.executeUpdate(remove);
								} finally {
									localSession.setSilent(silent);
								}
								localSession.executeUpdate("Delete from " + dmlTableReference(ENTITY_SET_ELEMENT, localSession) + " where set_id=" + setId + "");
							}
						}
					});
				}
			});
			return rc[0];
		}
		
		return 0;
	}
	
	/**
	 * Reads all entities which depends on given entity. 
	 * 
	 * @param table the table from which to read entities
	 * @param association the dependency
	 * @param resultSet current row is given entity
	 * @param reader reads the entities
	 * @param selectionSchema the selection schema
	 */
	@Override
	public void readDependentEntities(final Table table, final Association association, final ResultSet resultSet, ResultSetMetaData resultSetMetaData, final ResultSetReader reader, final Map<String, Integer> theTypeCache, final String selectionSchema, final String originalPKAliasPrefix) throws SQLException {
		CellContentConverter cellContentConverter = new CellContentConverter(resultSetMetaData, localSession, localSession.dbms);
		String select = "Select " + upkColumnList(table, "TO_") + " from " + dmlTableReference(DEPENDENCY, localSession) + " D" +
				 " Where " + pkEqualsEntityID(association.source, resultSet, "D", "FROM_", cellContentConverter) +
				 " and D.to_type=" + typeName(table) + "" +
				 " and D.from_type=" + typeName(association.source) + " and assoc=" + association.getId() +
				 " and D.r_entitygraph=" + graphID;

		localSession.executeQuery(select, new RemoteInlineViewBuilder("D", upkColumnList(table, "TO_"), true) {
			@Override
			protected void process(String inlineView) throws SQLException {
				String select;
				if (originalPKAliasPrefix != null) {
					StringBuffer selectOPK = new StringBuffer();
					List<Column> pkColumns = rowIdSupport.getPrimaryKey(table).getColumns();
					for (int i = 0; i < pkColumns.size(); ++i) {
						if (i > 0) {
							selectOPK.append(", ");
						}
						selectOPK.append("T." + quoting.requote(pkColumns.get(i).name) + " AS " + originalPKAliasPrefix + i);
					}
					select = 
						"Select " + selectionSchema + " from (" +  
						"Select " + selectOPK + ", " + filteredSelectionClause(table) + " from " + quoting.requote(table.getName()) + " T join " + inlineView + " on " +
						 pkEqualsEntityID(table, "T", "D", "TO_", false) + ") T";
				} else {
					select = "Select " + selectionSchema + " from " + quoting.requote(table.getName()) + " T join " + inlineView + " on " +
						 pkEqualsEntityID(table, "T", "D", "TO_", false) + "";
				}
				long rc = remoteSession.executeQuery(select, reader);
				executionContext.getProgressListenerRegistry().fireExported(table, rc);
				addExportedCount(rc);
			}
		});
	}
	
	/**
	 * Marks all entities which depends on given entity as traversed. 
	 * 
	 * @param table the table from which to read entities
	 * @param association the dependency
	 * @param resultSet current row is given entity
	 */
	@Override
	public void markDependentEntitiesAsTraversed(Association association, ResultSet resultSet, ResultSetMetaData resultSetMetaData, Map<String, Integer> typeCache) throws SQLException {
		String update;
		CellContentConverter cellContentConverter = new CellContentConverter(resultSetMetaData, localSession, localSession.dbms);
		if (DBMS.SYBASE.equals(localSession.dbms)) {
			update = "Update " + dmlTableReference(DEPENDENCY, localSession) + " set traversed=1" +
			 " Where " + pkEqualsEntityID(association.source, resultSet, dmlTableReference(DEPENDENCY, localSession), "FROM_", cellContentConverter) +
			 " and " + dmlTableReference(DEPENDENCY, localSession) + ".from_type=" + typeName(association.source) + " and assoc=" + association.getId() +
			 " and " + dmlTableReference(DEPENDENCY, localSession) + ".r_entitygraph=" + graphID;
		} else {
			update = "Update " + dmlTableReference(DEPENDENCY, localSession) + " D set traversed=1" +
			 " Where " + pkEqualsEntityID(association.source, resultSet, "D", "FROM_", cellContentConverter) +
			 " and D.from_type=" + typeName(association.source) + " and assoc=" + association.getId() +
			 " and D.r_entitygraph=" + graphID;
		}
		localSession.executeUpdate(update);
	}
	
	/**
	 * Reads all non-traversed dependencies. 
	 * 
	 * @param table the source of dependencies to look for
	 * @param reader reads the entities
	 */
	@Override
	public void readNonTraversedDependencies(Table table, ResultSetReader reader) throws SQLException {
		String select = "Select * from " + dmlTableReference(DEPENDENCY, localSession) + " D " +
			 " Where (traversed is null or traversed <> 1)" +
			 " and D.from_type=" + typeName(table) + "" +
			 " and D.r_entitygraph=" + graphID;
		localSession.executeQuery(select, reader);
	}
	
	/**
	 * Removes all reflexive dependencies of given table.
	 * 
	 * @param table the table
	 */
	@Override
	public void removeReflexiveDependencies(Table table) throws SQLException {
		Map<Column, Column> match = upkMatch(table);
		StringBuffer sb = new StringBuffer();
		for (Column column: universalPrimaryKey.getColumns()) {
			Column tableColumn = match.get(column);
			if (tableColumn != null) {
				if (sb.length() > 0) {
					sb.append(" and ");
				}
				sb.append("FROM_" + column.name + " = TO_" + column.name);
			}
		}
		String delete = "Delete from " + dmlTableReference(DEPENDENCY, localSession) +
			" Where " + sb +
			" and from_type=" + typeName(table) + "" +
			" and to_type=" + typeName(table) + "" +
			" and r_entitygraph=" + graphID;
		localSession.executeUpdate(delete);
	}

	/**
	 * Gets a SQL comparison expression for comparing rows with given entity.
	 * 
	 * @param table the table
	 * @param resultSet
	 * @return a SQL comparison expression for comparing rows of <code>table</code> with current row of resultSet
	 */
	private String pkEqualsEntityID(Table table, ResultSet resultSet, String alias, String columnPrefix, CellContentConverter cellContentConverter) throws SQLException {
		Map<Column, Column> match = upkMatch(table);
		StringBuffer sb = new StringBuffer();
		for (Column column: universalPrimaryKey.getColumns()) {
			if (sb.length() > 0) {
				sb.append(" and ");
			}
			sb.append(alias + "." + columnPrefix + column.name);
			Column tableColumn = match.get(column);
			if (tableColumn != null) {
				int i = 0;
				for (Column c: rowIdSupport.getPrimaryKey(table).getColumns()) {
					if (c.name.equals(tableColumn.name)) {
						break;
					}
					++i;
				}
				sb.append("=" + cellContentConverter.toSql(cellContentConverter.toSql(cellContentConverter.getObject(resultSet, "PK" + i))));
			} else {
				sb.append(" is null");
			}
		}
		return sb.toString();
	}

	private final Set<String> fieldProcTables = new HashSet<String>();
	
	/**
	 * Gets a SQL comparison expression for comparing rows with entities.
	 * 
	 * @param table the table
	 * @return a SQL comparison expression for comparing rows of <code>table</code> with entities
	 */
	private String pkEqualsEntityID(Table table, String tableAlias, String entityAlias, String columnPrefix, boolean checkNull) {
		Map<Column, Column> match = upkMatch(table);
		StringBuffer sb = new StringBuffer();
		for (Column column: universalPrimaryKey.getColumns()) {
			Column tableColumn = match.get(column);
			if (checkNull || tableColumn != null) {
				if (sb.length() > 0) {
					sb.append(" and ");
				}
				sb.append(entityAlias + "." + columnPrefix + column.name);
				if (tableColumn != null) {
					if (fieldProcTables.contains(table.getUnqualifiedName().toLowerCase())) {
						sb.append(" = " + tableColumn.type + "(" + tableAlias + "." + quoting.requote(tableColumn.name) + ")");
					} else {
						sb.append("=" + tableAlias + "." + quoting.requote(tableColumn.name));
					}
				} else {
					sb.append(" is null");
				}
			}
		}
		return sb.toString();
	}

	/**
	 * Gets PK-column list for a table.
	 * 
	 * @param table the table
	 * @param tableAlias the alias for table
	 * @return PK-column list for table
	 */
	private String pkList(Table table, String tableAlias) {
		return pkList(table, tableAlias, null);
	}
	
	/**
	 * Gets PK-column list for a table. (for Select clause)
	 * 
	 * @param table the table
	 * @param tableAlias the alias for table
	 * @param columnAliasPrefix optional prefix for column names
	 */
	private String pkList(Table table, String tableAlias, String columnAliasPrefix) {
		Map<Column, Column> match = upkMatch(table);
		StringBuffer sb = new StringBuffer();
		for (Column column: universalPrimaryKey.getColumns()) {
			Column tableColumn = match.get(column);
			if (tableColumn != null) {
				if (sb.length() > 0) {
					sb.append(", ");
				}
				if (tableAlias != null) {
					sb.append(tableAlias + ".");
				}
				sb.append(quoting.requote(tableColumn.name));
				sb.append(" AS " + (columnAliasPrefix == null? "" : columnAliasPrefix) + column.name);
		   }
		}
		return sb.toString();
	}

	/**
	 * Gets PK-column list for a table. (for Insert clause)
	 * 
	 * @param table the table
	 * @param columnAliasPrefix optional prefix for column names
	 */
	private String upkColumnList(Table table, String columnAliasPrefix) {
		return upkColumnList(table, null, columnAliasPrefix);
	}

	/**
	 * Gets PK-column list for a table. (for Insert clause)
	 * 
	 * @param table the table
	 * @param columnAliasPrefix optional prefix for column names
	 */
	private String upkColumnList(Table table, String tableAlias, String columnAliasPrefix) {
		Map<Column, Column> match = upkMatch(table);
		StringBuffer sb = new StringBuffer();
		for (Column column: universalPrimaryKey.getColumns()) {
			Column tableColumn = match.get(column);
			if (tableColumn != null) {
				if (sb.length() > 0) {
					sb.append(", ");
				}
				if (tableAlias != null) {
					sb.append(tableAlias + ".");
				}
				if (columnAliasPrefix != null) {
					sb.append(columnAliasPrefix);
				}
				sb.append(column.name);
			}
		}
		return sb.toString();
	}

	/**
	 * Total row-count.
	 */
	private long totalRowcount = 0;

	/**
	 * Gets total row-count.
	 * 
	 * @return total row-count
	 */
	@Override
	public long getTotalRowcount() {
		return totalRowcount;
	}

	/**
	 * Whether or not to store additional information in order to create a 'explain.log'
	 */
	private boolean explain = false;

	/**
	 * Next unique ID for association to be used for explanation.
	 */
	private int nextExplainID = 1;
	
	/**
	 * Whether or not to store additional information in order to create a 'explain.log'.
	 * 
	 * @param explain <code>true</code> iff predecessors of each entity must be stored
	 */
	@Override
	public void setExplain(boolean explain) {
		this.explain = false; // explain feature is not yet implemented for local entity graph
	}

	/**
	 * Gets the universal primary key.
	 * 
	 * @return the universal primary key
	 */
	@Override
	public PrimaryKey getUniversalPrimaryKey() {
		return universalPrimaryKey;
	}

	/**
	 * For creation of unique set-ids.
	 */
	private int nextSetId = 1;
	
	/**
	 * Creates a unique set id.
	 * 
	 * @return a unique set id
	 */
	private synchronized int getNextSetId() {
		return graphID + (nextSetId++);
	}

	/**
	 * Shuts down statement-executor.
	 */
	@Override
	public void shutDown() throws SQLException {
		remoteSession.shutDown();
	}

	@Override
	public Session getSession() {
		return localSession;
	}

	@Override
	public DataModel getDatamodel() {
		return dataModel;
	}

	@Override
	public Session getTargetSession() {
		return remoteSession;
	}

}
