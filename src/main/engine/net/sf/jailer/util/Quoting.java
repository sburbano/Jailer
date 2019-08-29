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
package net.sf.jailer.util;

import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.sf.jailer.ExecutionContext;
import net.sf.jailer.configuration.Configuration;
import net.sf.jailer.database.Session;
import net.sf.jailer.datamodel.Table;

/**
 * Quotes and un-quotes SQL identifier in a DBMS specific way.
 * 
 * @author Ralf Wisser
 */
public class Quoting {

	/**
	 * The quote character, null if quoting is not supported.
	 */
	private String quote = null;

	/**
	 * Whether the database treats mixed case unquoted SQL identifiers as case
	 * insensitive and stores them in upper case.
	 */
	private final boolean unquotedIdentifierInUpperCase;

	/**
	 * Whether the database treats mixed case unquoted SQL identifiers as case
	 * insensitive and stores them in mixed case.
	 */
	private final boolean unquotedIdentifierInMixedCase;

	/**
	 * All SQL keywords for each DBMS.
	 */
	private Map<String, Set<String>> keyWordsMap = new HashMap<String, Set<String>>(); 

	/**
	 * All SQL keywords for this DBMS.
	 */
	private Set<String> keyWords = new HashSet<String>(); 

	/**
	 * Constructor.
	 * 
	 * @param session the database session
	 */
	public Quoting(Session session) throws SQLException {
		DatabaseMetaData metaData = session.getMetaData();
		String quoteString = metaData.getIdentifierQuoteString();
		if (quoteString != null
				&& (quoteString.equals(" ") || quoteString.equals(""))) {
			quoteString = null;
		}
		try {
			String productName = metaData.getDatabaseProductName();
			if (productName != null) {
				if (productName.toUpperCase().contains("ADAPTIVE SERVER")) {
					// Sybase don't handle quoting correctly
					quoteString = null;
				  }
			}
		} catch (Exception e) {
			// ignore
		}
		quote = quoteString;
		unquotedIdentifierInUpperCase = metaData.storesUpperCaseIdentifiers();
		
		if (session.dbUrl != null && session.dbUrl.toLowerCase().startsWith("jdbc:jtds:")) {
			// workaround for JTDS-bug
			unquotedIdentifierInMixedCase = true;
		} else {
			unquotedIdentifierInMixedCase = metaData.storesMixedCaseIdentifiers();
		}
		
		String k = metaData.getSQLKeywords();
		if (k == null) {
			k = "";
		}
		keyWords = keyWordsMap.get(k);
		if (keyWords == null) {
			keyWords = new HashSet<String>();
			String additionalKeyWords = Configuration.getInstance().getAdditionalSQLKeywords();
			if (additionalKeyWords != null) {
				k += "," + additionalKeyWords;
			}
			for (String key : k.split(",")) {
				keyWords.add(key.trim().toUpperCase());
			}
			// add all SQL 92 keywords
			keyWords.addAll(UCSQL2003KEYWORDS);
			keyWordsMap.put(k, keyWords);
		}
	}

	/**
	 * Quotes an identifier.
	 * 
	 * @param identifier
	 *            the identifier
	 * @return quoted identifier
	 */
	public String quote(String identifier) {
		if (identifier != null) {
			identifier = identifier.trim();
		}
		identifier = unquote(identifier);
		if (quote != null && identifier != null && identifier.length() > 0) {
			if (!keyWords.contains(identifier.toUpperCase())) {
				String lower = "abcdefghijklmnopqrstuvwxyz_0123456789";
				String upper = "ABCDEFGHIJKLMNOPQRSTUVWXYZ_0123456789";
				String digits = "0123456789";
				boolean allUpperCase = true;
				boolean allLowerCase = true;
				boolean allLetters = true;
				for (int i = identifier.length() - 1; i >= 0; --i) {
					char c = identifier.charAt(i);
					if (lower.indexOf(c) < 0) {
						allLowerCase = false;
					}
					if (upper.indexOf(c) < 0) {
						allUpperCase = false;
					}
					if (lower.indexOf(c) < 0 && upper.indexOf(c) < 0 && digits.indexOf(c) < 0) {
						allLetters = false;
					}
				}
				if (digits.indexOf(identifier.charAt(0)) < 0) {
					if (unquotedIdentifierInMixedCase && allLetters) {
						return identifier;
					}
					if (unquotedIdentifierInUpperCase && allUpperCase) {
						return identifier;
					}
					if ((!unquotedIdentifierInUpperCase) && allLowerCase) {
						return identifier;
					}
				}
			}
			return quote + identifier + quote;
		}
		return identifier;
	}

	/**
	 * Checks if an identifier is quoted.
	 * 
	 * @param identifier the identifier
	 * @return <code>true</code> if identifier is quoted
	 */
	public boolean isQuoted(String identifier) {
		return isQuoted(identifier, quote);
	}

	/**
	 * Remove quotes from identifier.
	 * 
	 * @param identifier the identifier
	 * @return identifier without quotes
	 */
	public String unquote(String identifier) {
		if (isQuoted(identifier)) {
			return identifier.substring(1, identifier.length() - 1);
		}
		return identifier;
	}
	

	/**
	 * Remove quotes from identifier.
	 * 
	 * @param identifier the identifier
	 * @return identifier without quotes
	 */
	public static String staticUnquote(String identifier) {
		if (isQuoted(identifier, "\"")) {
			return identifier.substring(1, identifier.length() - 1);
		}
		return identifier;
	}

	/**
	 * Quotes an identifier only if it is already quoted (potentially with a different quoting-string).
	 * 
	 * @param identifier
	 *            the identifier
	 * @return quoted identifier
	 */
	public String requote(String identifier) {
		if (identifier != null) {
			identifier = identifier.trim();
			if (isQuoted(identifier)) {
				return quote + unquote(identifier) + quote;
			}
		}
		return identifier;
	}
	
	/**
	 * Gets unquoted qualified table name.
	 * 
	 * @param t
	 *            the table
	 * @return unquoted qualified name of t
	 */
	public static String unquotedTableName(Table t, ExecutionContext executionContext) {
		String schema = t.getOriginalSchema("");
		String mappedSchema = executionContext
				.getSchemaMapping().get(schema);
		if (mappedSchema != null) {
			schema = mappedSchema;
		}
		if (schema.length() == 0) {
			return staticUnquote(t.getUnqualifiedName());
		}
		return staticUnquote(schema) + "." + staticUnquote(t.getUnqualifiedName());
	}

	/**
	 * Checks if an identifier is quoted.
	 * 
	 * @param identifier the identifier
	 * @return <code>true</code> if identifier is quoted
	 */
	private static boolean isQuoted(String identifier, String qu) {
		if (identifier != null && identifier.length() > 1) {
			String q = identifier.substring(0, 1);
			if (q.equals("[") && identifier.endsWith("]")) {
				return true;
			}
			if (identifier.endsWith(q)) {
				char c = q.charAt(0);
				if (q.equals(qu) || isPotentialIdentifierQuote(c)) {
					return true;
				}
			}
		}
		return false;
	}

	public static boolean isPotentialIdentifierQuote(char c) {
		return c == '"' || c == '\'' || c == '\u00B4' || c == '`';
	}

	/**
	 * Sets the identifierQuoteString
	 * 
	 * @param identifierQuoteString
	 */
	public void setIdentifierQuoteString(String identifierQuoteString) {
		quote = identifierQuoteString;
	}

	/**
	 * Normalizes an identifier.
	 * <code>
	 * normalizeIdentifier(a).equals(normalizeIdentifier(b)) iff a and b identify the same thing
	 * </code>
	 * 
	 * @param identifier the identifier
	 * @return normalize identifier
	 */
	public static String normalizeIdentifier(String identifier) {
		if (identifier == null) {
			return null;
		}
		return staticUnquote(identifier).toUpperCase(Locale.ENGLISH);
	}

	public String normalizeCase(String identifier) {
		if (identifier == null) {
			return null;
		}
		if (unquotedIdentifierInMixedCase) {
			return identifier;
		}
		if (unquotedIdentifierInUpperCase) {
			return identifier.toUpperCase();
		}
		return identifier.toLowerCase();
	}
	
	/**
	 * Compares a String to another String, ignoring case considerations and quoting.
	 * 
	 * @param a first String
	 * @param b second String
	 * @return <code>true</code> if a equals b
	 */
	public static boolean equalsIgnoreQuotingAndCase(String a, String b) {
		if (a == null) {
			return b == null;
		}
		if (b == null) {
			return false;
		}
		return staticUnquote(a).equalsIgnoreCase(staticUnquote(b));
	}

	public static final HashSet<String> UCSQL2003KEYWORDS = new HashSet<String>(Arrays.asList(new String[] {
			"ABS",
			"ABSOLUTE",
			"ACTION",
			"ADD",
			"ADMIN",
			"AFTER",
			"AGGREGATE",
			"ALIAS",
			"ALL",
			"ALLOCATE",
			"ALTER",
			"ANALYSE",
			"ANALYZE",
			"AND",
			"ANY",
			"ARE",
			"ARRAY",
			"AS",
			"ASC",
			"ASENSITIVE",
			"ASSERTION",
			"ASYMMETRIC",
			"AT",
			"ATOMIC",
			"AUTHORIZATION",
			"AVG",
			"BEFORE",
			"BEGIN",
			"BETWEEN",
			"BIGINT",
			"BINARY",
			"BIT",
			"BIT_LENGTH",
			"BLOB",
			"BOOLEAN",
			"BOTH",
			"BREADTH",
			"BY",
			"CALL",
			"CALLED",
			"CARDINALITY",
			"CASCADE",
			"CASCADED",
			"CASE",
			"CAST",
			"CATALOG",
			"CEIL",
			"CEILING",
			"CHAR",
			"CHARACTER",
			"CHARACTER_LENGTH",
			"CHAR_LENGTH",
			"CHECK",
			"CLASS",
			"CLOB",
			"CLOSE",
			"COALESCE",
			"COLLATE",
			"COLLATION",
			"COLLECT",
			"COLUMN",
			"COMMIT",
			"COMPLETION",
			"CONDITION",
			"CONNECT",
			"CONNECTION",
			"CONSTRAINT",
			"CONSTRAINTS",
			"CONSTRUCTOR",
			"CONTINUE",
			"CONVERT",
			"CORR",
			"CORRESPONDING",
			"COUNT",
			"COVAR_POP",
			"COVAR_SAMP",
			"CREATE",
			"CROSS",
			"CUBE",
			"CUME_DIST",
			"CURRENT",
			"CURRENT_DATE",
			"CURRENT_DEFAULT_TRANSFORM_GROUP",
			"CURRENT_PATH",
			"CURRENT_ROLE",
			"CURRENT_TIME",
			"CURRENT_TIMESTAMP",
			"CURRENT_TRANSFORM_GROUP_FOR_TYPE",
			"CURRENT_USER",
			"CURSOR",
			"CYCLE",
			"DATA",
			"DATE",
			"DAY",
			"DEALLOCATE",
			"DEC",
			"DECIMAL",
			"DECLARE",
			"DEFAULT",
			"DEFERRABLE",
			"DEFERRED",
			"DELETE",
			"DENSE_RANK",
			"DEPTH",
			"DEREF",
			"DESC",
			"DESCRIBE",
			"DESCRIPTOR",
			"DESTROY",
			"DESTRUCTOR",
			"DETERMINISTIC",
			"DIAGNOSTICS",
			"DICTIONARY",
			"DISCONNECT",
			"DISTINCT",
			"DO",
			"DOMAIN",
			"DOUBLE",
			"DROP",
			"DYNAMIC",
			"EACH",
			"ELEMENT",
			"ELSE",
			"END",
			"END-EXEC",
			"EQUALS",
			"ESCAPE",
			"EVERY",
			"EXCEPT",
			"EXCEPTION",
			"EXEC",
			"EXECUTE",
			"EXISTS",
			"EXP",
			"EXTERNAL",
			"EXTRACT",
			"FALSE",
			"FETCH",
			"FILTER",
			"FIRST",
			"FLOAT",
			"FLOOR",
			"FOR",
			"FOREIGN",
			"FOUND",
			"FREE",
			"FREEZE",
			"FROM",
			"FULL",
			"FUNCTION",
			"FUSION",
			"GENERAL",
			"GET",
			"GLOBAL",
			"GO",
			"GOTO",
			"GRANT",
			"GROUP",
			"GROUPING",
			"HAVING",
			"HOLD",
			"HOST",
			"HOUR",
			"IDENTITY",
			"IGNORE",
			"ILIKE",
			"IMMEDIATE",
			"IN",
			"INDICATOR",
			"INITIALIZE",
			"INITIALLY",
			"INNER",
			"INOUT",
			"INPUT",
			"INSENSITIVE",
			"INSERT",
			"INT",
			"INTEGER",
			"INTERSECT",
			"INTERSECTION",
			"INTERVAL",
			"INTO",
			"IS",
			"ISNULL",
			"ISOLATION",
			"ITERATE",
			"JOIN",
			"KEY",
			"LARGE",
			"LAST",
			"LATERAL",
			"LEADING",
			"LEFT",
			"LESS",
			"LEVEL",
			"LIKE",
			"LIMIT",
			"LN",
			"LOCAL",
			"LOCALTIME",
			"LOCALTIMESTAMP",
			"LOCATOR",
			"LOWER",
			"MAP",
			"MATCH",
			"MAX",
			"MEMBER",
			"MERGE",
			"METHOD",
			"MIN",
			"MINUTE",
			"MOD",
			"MODIFIES",
			"MODIFY",
			"MODULE",
			"MONTH",
			"MULTISET",
			"NAMES",
			"NATIONAL",
			"NATURAL",
			"NCHAR",
			"NCLOB",
			"NEW",
			"NEXT",
			"NO",
			"NONE",
			"NORMALIZE",
			"NOT",
			"NOTNULL",
			"NULL",
			"NULLIF",
			"NUMERIC",
			"OBJECT",
			"OCTET_LENGTH",
			"OF",
			"OFF",
			"OFFSET",
			"OLD",
			"ON",
			"ONLY",
			"OPEN",
			"OPERATION",
			"OPTION",
			"OR",
			"ORDER",
			"ORDINALITY",
			"OUT",
			"OUTER",
			"OUTPUT",
			"OVER",
			"OVERLAPS",
			"OVERLAY",
			"PAD",
			"PARAMETER",
			"PARAMETERS",
			"PARTIAL",
			"PARTITION",
			"PATH",
			"PERCENTILE_CONT",
			"PERCENTILE_DISC",
			"PERCENT_RANK",
			"PLACING",
			"POSITION",
			"POSTFIX",
			"POWER",
			"PRECISION",
			"PREFIX",
			"PREORDER",
			"PREPARE",
			"PRESERVE",
			"PRIMARY",
			"PRIOR",
			"PRIVILEGES",
			"PROCEDURE",
			"PUBLIC",
			"RANGE",
			"RANK",
			"READ",
			"READS",
			"REAL",
			"RECURSIVE",
			"REF",
			"REFERENCES",
			"REFERENCING",
			"REGR_AVGX",
			"REGR_AVGY",
			"REGR_COUNT",
			"REGR_INTERCEPT",
			"REGR_R2",
			"REGR_SLOPE",
			"REGR_SXX",
			"REGR_SXY",
			"REGR_SYY",
			"RELATIVE",
			"RELEASE",
			"RESTRICT",
			"RESULT",
			"RETURN",
			"RETURNING",
			"RETURNS",
			"REVOKE",
			"RIGHT",
			"ROLLBACK",
			"ROLLUP",
			"ROUTINE",
			"ROW",
			"ROWS",
			"ROW_NUMBER",
			"SAVEPOINT",
			"SCHEMA",
			"SCOPE",
			"SCROLL",
			"SEARCH",
			"SECOND",
			"SECTION",
			"SELECT",
			"SENSITIVE",
			"SEQUENCE",
			"SESSION",
			"SESSION_USER",
			"SET",
			"SETS",
			"SIMILAR",
			"SIZE",
			"SMALLINT",
			"SOME",
			"SPACE",
			"SPECIFIC",
			"SPECIFICTYPE",
			"SQL",
			"SQLCODE",
			"SQLERROR",
			"SQLEXCEPTION",
			"SQLSTATE",
			"SQLWARNING",
			"SQRT",
			"START",
			"STATE",
			"STATEMENT",
			"STATIC",
			"STDDEV_POP",
			"STDDEV_SAMP",
			"STRUCTURE",
			"SUBMULTISET",
			"SUBSTRING",
			"SUM",
			"SYMMETRIC",
			"SYSTEM",
			"SYSTEM_USER",
			"TABLE",
			"TABLESAMPLE",
			"TEMPORARY",
			"TERMINATE",
			"THAN",
			"THEN",
			"TIME",
			"TIMESTAMP",
			"TIMEZONE_HOUR",
			"TIMEZONE_MINUTE",
			"TO",
			"TRAILING",
			"TRANSACTION",
			"TRANSLATE",
			"TRANSLATION",
			"TREAT",
			"TRIGGER",
			"TRIM",
			"TRUE",
			"UESCAPE",
			"UNDER",
			"UNION",
			"UNIQUE",
			"UNKNOWN",
			"UNNEST",
			"UPDATE",
			"UPPER",
			"USAGE",
			"USER",
			"USING",
			"VALUE",
			"VALUES",
			"VARCHAR",
			"VARIABLE",
			"VARYING",
			"VAR_POP",
			"VAR_SAMP",
			"VERBOSE",
			"VIEW",
			"WHEN",
			"WHENEVER",
			"WHERE",
			"WIDTH_BUCKET",
			"WINDOW",
			"WITH",
			"WITHIN",
			"WITHOUT",
			"WORK",
			"WRITE",
			"XML",
			"XMLAGG",
			"XMLATTRIBUTES",
			"XMLBINARY",
			"XMLCOMMENT",
			"XMLCONCAT",
			"XMLELEMENT",
			"XMLFOREST",
			"XMLNAMESPACES",
			"XMLPARSE",
			"XMLPI",
			"XMLROOT",
			"XMLSERIALIZE",
			"YEAR",
			"ZONE",
	}));		

}