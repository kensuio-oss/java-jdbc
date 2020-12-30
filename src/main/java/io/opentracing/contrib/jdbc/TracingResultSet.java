/*
 * Copyright 2017-2020 The OpenTracing Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.opentracing.contrib.jdbc;

import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.tag.Tag;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import javax.xml.namespace.QName;

public class TracingResultSet implements ResultSet {
    static final Logger logger = Logger.getLogger(TracingResultSet.class.getName());

    private final ResultSet resultSet;
    private final Span span;

    public TracingResultSet(ResultSet resultSet, Tracer tracer) {
        this.resultSet = resultSet;
        this.span = tracer.buildSpan("QueryResultStats").start();
    }

    private void traceCount() {
        try {
            span.setTag("db.count", getRow());
        } catch (Exception e) {
            e.printStackTrace();
            logger.warning("Fail to call `getRow` on ResultSet... trace span will not/never be updated with correct count. ResultSet:" + this);
        }
    }

    private void closeTracingSpan() {
        span.finish();
    }

    // TODO temporary
    private static class ColumnMetadata {
        public final String schemaName;
        public final String tableName;
        public final String name;
        public final String type;
        public final boolean nullable;
        public final Map<String, String> md;
        private Map<String, Double> stats = new HashMap<>();

        public ColumnMetadata(String schemaName, String tableName, String name, String type, boolean nullable) {
            this.schemaName = schemaName;
            this.tableName = tableName;
            this.name = name;
            this.type = type;
            this.nullable = nullable;
            this.md = Map.of("schemaName", schemaName, "tableName", tableName, "name", name, "type", type, "nullable", ""+nullable); 
        }

        public Map<String, String> toSpanMD() {
            return this.md;
        }

        public <V> Map<String, Double> toSpanStatsIncl(V val) {
            // update stats map with new values
            stats.merge("count", 1d, (oldV, newV)->oldV+1);
            if (val == null) {
                stats.merge("na.count", 1d, (oldV, newV)->oldV+1);
            } else {
                if (val instanceof Number) {
                    Number v = (Number) val;
                    stats.merge("sum", v.doubleValue(), (oldV, newV)->oldV+newV);
                    stats.put("mean", stats.get("sum")/stats.get("count"));
                }
            }
            return stats;
        }
        public <V> void trace(Span span, int arg0, V val) {
            span.setTag(new GenericTag<Map<String, String>>("db.column."+arg0+".md"), toSpanMD());
            span.setTag(new GenericTag<Map<String, Double>>("db.column."+arg0+".stats"), toSpanStatsIncl(val));
        }

    }

    private ConcurrentHashMap<Integer, ColumnMetadata> columnMetadataCache = new ConcurrentHashMap<>();

    synchronized private ColumnMetadata getColumnInfo(int arg0) {
        try {
            ColumnMetadata cmd = columnMetadataCache.get(arg0);
            if (cmd == null) {
                ResultSetMetaData md = getMetaData();
                cmd = new ColumnMetadata(
                        md.getSchemaName(arg0),
                        md.getTableName(arg0),
                        md.getColumnName(arg0),
                        md.getColumnTypeName(arg0),
                        md.isNullable(arg0) == ResultSetMetaData.columnNullable
                    );
                columnMetadataCache.put(arg0, cmd);
            }
            return cmd;
        } catch (Exception e) {
            e.printStackTrace();
            logger.warning("Fail to call `getColumnInfo` on ResultSet... trace span will not/never be updated with correct schema. ResultSet:" + this);
            return null;
        }
    }

    private ConcurrentHashMap<String, Integer> columnIndices = new ConcurrentHashMap<>();

    synchronized private int findColumnTheHardWay(String arg0) throws SQLException {
        if (columnIndices.isEmpty()) {
            ResultSetMetaData md = getMetaData();
            for (int i = 1 ; i <= md.getColumnCount() ; i++) {
                String name = md.getColumnName(i);
                columnIndices.put(name, i);
            }
        }
        Integer index = columnIndices.get(arg0); // this can blow, so it is caught in the caller function traceColumn
        return index;
    }

    private <V> void traceColumn(String arg0, V v) {
        try {
            int cIndex = 0;
            try {
                cIndex = findColumn(arg0);
            } catch (Exception fe) {
                // ClickHouse throws a UnsupportedOperationException
                // if a bad name is given, this will blow
                cIndex = findColumnTheHardWay(arg0);
            }
            traceColumn(cIndex, v);
        } catch (Exception e) {
            e.printStackTrace();
            logger.warning("Fail to call `getColumnInfo` on ResultSet... trace span will not/never be updated with correct schema. ResultSet:" + this);
        }

    }

    private <V> void traceColumn(int arg0, V v) {
        ColumnMetadata md = getColumnInfo(arg0);
        if (md != null) {
            md.trace(span, arg0, v);
        }
    }

    @Override
    public boolean next() throws SQLException {
        logger.fine("Current count before next: " + this.getRow());
        boolean next = resultSet.next();
        if (next) {
            traceCount();
            if (this.isLast()) {
                closeTracingSpan();
            }    
        }
        return next;
    }

    @Override
    public boolean isWrapperFor(Class<?> arg0) throws SQLException {
        return resultSet.isWrapperFor(arg0);
    }

    @Override
    public <T> T unwrap(Class<T> arg0) throws SQLException {
        return resultSet.unwrap(arg0);
    }

    @Override
    public boolean absolute(int arg0) throws SQLException {
        return resultSet.absolute(arg0);
    }

    @Override
    public void afterLast() throws SQLException {
        resultSet.afterLast();
    }

    @Override
    public void beforeFirst() throws SQLException {
        resultSet.beforeFirst();
    }

    @Override
    public void cancelRowUpdates() throws SQLException {
        resultSet.cancelRowUpdates();
    }

    @Override
    public void clearWarnings() throws SQLException {
        resultSet.clearWarnings();
    }

    @Override
    public void close() throws SQLException {
        resultSet.close();
        closeTracingSpan();
    }

    @Override
    public void deleteRow() throws SQLException {
        resultSet.deleteRow();
    }

    @Override
    public int findColumn(String arg0) throws SQLException {
        return resultSet.findColumn(arg0);
    }

    @Override
    public boolean first() throws SQLException {
        return resultSet.first();
    }

    @Override
    public Array getArray(int arg0) throws SQLException {
        Array v = resultSet.getArray(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public Array getArray(String arg0) throws SQLException {
        Array v = resultSet.getArray(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public InputStream getAsciiStream(int arg0) throws SQLException {
        InputStream v = resultSet.getAsciiStream(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public InputStream getAsciiStream(String arg0) throws SQLException {
        InputStream v = resultSet.getAsciiStream(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public BigDecimal getBigDecimal(int arg0) throws SQLException {
        BigDecimal v = resultSet.getBigDecimal(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public BigDecimal getBigDecimal(String arg0) throws SQLException {
        BigDecimal v = resultSet.getBigDecimal(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public BigDecimal getBigDecimal(int arg0, int arg1) throws SQLException {
        BigDecimal v = resultSet.getBigDecimal(arg0, arg1);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public BigDecimal getBigDecimal(String arg0, int arg1) throws SQLException {
        BigDecimal v = resultSet.getBigDecimal(arg0, arg1);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public InputStream getBinaryStream(int arg0) throws SQLException {
        InputStream v = resultSet.getBinaryStream(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public InputStream getBinaryStream(String arg0) throws SQLException {
        InputStream v = resultSet.getBinaryStream(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public Blob getBlob(int arg0) throws SQLException {
        Blob v = resultSet.getBlob(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public Blob getBlob(String arg0) throws SQLException {
        Blob v = resultSet.getBlob(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public boolean getBoolean(int arg0) throws SQLException {
        boolean v = resultSet.getBoolean(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public boolean getBoolean(String arg0) throws SQLException {
        boolean v = resultSet.getBoolean(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public byte getByte(int arg0) throws SQLException {
        byte v = resultSet.getByte(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public byte getByte(String arg0) throws SQLException {
        byte v = resultSet.getByte(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public byte[] getBytes(int arg0) throws SQLException {
        byte[] v = resultSet.getBytes(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public byte[] getBytes(String arg0) throws SQLException {
        byte[] v = resultSet.getBytes(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public Reader getCharacterStream(int arg0) throws SQLException {
        Reader v = resultSet.getCharacterStream(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public Reader getCharacterStream(String arg0) throws SQLException {
        Reader v = resultSet.getCharacterStream(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public Clob getClob(int arg0) throws SQLException {
        Clob v = resultSet.getClob(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public Clob getClob(String arg0) throws SQLException {
        Clob v = resultSet.getClob(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public int getConcurrency() throws SQLException {
        return resultSet.getConcurrency();
    }

    @Override
    public String getCursorName() throws SQLException {
        return resultSet.getCursorName();
    }

    @Override
    public Date getDate(int arg0) throws SQLException {
        Date v = resultSet.getDate(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public Date getDate(String arg0) throws SQLException {
        Date v = resultSet.getDate(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public Date getDate(int arg0, Calendar arg1) throws SQLException {
        Date v = resultSet.getDate(arg0, arg1);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public Date getDate(String arg0, Calendar arg1) throws SQLException {
        Date v = resultSet.getDate(arg0, arg1);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public double getDouble(int arg0) throws SQLException {
        double v = resultSet.getDouble(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public double getDouble(String arg0) throws SQLException {
        double v = resultSet.getDouble(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public int getFetchDirection() throws SQLException {
        return resultSet.getFetchDirection();
    }

    @Override
    public int getFetchSize() throws SQLException {
        return resultSet.getFetchSize();
    }

    @Override
    public float getFloat(int arg0) throws SQLException {
        float v = resultSet.getFloat(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public float getFloat(String arg0) throws SQLException {
        float v = resultSet.getFloat(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public int getHoldability() throws SQLException {
        return resultSet.getHoldability();
    }

    @Override
    public int getInt(int arg0) throws SQLException {
        int v = resultSet.getInt(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public int getInt(String arg0) throws SQLException {
        int v = resultSet.getInt(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public long getLong(int arg0) throws SQLException {
        long v = resultSet.getLong(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public long getLong(String arg0) throws SQLException {
        long v = resultSet.getLong(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        return resultSet.getMetaData();
    }

    @Override
    public Reader getNCharacterStream(int arg0) throws SQLException {
        Reader v = resultSet.getNCharacterStream(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public Reader getNCharacterStream(String arg0) throws SQLException {
        Reader v = resultSet.getNCharacterStream(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public NClob getNClob(int arg0) throws SQLException {
        NClob v = resultSet.getNClob(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public NClob getNClob(String arg0) throws SQLException {
        NClob v = resultSet.getNClob(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public String getNString(int arg0) throws SQLException {
        String v = resultSet.getNString(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public String getNString(String arg0) throws SQLException {
        String v = resultSet.getNString(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public Object getObject(int arg0) throws SQLException {
        Object v = resultSet.getObject(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public Object getObject(String arg0) throws SQLException {
        Object v = resultSet.getObject(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public Object getObject(int arg0, Map<String, Class<?>> arg1) throws SQLException {
        Object v = resultSet.getObject(arg0, arg1);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public Object getObject(String arg0, Map<String, Class<?>> arg1) throws SQLException {
        Object v = resultSet.getObject(arg0, arg1);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public <T> T getObject(int arg0, Class<T> arg1) throws SQLException {
        T v = resultSet.getObject(arg0, arg1);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public <T> T getObject(String arg0, Class<T> arg1) throws SQLException {
        T v = resultSet.getObject(arg0, arg1);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public Ref getRef(int arg0) throws SQLException {
        Ref v = resultSet.getRef(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public Ref getRef(String arg0) throws SQLException {
        Ref v = resultSet.getRef(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public int getRow() throws SQLException {
        return resultSet.getRow();
    }

    @Override
    public RowId getRowId(int arg0) throws SQLException {
        return resultSet.getRowId(arg0);
    }

    @Override
    public RowId getRowId(String arg0) throws SQLException {
        return resultSet.getRowId(arg0);
    }

    @Override
    public SQLXML getSQLXML(int arg0) throws SQLException {
        SQLXML v = resultSet.getSQLXML(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public SQLXML getSQLXML(String arg0) throws SQLException {
        SQLXML v = resultSet.getSQLXML(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public short getShort(int arg0) throws SQLException {
        short v = resultSet.getShort(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public short getShort(String arg0) throws SQLException {
        short v = resultSet.getShort(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public Statement getStatement() throws SQLException {
        return resultSet.getStatement();
    }

    @Override
    public String getString(int arg0) throws SQLException {
        String v = resultSet.getString(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public String getString(String arg0) throws SQLException {
        String v = resultSet.getString(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public Time getTime(int arg0) throws SQLException {
        Time v = resultSet.getTime(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public Time getTime(String arg0) throws SQLException {
        Time v = resultSet.getTime(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public Time getTime(int arg0, Calendar arg1) throws SQLException {
        Time v = resultSet.getTime(arg0, arg1);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public Time getTime(String arg0, Calendar arg1) throws SQLException {
        Time v = resultSet.getTime(arg0, arg1);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public Timestamp getTimestamp(int arg0) throws SQLException {
        Timestamp v = resultSet.getTimestamp(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public Timestamp getTimestamp(String arg0) throws SQLException {
        Timestamp v = resultSet.getTimestamp(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public Timestamp getTimestamp(int arg0, Calendar arg1) throws SQLException {
        Timestamp v = resultSet.getTimestamp(arg0, arg1);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public Timestamp getTimestamp(String arg0, Calendar arg1) throws SQLException {
        Timestamp v = resultSet.getTimestamp(arg0, arg1);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public int getType() throws SQLException {
        return resultSet.getType();
    }

    @Override
    public URL getURL(int arg0) throws SQLException {
        URL v = resultSet.getURL(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public URL getURL(String arg0) throws SQLException {
        URL v = resultSet.getURL(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public InputStream getUnicodeStream(int arg0) throws SQLException {
        InputStream v = resultSet.getUnicodeStream(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public InputStream getUnicodeStream(String arg0) throws SQLException {
        InputStream v = resultSet.getUnicodeStream(arg0);
        traceColumn(arg0, v);
        return v;
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        return resultSet.getWarnings();
    }

    @Override
    public void insertRow() throws SQLException {
        resultSet.insertRow();
    }

    @Override
    public boolean isAfterLast() throws SQLException {
        return resultSet.isAfterLast();
    }

    @Override
    public boolean isBeforeFirst() throws SQLException {
        return resultSet.isBeforeFirst();
    }

    @Override
    public boolean isClosed() throws SQLException {
        return resultSet.isClosed();
    }

    @Override
    public boolean isFirst() throws SQLException {
        return resultSet.isFirst();
    }

    @Override
    public boolean isLast() throws SQLException {
        return resultSet.isLast();
    }

    @Override
    public boolean last() throws SQLException {
        // TODO => count with getRow?
        return resultSet.last();
    }

    @Override
    public void moveToCurrentRow() throws SQLException {
        resultSet.moveToCurrentRow();
    }

    @Override
    public void moveToInsertRow() throws SQLException {
        resultSet.moveToInsertRow();
    }

    @Override
    public boolean previous() throws SQLException {
        return resultSet.previous();
    }

    @Override
    public void refreshRow() throws SQLException {
        resultSet.refreshRow();
    }

    @Override
    public boolean relative(int arg0) throws SQLException {
        return resultSet.relative(arg0);
    }

    @Override
    public boolean rowDeleted() throws SQLException {
        return resultSet.rowDeleted();
    }

    @Override
    public boolean rowInserted() throws SQLException {
        return resultSet.rowInserted();
    }

    @Override
    public boolean rowUpdated() throws SQLException {
        return resultSet.rowUpdated();
    }

    @Override
    public void setFetchDirection(int arg0) throws SQLException {
        resultSet.setFetchDirection(arg0);
    }

    @Override
    public void setFetchSize(int arg0) throws SQLException {
        resultSet.setFetchSize(arg0);
    }

    @Override
    public void updateArray(int arg0, Array arg1) throws SQLException {
        resultSet.updateArray(arg0, arg1);
    }

    @Override
    public void updateArray(String arg0, Array arg1) throws SQLException {
        resultSet.updateArray(arg0, arg1);
    }

    @Override
    public void updateAsciiStream(int arg0, InputStream arg1) throws SQLException {
        resultSet.updateAsciiStream(arg0, arg1);
    }

    @Override
    public void updateAsciiStream(String arg0, InputStream arg1) throws SQLException {
        resultSet.updateAsciiStream(arg0, arg1);
    }

    @Override
    public void updateAsciiStream(int arg0, InputStream arg1, int arg2) throws SQLException {
        resultSet.updateAsciiStream(arg0, arg1, arg2);
    }

    @Override
    public void updateAsciiStream(String arg0, InputStream arg1, int arg2) throws SQLException {
        resultSet.updateAsciiStream(arg0, arg1, arg2);
    }

    @Override
    public void updateAsciiStream(int arg0, InputStream arg1, long arg2) throws SQLException {
        resultSet.updateAsciiStream(arg0, arg1, arg2);
    }

    @Override
    public void updateAsciiStream(String arg0, InputStream arg1, long arg2) throws SQLException {
        resultSet.updateAsciiStream(arg0, arg1, arg2);
    }

    @Override
    public void updateBigDecimal(int arg0, BigDecimal arg1) throws SQLException {
        resultSet.updateBigDecimal(arg0, arg1);
    }

    @Override
    public void updateBigDecimal(String arg0, BigDecimal arg1) throws SQLException {
        resultSet.updateBigDecimal(arg0, arg1);
    }

    @Override
    public void updateBinaryStream(int arg0, InputStream arg1) throws SQLException {
        resultSet.updateBinaryStream(arg0, arg1);
    }

    @Override
    public void updateBinaryStream(String arg0, InputStream arg1) throws SQLException {
        resultSet.updateBinaryStream(arg0, arg1);
    }

    @Override
    public void updateBinaryStream(int arg0, InputStream arg1, int arg2) throws SQLException {
        resultSet.updateBinaryStream(arg0, arg1, arg2);
    }

    @Override
    public void updateBinaryStream(String arg0, InputStream arg1, int arg2) throws SQLException {
        resultSet.updateBinaryStream(arg0, arg1, arg2);
    }

    @Override
    public void updateBinaryStream(int arg0, InputStream arg1, long arg2) throws SQLException {
        resultSet.updateBinaryStream(arg0, arg1, arg2);
    }

    @Override
    public void updateBinaryStream(String arg0, InputStream arg1, long arg2) throws SQLException {
        resultSet.updateBinaryStream(arg0, arg1, arg2);
    }

    @Override
    public void updateBlob(int arg0, Blob arg1) throws SQLException {
        resultSet.updateBlob(arg0, arg1);
    }

    @Override
    public void updateBlob(String arg0, Blob arg1) throws SQLException {
        resultSet.updateBlob(arg0, arg1);
    }

    @Override
    public void updateBlob(int arg0, InputStream arg1) throws SQLException {
        resultSet.updateBlob(arg0, arg1);
    }

    @Override
    public void updateBlob(String arg0, InputStream arg1) throws SQLException {
        resultSet.updateBlob(arg0, arg1);
    }

    @Override
    public void updateBlob(int arg0, InputStream arg1, long arg2) throws SQLException {
        resultSet.updateBlob(arg0, arg1, arg2);
    }

    @Override
    public void updateBlob(String arg0, InputStream arg1, long arg2) throws SQLException {
        resultSet.updateBlob(arg0, arg1, arg2);
    }

    @Override
    public void updateBoolean(int arg0, boolean arg1) throws SQLException {
        resultSet.updateBoolean(arg0, arg1);
    }

    @Override
    public void updateBoolean(String arg0, boolean arg1) throws SQLException {
        resultSet.updateBoolean(arg0, arg1);
    }

    @Override
    public void updateByte(int arg0, byte arg1) throws SQLException {
        resultSet.updateByte(arg0, arg1);
    }

    @Override
    public void updateByte(String arg0, byte arg1) throws SQLException {
        resultSet.updateByte(arg0, arg1);
    }

    @Override
    public void updateBytes(int arg0, byte[] arg1) throws SQLException {
        resultSet.updateBytes(arg0, arg1);
    }

    @Override
    public void updateBytes(String arg0, byte[] arg1) throws SQLException {
        resultSet.updateBytes(arg0, arg1);
    }

    @Override
    public void updateCharacterStream(int arg0, Reader arg1) throws SQLException {
        resultSet.updateCharacterStream(arg0, arg1);
    }

    @Override
    public void updateCharacterStream(String arg0, Reader arg1) throws SQLException {
        resultSet.updateCharacterStream(arg0, arg1);
    }

    @Override
    public void updateCharacterStream(int arg0, Reader arg1, int arg2) throws SQLException {
        resultSet.updateCharacterStream(arg0, arg1, arg2);
    }

    @Override
    public void updateCharacterStream(String arg0, Reader arg1, int arg2) throws SQLException {
        resultSet.updateCharacterStream(arg0, arg1, arg2);
    }

    @Override
    public void updateCharacterStream(int arg0, Reader arg1, long arg2) throws SQLException {
        resultSet.updateCharacterStream(arg0, arg1, arg2);
    }

    @Override
    public void updateCharacterStream(String arg0, Reader arg1, long arg2) throws SQLException {
        resultSet.updateCharacterStream(arg0, arg1, arg2);
    }

    @Override
    public void updateClob(int arg0, Clob arg1) throws SQLException {
        resultSet.updateClob(arg0, arg1);
    }

    @Override
    public void updateClob(String arg0, Clob arg1) throws SQLException {
        resultSet.updateClob(arg0, arg1);
    }

    @Override
    public void updateClob(int arg0, Reader arg1) throws SQLException {
        resultSet.updateClob(arg0, arg1);
    }

    @Override
    public void updateClob(String arg0, Reader arg1) throws SQLException {
        resultSet.updateClob(arg0, arg1);
    }

    @Override
    public void updateClob(int arg0, Reader arg1, long arg2) throws SQLException {
        resultSet.updateClob(arg0, arg1, arg2);
    }

    @Override
    public void updateClob(String arg0, Reader arg1, long arg2) throws SQLException {
        resultSet.updateClob(arg0, arg1, arg2);
    }

    @Override
    public void updateDate(int arg0, Date arg1) throws SQLException {
        resultSet.updateDate(arg0, arg1);
    }

    @Override
    public void updateDate(String arg0, Date arg1) throws SQLException {
        resultSet.updateDate(arg0, arg1);
    }

    @Override
    public void updateDouble(int arg0, double arg1) throws SQLException {
        resultSet.updateDouble(arg0, arg1);
    }

    @Override
    public void updateDouble(String arg0, double arg1) throws SQLException {
        resultSet.updateDouble(arg0, arg1);
    }

    @Override
    public void updateFloat(int arg0, float arg1) throws SQLException {
        resultSet.updateFloat(arg0, arg1);
    }

    @Override
    public void updateFloat(String arg0, float arg1) throws SQLException {
        resultSet.updateFloat(arg0, arg1);
    }

    @Override
    public void updateInt(int arg0, int arg1) throws SQLException {
        resultSet.updateInt(arg0, arg1);
    }

    @Override
    public void updateInt(String arg0, int arg1) throws SQLException {
        resultSet.updateInt(arg0, arg1);
    }

    @Override
    public void updateLong(int arg0, long arg1) throws SQLException {
        resultSet.updateLong(arg0, arg1);
    }

    @Override
    public void updateLong(String arg0, long arg1) throws SQLException {
        resultSet.updateLong(arg0, arg1);
    }

    @Override
    public void updateNCharacterStream(int arg0, Reader arg1) throws SQLException {
        resultSet.updateNCharacterStream(arg0, arg1);
    }

    @Override
    public void updateNCharacterStream(String arg0, Reader arg1) throws SQLException {
        resultSet.updateNCharacterStream(arg0, arg1);
    }

    @Override
    public void updateNCharacterStream(int arg0, Reader arg1, long arg2) throws SQLException {
        resultSet.updateNCharacterStream(arg0, arg1, arg2);
    }

    @Override
    public void updateNCharacterStream(String arg0, Reader arg1, long arg2) throws SQLException {
        resultSet.updateNCharacterStream(arg0, arg1, arg2);
    }

    @Override
    public void updateNClob(int arg0, NClob arg1) throws SQLException {
        resultSet.updateNClob(arg0, arg1);
    }

    @Override
    public void updateNClob(String arg0, NClob arg1) throws SQLException {
        resultSet.updateNClob(arg0, arg1);
    }

    @Override
    public void updateNClob(int arg0, Reader arg1) throws SQLException {
        resultSet.updateNClob(arg0, arg1);
    }

    @Override
    public void updateNClob(String arg0, Reader arg1) throws SQLException {
        resultSet.updateNClob(arg0, arg1);
    }

    @Override
    public void updateNClob(int arg0, Reader arg1, long arg2) throws SQLException {
        resultSet.updateNClob(arg0, arg1, arg2);
    }

    @Override
    public void updateNClob(String arg0, Reader arg1, long arg2) throws SQLException {
        resultSet.updateNClob(arg0, arg1, arg2);
    }

    @Override
    public void updateNString(int arg0, String arg1) throws SQLException {
        resultSet.updateNString(arg0, arg1);
    }

    @Override
    public void updateNString(String arg0, String arg1) throws SQLException {
        resultSet.updateNString(arg0, arg1);
    }

    @Override
    public void updateNull(int arg0) throws SQLException {
        resultSet.updateNull(arg0);
    }

    @Override
    public void updateNull(String arg0) throws SQLException {
        resultSet.updateNull(arg0);
    }

    @Override
    public void updateObject(int arg0, Object arg1) throws SQLException {
        resultSet.updateObject(arg0, arg1);
    }

    @Override
    public void updateObject(String arg0, Object arg1) throws SQLException {
        resultSet.updateObject(arg0, arg1);
    }

    @Override
    public void updateObject(int arg0, Object arg1, int arg2) throws SQLException {
        resultSet.updateObject(arg0, arg1, arg2);
    }

    @Override
    public void updateObject(String arg0, Object arg1, int arg2) throws SQLException {
        resultSet.updateObject(arg0, arg1, arg2);
    }

    @Override
    public void updateRef(int arg0, Ref arg1) throws SQLException {
        resultSet.updateRef(arg0, arg1);
    }

    @Override
    public void updateRef(String arg0, Ref arg1) throws SQLException {
        resultSet.updateRef(arg0, arg1);
    }

    @Override
    public void updateRow() throws SQLException {
        resultSet.updateRow();
    }

    @Override
    public void updateRowId(int arg0, RowId arg1) throws SQLException {
        resultSet.updateRowId(arg0, arg1);
    }

    @Override
    public void updateRowId(String arg0, RowId arg1) throws SQLException {
        resultSet.updateRowId(arg0, arg1);
    }

    @Override
    public void updateSQLXML(int arg0, SQLXML arg1) throws SQLException {
        resultSet.updateSQLXML(arg0, arg1);
    }

    @Override
    public void updateSQLXML(String arg0, SQLXML arg1) throws SQLException {
        resultSet.updateSQLXML(arg0, arg1);
    }

    @Override
    public void updateShort(int arg0, short arg1) throws SQLException {
        resultSet.updateShort(arg0, arg1);
    }

    @Override
    public void updateShort(String arg0, short arg1) throws SQLException {
        resultSet.updateShort(arg0, arg1);
    }

    @Override
    public void updateString(int arg0, String arg1) throws SQLException {
        resultSet.updateString(arg0, arg1);
    }

    @Override
    public void updateString(String arg0, String arg1) throws SQLException {
        resultSet.updateString(arg0, arg1);
    }

    @Override
    public void updateTime(int arg0, Time arg1) throws SQLException {
        resultSet.updateTime(arg0, arg1);
    }

    @Override
    public void updateTime(String arg0, Time arg1) throws SQLException {
        resultSet.updateTime(arg0, arg1);
    }

    @Override
    public void updateTimestamp(int arg0, Timestamp arg1) throws SQLException {
        resultSet.updateTimestamp(arg0, arg1);
    }

    @Override
    public void updateTimestamp(String arg0, Timestamp arg1) throws SQLException {
        resultSet.updateTimestamp(arg0, arg1);
    }

    @Override
    public boolean wasNull() throws SQLException {
        return resultSet.wasNull();
    }

}