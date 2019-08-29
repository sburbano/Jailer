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
package net.sf.jailer.ui.databrowser.metadata;

import java.lang.reflect.Method;
import java.sql.DatabaseMetaData;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JComponent;

import org.apache.log4j.Logger;

import net.sf.jailer.ExecutionContext;
import net.sf.jailer.database.Session;
import net.sf.jailer.datamodel.DataModel;
import net.sf.jailer.modelbuilder.MemorizedResultSet;

/**
 * Information about the database.
 * 
 * @author Ralf Wisser
 */
public class MDDatabase extends MDGeneric {

	/**
	 * The logger.
	 */
	private static final Logger logger = Logger.getLogger(MDDatabase.class);

	private final DataModel dataModel;

	/**
	 * Constructor.
	 * 
	 * @param name the object name
	 */
	public MDDatabase(String name, MetaDataSource metaDataSource, DataModel dataModel, ExecutionContext executionContext) {
		super(name, metaDataSource);
		this.dataModel = dataModel;
	}

	/**
	 * Gets the render of the database object.
	 * 
	 * @return render of the database object
	 */
	@Override
	public JComponent createRender(Session session, ExecutionContext executionContext) throws Exception {
        List<Object[]> rowList = new ArrayList<Object[]>();
        
        DatabaseMetaData md = getMetaDataSource().getSession().getMetaData();
        String[] names = new String[] {
	        "getURL",
	        "getUserName",
	        "isReadOnly",
	        "getDatabaseMajorVersion",
	        "getDatabaseMinorVersion",
	        "getDatabaseProductName",
	        "getDatabaseProductVersion",
	        "getDriverName",
	        "getDriverVersion",
	        "getDriverMajorVersion",
	        "getDriverMinorVersion",
	        "getIdentifierQuoteString",
	        "getCatalogSeparator",
	        "getCatalogTerm",
	        "getSchemaTerm",
	        "getProcedureTerm",
	        "getJDBCMajorVersion",
	        "getJDBCMinorVersion",
	        "getResultSetHoldability",
	        "supportsNamedParameters",
	        "supportsMultipleOpenResults",
	        "supportsGetGeneratedKeys",
	        "getSQLStateType",
	        "locatorsUpdateCopy",
	        "supportsStatementPooling",
	        "generatedKeyAlwaysReturned",
	        "getRowIdLifetime",
	        "supportsCatalogsInTableDefinitions",
	        "supportsSchemasInTableDefinitions",
	        "supportsMixedCaseQuotedIdentifiers",
	        "storesUpperCaseQuotedIdentifiers",
	        "storesLowerCaseQuotedIdentifiers",
	        "storesMixedCaseQuotedIdentifiers",
	        "supportsAlterTableWithAddColumn",
	        "supportsAlterTableWithDropColumn",
	        "supportsTableCorrelationNames",
	        "supportsDifferentTableCorrelationNames",
	        "supportsANSI92IntermediateSQL",
	        "supportsIntegrityEnhancementFacility",
	        "supportsSchemasInProcedureCalls",
	        "supportsSchemasInIndexDefinitions",
	        "supportsSchemasInPrivilegeDefinitions",
	        "supportsCatalogsInIndexDefinitions",
	        "supportsCatalogsInPrivilegeDefinitions",
	        "supportsSubqueriesInComparisons",
	        "supportsSubqueriesInQuantifieds",
	        "supportsOpenCursorsAcrossCommit",
	        "supportsOpenCursorsAcrossRollback",
	        "supportsOpenStatementsAcrossCommit",
	        "supportsOpenStatementsAcrossRollback",
	        "getDefaultTransactionIsolation",
	        "supportsDataManipulationTransactionsOnly",
	        "dataDefinitionCausesTransactionCommit",
	        "dataDefinitionIgnoredInTransactions",
	        "autoCommitFailureClosesAllResultSets",
	        "supportsStoredFunctionsUsingCallSyntax",
	        "supportsCatalogsInProcedureCalls",
	        "storesMixedCaseIdentifiers",
	        "storesUpperCaseIdentifiers",
	        "supportsSavepoints",
	        "supportsMultipleResultSets",
	        "allProceduresAreCallable",
	        "allTablesAreSelectable",
	        "nullsAreSortedHigh",
	        "nullsAreSortedLow",
	        "nullsAreSortedAtStart",
	        "nullsAreSortedAtEnd",
	        "usesLocalFiles",
	        "usesLocalFilePerTable",
	        "supportsMixedCaseIdentifiers",
	        "storesLowerCaseIdentifiers",
	        "getSearchStringEscape",
	        "getExtraNameCharacters",
	        "supportsColumnAliasing",
	        "nullPlusNonNullIsNull",
	        "supportsConvert",
	        "supportsExpressionsInOrderBy",
	        "supportsOrderByUnrelated",
	        "supportsGroupBy",
	        "supportsGroupByUnrelated",
	        "supportsGroupByBeyondSelect",
	        "supportsLikeEscapeClause",
	        "supportsMultipleTransactions",
	        "supportsNonNullableColumns",
	        "supportsMinimumSQLGrammar",
	        "supportsCoreSQLGrammar",
	        "supportsExtendedSQLGrammar",
	        "supportsANSI92EntryLevelSQL",
	        "supportsANSI92FullSQL",
	        "supportsOuterJoins",
	        "supportsFullOuterJoins",
	        "supportsLimitedOuterJoins",
	        "isCatalogAtStart",
	        "supportsPositionedDelete",
	        "supportsPositionedUpdate",
	        "supportsSelectForUpdate",
	        "supportsSubqueriesInExists",
	        "supportsSubqueriesInIns",
	        "supportsCorrelatedSubqueries",
	        "supportsUnion",
	        "supportsUnionAll",
	        "getMaxBinaryLiteralLength",
	        "getMaxCharLiteralLength",
	        "getMaxColumnNameLength",
	        "getMaxColumnsInGroupBy",
	        "getMaxColumnsInIndex",
	        "getMaxColumnsInOrderBy",
	        "getMaxColumnsInSelect",
	        "getMaxColumnsInTable",
	        "getMaxConnections",
	        "getMaxCursorNameLength",
	        "getMaxIndexLength",
	        "getMaxSchemaNameLength",
	        "getMaxProcedureNameLength",
	        "getMaxCatalogNameLength",
	        "getMaxRowSize",
	        "doesMaxRowSizeIncludeBlobs",
	        "getMaxStatementLength",
	        "getMaxStatements",
	        "getMaxTableNameLength",
	        "getMaxTablesInSelect",
	        "getMaxUserNameLength",
	        "supportsTransactions",
	        "supportsBatchUpdates",
	        "supportsSchemasInDataManipulation",
	        "supportsCatalogsInDataManipulation",
	        "supportsStoredProcedures"
	    };

        for (String name: names) {
        	try {
        		Method m = md.getClass().getMethod(name);
        		rowList.add(new Object[] { name.startsWith("get")? name.substring(3) : name, m.invoke(md) });
        	} catch (Throwable t) {
        		logger.info("error", t);
        	}
        }

        MemorizedResultSet rs = new MemorizedResultSet(rowList, 2, new String[] { "Property", "Value" }, new int[] { Types.VARCHAR, Types.VARCHAR });
        return new ResultSetRenderer(rs, getName(), dataModel, getMetaDataSource().getSession(), executionContext);
	}

}
