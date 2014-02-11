/*
 * Copyright 2011, Mysema Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.mysema.query.sql;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.collect.Maps;
import com.mysema.commons.lang.Pair;
import com.mysema.query.sql.types.BigDecimalAsDoubleType;
import com.mysema.query.sql.types.Null;
import com.mysema.query.sql.types.Type;
import com.mysema.query.types.Path;

/**
 * Configuration for SQLQuery instances
 *
 * @author tiwe
 *
 */
public final class Configuration {

    private static final BigDecimalAsDoubleType BIGDECIMAL_AS_DOUBLE = new BigDecimalAsDoubleType();

    public static final Configuration DEFAULT = new Configuration(SQLTemplates.DEFAULT);

    private final JDBCTypeMapping jdbcTypeMapping = new JDBCTypeMapping();

    private final JavaTypeMapping javaTypeMapping = new JavaTypeMapping();

    private final Map<String, String> schemas = Maps.newHashMap();

    private final Map<Pair<String, String>, String> schemaTables = Maps.newHashMap();

    private final Map<String, String> tables = Maps.newHashMap();

    private final Map<String, Class<?>> typeToName = Maps.newHashMap();

    private final SQLTemplates templates;

    private SQLExceptionTranslator exceptionTranslator = DefaultSQLExceptionTranslator.DEFAULT;

    private final SQLListeners listeners = new SQLListeners();

    private boolean hasTableColumnTypes = false;

    private boolean useLiterals = false;

    /**
     * Create a new Configuration instance
     *
     * @param templates
     */
    public Configuration(SQLTemplates templates) {
        this.templates = templates;
        if (!templates.isBigDecimalSupported()) {
            javaTypeMapping.register(BIGDECIMAL_AS_DOUBLE);
        }
        for (Type<?> customType : templates.getCustomTypes()) {
            javaTypeMapping.register(customType);
        }
    }

    public SQLTemplates getTemplates() {
        return templates;
    }

    /**
     * Get the java type for the given jdbc type, table name and column name
     *
     * @param sqlType
     * @param typeName
     * @param size
     * @param digits
     * @param tableName
     * @param columnName
     * @return
     */
    public Class<?> getJavaType(int sqlType, String typeName, int size, int digits, String tableName, String columnName) {
        // table.column mapped class
        Type<?> type = javaTypeMapping.getType(tableName, columnName);
        if (type != null) {
            return type.getReturnedClass();
        } else if (!typeToName.isEmpty()) {
            // typename mapped class
            Class<?> clazz = typeToName.get(typeName.toLowerCase());
            if (clazz != null) {
                return clazz;
            }
        }
        // sql type mapped class
        return jdbcTypeMapping.get(sqlType, size, digits);
    }

    /**
     * @param <T>
     * @param rs
     * @param path
     * @param i
     * @param clazz
     * @return
     * @throws SQLException
     */
    @Nullable
    public <T> T get(ResultSet rs, @Nullable Path<?> path, int i, Class<T> clazz) throws SQLException {
        return getType(path, clazz).getValue(rs, i);
    }

    /**
     * Get schema override or schema
     *
     * @param schema
     * @return
     */
    public String getSchema(String schema) {
        if (schemas.containsKey(schema)) {
            return schemas.get(schema);
        } else {
            return schema;
        }
    }

    /**
     * Get table override or table
     *
     * @param schema
     * @param table
     * @return
     */
    public String getTable(String schema, String table) {
        if (!schemaTables.isEmpty() && schema != null) {
            Pair<String, String> key = Pair.of(schema, table);
            if (schemaTables.containsKey(key)) {
                return schemaTables.get(key);
            }
        }
        if (tables.containsKey(table)) {
            return tables.get(table);
        } else {
            return table;
        }
    }

    /**
     * @param <T>
     * @param stmt
     * @param path
     * @param i
     * @param value
     * @return
     * @throws SQLException
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public <T> void set(PreparedStatement stmt, Path<?> path, int i, T value) throws SQLException {
        if (Null.class.isInstance(value)) {
            Integer sqlType = path != null ? jdbcTypeMapping.get(path.getType()) : null;
            if (sqlType != null) {
                stmt.setNull(i, sqlType);
            } else {
                stmt.setNull(i, Types.NULL);
            }
        } else {
            getType(path, (Class)value.getClass()).setValue(stmt, i, value);
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private <T> Type<T> getType(@Nullable Path<?> path, Class<T> clazz) {
        if (hasTableColumnTypes && path != null && !clazz.equals(Null.class)
                && path.getMetadata().getParent() instanceof RelationalPath) {
            String table = ((RelationalPath)path.getMetadata().getParent()).getTableName();
            String column = ColumnMetadata.getName(path);
            Type<T> type = (Type)javaTypeMapping.getType(table, column);
            if (type != null) {
                return type;
            }
        }
        return javaTypeMapping.getType(clazz);
    }

    /**
     * Register a schema override
     *
     * @param oldSchema
     * @param newSchema
     * @return
     */
    public String registerSchemaOverride(String oldSchema, String newSchema) {
        return schemas.put(oldSchema, newSchema);
    }

    /**
     * Register a table override
     *
     * @param oldTable
     * @param newTable
     * @return
     */
    public String registerTableOverride(String oldTable, String newTable) {
        return tables.put(oldTable, newTable);
    }

    /**
     * Register a schema specific table override
     *
     * @param schema
     * @param oldTable
     * @param newTable
     * @return
     */
    public String registerTableOverride(String schema, String oldTable, String newTable) {
        return schemaTables.put(Pair.of(schema, oldTable), newTable);
    }

    /**
     * Register the given Type to be used
     *
     * @param type
     */
    public void register(Type<?> type) {
        jdbcTypeMapping.register(type.getSQLTypes()[0], type.getReturnedClass());
        javaTypeMapping.register(type);
    }

    /**
     * Register a typeName to Class mapping
     *
     * @param typeName
     * @param clazz
     */
    public void registerType(String typeName, Class<?> clazz) {
        typeToName.put(typeName.toLowerCase(), clazz);
    }

    /**
     * Override the binding for the given NUMERIC type
     *
     * @param size
     * @param digits
     * @param javaType
     */
    public void registerNumeric(int size, int digits, Class<?> javaType) {
        jdbcTypeMapping.registerNumeric(size, digits, javaType);
    }

    /**
     * Register the given javaType for the given table and column
     *
     * @param table
     * @param column
     * @param javaType
     */
    public void register(String table, String column, Class<?> javaType) {
        register(table, column, javaTypeMapping.getType(javaType));
    }

    /**
     * Register the given Type for the given table and column
     *
     * @param table
     * @param column
     * @param type
     */
    public void register(String table, String column, Type<?> type) {
        javaTypeMapping.setType(table, column, type);
        hasTableColumnTypes = true;
    }

    /**
     * Translate the given SQLException
     *
     * @param ex
     * @return
     */
    public RuntimeException translate(SQLException ex) {
        return exceptionTranslator.translate(null, ex);
    }

    /**
     * Translate the given SQLException
     *
     * @param sql
     * @param ex
     * @return
     */
    public RuntimeException translate(String sql, SQLException ex) {
        return exceptionTranslator.translate(sql, ex);
    }

    /**
     * @param listeners
     */
    public void addListener(SQLListener listener) {
        listeners.add(listener);
    }

    /**
     * @return
     */
    public SQLListeners getListeners() {
        return listeners;
    }

    /**
     * @return
     */
    public boolean getUseLiterals() {
        return useLiterals;
    }

    /**
     * @param useLiterals
     */
    public void setUseLiterals(boolean useLiterals) {
        this.useLiterals = useLiterals;
    }

    /**
     * @param exceptionTranslator
     */
    public void setExceptionTranslator(SQLExceptionTranslator exceptionTranslator) {
        this.exceptionTranslator = exceptionTranslator;
    }

}
