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


package net.sf.jailer.datamodel;


/**
 * Source of {@link Filter} derived from primary key column.
 * 
 * @author Wisser
 */
public class PKColumnFilterSource implements FilterSource {
	
	public final Table table;
	public final Column column;

	/**
	 * Constructor.
	 * 
	 * @param table the table
	 * @param column primary key column
	 */
	public PKColumnFilterSource(Table table, Column column) {
		this.table = table;
		this.column = column;
	}
	
	/**
	 * Gets clear text description of what the source is.
	 * 
	 * @return clear text description
	 */
	@Override
	public String getDescription() {
		return "Filter on " + table.getName() + "." + column.name;
	}
	
}
