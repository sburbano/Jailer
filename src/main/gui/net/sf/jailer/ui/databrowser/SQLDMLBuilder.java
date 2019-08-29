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

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.sf.jailer.database.Session;
import net.sf.jailer.datamodel.Column;
import net.sf.jailer.datamodel.Table;
import net.sf.jailer.util.CellContentConverter;
import net.sf.jailer.util.Quoting;
import net.sf.jailer.util.SqlUtil;


/**
 * Builder for Insert/Delete/Update statements.
 * 
 * @author Ralf Wisser
 */
public class SQLDMLBuilder {

	/**
	 * Build Update statements.
	 * 
	 * @param table the table
	 * @param rows rows
	 * @param session current DB session
	 * @return update statements for rows
	 */
	public static String buildUpdate(Table table, List<Row> rows, boolean withComments, Session session) {
		StringBuilder sb = new StringBuilder();
		
		for (Row row: unique(rows)) {
			sb.append(buildUpdate(table, row, withComments, session)).append(";" + LF + LF);
		}
		return sb.toString();
	}
	
	/**
	 * Build Update statements.
	 * 
	 * @param table the table
	 * @param row row to be updated
	 * @param session current DB session
	 * @return update statement for row
	 */
	public static String buildUpdate(Table table, Row theRow, boolean withComments, Session session) {
		return buildUpdate(table, theRow, withComments, -1, session);
	}
	

	/**
	 * Build Update statements for a given column.
	 * 
	 * @param table the table
	 * @param row row to be updated
	 * @param columnToUpdate the column to update
	 * @param session current DB session
	 * @return update statement for row
	 */
	public static String buildUpdate(Table table, Row row, boolean withComments, int columnToUpdate, Session session) {
		Quoting quoting;
		try {
			quoting = new Quoting(session);
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
		String sql = "Update " + table.getName() + " " + LF + "Set ";
		boolean f = true;
		int i = 0;
		CellContentConverter cellContentConverter = new CellContentConverter(null, session, session.dbms);
		Set<String> usedColumns = new HashSet<String>();
		for (Column column : table.getColumns()) {
			String value = getSQLLiteral(row.values[i++], cellContentConverter);
			if (columnToUpdate != i - 1 && columnToUpdate >= 0) {
				continue;
			}
			if (value == null) {
				continue;
			}
			if (column.name == null || column.isVirtual()) {
				continue;
			}
			if (usedColumns.contains(column.name)) {
				continue;
			}
			usedColumns.add(column.name);
			String name = quoting.requote(column.name);
			sql += (f? "" : ", " + LF + "    ") + name + "=" + value + comment(withComments, column, false);
			f = false;
		}
		sql += " " + LF + "Where " + SqlUtil.replaceAliases(row.rowId, null, null);
		return sql;
	}
	
	private static String comment(boolean withComments, Column column, boolean withName) {
		if (withComments) {
			String content;
			
			if (withName) {
				if (column.type == null) {
					content = column.name;
				} else {
					content = column.toSQL(null);
				}
			} else {
				if (column.type == null) {
					content = "";
				} else {
					content = column.toSQL(null).substring(column.name.length()).trim();
				}
			}
			if (!content.isEmpty()) {
				return "   /* " + content + " */";
			}
		}
		return "";
	}

	/**
	 * Build Insert statements.
	 * 
	 * @param table the table
	 * @param rows rows
	 * @param session current DB session
	 * @return insert statements for rows
	 */
	public static String buildInsert(Table table, List<Row> rows, boolean withComments, Session session) {
		StringBuilder sb = new StringBuilder();
		
		for (Row row: unique(rows)) {
			sb.append(buildInsert(table, row, withComments, session)).append(";" + LF + LF);
		}
		return sb.toString();
	}
	
	/**
	 * Build Insert statements.
	 * 
	 * @param table the table
	 * @param row row to be updated
	 * @param session current DB session
	 * @return update statement for row
	 */
	public static String buildInsert(Table table, Row row, boolean withComments, Session session) {
		Quoting quoting;
		try {
			quoting = new Quoting(session);
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
		String sql = "Insert into " + table.getName() + " (" + LF + "    ";
		String values = "";
		boolean f = true;
		int i = 0;
		Set<String> usedColumns = new HashSet<String>();
		CellContentConverter cellContentConverter = new CellContentConverter(null, session, session.dbms);
		for (Column column : table.getColumns()) {
			String value = getSQLLiteral(row.values[i++], cellContentConverter);
			if (value == null) {
				continue;
			}
			if (column.name == null || column.isVirtual()) {
				continue;
			}
			if (usedColumns.contains(column.name)) {
				continue;
			}
			usedColumns.add(column.name);
			String name = quoting.requote(column.name);
			sql += (f? "" : ", " + LF + "    ") + name + comment(withComments, column, false);
			values += (f? "" : ", " + LF + "    ") + value + comment(withComments, column, true);
			f = false;
		}
		sql += ") " + LF + "Values (" + LF + "    " + values + ")";
		return sql;
	}
	
	/**
	 * Build Delete statements.
	 * 
	 * @param table the table
	 * @param row row to be updated
	 * @param session current DB session
	 * @return update statement for row
	 */
	public static String buildDelete(Table table, Row row, boolean withComments, Session session) {
		String sql = "Delete from " + table.getName() + " Where " + SqlUtil.replaceAliases(row.rowId, null, null);
		return sql;
	}

	/**
	 * Build Delete statements.
	 * 
	 * @param table the table
	 * @param rows rows
	 * @param session current DB session
	 * @return delete statements for rows
	 */
	public static String buildDelete(Table table, List<Row> rows, boolean withComments, Session session) {
		StringBuilder sb = new StringBuilder();
		
		for (Row row: unique(rows)) {
			sb.append(buildDelete(table, row, withComments, session)).append(";" + LF + "");
		}
		return sb.toString();
	}
	
	/**
	 * Removes all duplicates out of a list of rows.
	 * 
	 * @param rows list of rows
	 * @return list of rows without duplicates
	 */
	private static List<Row> unique(List<Row> rows) {
		List<Row> result = new ArrayList<Row>();
		Set<String> ids = new HashSet<String>();
		for (Row row: rows) {
			if (row.rowId.length() == 0 || !ids.contains(row.rowId)) {
				ids.add(row.rowId);
				result.add(row);
			}
		}
		return result;
	}

	/**
	 * Gets SQL literal for a given object. Returns <code>null</code> if the object cannot be converted into a SQL literal (LOBs).
	 * 
	 * @param value the value
	 * @param cellContentConverter
	 * @return SQL literal or <code>null</code>
	 */
	private static String getSQLLiteral(Object value, CellContentConverter cellContentConverter) {
		if (value instanceof LobValue) {
			return null;
		}
		if (value instanceof BinValue) {
			return cellContentConverter.toSql(((BinValue) value).getContent());
		}
		return cellContentConverter.toSql(value);
	}
	
	private static final String LF = System.getProperty("line.separator", "\n");

}
