package com.tangluobo.tomato.module.connect.service;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import com.tangluobo.tomato.module.connect.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * 数据库服务：管理JDBC连接，查询数据库/表/视图列表
 */
public class DatabaseService {

    // 缓存：ConnectionConfig.id -> JDBC Connection（主连接，不绑定具体数据库）
    private static final Map<String, Connection> connectionCache = new ConcurrentHashMap<>();
    // 缓存：ConnectionConfig.id + "_" + databaseName -> JDBC Connection（仅用于必须绑定数据库的场景，如用户自定义SQL）
    private static final Map<String, Connection> databaseConnectionCache = new ConcurrentHashMap<>();
    // 缓存：ConnectionConfig.id -> SshTunnel
    private static final Map<String, SshTunnel> tunnelCache = new ConcurrentHashMap<>();
    // 连接建立锁：保证同一 key 的连接建立互斥，避免并发重复建连
    private static final Map<String, Object> connectionLocks = new ConcurrentHashMap<>();
    // 连接使用锁：保证同一连接不会被多线程并发使用（JDBC Connection非线程安全）
    private static final Map<String, ReentrantLock> connectionUsageLocks = new ConcurrentHashMap<>();

    /**
     * 获取连接使用锁。调用方在后台线程中使用连接前应获取此锁，使用完毕后释放。
     * JDBC Connection不是线程安全的，并发使用会导致协议损坏和无限挂起。
     * 锁key与连接缓存key一致：PostgreSQL为 configId_databaseName，其他为 configId。
     */
    public static ReentrantLock acquireUsageLock(ConnectionConfig config, String databaseName) {
        String key = (config.getType() == ConnectType.POSTGRESQL && databaseName != null)
                ? config.getId() + "_" + databaseName
                : config.getId();
        return connectionUsageLocks.computeIfAbsent(key, k -> new ReentrantLock());
    }

    /**
     * 获取或创建JDBC主连接（不绑定具体数据库，带SSH隧道支持）。
     * 多数数据库操作（SHOW DATABASES、使用全限定名的SQL、JDBC元数据API）均复用此主连接，
     * 避免为每个数据库建立独立连接导致的握手开销。
     */
    public static Connection getConnection(ConnectionConfig config) throws Exception {
        String key = config.getId();
        Connection existing = connectionCache.get(key);
        if (existing != null && !existing.isClosed()) {
            return existing;
        }

        // 同一 key 互斥建连，避免并发线程重复建立连接
        Object lock = connectionLocks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            // 双重检查
            existing = connectionCache.get(key);
            if (existing != null && !existing.isClosed()) {
                return existing;
            }

            // 建立SSH隧道（如果启用）
            String host = config.getHost();
            int port = config.getPort();

            if (config.isUseSshTunnel()) {
                SshTunnel oldTunnel = tunnelCache.get(key);
                if (oldTunnel != null && oldTunnel.isActive()) {
                    port = oldTunnel.getForwardedLocalPort();
                    host = "127.0.0.1";
                } else {
                    // 隧道已失效或不复用：先断开旧隧道并移除缓存，确保重建得到干净的新隧道，避免端口占用与资源泄漏
                    if (oldTunnel != null) {
                        tunnelCache.remove(key);
                        try { oldTunnel.disconnect(); } catch (Exception ignored) {}
                    }
                    SshTunnel tunnel = SshTunnel.fromConfig(config);
                    int localPort = tunnel.connect();
                    tunnelCache.put(key, tunnel);
                    host = "127.0.0.1";
                    port = localPort;
                }
            }

            String url = buildJdbcUrl(config, host, port, null);
            long time=System.currentTimeMillis();
            Connection conn = DriverManager.getConnection(url, config.getUsername(), config.getPassword());
            System.out.println(System.currentTimeMillis()-time);
            connectionCache.put(key, conn);
            return conn;
        }
    }

    /**
     * 获取有权限的数据库列表
     */
    public static List<String> getDatabases(ConnectionConfig config) throws Exception {
        Connection conn = getConnection(config);
        List<String> databases = new ArrayList<>();

        if (config.getType() == ConnectType.MYSQL) {
            // MySQL: SHOW DATABASES
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SHOW DATABASES")) {
                while (rs.next()) {
                    String dbName = rs.getString(1);
                        databases.add(dbName);
                }
            }
        } else if (config.getType() == ConnectType.POSTGRESQL) {
            // PostgreSQL: 查询有权限的数据库
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT datname FROM pg_database WHERE datistemplate = false AND has_database_privilege(current_user, datname, 'CONNECT') ORDER BY datname")) {
                while (rs.next()) {
                    databases.add(rs.getString(1));
                }
            }
        } else if (config.getType() == ConnectType.ORACLE) {
            // Oracle: 查询有权限的schema
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT username FROM all_users ORDER BY username")) {
                while (rs.next()) {
                    databases.add(rs.getString(1));
                }
            }
        }

        return databases;
    }

    /**
     * 获取模式(schema)列表（仅 PostgreSQL 使用，其他类型返回空列表）。
     * PostgreSQL 必须连接到具体数据库才能查询其 schema 列表，因此使用绑定到 databaseName 的连接。
     */
    public static List<String> getSchemas(ConnectionConfig config, String databaseName) throws Exception {
        if (config.getType() != ConnectType.POSTGRESQL) {
            return Collections.emptyList();
        }
        Connection conn = getConnection(config, databaseName);
        List<String> schemas = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(
                 "SELECT schema_name FROM information_schema.schemata "
                 + "WHERE schema_name NOT LIKE 'pg\\_%' AND schema_name <> 'information_schema' "
                 + "AND has_schema_privilege(current_user, schema_name, 'USAGE') "
                 + "ORDER BY schema_name")) {
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    schemas.add(rs.getString(1));
                }
            }
        }
        return schemas;
    }

    /**
     * 获取表列表
     */
    public static List<String> getTables(ConnectionConfig config, String databaseName) throws Exception {
        return getTables(config, databaseName, null);
    }

    /**
     * 获取表列表
     * @param schemaName 模式名（PostgreSQL 使用；为 null 时 PostgreSQL 回退用 databaseName 当 schema 名）
     */
    public static List<String> getTables(ConnectionConfig config, String databaseName, String schemaName) throws Exception {
        List<String> tables = new ArrayList<>();

        if (config.getType() == ConnectType.MYSQL) {
            Connection conn = getConnection(config);
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SHOW TABLES FROM `" + databaseName + "`")) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
        } else if (config.getType() == ConnectType.POSTGRESQL) {
            String schema = schemaName != null ? schemaName : databaseName;
            Connection conn = getConnection(config, databaseName);
            // 使用 pg_class 系统目录查询，比 information_schema.tables 更可靠
            // information_schema.tables 只显示当前用户有显式权限的表，可能漏掉部分表
            // pg_class 显示所有表（包括其他用户创建但当前用户有 schema USAGE 权限的表）
            // relkind: r=普通表, p=分区表
            try (PreparedStatement stmt = conn.prepareStatement(
                     "SELECT c.relname FROM pg_class c "
                     + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                     + "WHERE n.nspname = ? AND c.relkind IN ('r','p') "
                     + "ORDER BY c.relname")) {
                stmt.setString(1, schema);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        tables.add(rs.getString(1));
                    }
                }
            }
        } else if (config.getType() == ConnectType.ORACLE) {
            Connection conn = getConnection(config);
            try (PreparedStatement stmt = conn.prepareStatement(
                     "SELECT table_name FROM all_tables WHERE owner = ? ORDER BY table_name")) {
                stmt.setString(1, databaseName);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        tables.add(rs.getString(1));
                    }
                }
            }
        }

        return tables;
    }

    /**
     * 获取视图列表
     */
    public static List<String> getViews(ConnectionConfig config, String databaseName) throws Exception {
        return getViews(config, databaseName, null);
    }

    /**
     * 获取视图列表
     * @param schemaName 模式名（PostgreSQL 使用；为 null 时 PostgreSQL 回退用 databaseName 当 schema 名）
     */
    public static List<String> getViews(ConnectionConfig config, String databaseName, String schemaName) throws Exception {
        List<String> views = new ArrayList<>();

        if (config.getType() == ConnectType.MYSQL) {
            Connection conn = getConnection(config);
            try (PreparedStatement stmt = conn.prepareStatement(
                     "SELECT TABLE_NAME FROM information_schema.VIEWS WHERE TABLE_SCHEMA = ? ORDER BY TABLE_NAME")) {
                stmt.setString(1, databaseName);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        views.add(rs.getString(1));
                    }
                }
            }
        } else if (config.getType() == ConnectType.POSTGRESQL) {
            String schema = schemaName != null ? schemaName : databaseName;
            Connection conn = getConnection(config, databaseName);
            // 使用 pg_class 查询视图，relkind: v=视图, m=物化视图
            try (PreparedStatement stmt = conn.prepareStatement(
                     "SELECT c.relname FROM pg_class c "
                     + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                     + "WHERE n.nspname = ? AND c.relkind IN ('v','m') "
                     + "ORDER BY c.relname")) {
                stmt.setString(1, schema);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        views.add(rs.getString(1));
                    }
                }
            }
        } else if (config.getType() == ConnectType.ORACLE) {
            Connection conn = getConnection(config);
            try (PreparedStatement stmt = conn.prepareStatement(
                     "SELECT view_name FROM all_views WHERE owner = ? ORDER BY view_name")) {
                stmt.setString(1, databaseName);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        views.add(rs.getString(1));
                    }
                }
            }
        }

        return views;
    }

    /**
     * 批量获取当前数据库/Schema 下所有表和视图的元数据信息，用于对象视图的详细列表。
     * @return 每个 Map 包含：name(名), type(TABLE/VIEW), engine(引擎), autoIncrement(自动递增值),
     *         updateTime(修改日期), dataLength(数据长度), rows(行数), comment(注释)
     */
    public static List<Map<String, String>> getTablesInfo(ConnectionConfig config, String databaseName, String schemaName) throws Exception {
        List<Map<String, String>> result = new ArrayList<>();
        Connection conn = (config.getType() == ConnectType.POSTGRESQL) ? getConnection(config, databaseName) : getConnection(config);
        String pgSchema = schemaName != null ? schemaName : databaseName;

        if (config.getType() == ConnectType.MYSQL) {
            String sql = "SELECT TABLE_NAME, TABLE_TYPE, ENGINE, AUTO_INCREMENT, "
                    + "DATE_FORMAT(UPDATE_TIME, '%Y-%m-%d %H:%i:%s') AS UPDATE_TIME, "
                    + "(IFNULL(DATA_LENGTH,0) + IFNULL(INDEX_LENGTH,0)) AS DATA_LENGTH, "
                    + "TABLE_ROWS, TABLE_COMMENT "
                    + "FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? "
                    + "ORDER BY TABLE_TYPE, TABLE_NAME";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, databaseName);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        Map<String, String> m = new LinkedHashMap<>();
                        m.put("name", rs.getString("TABLE_NAME"));
                        String tableType = rs.getString("TABLE_TYPE");
                        m.put("type", "VIEW".equalsIgnoreCase(tableType) ? "VIEW" : "TABLE");
                        m.put("engine", rs.getString("ENGINE") != null ? rs.getString("ENGINE") : "");
                        String ai = rs.getString("AUTO_INCREMENT");
                        m.put("autoIncrement", ai != null ? ai : "");
                        String ut = rs.getString("UPDATE_TIME");
                        m.put("updateTime", ut != null ? ut : "");
                        long dl = rs.getLong("DATA_LENGTH");
                        m.put("dataLength", dl > 0 ? formatDataLength(dl) : "");
                        long rows = rs.getLong("TABLE_ROWS");
                        m.put("rows", rs.wasNull() ? "" : String.valueOf(rows));
                        m.put("comment", rs.getString("TABLE_COMMENT") != null ? rs.getString("TABLE_COMMENT") : "");
                        result.add(m);
                    }
                }
            }
        } else if (config.getType() == ConnectType.POSTGRESQL) {
            // 表
            String tableSql = "SELECT c.relname AS name, 'TABLE' AS type, "
                    + "'' AS engine, "
                    + "'' AS autoIncrement, "
                    + "(SELECT pg_stat_last_operation_time(c.oid, 'AUTO_INCREMENT')) AS update_time, "
                    + "pg_total_relation_size(c.oid) AS data_length, "
                    + "c.reltuples::bigint AS rows, "
                    + "COALESCE(pg_catalog.obj_description(c.oid), '') AS comment "
                    + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                    + "WHERE n.nspname = ? AND c.relkind IN ('r','p') "
                    + "ORDER BY c.relname";
            try (PreparedStatement stmt = conn.prepareStatement(tableSql)) {
                stmt.setString(1, pgSchema);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        Map<String, String> m = new LinkedHashMap<>();
                        m.put("name", rs.getString("name"));
                        m.put("type", "TABLE");
                        m.put("engine", "");
                        m.put("autoIncrement", "");
                        m.put("updateTime", "");
                        long dl = rs.getLong("data_length");
                        m.put("dataLength", dl > 0 ? formatDataLength(dl) : "");
                        long rows = rs.getLong("rows");
                        m.put("rows", rs.wasNull() ? "" : String.valueOf(rows));
                        m.put("comment", rs.getString("comment") != null ? rs.getString("comment") : "");
                        result.add(m);
                    }
                }
            }
            // 视图
            String viewSql = "SELECT c.relname AS name, 'VIEW' AS type, "
                    + "'' AS engine, '' AS autoIncrement, '' AS update_time, 0 AS data_length, 0 AS rows, "
                    + "COALESCE(pg_catalog.obj_description(c.oid), '') AS comment "
                    + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                    + "WHERE n.nspname = ? AND c.relkind IN ('v','m') "
                    + "ORDER BY c.relname";
            try (PreparedStatement stmt = conn.prepareStatement(viewSql)) {
                stmt.setString(1, pgSchema);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        Map<String, String> m = new LinkedHashMap<>();
                        m.put("name", rs.getString("name"));
                        m.put("type", "VIEW");
                        m.put("engine", "");
                        m.put("autoIncrement", "");
                        m.put("updateTime", "");
                        m.put("dataLength", "");
                        m.put("rows", "");
                        m.put("comment", rs.getString("comment") != null ? rs.getString("comment") : "");
                        result.add(m);
                    }
                }
            }
        } else if (config.getType() == ConnectType.ORACLE) {
            // 表
            String tableSql = "SELECT t.table_name AS name, 'TABLE' AS type, "
                    + "'' AS engine, '' AS autoIncrement, "
                    + "TO_CHAR(t.last_analyzed, 'YYYY-MM-DD HH24:MI:SS') AS update_time, "
                    + "(t.blocks * 8192) AS data_length, t.num_rows AS rows, "
                    + "NVL(c.comments, '') AS comment "
                    + "FROM all_tables t LEFT JOIN all_tab_comments c "
                    + "ON t.owner = c.owner AND t.table_name = c.table_name AND c.table_type = 'TABLE' "
                    + "WHERE t.owner = ? ORDER BY t.table_name";
            try (PreparedStatement stmt = conn.prepareStatement(tableSql)) {
                stmt.setString(1, databaseName);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        Map<String, String> m = new LinkedHashMap<>();
                        m.put("name", rs.getString("name"));
                        m.put("type", "TABLE");
                        m.put("engine", "");
                        m.put("autoIncrement", "");
                        m.put("updateTime", rs.getString("update_time") != null ? rs.getString("update_time") : "");
                        long dl = rs.getLong("data_length");
                        m.put("dataLength", dl > 0 ? formatDataLength(dl) : "");
                        long rows = rs.getLong("rows");
                        m.put("rows", rs.wasNull() ? "" : String.valueOf(rows));
                        m.put("comment", rs.getString("comment") != null ? rs.getString("comment") : "");
                        result.add(m);
                    }
                }
            }
            // 视图
            String viewSql = "SELECT v.view_name AS name, 'VIEW' AS type, "
                    + "'' AS engine, '' AS autoIncrement, '' AS update_time, 0 AS data_length, 0 AS rows, "
                    + "NVL(c.comments, '') AS comment "
                    + "FROM all_views v LEFT JOIN all_tab_comments c "
                    + "ON v.owner = c.owner AND v.view_name = c.table_name AND c.table_type = 'VIEW' "
                    + "WHERE v.owner = ? ORDER BY v.view_name";
            try (PreparedStatement stmt = conn.prepareStatement(viewSql)) {
                stmt.setString(1, databaseName);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        Map<String, String> m = new LinkedHashMap<>();
                        m.put("name", rs.getString("name"));
                        m.put("type", "VIEW");
                        m.put("engine", "");
                        m.put("autoIncrement", "");
                        m.put("updateTime", "");
                        m.put("dataLength", "");
                        m.put("rows", "");
                        m.put("comment", rs.getString("comment") != null ? rs.getString("comment") : "");
                        result.add(m);
                    }
                }
            }
        }

        return result;
    }

    /** 将字节长度格式化为易读字符串（B/KB/MB/GB/TB） */
    private static String formatDataLength(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.2f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.2f MB", mb);
        double gb = mb / 1024.0;
        if (gb < 1024) return String.format("%.2f GB", gb);
        return String.format("%.2f TB", gb / 1024.0);
    }

    public static List<String> getFunctions(ConnectionConfig config, String databaseName) throws Exception {
        Connection conn = getConnection(config);
        List<String> functions = new ArrayList<>();

        if (config.getType() == ConnectType.MYSQL) {
            try (PreparedStatement stmt = conn.prepareStatement(
                     "SELECT ROUTINE_NAME FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA = ? AND ROUTINE_TYPE = 'FUNCTION' ORDER BY ROUTINE_NAME")) {
                stmt.setString(1, databaseName);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        functions.add(rs.getString(1));
                    }
                }
            }
        }

        return functions;
    }

    public static List<String> getEvents(ConnectionConfig config, String databaseName) throws Exception {
        Connection conn = getConnection(config);
        List<String> events = new ArrayList<>();

        if (config.getType() == ConnectType.MYSQL) {
            try (PreparedStatement stmt = conn.prepareStatement(
                     "SELECT EVENT_NAME FROM information_schema.EVENTS WHERE EVENT_SCHEMA = ? ORDER BY EVENT_NAME")) {
                stmt.setString(1, databaseName);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        events.add(rs.getString(1));
                    }
                }
            }
        }

        return events;
    }

    /**
     * 分页查询表/视图数据
     * @return TableRowData 包含列名列表和数据行
     */
    public static TableRowData queryTableData(ConnectionConfig config, String databaseName, String tableName, int page, int pageSize) throws Exception {
        return queryTableData(config, databaseName, tableName, page, pageSize, null, false);
    }

    public static TableRowData queryTableData(ConnectionConfig config, String databaseName, String tableName, int page, int pageSize, String sortColumn, boolean sortDescending) throws Exception {
        return queryTableData(config, databaseName, null, tableName, page, pageSize, sortColumn, sortDescending);
    }

    /**
     * 分页查询表/视图数据
     * @param schemaName 模式名（PostgreSQL 使用；为 null 时 PostgreSQL 回退用 databaseName 当 schema 名）
     */
    public static TableRowData queryTableData(ConnectionConfig config, String databaseName, String schemaName, String tableName, int page, int pageSize, String sortColumn, boolean sortDescending) throws Exception {
        // PostgreSQL 必须绑定到具体数据库才能查询该库的表；MySQL/Oracle 复用主连接（SQL 使用全限定名）
        Connection conn = (config.getType() == ConnectType.POSTGRESQL)
                ? getConnection(config, databaseName)
                : getConnection(config);
        TableRowData result = new TableRowData();

        // 获取总行数
        long totalCount;
        if (config.getType() == ConnectType.POSTGRESQL) {
            // PostgreSQL: 使用 pg_class.reltuples 估算行数，避免 COUNT(*) 全表扫描导致大表卡顿
            totalCount = getEstimatedRowCount(conn, schemaName, databaseName, tableName);
        } else {
            String countSql = buildCountSql(config, databaseName, schemaName, tableName);
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(countSql)) {
                rs.next();
                totalCount = rs.getLong(1);
            }
        }

        result.setTotalCount(totalCount);
        result.setPage(page);
        result.setPageSize(pageSize);
        result.setTotalPages((int) Math.ceil((double) totalCount / pageSize));

        // 分页查询数据
        String dataSql = buildPageSql(config, databaseName, schemaName, tableName, page, pageSize, sortColumn, sortDescending);
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(dataSql)) {
            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();

            // 获取列名
            List<String> columnNames = new ArrayList<>();
            for (int i = 1; i <= columnCount; i++) {
                columnNames.add(metaData.getColumnLabel(i));
            }
            result.setColumnNames(columnNames);

            // 获取数据行
            ObservableList<ObservableList<String>> rows = FXCollections.observableArrayList();
            while (rs.next()) {
                ObservableList<String> row = FXCollections.observableArrayList();
                for (int i = 1; i <= columnCount; i++) {
                    String value = rs.getString(i);
                    row.add(value != null ? value : "NULL");
                }
                rows.add(row);
            }
            result.setRows(rows);
        }

        // PostgreSQL: 根据实际返回行数修正分页信息（估算值可能不准确或过时）
        if (config.getType() == ConnectType.POSTGRESQL) {
            int actualRows = result.getRows().size();
            if (actualRows == pageSize && result.getTotalPages() <= page) {
                // 返回了满页但估算总页数不足，说明还有更多数据
                result.setTotalPages(page + 1);
                if (totalCount < (long) page * pageSize + 1) {
                    result.setTotalCount((long) page * pageSize + 1);
                }
            } else if (actualRows < pageSize && result.getTotalPages() > page) {
                // 不足一页，这是最后一页
                result.setTotalPages(page);
                long actualCount = (long) (page - 1) * pageSize + actualRows;
                if (totalCount > actualCount) {
                    result.setTotalCount(actualCount);
                }
            }
            // 如果估算值为0但实际有数据，修正
            if (totalCount == 0 && actualRows > 0) {
                result.setTotalCount((long) (page - 1) * pageSize + actualRows);
                result.setTotalPages(actualRows == pageSize ? page + 1 : page);
            }
        }

        return result;
    }

    /**
     * 获取PostgreSQL表的估算行数（基于pg_class.reltuples，由ANALYZE更新）。
     * 避免对大表执行 COUNT(*) 全表扫描导致的性能问题。
     * 估算值可能不完全准确，调用方会根据实际返回行数修正分页信息。
     */
    private static long getEstimatedRowCount(Connection conn, String schemaName, String databaseName, String tableName) {
        String pgSchema = schemaName != null ? schemaName : databaseName;
        String sql = "SELECT GREATEST(c.reltuples, 0)::bigint FROM pg_class c "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE n.nspname = ? AND c.relname = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, pgSchema);
            stmt.setString(2, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        } catch (SQLException e) {
            // 估算失败时返回0，调用方会根据实际数据修正
        }
        return 0;
    }

    /**
     * 获取指定数据库的JDBC连接（绑定到具体数据库，用于用户自定义SQL等需要当前数据库上下文的场景）。
     * 注意：表数据查询、表结构查询、主键查询等使用全限定名或JDBC元数据API的操作应改用
     * {@link #getConnection(ConnectionConfig)} 复用主连接，避免每个数据库都建立独立连接。
     */
    public static Connection getConnection(ConnectionConfig config, String databaseName) throws Exception {
        String key = config.getId() + "_" + databaseName;
        Connection existing = databaseConnectionCache.get(key);
        if (existing != null && !existing.isClosed()) {
            return existing;
        }

        Object lock = connectionLocks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            existing = databaseConnectionCache.get(key);
            if (existing != null && !existing.isClosed()) {
                return existing;
            }

            String host = config.getHost();
            int port = config.getPort();

            if (config.isUseSshTunnel()) {
                String tunnelKey = config.getId();
                SshTunnel oldTunnel = tunnelCache.get(tunnelKey);
                if (oldTunnel != null && oldTunnel.isActive()) {
                    port = oldTunnel.getForwardedLocalPort();
                    host = "127.0.0.1";
                } else {
                    // 隧道已失效或不复用：先断开旧隧道并移除缓存，确保重建得到干净的新隧道，避免端口占用与资源泄漏
                    if (oldTunnel != null) {
                        tunnelCache.remove(tunnelKey);
                        try { oldTunnel.disconnect(); } catch (Exception ignored) {}
                    }
                    SshTunnel tunnel = SshTunnel.fromConfig(config);
                    int localPort = tunnel.connect();
                    tunnelCache.put(tunnelKey, tunnel);
                    host = "127.0.0.1";
                    port = localPort;
                }
            }

            String url = buildJdbcUrl(config, host, port, databaseName);
            Connection conn = DriverManager.getConnection(url, config.getUsername(), config.getPassword());
            databaseConnectionCache.put(key, conn);
            return conn;
        }
    }

    /**
     * 构建计数SQL
     */
    private static String buildCountSql(ConnectionConfig config, String databaseName, String tableName) {
        return buildCountSql(config, databaseName, null, tableName);
    }

    /**
     * 构建计数SQL
     * @param schemaName 模式名（PostgreSQL 使用；为 null 时回退用 databaseName 当 schema 名）
     */
    private static String buildCountSql(ConnectionConfig config, String databaseName, String schemaName, String tableName) {
        String pgSchema = schemaName != null ? schemaName : databaseName;
        return switch (config.getType()) {
            case MYSQL -> "SELECT COUNT(*) FROM `" + databaseName + "`.`" + tableName + "`";
            case POSTGRESQL -> "SELECT COUNT(*) FROM \"" + pgSchema + "\".\"" + tableName + "\"";
            case ORACLE -> "SELECT COUNT(*) FROM \"" + databaseName + "\".\"" + tableName + "\"";
            default -> throw new IllegalArgumentException("Unsupported database type: " + config.getType());
        };
    }

    /**
     * 获取用于执行用户SQL的连接：MySQL复用主连接并setCatalog切换数据库，
     * PostgreSQL/Oracle使用绑定到具体数据库的连接。
     */
    private static Connection getConnectionForQuery(ConnectionConfig config, String databaseName) throws Exception {
        if (config.getType() == ConnectType.MYSQL) {
            Connection conn = getConnection(config);
            conn.setCatalog(databaseName);
            return conn;
        }
        return getConnection(config, databaseName);
    }

    /**
     * 执行自定义SQL查询（SELECT语句），返回结果
     */
    public static TableRowData executeSqlQuery(ConnectionConfig config, String databaseName, String sql, int pageSize) throws Exception {
        Connection conn = getConnectionForQuery(config, databaseName);
        long startTime = System.currentTimeMillis();

        TableRowData result = new TableRowData();

        try (Statement stmt = conn.createStatement()) {
            stmt.setMaxRows(pageSize);
            boolean hasResultSet = stmt.execute(sql);

            long queryTime = System.currentTimeMillis() - startTime;

            if (hasResultSet) {
                try (ResultSet rs = stmt.getResultSet()) {
                    ResultSetMetaData metaData = rs.getMetaData();
                    int columnCount = metaData.getColumnCount();

                    List<String> columnNames = new ArrayList<>();
                    for (int i = 1; i <= columnCount; i++) {
                        columnNames.add(metaData.getColumnLabel(i));
                    }
                    result.setColumnNames(columnNames);

                    ObservableList<ObservableList<String>> rows = FXCollections.observableArrayList();
                    long count = 0;
                    while (rs.next() && count < pageSize) {
                        ObservableList<String> row = FXCollections.observableArrayList();
                        for (int i = 1; i <= columnCount; i++) {
                            String val = rs.getString(i);
                            row.add(val != null ? val : "");
                        }
                        rows.add(row);
                        count++;
                    }
                    result.setRows(rows);
                    result.setTotalCount(count);
                }
            } else {
                int updateCount = stmt.getUpdateCount();
                result.setColumnNames(List.of("结果"));
                ObservableList<ObservableList<String>> rows = FXCollections.observableArrayList();
                ObservableList<String> row = FXCollections.observableArrayList();
                row.add(updateCount >= 0 ? updateCount + " 行受影响" : "执行成功");
                rows.add(row);
                result.setRows(rows);
                result.setTotalCount(1);
            }

            result.setPage(1);
            result.setPageSize(pageSize);
            result.setTotalPages(1);
            result.setQueryTime(queryTime);
        }

        return result;
    }

    /**
     * 构建分页查询SQL
     */
    private static String buildPageSql(ConnectionConfig config, String databaseName, String tableName, int page, int pageSize, String sortColumn, boolean sortDescending) {
        return buildPageSql(config, databaseName, null, tableName, page, pageSize, sortColumn, sortDescending);
    }

    /**
     * 构建分页查询SQL
     * @param schemaName 模式名（PostgreSQL 使用；为 null 时回退用 databaseName 当 schema 名）
     */
    private static String buildPageSql(ConnectionConfig config, String databaseName, String schemaName, String tableName, int page, int pageSize, String sortColumn, boolean sortDescending) {
        int offset = (page - 1) * pageSize;
        String orderBy = "";
        if (sortColumn != null && !sortColumn.isEmpty()) {
            String quotedCol = switch (config.getType()) {
                case MYSQL -> "`" + sortColumn + "`";
                case POSTGRESQL, ORACLE -> "\"" + sortColumn + "\"";
                default -> sortColumn;
            };
            orderBy = " ORDER BY " + quotedCol + (sortDescending ? " DESC" : " ASC");
        }
        final String order = orderBy;
        String pgSchema = schemaName != null ? schemaName : databaseName;
        return switch (config.getType()) {
            case MYSQL -> "SELECT * FROM `" + databaseName + "`.`" + tableName + "`" + order + " LIMIT " + pageSize + " OFFSET " + offset;
            case POSTGRESQL -> "SELECT * FROM \"" + pgSchema + "\".\"" + tableName + "\"" + order + " LIMIT " + pageSize + " OFFSET " + offset;
            case ORACLE -> "SELECT * FROM (SELECT a.*, ROWNUM rn FROM \"" + databaseName + "\".\"" + tableName + "\" a WHERE ROWNUM <= " + (offset + pageSize) + order + ") WHERE rn > " + offset;
            default -> throw new IllegalArgumentException("Unsupported database type: " + config.getType());
        };
    }

    /**
     * 删除多个表
     * @return 成功删除的表名列表
     */
    public static List<String> dropTables(ConnectionConfig config, String databaseName, List<String> tableNames) throws Exception {
        return dropTables(config, databaseName, null, tableNames);
    }

    /**
     * 删除多个表
     * @param schemaName 模式名（PostgreSQL 使用；为 null 时回退用 databaseName 当 schema 名）
     * @return 成功删除的表名列表
     */
    public static List<String> dropTables(ConnectionConfig config, String databaseName, String schemaName, List<String> tableNames) throws Exception {
        // PostgreSQL 绑定到具体数据库；MySQL/Oracle 复用主连接（SQL 使用全限定名）
        Connection conn = (config.getType() == ConnectType.POSTGRESQL)
                ? getConnection(config, databaseName)
                : getConnection(config);
        List<String> dropped = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (String tableName : tableNames) {
            String sql = buildDropTableSql(config, databaseName, schemaName, tableName);
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(sql);
                dropped.add(tableName);
            } catch (Exception e) {
                errors.add(tableName + ": " + e.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            throw new RuntimeException("部分表删除失败:\n" + String.join("\n", errors));
        }
        return dropped;
    }

    /**
     * 清空多个表的数据（DELETE FROM，可回滚，不重置自增列）
     * @return 成功清空的表名列表
     */
    public static List<String> clearTables(ConnectionConfig config, String databaseName, List<String> tableNames) throws Exception {
        return clearTables(config, databaseName, null, tableNames);
    }

    /**
     * 清空多个表的数据（DELETE FROM，可回滚，不重置自增列）
     * @param schemaName 模式名（PostgreSQL 使用；为 null 时回退用 databaseName 当 schema 名）
     * @return 成功清空的表名列表
     */
    public static List<String> clearTables(ConnectionConfig config, String databaseName, String schemaName, List<String> tableNames) throws Exception {
        Connection conn = (config.getType() == ConnectType.POSTGRESQL)
                ? getConnection(config, databaseName)
                : getConnection(config);
        List<String> cleared = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (String tableName : tableNames) {
            String sql = buildClearTableSql(config, databaseName, schemaName, tableName);
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(sql);
                cleared.add(tableName);
            } catch (Exception e) {
                errors.add(tableName + ": " + e.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            throw new RuntimeException("部分表清空失败:\n" + String.join("\n", errors));
        }
        return cleared;
    }

    /**
     * 截断多个表（TRUNCATE TABLE，不可回滚，重置自增列）
     * @return 成功截断的表名列表
     */
    public static List<String> truncateTables(ConnectionConfig config, String databaseName, List<String> tableNames) throws Exception {
        return truncateTables(config, databaseName, null, tableNames);
    }

    /**
     * 截断多个表（TRUNCATE TABLE，不可回滚，重置自增列）
     * @param schemaName 模式名（PostgreSQL 使用；为 null 时回退用 databaseName 当 schema 名）
     * @return 成功截断的表名列表
     */
    public static List<String> truncateTables(ConnectionConfig config, String databaseName, String schemaName, List<String> tableNames) throws Exception {
        Connection conn = (config.getType() == ConnectType.POSTGRESQL)
                ? getConnection(config, databaseName)
                : getConnection(config);
        List<String> truncated = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (String tableName : tableNames) {
            String sql = buildTruncateTableSql(config, databaseName, schemaName, tableName);
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(sql);
                truncated.add(tableName);
            } catch (Exception e) {
                errors.add(tableName + ": " + e.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            throw new RuntimeException("部分表截断失败:\n" + String.join("\n", errors));
        }
        return truncated;
    }

    /**
     * 删除多个视图
     * @return 成功删除的视图名列表
     */
    public static List<String> dropViews(ConnectionConfig config, String databaseName, List<String> viewNames) throws Exception {
        return dropViews(config, databaseName, null, viewNames);
    }

    /**
     * 删除多个视图
     * @param schemaName 模式名（PostgreSQL 使用；为 null 时回退用 databaseName 当 schema 名）
     * @return 成功删除的视图名列表
     */
    public static List<String> dropViews(ConnectionConfig config, String databaseName, String schemaName, List<String> viewNames) throws Exception {
        Connection conn = (config.getType() == ConnectType.POSTGRESQL)
                ? getConnection(config, databaseName)
                : getConnection(config);
        List<String> dropped = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (String viewName : viewNames) {
            String sql = buildDropViewSql(config, databaseName, schemaName, viewName);
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(sql);
                dropped.add(viewName);
            } catch (Exception e) {
                errors.add(viewName + ": " + e.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            throw new RuntimeException("部分视图删除失败:\n" + String.join("\n", errors));
        }
        return dropped;
    }

    /**
     * 构建删除表SQL
     */
    private static String buildDropTableSql(ConnectionConfig config, String databaseName, String tableName) {
        return buildDropTableSql(config, databaseName, null, tableName);
    }

    private static String buildDropTableSql(ConnectionConfig config, String databaseName, String schemaName, String tableName) {
        String pgSchema = schemaName != null ? schemaName : databaseName;
        return switch (config.getType()) {
            case MYSQL -> "DROP TABLE `" + databaseName + "`.`" + tableName + "`";
            case POSTGRESQL -> "DROP TABLE \"" + pgSchema + "\".\"" + tableName + "\"";
            case ORACLE -> "DROP TABLE \"" + databaseName + "\".\"" + tableName + "\"";
            default -> throw new IllegalArgumentException("Unsupported database type: " + config.getType());
        };
    }

    /**
     * 构建清空表数据SQL（DELETE FROM）
     */
    private static String buildClearTableSql(ConnectionConfig config, String databaseName, String schemaName, String tableName) {
        String pgSchema = schemaName != null ? schemaName : databaseName;
        return switch (config.getType()) {
            case MYSQL -> "DELETE FROM `" + databaseName + "`.`" + tableName + "`";
            case POSTGRESQL -> "DELETE FROM \"" + pgSchema + "\".\"" + tableName + "\"";
            case ORACLE -> "DELETE FROM \"" + databaseName + "\".\"" + tableName + "\"";
            default -> throw new IllegalArgumentException("Unsupported database type: " + config.getType());
        };
    }

    /**
     * 构建截断表SQL（TRUNCATE TABLE）
     */
    private static String buildTruncateTableSql(ConnectionConfig config, String databaseName, String schemaName, String tableName) {
        String pgSchema = schemaName != null ? schemaName : databaseName;
        return switch (config.getType()) {
            case MYSQL -> "TRUNCATE TABLE `" + databaseName + "`.`" + tableName + "`";
            case POSTGRESQL -> "TRUNCATE TABLE \"" + pgSchema + "\".\"" + tableName + "\"";
            case ORACLE -> "TRUNCATE TABLE \"" + databaseName + "\".\"" + tableName + "\"";
            default -> throw new IllegalArgumentException("Unsupported database type: " + config.getType());
        };
    }

    /**
     * 重命名表
     */
    public static void renameTable(ConnectionConfig config, String databaseName, String oldTableName, String newTableName) throws Exception {
        renameTable(config, databaseName, null, oldTableName, newTableName);
    }

    /**
     * 重命名表
     * @param schemaName 模式名（PostgreSQL 使用；为 null 时回退用 databaseName 当 schema 名）
     */
    public static void renameTable(ConnectionConfig config, String databaseName, String schemaName, String oldTableName, String newTableName) throws Exception {
        Connection conn = (config.getType() == ConnectType.POSTGRESQL)
                ? getConnection(config, databaseName)
                : getConnection(config);
        String pgSchema = schemaName != null ? schemaName : databaseName;
        String sql = switch (config.getType()) {
            case MYSQL -> "RENAME TABLE `" + databaseName + "`.`" + oldTableName + "` TO `" + databaseName + "`.`" + newTableName + "`";
            case POSTGRESQL -> "ALTER TABLE \"" + pgSchema + "\".\"" + oldTableName + "\" RENAME TO \"" + newTableName + "\"";
            case ORACLE -> "ALTER TABLE \"" + databaseName + "\".\"" + oldTableName + "\" RENAME TO \"" + newTableName + "\"";
            default -> throw new IllegalArgumentException("Unsupported database type: " + config.getType());
        };
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(sql);
        }
    }

    /**
     * 重命名视图
     */
    public static void renameView(ConnectionConfig config, String databaseName, String oldViewName, String newViewName) throws Exception {
        renameView(config, databaseName, null, oldViewName, newViewName);
    }

    /**
     * 重命名视图
     * @param schemaName 模式名（PostgreSQL 使用；为 null 时回退用 databaseName 当 schema 名）
     */
    public static void renameView(ConnectionConfig config, String databaseName, String schemaName, String oldViewName, String newViewName) throws Exception {
        Connection conn = (config.getType() == ConnectType.POSTGRESQL)
                ? getConnection(config, databaseName)
                : getConnection(config);
        String pgSchema = schemaName != null ? schemaName : databaseName;
        String sql = switch (config.getType()) {
            case MYSQL -> "RENAME TABLE `" + databaseName + "`.`" + oldViewName + "` TO `" + databaseName + "`.`" + newViewName + "`";
            case POSTGRESQL -> "ALTER VIEW \"" + pgSchema + "\".\"" + oldViewName + "\" RENAME TO \"" + newViewName + "\"";
            case ORACLE -> "RENAME \"" + oldViewName + "\" TO \"" + newViewName + "\"";
            default -> throw new IllegalArgumentException("Unsupported database type: " + config.getType());
        };
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(sql);
        }
    }

    /**
     * 构建删除视图SQL
     */
    private static String buildDropViewSql(ConnectionConfig config, String databaseName, String viewName) {
        return buildDropViewSql(config, databaseName, null, viewName);
    }

    private static String buildDropViewSql(ConnectionConfig config, String databaseName, String schemaName, String viewName) {
        String pgSchema = schemaName != null ? schemaName : databaseName;
        return switch (config.getType()) {
            case MYSQL -> "DROP VIEW `" + databaseName + "`.`" + viewName + "`";
            case POSTGRESQL -> "DROP VIEW \"" + pgSchema + "\".\"" + viewName + "\"";
            case ORACLE -> "DROP VIEW \"" + databaseName + "\".\"" + viewName + "\"";
            default -> throw new IllegalArgumentException("Unsupported database type: " + config.getType());
        };
    }

    /**
     * 创建数据库
     */
    public static void createDatabase(ConnectionConfig config, String databaseName, String charset, String collation) throws Exception {
        String sql = buildCreateDatabaseSql(config, databaseName, charset, collation);
        Connection conn = getConnection(config);
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(sql);
        }
    }

    /**
     * 修改数据库字符集/排序规则
     */
    public static void alterDatabase(ConnectionConfig config, String databaseName, String charset, String collation) throws Exception {
        StringBuilder sql = new StringBuilder();
        if (config.getType() == ConnectType.MYSQL) {
            sql.append("ALTER DATABASE `").append(databaseName).append("`");
            if (charset != null && !charset.isEmpty()) {
                sql.append(" CHARACTER SET ").append(charset);
            }
            if (collation != null && !collation.isEmpty()) {
                sql.append(" COLLATE ").append(collation);
            }
        } else if (config.getType() == ConnectType.POSTGRESQL) {
            sql.append("ALTER DATABASE \"").append(databaseName).append("\"");
            if (charset != null && !charset.isEmpty()) {
                sql.append(" SET encoding = '").append(charset).append("'");
            }
        } else if (config.getType() == ConnectType.ORACLE) {
            // Oracle不支持ALTER DATABASE修改字符集，跳过
            return;
        }
        if (sql.length() > 0) {
            Connection conn = getConnection(config);
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(sql.toString());
            }
        }
    }

    /**
     * 删除数据库
     */
    public static void dropDatabase(ConnectionConfig config, String databaseName) throws Exception {
        String sql = buildDropDatabaseSql(config, databaseName);
        Connection conn = getConnection(config);
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(sql);
        }
    }

    /**
     * 获取数据库的当前字符集和排序规则
     */
    public static String[] getDatabaseCharsetCollation(ConnectionConfig config, String databaseName) throws Exception {
        // 查询 information_schema 并通过参数传入 databaseName，复用主连接
        Connection conn = getConnection(config);
        String charset = null;
        String collation = null;

        if (config.getType() == ConnectType.MYSQL) {
            try (PreparedStatement stmt = conn.prepareStatement(
                     "SELECT DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = ?")) {
                stmt.setString(1, databaseName);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        charset = rs.getString("DEFAULT_CHARACTER_SET_NAME");
                        collation = rs.getString("DEFAULT_COLLATION_NAME");
                    }
                }
            }
        } else if (config.getType() == ConnectType.POSTGRESQL) {
            try (PreparedStatement stmt = conn.prepareStatement(
                     "SELECT pg_encoding_to_char(encoding) AS encoding, datcollate FROM pg_database WHERE datname = ?")) {
                stmt.setString(1, databaseName);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        charset = rs.getString("encoding");
                        collation = rs.getString("datcollate");
                    }
                }
            }
        }

        return new String[]{charset, collation};
    }

    /**
     * 获取服务器支持的字符集及对应排序规则
     */
    public static Map<String, List<String>> getCharsets(ConnectionConfig config) throws Exception {
        Connection conn = getConnection(config);
        Map<String, List<String>> result = new LinkedHashMap<>();

        if (config.getType() == ConnectType.MYSQL) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT CHARACTER_SET_NAME, COLLATION_NAME FROM information_schema.COLLATIONS ORDER BY CHARACTER_SET_NAME, COLLATION_NAME")) {
                while (rs.next()) {
                    String charsetName = rs.getString("CHARACTER_SET_NAME");
                    String collationName = rs.getString("COLLATION_NAME");
                    result.computeIfAbsent(charsetName, k -> new ArrayList<>()).add(collationName);
                }
            }
        } else if (config.getType() == ConnectType.POSTGRESQL) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT DISTINCT encodingname, collation_name FROM pg_collation c JOIN pg_encodings e ON c.collencoding = e.encoding ORDER BY encodingname, collation_name")) {
                while (rs.next()) {
                    String charsetName = rs.getString("encodingname");
                    String collationName = rs.getString("collation_name");
                    result.computeIfAbsent(charsetName, k -> new ArrayList<>()).add(collationName);
                }
            }
        }

        return result;
    }

    /**
     * 构建创建数据库SQL
     */
    private static String buildCreateDatabaseSql(ConnectionConfig config, String databaseName, String charset, String collation) {
        StringBuilder sql = new StringBuilder();
        if (config.getType() == ConnectType.MYSQL) {
            sql.append("CREATE DATABASE `").append(databaseName).append("`");
            if (charset != null && !charset.isEmpty()) {
                sql.append(" CHARACTER SET ").append(charset);
            }
            if (collation != null && !collation.isEmpty()) {
                sql.append(" COLLATE ").append(collation);
            }
        } else if (config.getType() == ConnectType.POSTGRESQL) {
            sql.append("CREATE DATABASE \"").append(databaseName).append("\"");
            if (charset != null && !charset.isEmpty()) {
                sql.append(" ENCODING '").append(charset).append("'");
            }
            if (collation != null && !collation.isEmpty()) {
                sql.append(" LC_COLLATE '").append(collation).append("'");
            }
        } else if (config.getType() == ConnectType.ORACLE) {
            sql.append("CREATE USER \"").append(databaseName).append("\" IDENTIFIED BY \"").append(databaseName).append("\"");
        }
        return sql.toString();
    }

    /**
     * 构建删除数据库SQL
     */
    private static String buildDropDatabaseSql(ConnectionConfig config, String databaseName) {
        return switch (config.getType()) {
            case MYSQL -> "DROP DATABASE `" + databaseName + "`";
            case POSTGRESQL -> "DROP DATABASE \"" + databaseName + "\"";
            case ORACLE -> "DROP USER \"" + databaseName + "\" CASCADE";
            default -> throw new IllegalArgumentException("Unsupported database type: " + config.getType());
        };
    }

    /**
     * 关闭指定连接配置的JDBC连接和SSH隧道。
     * 会同时关闭主连接、所有数据库专用连接，以及SSH隧道。
     */
    public static void closeConnection(String configId) {
        // 关闭主连接
        Connection conn = connectionCache.remove(configId);
        if (conn != null) {
            try { conn.close(); } catch (Exception ignored) {}
        }
        // 关闭该配置下所有数据库专用连接（key 形如 configId_databaseName）
        String prefix = configId + "_";
        Iterator<Map.Entry<String, Connection>> it = databaseConnectionCache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Connection> entry = it.next();
            if (entry.getKey().startsWith(prefix)) {
                try { entry.getValue().close(); } catch (Exception ignored) {}
                it.remove();
            }
        }
        // 关闭SSH隧道
        SshTunnel tunnel = tunnelCache.remove(configId);
        if (tunnel != null) {
            tunnel.disconnect();
        }
        // 清理锁
        connectionLocks.remove(configId);
    }

    /**
     * 检查连接是否活跃（基于主连接判断）
     */
    public static boolean isConnectionActive(String configId) {
        Connection conn = connectionCache.get(configId);
        if (conn != null) {
            try {
                return !conn.isClosed() && conn.isValid(3);
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    /**
     * 执行多条SQL语句，每条独立执行，一条失败不中断后续
     */
    public static MultiStatementResult executeMultiSqlQuery(ConnectionConfig config, String databaseName, String sql, int pageSize) throws Exception {
        List<String> statements = SqlSplitter.split(sql);
        MultiStatementResult multiResult = new MultiStatementResult();
        List<SqlStatementResult> results = new ArrayList<>();

        long totalStart = System.currentTimeMillis();
        Connection conn = getConnectionForQuery(config, databaseName);

        for (String stmt : statements) {
            SqlStatementResult sr = new SqlStatementResult();
            sr.setSql(stmt);
            sr.setSelect(SqlSplitter.isSelectStatement(stmt));

            // 尝试从SELECT语句中提取源表名，用于右键删除行
            if (sr.isSelect()) {
                sr.setSourceTableName(SqlSplitter.extractTableName(stmt));
            }

            long start = System.currentTimeMillis();
            try (Statement jdbcStmt = conn.createStatement()) {
                jdbcStmt.setMaxRows(pageSize);
                boolean hasResultSet = jdbcStmt.execute(stmt);
                long queryTime = System.currentTimeMillis() - start;

                sr.setSuccess(true);
                sr.setQueryTime(queryTime);
                sr.setHasResultSet(hasResultSet);

                if (hasResultSet) {
                    try (ResultSet rs = jdbcStmt.getResultSet()) {
                        TableRowData result = new TableRowData();
                        ResultSetMetaData metaData = rs.getMetaData();
                        int columnCount = metaData.getColumnCount();

                        List<String> columnNames = new ArrayList<>();
                        for (int i = 1; i <= columnCount; i++) {
                            columnNames.add(metaData.getColumnLabel(i));
                        }
                        result.setColumnNames(columnNames);

                        ObservableList<ObservableList<String>> rows = FXCollections.observableArrayList();
                        long count = 0;
                        while (rs.next() && count < pageSize) {
                            ObservableList<String> row = FXCollections.observableArrayList();
                            for (int i = 1; i <= columnCount; i++) {
                                String val = rs.getString(i);
                                row.add(val != null ? val : "");
                            }
                            rows.add(row);
                            count++;
                        }
                        result.setRows(rows);
                        result.setTotalCount(count);
                        result.setQueryTime(queryTime);
                        sr.setResultData(result);
                    }
                } else {
                    int updateCount = jdbcStmt.getUpdateCount();
                    sr.setUpdateCount(updateCount);
                }
            } catch (Exception e) {
                long queryTime = System.currentTimeMillis() - start;
                sr.setSuccess(false);
                sr.setQueryTime(queryTime);
                sr.setErrorMessage(e.getMessage());
            }

            results.add(sr);
        }

        multiResult.setResults(results);
        multiResult.setTotalTime(System.currentTimeMillis() - totalStart);
        return multiResult;
    }

    /**
     * 执行EXPLAIN查询，返回执行计划
     */
    public static TableRowData executeExplainQuery(ConnectionConfig config, String databaseName, String sql) {
        try {
            return executeSqlQuery(config, databaseName, "EXPLAIN " + sql, 1000);
        } catch (Exception e) {
            TableRowData result = new TableRowData();
            result.setColumnNames(List.of("错误"));
            ObservableList<ObservableList<String>> rows = FXCollections.observableArrayList();
            rows.add(FXCollections.observableArrayList(e.getMessage()));
            result.setRows(rows);
            result.setTotalCount(1);
            return result;
        }
    }

    /**
     * 获取服务器状态信息
     */
    public static TableRowData executeStatusQuery(ConnectionConfig config, String databaseName) {
        try {
            if (config.getType() == ConnectType.MYSQL) {
                return executeSqlQuery(config, databaseName, "SHOW STATUS", 1000);
            } else if (config.getType() == ConnectType.POSTGRESQL) {
                return executeSqlQuery(config, databaseName, "SELECT name, setting, short_desc AS \"Description\", category FROM pg_settings ORDER BY category, name", 1000);
            } else {
                TableRowData result = new TableRowData();
                result.setColumnNames(List.of("信息"));
                ObservableList<ObservableList<String>> rows = FXCollections.observableArrayList();
                rows.add(FXCollections.observableArrayList("当前数据库类型不支持状态查询"));
                result.setRows(rows);
                result.setTotalCount(1);
                return result;
            }
        } catch (Exception e) {
            TableRowData result = new TableRowData();
            result.setColumnNames(List.of("错误"));
            ObservableList<ObservableList<String>> rows = FXCollections.observableArrayList();
            rows.add(FXCollections.observableArrayList(e.getMessage()));
            result.setRows(rows);
            result.setTotalCount(1);
            return result;
        }
    }

    /**
     * 获取表的主键列名列表
     * @return 主键列名列表，若无主键返回空列表
     */
    public static List<String> getPrimaryKeys(ConnectionConfig config, String databaseName, String tableName) throws Exception {
        return getPrimaryKeys(config, databaseName, null, tableName);
    }

    /**
     * 获取表的主键列名列表
     * @param schemaName 模式名（PostgreSQL 使用；为 null 时回退用 databaseName 当 schema 名）
     */
    public static List<String> getPrimaryKeys(ConnectionConfig config, String databaseName, String schemaName, String tableName) throws Exception {
        // PostgreSQL 绑定到具体数据库，并传入 schema 给 JDBC 元数据；MySQL/Oracle 复用主连接
        Connection conn = (config.getType() == ConnectType.POSTGRESQL)
                ? getConnection(config, databaseName)
                : getConnection(config);
        List<String> primaryKeys = new ArrayList<>();

        String catalog = databaseName;
        String schema = null;
        if (config.getType() == ConnectType.POSTGRESQL) {
            catalog = null;
            schema = schemaName != null ? schemaName : databaseName;
        }

        try (ResultSet rs = conn.getMetaData().getPrimaryKeys(catalog, schema, tableName)) {
            while (rs.next()) {
                primaryKeys.add(rs.getString("COLUMN_NAME"));
            }
        }

        return primaryKeys;
    }

    /**
     * 根据主键删除指定行
     * @param config 连接配置
     * @param databaseName 数据库名
     * @param tableName 表名
     * @param primaryKeyColumns 主键列名列表
     * @param columnNames 所有列名列表
     * @param rows 要删除的行数据（每行为列值列表，顺序与columnNames对应）
     * @return 删除的行数
     */
    public static int deleteRowsByPrimaryKeys(ConnectionConfig config, String databaseName, String tableName,
                                               List<String> primaryKeyColumns, List<String> columnNames,
                                               List<ObservableList<String>> rows) throws Exception {
        return deleteRowsByPrimaryKeys(config, databaseName, null, tableName, primaryKeyColumns, columnNames, rows);
    }

    /**
     * 根据主键删除指定行
     * @param schemaName 模式名（PostgreSQL 使用；为 null 时回退用 databaseName 当 schema 名）
     */
    public static int deleteRowsByPrimaryKeys(ConnectionConfig config, String databaseName, String schemaName, String tableName,
                                               List<String> primaryKeyColumns, List<String> columnNames,
                                               List<ObservableList<String>> rows) throws Exception {
        // PostgreSQL 绑定到具体数据库；MySQL/Oracle 复用主连接（SQL 使用全限定名）
        Connection conn = (config.getType() == ConnectType.POSTGRESQL)
                ? getConnection(config, databaseName)
                : getConnection(config);
        String pgSchema = schemaName != null ? schemaName : databaseName;
        int totalDeleted = 0;

        // 构建主键列在columnNames中的索引
        List<Integer> pkIndexes = new ArrayList<>();
        for (String pkCol : primaryKeyColumns) {
            int idx = -1;
            for (int i = 0; i < columnNames.size(); i++) {
                if (columnNames.get(i).equalsIgnoreCase(pkCol)) {
                    idx = i;
                    break;
                }
            }
            if (idx < 0) {
                throw new RuntimeException("主键列 " + pkCol + " 在结果集中未找到");
            }
            pkIndexes.add(idx);
        }

        // 构建DELETE语句的WHERE部分：WHERE pk1=? AND pk2=?
        String qualifiedTable = switch (config.getType()) {
            case MYSQL -> "`" + databaseName + "`.`" + tableName + "`";
            case POSTGRESQL -> "\"" + pgSchema + "\".\"" + tableName + "\"";
            case ORACLE -> "\"" + databaseName + "\".\"" + tableName + "\"";
            default -> throw new IllegalArgumentException("Unsupported database type: " + config.getType());
        };

        StringBuilder whereClause = new StringBuilder();
        for (int i = 0; i < primaryKeyColumns.size(); i++) {
            if (i > 0) whereClause.append(" AND ");
            String pkCol = primaryKeyColumns.get(i);
            String quotedCol = switch (config.getType()) {
                case MYSQL -> "`" + pkCol + "`";
                case POSTGRESQL, ORACLE -> "\"" + pkCol + "\"";
                default -> pkCol;
            };
            whereClause.append(quotedCol).append(" = ?");
        }

        String deleteSql = "DELETE FROM " + qualifiedTable + " WHERE " + whereClause;

        // 使用事务 + 批量执行：保证多行删除的原子性（全部成功或全部回滚）
        try (PreparedStatement pstmt = conn.prepareStatement(deleteSql)) {
            conn.setAutoCommit(false);
            try {
                for (ObservableList<String> row : rows) {
                    for (int i = 0; i < pkIndexes.size(); i++) {
                        String value = row.get(pkIndexes.get(i));
                        if ("NULL".equals(value)) {
                            pstmt.setNull(i + 1, Types.VARCHAR);
                        } else {
                            pstmt.setString(i + 1, value);
                        }
                    }
                    pstmt.addBatch();
                }
                int[] results = pstmt.executeBatch();
                for (int r : results) {
                    if (r >= 0) {
                        totalDeleted += r;
                    } else if (r == java.sql.Statement.SUCCESS_NO_INFO) {
                        // 批量执行时驱动可能不返回确切行数
                        totalDeleted++;
                    }
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }

        return totalDeleted;
    }

    /**
     * 根据主键更新指定单元格
     * @param config 连接配置
     * @param databaseName 数据库名
     * @param tableName 表名
     * @param primaryKeyColumns 主键列名列表
     * @param columnNames 所有列名列表
     * @param row 行数据（主键值在row中按columnNames顺序取）
     * @param columnIndex 要更新的列在columnNames中的索引
     * @param newValue 新值
     * @return 受影响行数
     */
    public static int updateCell(ConnectionConfig config, String databaseName, String tableName,
                                 List<String> primaryKeyColumns, List<String> columnNames,
                                 ObservableList<String> row, int columnIndex, String newValue) throws Exception {
        return updateCell(config, databaseName, null, tableName, primaryKeyColumns, columnNames, row, columnIndex, newValue);
    }

    /**
     * 根据主键更新指定单元格
     * @param schemaName 模式名（PostgreSQL 使用；为 null 时回退用 databaseName 当 schema 名）
     */
    public static int updateCell(ConnectionConfig config, String databaseName, String schemaName, String tableName,
                                 List<String> primaryKeyColumns, List<String> columnNames,
                                 ObservableList<String> row, int columnIndex, String newValue) throws Exception {
        // PostgreSQL 绑定到具体数据库；MySQL/Oracle 复用主连接（SQL 使用全限定名）
        Connection conn = (config.getType() == ConnectType.POSTGRESQL)
                ? getConnection(config, databaseName)
                : getConnection(config);
        String pgSchema = schemaName != null ? schemaName : databaseName;

        String qualifiedTable = switch (config.getType()) {
            case MYSQL -> "`" + databaseName + "`.`" + tableName + "`";
            case POSTGRESQL -> "\"" + pgSchema + "\".\"" + tableName + "\"";
            case ORACLE -> "\"" + databaseName + "\".\"" + tableName + "\"";
            default -> throw new IllegalArgumentException("Unsupported database type: " + config.getType());
        };

        String updateCol = columnNames.get(columnIndex);
        String quotedUpdateCol = switch (config.getType()) {
            case MYSQL -> "`" + updateCol + "`";
            case POSTGRESQL, ORACLE -> "\"" + updateCol + "\"";
            default -> updateCol;
        };

        StringBuilder whereClause = new StringBuilder();
        List<Integer> pkIndexes = new ArrayList<>();
        for (int i = 0; i < primaryKeyColumns.size(); i++) {
            if (i > 0) whereClause.append(" AND ");
            String pkCol = primaryKeyColumns.get(i);
            String quotedCol = switch (config.getType()) {
                case MYSQL -> "`" + pkCol + "`";
                case POSTGRESQL, ORACLE -> "\"" + pkCol + "\"";
                default -> pkCol;
            };
            whereClause.append(quotedCol).append(" = ?");
            for (int j = 0; j < columnNames.size(); j++) {
                if (columnNames.get(j).equalsIgnoreCase(pkCol)) {
                    pkIndexes.add(j);
                    break;
                }
            }
        }

        String sql = "UPDATE " + qualifiedTable + " SET " + quotedUpdateCol + " = ? WHERE " + whereClause;

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            if ("NULL".equals(newValue)) {
                pstmt.setNull(1, Types.VARCHAR);
            } else {
                pstmt.setString(1, newValue);
            }
            for (int i = 0; i < pkIndexes.size(); i++) {
                String pkValue = row.get(pkIndexes.get(i));
                if ("NULL".equals(pkValue)) {
                    pstmt.setNull(i + 2, Types.VARCHAR);
                } else {
                    pstmt.setString(i + 2, pkValue);
                }
            }
            return pstmt.executeUpdate();
        }
    }

    /**
     * 插入一行空数据（所有列设为DEFAULT/NULL）
     * @param config 连接配置
     * @param databaseName 数据库名
     * @param tableName 表名
     * @param columnNames 列名列表
     */
    public static void insertEmptyRow(ConnectionConfig config, String databaseName, String tableName,
                                      List<String> columnNames) throws Exception {
        insertEmptyRow(config, databaseName, null, tableName, columnNames);
    }

    /**
     * 插入一行空数据（所有列设为DEFAULT/NULL）
     * @param schemaName 模式名（PostgreSQL 使用；为 null 时回退用 databaseName 当 schema 名）
     */
    public static void insertEmptyRow(ConnectionConfig config, String databaseName, String schemaName, String tableName,
                                      List<String> columnNames) throws Exception {
        // PostgreSQL 绑定到具体数据库；MySQL/Oracle 复用主连接（SQL 使用全限定名）
        Connection conn = (config.getType() == ConnectType.POSTGRESQL)
                ? getConnection(config, databaseName)
                : getConnection(config);
        String pgSchema = schemaName != null ? schemaName : databaseName;

        String qualifiedTable = switch (config.getType()) {
            case MYSQL -> "`" + databaseName + "`.`" + tableName + "`";
            case POSTGRESQL -> "\"" + pgSchema + "\".\"" + tableName + "\"";
            case ORACLE -> "\"" + databaseName + "\".\"" + tableName + "\"";
            default -> throw new IllegalArgumentException("Unsupported database type: " + config.getType());
        };

        StringBuilder cols = new StringBuilder();
        StringBuilder vals = new StringBuilder();
        for (int i = 0; i < columnNames.size(); i++) {
            if (i > 0) { cols.append(", "); vals.append(", "); }
            String quotedCol = switch (config.getType()) {
                case MYSQL -> "`" + columnNames.get(i) + "`";
                case POSTGRESQL, ORACLE -> "\"" + columnNames.get(i) + "\"";
                default -> columnNames.get(i);
            };
            cols.append(quotedCol);
            vals.append("DEFAULT");
        }

        String sql = "INSERT INTO " + qualifiedTable + " (" + cols + ") VALUES (" + vals + ")";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.executeUpdate();
        }
    }

    /**
     * 插入多行数据（使用具体值而非DEFAULT）
     * @param config 连接配置
     * @param databaseName 数据库名
     * @param tableName 表名
     * @param columnNames 列名列表
     * @param rows 要插入的行数据
     * @param primaryKeyColumns 主键列名列表（空值的自增PK列跳过，让DB自动生成）
     * @return 插入的行数
     */
    public static int insertRows(ConnectionConfig config, String databaseName, String tableName,
                                 List<String> columnNames, List<ObservableList<String>> rows,
                                 List<String> primaryKeyColumns) throws Exception {
        return insertRows(config, databaseName, null, tableName, columnNames, rows, primaryKeyColumns);
    }

    /**
     * 插入多行数据（使用具体值而非DEFAULT）
     * @param schemaName 模式名（PostgreSQL 使用；为 null 时回退用 databaseName 当 schema 名）
     */
    public static int insertRows(ConnectionConfig config, String databaseName, String schemaName, String tableName,
                                 List<String> columnNames, List<ObservableList<String>> rows,
                                 List<String> primaryKeyColumns) throws Exception {
        // PostgreSQL 绑定到具体数据库；MySQL/Oracle 复用主连接（SQL 使用全限定名）
        Connection conn = (config.getType() == ConnectType.POSTGRESQL)
                ? getConnection(config, databaseName)
                : getConnection(config);
        String pgSchema = schemaName != null ? schemaName : databaseName;
        int totalInserted = 0;

        String qualifiedTable = switch (config.getType()) {
            case MYSQL -> "`" + databaseName + "`.`" + tableName + "`";
            case POSTGRESQL -> "\"" + pgSchema + "\".\"" + tableName + "\"";
            case ORACLE -> "\"" + databaseName + "\".\"" + tableName + "\"";
            default -> throw new IllegalArgumentException("Unsupported database type: " + config.getType());
        };

        for (ObservableList<String> row : rows) {
            // 确定要包含的列（空值的自增PK列跳过）
            List<Integer> includeIndexes = new ArrayList<>();
            StringBuilder cols = new StringBuilder();
            StringBuilder placeholders = new StringBuilder();

            for (int i = 0; i < columnNames.size(); i++) {
                String value = i < row.size() ? row.get(i) : "";
                // 空值的主键列跳过（让DB自动生成）
                if (isNullOrEmpty(value) && isPrimaryKeyColumn(columnNames.get(i), primaryKeyColumns)) {
                    continue;
                }
                if (!includeIndexes.isEmpty()) { cols.append(", "); placeholders.append(", "); }
                String quotedCol = switch (config.getType()) {
                    case MYSQL -> "`" + columnNames.get(i) + "`";
                    case POSTGRESQL, ORACLE -> "\"" + columnNames.get(i) + "\"";
                    default -> columnNames.get(i);
                };
                cols.append(quotedCol);
                placeholders.append("?");
                includeIndexes.add(i);
            }

            String sql = "INSERT INTO " + qualifiedTable + " (" + cols + ") VALUES (" + placeholders + ")";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                for (int j = 0; j < includeIndexes.size(); j++) {
                    int colIdx = includeIndexes.get(j);
                    String value = colIdx < row.size() ? row.get(colIdx) : "";
                    if (isNullOrEmpty(value) || "NULL".equals(value)) {
                        pstmt.setNull(j + 1, Types.VARCHAR);
                    } else {
                        pstmt.setString(j + 1, value);
                    }
                }
                totalInserted += pstmt.executeUpdate();
            }
        }

        return totalInserted;
    }

    /**
     * 更新多行数据（仅更新修改过的列）
     * @param config 连接配置
     * @param databaseName 数据库名
     * @param tableName 表名
     * @param primaryKeyColumns 主键列名列表
     * @param columnNames 所有列名列表
     * @param currentRows 当前行值
     * @param originalRows 原始行值（WHERE子句使用原始主键值）
     * @param modifiedColumnsPerRow 每行修改过的列索引集合
     * @return 总受影响行数
     */
    public static int updateRows(ConnectionConfig config, String databaseName, String tableName,
                                 List<String> primaryKeyColumns, List<String> columnNames,
                                 List<ObservableList<String>> currentRows,
                                 List<ObservableList<String>> originalRows,
                                 List<java.util.Set<Integer>> modifiedColumnsPerRow) throws Exception {
        return updateRows(config, databaseName, null, tableName, primaryKeyColumns, columnNames,
                          currentRows, originalRows, modifiedColumnsPerRow);
    }

    /**
     * 更新多行数据（仅更新修改过的列）
     * @param schemaName 模式名（PostgreSQL 使用；为 null 时回退用 databaseName 当 schema 名）
     */
    public static int updateRows(ConnectionConfig config, String databaseName, String schemaName, String tableName,
                                 List<String> primaryKeyColumns, List<String> columnNames,
                                 List<ObservableList<String>> currentRows,
                                 List<ObservableList<String>> originalRows,
                                 List<java.util.Set<Integer>> modifiedColumnsPerRow) throws Exception {
        // PostgreSQL 绑定到具体数据库；MySQL/Oracle 复用主连接（SQL 使用全限定名）
        Connection conn = (config.getType() == ConnectType.POSTGRESQL)
                ? getConnection(config, databaseName)
                : getConnection(config);
        String pgSchema = schemaName != null ? schemaName : databaseName;
        int totalUpdated = 0;

        String qualifiedTable = switch (config.getType()) {
            case MYSQL -> "`" + databaseName + "`.`" + tableName + "`";
            case POSTGRESQL -> "\"" + pgSchema + "\".\"" + tableName + "\"";
            case ORACLE -> "\"" + databaseName + "\".\"" + tableName + "\"";
            default -> throw new IllegalArgumentException("Unsupported database type: " + config.getType());
        };

        // 构建主键列索引映射
        List<Integer> pkIndexes = new ArrayList<>();
        for (String pkCol : primaryKeyColumns) {
            for (int i = 0; i < columnNames.size(); i++) {
                if (columnNames.get(i).equalsIgnoreCase(pkCol)) {
                    pkIndexes.add(i);
                    break;
                }
            }
        }

        for (int rowIdx = 0; rowIdx < currentRows.size(); rowIdx++) {
            ObservableList<String> currentRow = currentRows.get(rowIdx);
            ObservableList<String> originalRow = originalRows.get(rowIdx);
            java.util.Set<Integer> modifiedCols = modifiedColumnsPerRow.get(rowIdx);

            // SET子句仅包含修改过的列
            StringBuilder setClause = new StringBuilder();
            List<Integer> setColIndexes = new ArrayList<>();
            for (int colIdx : modifiedCols) {
                if (!setColIndexes.isEmpty()) setClause.append(", ");
                String colName = columnNames.get(colIdx);
                String quotedCol = switch (config.getType()) {
                    case MYSQL -> "`" + colName + "`";
                    case POSTGRESQL, ORACLE -> "\"" + colName + "\"";
                    default -> colName;
                };
                setClause.append(quotedCol).append(" = ?");
                setColIndexes.add(colIdx);
            }

            // WHERE子句使用原始主键值
            StringBuilder whereClause = new StringBuilder();
            for (int i = 0; i < primaryKeyColumns.size(); i++) {
                if (i > 0) whereClause.append(" AND ");
                String pkCol = primaryKeyColumns.get(i);
                String quotedCol = switch (config.getType()) {
                    case MYSQL -> "`" + pkCol + "`";
                    case POSTGRESQL, ORACLE -> "\"" + pkCol + "\"";
                    default -> pkCol;
                };
                whereClause.append(quotedCol).append(" = ?");
            }

            String sql = "UPDATE " + qualifiedTable + " SET " + setClause + " WHERE " + whereClause;

            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                int paramIdx = 1;
                // SET参数（新值）
                for (int colIdx : setColIndexes) {
                    String newValue = currentRow.get(colIdx);
                    if ("NULL".equals(newValue) || isNullOrEmpty(newValue)) {
                        pstmt.setNull(paramIdx++, Types.VARCHAR);
                    } else {
                        pstmt.setString(paramIdx++, newValue);
                    }
                }
                // WHERE参数（原始主键值）
                for (int pkIdx : pkIndexes) {
                    String pkValue = originalRow.get(pkIdx);
                    if ("NULL".equals(pkValue)) {
                        pstmt.setNull(paramIdx++, Types.VARCHAR);
                    } else {
                        pstmt.setString(paramIdx++, pkValue);
                    }
                }
                totalUpdated += pstmt.executeUpdate();
            }
        }

        return totalUpdated;
    }

    private static boolean isNullOrEmpty(String value) {
        return value == null || value.isEmpty();
    }

    private static boolean isPrimaryKeyColumn(String columnName, List<String> primaryKeyColumns) {
        if (primaryKeyColumns == null) return false;
        for (String pk : primaryKeyColumns) {
            if (pk.equalsIgnoreCase(columnName)) return true;
        }
        return false;
    }

    /**
     * 获取表的列信息列表
     * @param config 连接配置
     * @param databaseName 数据库名
     * @param tableName 表名
     * @return 列信息列表，每个元素为一个列的属性Map
     */
    public static List<Map<String, String>> getTableColumns(ConnectionConfig config, String databaseName, String tableName) throws Exception {
        return getTableColumns(config, databaseName, null, tableName);
    }

    /**
     * 获取表的列信息列表
     * @param schemaName 模式名（PostgreSQL 使用；为 null 时回退用 databaseName 当 schema 名）
     */
    public static List<Map<String, String>> getTableColumns(ConnectionConfig config, String databaseName, String schemaName, String tableName) throws Exception {
        // PostgreSQL 绑定到具体数据库，并传入 schema 给 JDBC 元数据；MySQL/Oracle 复用主连接
        Connection conn = (config.getType() == ConnectType.POSTGRESQL)
                ? getConnection(config, databaseName)
                : getConnection(config);
        List<Map<String, String>> columns = new ArrayList<>();

        String catalog = databaseName;
        String schema = null;
        if (config.getType() == ConnectType.POSTGRESQL) {
            catalog = null;
            schema = schemaName != null ? schemaName : databaseName;
        }

        // 获取主键列表
        List<String> primaryKeys = new ArrayList<>();
        try (ResultSet pkRs = conn.getMetaData().getPrimaryKeys(catalog, schema, tableName)) {
            while (pkRs.next()) {
                primaryKeys.add(pkRs.getString("COLUMN_NAME"));
            }
        }

        // 获取列信息
        try (ResultSet rs = conn.getMetaData().getColumns(catalog, schema, tableName, null)) {
            while (rs.next()) {
                Map<String, String> col = new LinkedHashMap<>();
                String colName = rs.getString("COLUMN_NAME");
                col.put("字段名", colName);
                String typeName = rs.getString("TYPE_NAME");
                // MySQL JDBC 返回的 TYPE_NAME 会带 UNSIGNED/ZEROFILL 后缀（如 "TINYINT UNSIGNED"），
                // 这里拆分出基础类型填入"类型"，把无符号/填充零状态写入对应隐藏列，
                // 与 Navicat 行为保持一致：类型只显示基础类型，无符号通过下方复选框勾选。
                boolean isUnsigned = false;
                boolean isZerofill = false;
                if (config.getType() == ConnectType.MYSQL && typeName != null) {
                    String upper = typeName.toUpperCase();
                    int idxUnsigned = upper.indexOf(" UNSIGNED");
                    int idxZerofill = upper.indexOf(" ZEROFILL");
                    if (idxUnsigned >= 0) {
                        isUnsigned = true;
                        typeName = typeName.substring(0, idxUnsigned).trim();
                        upper = typeName.toUpperCase();
                        idxZerofill = upper.indexOf(" ZEROFILL");
                    }
                    if (idxZerofill >= 0) {
                        isZerofill = true;
                        typeName = typeName.substring(0, idxZerofill).trim();
                    }
                }
                col.put("类型", typeName != null ? typeName : "");
                col.put("长度", rs.getString("COLUMN_SIZE"));
                String decimalDigits = rs.getString("DECIMAL_DIGITS");
                col.put("小数点", decimalDigits != null ? decimalDigits : "");
                col.put("非空", "NO".equalsIgnoreCase(rs.getString("IS_NULLABLE")) ? "是" : "否");
                col.put("主键", primaryKeys.contains(colName) ? "是" : "否");
                String autoIncrement = rs.getString("IS_AUTOINCREMENT");
                col.put("自增", "YES".equalsIgnoreCase(autoIncrement) ? "是" : "否");
                String defaultValue = rs.getString("COLUMN_DEF");
                col.put("默认值", defaultValue != null ? defaultValue : "");
                String remarks = rs.getString("REMARKS");
                col.put("注释", remarks != null ? remarks : "");
                // 以下隐藏列不在表格中显示（由字段属性面板编辑），但需存在以便面板读写，
                // 并保证加载已有表与新建表模式下的 columnTitles 一致
                col.put("无符号", isUnsigned ? "是" : "否");
                col.put("填充零", isZerofill ? "是" : "否");
                col.put("字符集", "");
                col.put("排序规则", "");
                col.put("键长度", "");
                col.put("二进制", "否");
                columns.add(col);
            }
        }

        return columns;
    }

    /**
     * 获取数据库产品版本字符串
     * @param config 连接配置
     * @return 版本字符串，如 "8.0.32"、"15.2"、"19c"
     */
    public static String getDatabaseProductVersion(ConnectionConfig config) throws Exception {
        Connection conn = getConnection(config);
        return conn.getMetaData().getDatabaseProductVersion();
    }

    /**
     * 获取表的索引信息列表
     * @return 索引信息列表，每个元素为一个索引的属性Map（键：名称、字段、类型、方法、注释、可空、唯一）
     */
    public static List<Map<String, String>> getTableIndexes(ConnectionConfig config, String databaseName, String tableName) throws Exception {
        return getTableIndexes(config, databaseName, null, tableName);
    }

    /**
     * 获取表的索引信息列表
     * @param schemaName 模式名（PostgreSQL 使用；为 null 时回退用 databaseName 当 schema 名）
     */
    public static List<Map<String, String>> getTableIndexes(ConnectionConfig config, String databaseName, String schemaName, String tableName) throws Exception {
        Connection conn = (config.getType() == ConnectType.POSTGRESQL) ? getConnection(config, databaseName) : getConnection(config);
        String pgSchema = schemaName != null ? schemaName : databaseName;
        List<Map<String, String>> indexes = new ArrayList<>();

        if (config.getType() == ConnectType.MYSQL) {
            // 查询STATISTICS获取索引列信息，按索引名聚合
            String sql = "SELECT INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',') AS COLUMNS, "
                    + "NON_UNIQUE, INDEX_TYPE, COMMENT, INDEX_COMMENT "
                    + "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? "
                    + "GROUP BY INDEX_NAME, NON_UNIQUE, INDEX_TYPE, COMMENT, INDEX_COMMENT ORDER BY INDEX_NAME";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, databaseName);
                stmt.setString(2, tableName);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String indexName = rs.getString("INDEX_NAME");
                        boolean isPrimaryKey = "PRIMARY".equals(indexName);
                        if(!isPrimaryKey) {
                            Map<String, String> idx = new LinkedHashMap<>();
                            idx.put("名称", indexName);
                            idx.put("字段", rs.getString("COLUMNS"));
                            idx.put("方法", isPrimaryKey ? "BTREE" : rs.getString("INDEX_TYPE"));
                            boolean nonUnique = rs.getBoolean("NON_UNIQUE");
                            idx.put("类型", isPrimaryKey ? "PRIMARY" : (nonUnique ? "NORMAL" : "UNIQUE"));
                            idx.put("唯一", nonUnique ? "否" : "是");
                            String comment = rs.getString("INDEX_COMMENT");
                            idx.put("注释", comment != null ? comment : "");
                            indexes.add(idx);
                        }
                    }
                }
            }
        } else if (config.getType() == ConnectType.POSTGRESQL) {
            String sql = "SELECT i.relname AS index_name, "
                    + "string_agg(a.attname, ',' ORDER BY array_position(unnest(string_to_array(substring(pg_get_indexdef(i.oid) from '\\((.+)\\)'), ',')), a.attname)) AS columns, "
                    + "CASE WHEN ix.indisunique THEN 'UNIQUE' WHEN ix.indisprimary THEN 'PRIMARY' ELSE 'NORMAL' END AS index_type, "
                    + "am.amname AS index_method "
                    + "FROM pg_index ix "
                    + "JOIN pg_class t ON t.oid = ix.indrelid "
                    + "JOIN pg_class i ON i.oid = ix.indexrelid "
                    + "JOIN pg_namespace n ON n.oid = t.relnamespace "
                    + "JOIN pg_am am ON am.oid = i.relam "
                    + "JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY(ix.indkey) "
                    + "WHERE n.nspname = ? AND t.relname = ? "
                    + "GROUP BY i.relname, ix.indisunique, ix.indisprimary, am.amname ORDER BY i.relname";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, pgSchema);
                stmt.setString(2, tableName);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        Map<String, String> idx = new LinkedHashMap<>();
                        idx.put("名称", rs.getString("index_name"));
                        idx.put("字段", rs.getString("columns"));
                        idx.put("方法", rs.getString("index_method"));
                        String idxType = rs.getString("index_type");
                        idx.put("类型", idxType);
                        idx.put("唯一", "UNIQUE".equals(idxType) || "PRIMARY".equals(idxType) ? "是" : "否");
                        idx.put("注释", "");
                        indexes.add(idx);
                    }
                }
            }
        } else if (config.getType() == ConnectType.ORACLE) {
            String sql = "SELECT i.INDEX_NAME, "
                    + "LISTAGG(c.COLUMN_NAME, ',') WITHIN GROUP (ORDER BY c.COLUMN_POSITION) AS COLUMNS, "
                    + "i.UNIQUENESS, i.INDEX_TYPE "
                    + "FROM ALL_INDEXES i "
                    + "JOIN ALL_IND_COLUMNS c ON i.INDEX_NAME = c.INDEX_NAME AND i.OWNER = c.INDEX_OWNER "
                    + "WHERE i.TABLE_OWNER = ? AND i.TABLE_NAME = ? "
                    + "GROUP BY i.INDEX_NAME, i.UNIQUENESS, i.INDEX_TYPE ORDER BY i.INDEX_NAME";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, databaseName);
                stmt.setString(2, tableName);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        Map<String, String> idx = new LinkedHashMap<>();
                        idx.put("名称", rs.getString("INDEX_NAME"));
                        idx.put("字段", rs.getString("COLUMNS"));
                        idx.put("方法", rs.getString("INDEX_TYPE"));
                        String uniqueness = rs.getString("UNIQUENESS");
                        idx.put("类型", "UNIQUE".equals(uniqueness) ? "UNIQUE" : "NORMAL");
                        idx.put("唯一", "UNIQUE".equals(uniqueness) ? "是" : "否");
                        idx.put("注释", "");
                        indexes.add(idx);
                    }
                }
            }
        }

        return indexes;
    }

    /**
     * 获取表的外键信息列表
     * @return 外键信息列表，每个元素为一个外键的属性Map
     */
    public static List<Map<String, String>> getTableForeignKeys(ConnectionConfig config, String databaseName, String tableName) throws Exception {
        return getTableForeignKeys(config, databaseName, null, tableName);
    }

    /**
     * 获取表的外键信息列表
     * @param schemaName 模式名（PostgreSQL 使用；为 null 时回退用 databaseName 当 schema 名）
     */
    public static List<Map<String, String>> getTableForeignKeys(ConnectionConfig config, String databaseName, String schemaName, String tableName) throws Exception {
        Connection conn = (config.getType() == ConnectType.POSTGRESQL) ? getConnection(config, databaseName) : getConnection(config);
        String pgSchema = schemaName != null ? schemaName : databaseName;
        List<Map<String, String>> foreignKeys = new ArrayList<>();

        if (config.getType() == ConnectType.MYSQL) {
            String sql = "SELECT kcu.CONSTRAINT_NAME, "
                    + "GROUP_CONCAT(kcu.COLUMN_NAME ORDER BY kcu.ORDINAL_POSITION SEPARATOR ',') AS COLUMNS, "
                    + "kcu.REFERENCED_TABLE_SCHEMA AS REF_DB, "
                    + "kcu.REFERENCED_TABLE_NAME AS REF_TABLE, "
                    + "GROUP_CONCAT(kcu.REFERENCED_COLUMN_NAME ORDER BY kcu.ORDINAL_POSITION SEPARATOR ',') AS REF_COLUMNS, "
                    + "rc.DELETE_RULE, rc.UPDATE_RULE "
                    + "FROM information_schema.KEY_COLUMN_USAGE kcu "
                    + "JOIN information_schema.REFERENTIAL_CONSTRAINTS rc "
                    + "ON kcu.CONSTRAINT_NAME = rc.CONSTRAINT_NAME AND kcu.TABLE_SCHEMA = rc.CONSTRAINT_SCHEMA "
                    + "WHERE kcu.TABLE_SCHEMA = ? AND kcu.TABLE_NAME = ? AND kcu.REFERENCED_TABLE_NAME IS NOT NULL "
                    + "GROUP BY kcu.CONSTRAINT_NAME, kcu.REFERENCED_TABLE_SCHEMA, kcu.REFERENCED_TABLE_NAME, rc.DELETE_RULE, rc.UPDATE_RULE "
                    + "ORDER BY kcu.CONSTRAINT_NAME";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, databaseName);
                stmt.setString(2, tableName);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        Map<String, String> fk = new LinkedHashMap<>();
                        fk.put("名称", rs.getString("CONSTRAINT_NAME"));
                        fk.put("字段", rs.getString("COLUMNS"));
                        fk.put("参考数据库", rs.getString("REF_DB"));
                        fk.put("参考表", rs.getString("REF_TABLE"));
                        fk.put("参考字段", rs.getString("REF_COLUMNS"));
                        fk.put("删除时", rs.getString("DELETE_RULE"));
                        fk.put("更新时", rs.getString("UPDATE_RULE"));
                        foreignKeys.add(fk);
                    }
                }
            }
        } else if (config.getType() == ConnectType.POSTGRESQL) {
            String sql = "SELECT con.conname AS constraint_name, "
                    + "string_agg(a.attname, ',' ORDER BY u.ord) AS columns, "
                    + "rn.nspname AS ref_schema, cl.relname AS ref_table, "
                    + "string_agg(af.attname, ',' ORDER BY uf.ord) AS ref_columns, "
                    + "con.confdeltype AS delete_rule, con.confupdtype AS update_rule "
                    + "FROM pg_constraint con "
                    + "JOIN pg_class c ON c.oid = con.conrelid "
                    + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                    + "JOIN pg_class cl ON cl.oid = con.confrelid "
                    + "JOIN pg_namespace rn ON rn.oid = cl.relnamespace "
                    + "JOIN LATERAL unnest(con.conkey) WITH ORDINALITY AS u(attnum, ord) ON true "
                    + "JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = u.attnum "
                    + "JOIN LATERAL unnest(con.confkey) WITH ORDINALITY AS uf(attnum, ord) ON uf.ord = u.ord "
                    + "JOIN pg_attribute af ON af.attrelid = cl.oid AND af.attnum = uf.attnum "
                    + "WHERE n.nspname = ? AND c.relname = ? AND con.contype = 'f' "
                    + "GROUP BY con.conname, rn.nspname, cl.relname, con.confdeltype, con.confupdtype "
                    + "ORDER BY con.conname";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, pgSchema);
                stmt.setString(2, tableName);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        Map<String, String> fk = new LinkedHashMap<>();
                        fk.put("名称", rs.getString("constraint_name"));
                        fk.put("字段", rs.getString("columns"));
                        fk.put("参考数据库", rs.getString("ref_schema"));
                        fk.put("参考表", rs.getString("ref_table"));
                        fk.put("参考字段", rs.getString("ref_columns"));
                        fk.put("删除时", mapPgRule(rs.getString("delete_rule")));
                        fk.put("更新时", mapPgRule(rs.getString("update_rule")));
                        foreignKeys.add(fk);
                    }
                }
            }
        } else if (config.getType() == ConnectType.ORACLE) {
            String sql = "SELECT c.CONSTRAINT_NAME, "
                    + "LISTAGG(col.COLUMN_NAME, ',') WITHIN GROUP (ORDER BY col.POSITION) AS COLUMNS, "
                    + "rc.OWNER AS REF_OWNER, rc.TABLE_NAME AS REF_TABLE, "
                    + "LISTAGG(rcol.COLUMN_NAME, ',') WITHIN GROUP (ORDER BY rcol.POSITION) AS REF_COLUMNS, "
                    + "c.DELETE_RULE "
                    + "FROM ALL_CONSTRAINTS c "
                    + "JOIN ALL_CONS_COLUMNS col ON c.OWNER = col.OWNER AND c.CONSTRAINT_NAME = col.CONSTRAINT_NAME "
                    + "JOIN ALL_CONSTRAINTS rc ON c.R_OWNER = rc.OWNER AND c.R_CONSTRAINT_NAME = rc.CONSTRAINT_NAME "
                    + "JOIN ALL_CONS_COLUMNS rcol ON rc.OWNER = rcol.OWNER AND rc.CONSTRAINT_NAME = rcol.CONSTRAINT_NAME "
                    + "WHERE c.OWNER = ? AND c.TABLE_NAME = ? AND c.CONSTRAINT_TYPE = 'R' "
                    + "GROUP BY c.CONSTRAINT_NAME, rc.OWNER, rc.TABLE_NAME, c.DELETE_RULE ORDER BY c.CONSTRAINT_NAME";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, databaseName);
                stmt.setString(2, tableName);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        Map<String, String> fk = new LinkedHashMap<>();
                        fk.put("名称", rs.getString("CONSTRAINT_NAME"));
                        fk.put("字段", rs.getString("COLUMNS"));
                        fk.put("参考数据库", rs.getString("REF_OWNER"));
                        fk.put("参考表", rs.getString("REF_TABLE"));
                        fk.put("参考字段", rs.getString("REF_COLUMNS"));
                        String delRule = rs.getString("DELETE_RULE");
                        fk.put("删除时", delRule != null ? delRule : "NO ACTION");
                        fk.put("更新时", "NO ACTION");
                        foreignKeys.add(fk);
                    }
                }
            }
        }

        return foreignKeys;
    }

    /**
     * 获取数据库/schema 下所有表的外键关系列表（用于 ER 视图）。
     * 与 getTableForeignKeys 区别：不限定单表，返回所有表的外键，并在 Map 中增加 "表名" 字段标识外键所属表。
     * @param schemaName 模式名（PostgreSQL 使用；为 null 时回退用 databaseName 当 schema 名）
     */
    public static List<Map<String, String>> getDatabaseForeignKeys(ConnectionConfig config, String databaseName, String schemaName) throws Exception {
        Connection conn = (config.getType() == ConnectType.POSTGRESQL) ? getConnection(config, databaseName) : getConnection(config);
        String pgSchema = schemaName != null ? schemaName : databaseName;
        List<Map<String, String>> foreignKeys = new ArrayList<>();

        if (config.getType() == ConnectType.MYSQL) {
            String sql = "SELECT kcu.TABLE_NAME, kcu.CONSTRAINT_NAME, "
                    + "GROUP_CONCAT(kcu.COLUMN_NAME ORDER BY kcu.ORDINAL_POSITION SEPARATOR ',') AS COLUMNS, "
                    + "kcu.REFERENCED_TABLE_SCHEMA AS REF_DB, "
                    + "kcu.REFERENCED_TABLE_NAME AS REF_TABLE, "
                    + "GROUP_CONCAT(kcu.REFERENCED_COLUMN_NAME ORDER BY kcu.ORDINAL_POSITION SEPARATOR ',') AS REF_COLUMNS, "
                    + "rc.DELETE_RULE, rc.UPDATE_RULE "
                    + "FROM information_schema.KEY_COLUMN_USAGE kcu "
                    + "JOIN information_schema.REFERENTIAL_CONSTRAINTS rc "
                    + "ON kcu.CONSTRAINT_NAME = rc.CONSTRAINT_NAME AND kcu.TABLE_SCHEMA = rc.CONSTRAINT_SCHEMA "
                    + "WHERE kcu.TABLE_SCHEMA = ? AND kcu.REFERENCED_TABLE_NAME IS NOT NULL "
                    + "GROUP BY kcu.TABLE_NAME, kcu.CONSTRAINT_NAME, kcu.REFERENCED_TABLE_SCHEMA, kcu.REFERENCED_TABLE_NAME, rc.DELETE_RULE, rc.UPDATE_RULE "
                    + "ORDER BY kcu.TABLE_NAME, kcu.CONSTRAINT_NAME";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, databaseName);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        Map<String, String> fk = new LinkedHashMap<>();
                        fk.put("表名", rs.getString("TABLE_NAME"));
                        fk.put("名称", rs.getString("CONSTRAINT_NAME"));
                        fk.put("字段", rs.getString("COLUMNS"));
                        fk.put("参考数据库", rs.getString("REF_DB"));
                        fk.put("参考表", rs.getString("REF_TABLE"));
                        fk.put("参考字段", rs.getString("REF_COLUMNS"));
                        fk.put("删除时", rs.getString("DELETE_RULE"));
                        fk.put("更新时", rs.getString("UPDATE_RULE"));
                        foreignKeys.add(fk);
                    }
                }
            }
        } else if (config.getType() == ConnectType.POSTGRESQL) {
            String sql = "SELECT c.relname AS table_name, con.conname AS constraint_name, "
                    + "string_agg(a.attname, ',' ORDER BY u.ord) AS columns, "
                    + "rn.nspname AS ref_schema, cl.relname AS ref_table, "
                    + "string_agg(af.attname, ',' ORDER BY uf.ord) AS ref_columns, "
                    + "con.confdeltype AS delete_rule, con.confupdtype AS update_rule "
                    + "FROM pg_constraint con "
                    + "JOIN pg_class c ON c.oid = con.conrelid "
                    + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                    + "JOIN pg_class cl ON cl.oid = con.confrelid "
                    + "JOIN pg_namespace rn ON rn.oid = cl.relnamespace "
                    + "JOIN LATERAL unnest(con.conkey) WITH ORDINALITY AS u(attnum, ord) ON true "
                    + "JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = u.attnum "
                    + "JOIN LATERAL unnest(con.confkey) WITH ORDINALITY AS uf(attnum, ord) ON uf.ord = u.ord "
                    + "JOIN pg_attribute af ON af.attrelid = cl.oid AND af.attnum = uf.attnum "
                    + "WHERE n.nspname = ? AND con.contype = 'f' "
                    + "GROUP BY c.relname, con.conname, rn.nspname, cl.relname, con.confdeltype, con.confupdtype "
                    + "ORDER BY c.relname, con.conname";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, pgSchema);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        Map<String, String> fk = new LinkedHashMap<>();
                        fk.put("表名", rs.getString("table_name"));
                        fk.put("名称", rs.getString("constraint_name"));
                        fk.put("字段", rs.getString("columns"));
                        fk.put("参考数据库", rs.getString("ref_schema"));
                        fk.put("参考表", rs.getString("ref_table"));
                        fk.put("参考字段", rs.getString("ref_columns"));
                        fk.put("删除时", mapPgRule(rs.getString("delete_rule")));
                        fk.put("更新时", mapPgRule(rs.getString("update_rule")));
                        foreignKeys.add(fk);
                    }
                }
            }
        } else if (config.getType() == ConnectType.ORACLE) {
            String sql = "SELECT c.TABLE_NAME, c.CONSTRAINT_NAME, "
                    + "LISTAGG(col.COLUMN_NAME, ',') WITHIN GROUP (ORDER BY col.POSITION) AS COLUMNS, "
                    + "rc.OWNER AS REF_OWNER, rc.TABLE_NAME AS REF_TABLE, "
                    + "LISTAGG(rcol.COLUMN_NAME, ',') WITHIN GROUP (ORDER BY rcol.POSITION) AS REF_COLUMNS, "
                    + "c.DELETE_RULE "
                    + "FROM ALL_CONSTRAINTS c "
                    + "JOIN ALL_CONS_COLUMNS col ON c.OWNER = col.OWNER AND c.CONSTRAINT_NAME = col.CONSTRAINT_NAME "
                    + "JOIN ALL_CONSTRAINTS rc ON c.R_OWNER = rc.OWNER AND c.R_CONSTRAINT_NAME = rc.CONSTRAINT_NAME "
                    + "JOIN ALL_CONS_COLUMNS rcol ON rc.OWNER = rcol.OWNER AND rc.CONSTRAINT_NAME = rcol.CONSTRAINT_NAME "
                    + "WHERE c.OWNER = ? AND c.CONSTRAINT_TYPE = 'R' "
                    + "GROUP BY c.TABLE_NAME, c.CONSTRAINT_NAME, rc.OWNER, rc.TABLE_NAME, c.DELETE_RULE "
                    + "ORDER BY c.TABLE_NAME, c.CONSTRAINT_NAME";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, databaseName);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        Map<String, String> fk = new LinkedHashMap<>();
                        fk.put("表名", rs.getString("TABLE_NAME"));
                        fk.put("名称", rs.getString("CONSTRAINT_NAME"));
                        fk.put("字段", rs.getString("COLUMNS"));
                        fk.put("参考数据库", rs.getString("REF_OWNER"));
                        fk.put("参考表", rs.getString("REF_TABLE"));
                        fk.put("参考字段", rs.getString("REF_COLUMNS"));
                        String delRule = rs.getString("DELETE_RULE");
                        fk.put("删除时", delRule != null ? delRule : "NO ACTION");
                        fk.put("更新时", "NO ACTION");
                        foreignKeys.add(fk);
                    }
                }
            }
        }

        return foreignKeys;
    }

    /**
     * PostgreSQL外键规则代码映射
     */
    private static String mapPgRule(String code) {
        if (code == null) return "NO ACTION";
        return switch (code) {
            case "a" -> "NO ACTION";
            case "r" -> "RESTRICT";
            case "c" -> "CASCADE";
            case "n" -> "SET NULL";
            case "d" -> "SET DEFAULT";
            default -> "NO ACTION";
        };
    }

    /**
     * 获取表的触发器信息列表
     * @return 触发器信息列表，每个元素为一个触发器的属性Map
     */
    public static List<Map<String, String>> getTableTriggers(ConnectionConfig config, String databaseName, String tableName) throws Exception {
        return getTableTriggers(config, databaseName, null, tableName);
    }

    /**
     * 获取表的触发器信息列表
     * @param schemaName 模式名（PostgreSQL 使用；为 null 时回退用 databaseName 当 schema 名）
     */
    public static List<Map<String, String>> getTableTriggers(ConnectionConfig config, String databaseName, String schemaName, String tableName) throws Exception {
        Connection conn = (config.getType() == ConnectType.POSTGRESQL) ? getConnection(config, databaseName) : getConnection(config);
        String pgSchema = schemaName != null ? schemaName : databaseName;
        List<Map<String, String>> triggers = new ArrayList<>();

        if (config.getType() == ConnectType.MYSQL) {
            String sql = "SELECT TRIGGER_NAME, ACTION_TIMING, EVENT_MANIPULATION, ACTION_STATEMENT, EVENT_OBJECT_TABLE "
                    + "FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA = ? AND EVENT_OBJECT_TABLE = ? ORDER BY TRIGGER_NAME";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, databaseName);
                stmt.setString(2, tableName);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        Map<String, String> trg = new LinkedHashMap<>();
                        trg.put("名称", rs.getString("TRIGGER_NAME"));
                        trg.put("时机", rs.getString("ACTION_TIMING"));
                        trg.put("事件", rs.getString("EVENT_MANIPULATION"));
                        trg.put("语句", rs.getString("ACTION_STATEMENT"));
                        triggers.add(trg);
                    }
                }
            }
        } else if (config.getType() == ConnectType.POSTGRESQL) {
            String sql = "SELECT t.tgname AS trigger_name, "
                    + "CASE WHEN (t.tgtype & 2) > 0 THEN 'BEFORE' ELSE 'AFTER' END AS timing, "
                    + "CASE WHEN (t.tgtype & 4) > 0 THEN 'INSERT' "
                    + "WHEN (t.tgtype & 16) > 0 THEN 'UPDATE' "
                    + "WHEN (t.tgtype & 8) > 0 THEN 'DELETE' END AS event, "
                    + "pg_get_triggerdef(t.oid) AS statement "
                    + "FROM pg_trigger t "
                    + "JOIN pg_class c ON c.oid = t.tgrelid "
                    + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                    + "WHERE n.nspname = ? AND c.relname = ? AND NOT t.tgisinternal "
                    + "ORDER BY t.tgname";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, pgSchema);
                stmt.setString(2, tableName);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        Map<String, String> trg = new LinkedHashMap<>();
                        trg.put("名称", rs.getString("trigger_name"));
                        trg.put("时机", rs.getString("timing"));
                        trg.put("事件", rs.getString("event"));
                        trg.put("语句", rs.getString("statement"));
                        triggers.add(trg);
                    }
                }
            }
        } else if (config.getType() == ConnectType.ORACLE) {
            String sql = "SELECT TRIGGER_NAME, TRIGGERING_EVENT, TRIGGER_TYPE, TRIGGER_BODY "
                    + "FROM ALL_TRIGGERS WHERE OWNER = ? AND TABLE_NAME = ? ORDER BY TRIGGER_NAME";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, databaseName);
                stmt.setString(2, tableName);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        Map<String, String> trg = new LinkedHashMap<>();
                        String trigType = rs.getString("TRIGGER_TYPE");
                        trg.put("名称", rs.getString("TRIGGER_NAME"));
                        trg.put("时机", trigType != null && trigType.contains("BEFORE") ? "BEFORE" : "AFTER");
                        trg.put("事件", rs.getString("TRIGGERING_EVENT"));
                        trg.put("语句", rs.getString("TRIGGER_BODY"));
                        triggers.add(trg);
                    }
                }
            }
        }

        return triggers;
    }

    /**
     * 获取表选项信息（引擎、字符集、排序规则、自增值、行格式、注释、自动递增等）
     * @return 表选项Map
     */
    public static Map<String, String> getTableOptions(ConnectionConfig config, String databaseName, String tableName) throws Exception {
        return getTableOptions(config, databaseName, null, tableName);
    }

    /**
     * 获取表选项信息
     * @param schemaName 模式名（PostgreSQL 使用；为 null 时回退用 databaseName 当 schema 名）
     */
    public static Map<String, String> getTableOptions(ConnectionConfig config, String databaseName, String schemaName, String tableName) throws Exception {
        Connection conn = (config.getType() == ConnectType.POSTGRESQL) ? getConnection(config, databaseName) : getConnection(config);
        String pgSchema = schemaName != null ? schemaName : databaseName;
        Map<String, String> options = new LinkedHashMap<>();

        if (config.getType() == ConnectType.MYSQL) {
            String sql = "SELECT ENGINE, TABLE_COLLATION, AUTO_INCREMENT, TABLE_COMMENT, ROW_FORMAT, "
                    + "CREATE_OPTIONS, AVG_ROW_LENGTH, TABLE_ROWS "
                    + "FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, databaseName);
                stmt.setString(2, tableName);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        options.put("引擎", rs.getString("ENGINE"));
                        options.put("排序规则", rs.getString("TABLE_COLLATION"));
                        options.put("自增值", rs.getString("AUTO_INCREMENT"));
                        options.put("注释", rs.getString("TABLE_COMMENT"));
                        options.put("行格式", rs.getString("ROW_FORMAT"));
                        options.put("平均行长", rs.getString("AVG_ROW_LENGTH"));
                        options.put("行数", rs.getString("TABLE_ROWS"));
                    }
                }
            }
            // 根据排序规则推导字符集（排序规则格式: charset_language_ci，取第一个下划线前的部分）
            String collation = options.get("排序规则");
            if (collation != null && collation.contains("_")) {
                options.put("字符集", collation.substring(0, collation.indexOf('_')));
            } else {
                options.put("字符集", "");
            }
        } else if (config.getType() == ConnectType.POSTGRESQL) {
            String sql = "SELECT c.relkind, c.reloptions, "
                    + "pg_catalog.obj_description(c.oid) AS comment, "
                    + "(SELECT count(*) FROM pg_class WHERE relname = c.relname) AS table_rows "
                    + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                    + "WHERE n.nspname = ? AND c.relname = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, pgSchema);
                stmt.setString(2, tableName);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        options.put("引擎", "");
                        options.put("字符集", "");
                        options.put("排序规则", "");
                        options.put("自增值", "");
                        options.put("注释", rs.getString("comment") != null ? rs.getString("comment") : "");
                        options.put("行格式", "");
                        options.put("行数", rs.getString("table_rows"));
                    }
                }
            }
        } else if (config.getType() == ConnectType.ORACLE) {
            try (PreparedStatement stmt = conn.prepareStatement(
                     "SELECT comments FROM all_tab_comments WHERE owner = ? AND table_name = ?")) {
                stmt.setString(1, databaseName);
                stmt.setString(2, tableName);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        options.put("注释", rs.getString("comments") != null ? rs.getString("comments") : "");
                    }
                }
            }
            options.putIfAbsent("注释", "");
        }

        return options;
    }

    /**
     * 获取MySQL可用的存储引擎列表
     */
    public static List<String> getEngines(ConnectionConfig config) throws Exception {
        if (config.getType() != ConnectType.MYSQL) {
            return Collections.emptyList();
        }
        Connection conn = getConnection(config);
        List<String> engines = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT ENGINE FROM information_schema.ENGINES WHERE SUPPORT IN ('YES','DEFAULT') ORDER BY ENGINE")) {
            while (rs.next()) {
                engines.add(rs.getString("ENGINE"));
            }
        }
        return engines;
    }

    /**
     * 获取表的DDL（CREATE TABLE语句）
     * @return DDL字符串
     */
    public static String getTableDdl(ConnectionConfig config, String databaseName, String tableName) throws Exception {
        return getTableDdl(config, databaseName, null, tableName);
    }

    /**
     * 获取表的DDL（CREATE TABLE语句）
     * @param schemaName 模式名（PostgreSQL 使用；为 null 时回退用 databaseName 当 schema 名）
     */
    public static String getTableDdl(ConnectionConfig config, String databaseName, String schemaName, String tableName) throws Exception {
        // PostgreSQL 绑定到具体数据库；MySQL/Oracle 复用主连接
        Connection conn = (config.getType() == ConnectType.POSTGRESQL)
                ? getConnection(config, databaseName)
                : getConnection(config);

        if (config.getType() == ConnectType.MYSQL) {
            try (PreparedStatement stmt = conn.prepareStatement(
                     "SHOW CREATE TABLE `" + databaseName + "`.`" + tableName + "`")) {
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString(2);
                    }
                }
            }
        } else if (config.getType() == ConnectType.POSTGRESQL) {
            // PostgreSQL 没有直接的 SHOW CREATE TABLE，使用 pg_get_tabledef 不可用，构造简化版DDL
            return generatePostgresDdl(config, databaseName, schemaName, tableName);
        } else if (config.getType() == ConnectType.ORACLE) {
            try (PreparedStatement stmt = conn.prepareStatement(
                     "SELECT DBMS_METADATA.GET_DDL('TABLE', ?, ?) FROM DUAL")) {
                stmt.setString(1, tableName);
                stmt.setString(2, databaseName);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        java.io.Reader reader = rs.getCharacterStream(1);
                        if (reader != null) {
                            StringBuilder sb = new StringBuilder();
                            char[] buf = new char[4096];
                            int len;
                            while ((len = reader.read(buf)) >= 0) {
                                sb.append(buf, 0, len);
                            }
                            return sb.toString().trim();
                        }
                    }
                }
            }
        }
        return "-- 当前数据库类型不支持获取DDL";
    }

    /**
     * 为PostgreSQL生成简化的CREATE TABLE DDL
     * @param schemaName 模式名（为 null 时回退用 databaseName 当 schema 名）
     */
    private static String generatePostgresDdl(ConnectionConfig config, String databaseName, String schemaName, String tableName) throws Exception {
        List<Map<String, String>> columns = getTableColumns(config, databaseName, schemaName, tableName);
        List<String> primaryKeys = getPrimaryKeys(config, databaseName, schemaName, tableName);
        String pgSchema = schemaName != null ? schemaName : databaseName;
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE \"").append(pgSchema).append("\".\"").append(tableName).append("\" (\n");
        for (int i = 0; i < columns.size(); i++) {
            Map<String, String> col = columns.get(i);
            sb.append("    \"").append(col.get("字段名")).append("\" ");
            sb.append(col.get("类型"));
            if (col.get("长度") != null && !col.get("长度").isEmpty()) {
                sb.append("(").append(col.get("长度")).append(")");
            }
            if ("是".equals(col.get("非空"))) {
                sb.append(" NOT NULL");
            }
            if (i < columns.size() - 1 || !primaryKeys.isEmpty()) {
                sb.append(",");
            }
            sb.append("\n");
        }
        if (!primaryKeys.isEmpty()) {
            sb.append("    PRIMARY KEY (");
            for (int i = 0; i < primaryKeys.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append("\"").append(primaryKeys.get(i)).append("\"");
            }
            sb.append(")\n");
        }
        sb.append(");");
        return sb.toString();
    }

    /**
     * 获取表的注释
     */
    public static String getTableComment(ConnectionConfig config, String databaseName, String tableName) throws Exception {
        return getTableComment(config, databaseName, null, tableName);
    }

    /**
     * 获取表的注释
     * @param schemaName 模式名（PostgreSQL 使用；为 null 时回退用 databaseName 当 schema 名）
     */
    public static String getTableComment(ConnectionConfig config, String databaseName, String schemaName, String tableName) throws Exception {
        Map<String, String> options = getTableOptions(config, databaseName, schemaName, tableName);
        return options.getOrDefault("注释", "");
    }

    /**
     * 生成更新表注释的SQL
     */
    public static String generateUpdateTableCommentSql(ConnectionConfig config, String databaseName, String tableName, String comment) {
        return generateUpdateTableCommentSql(config, databaseName, null, tableName, comment);
    }

    /**
     * 生成更新表注释的SQL
     * @param schemaName 模式名（PostgreSQL 使用；为 null 时回退用 databaseName 当 schema 名）
     */
    public static String generateUpdateTableCommentSql(ConnectionConfig config, String databaseName, String schemaName, String tableName, String comment) {
        String escapedComment = comment != null ? comment.replace("'", "''") : "";
        String pgSchema = schemaName != null ? schemaName : databaseName;
        return switch (config.getType()) {
            case MYSQL -> "ALTER TABLE `" + databaseName + "`.`" + tableName + "` COMMENT = '" + escapedComment + "'";
            case POSTGRESQL -> "COMMENT ON TABLE \"" + pgSchema + "\".\"" + tableName + "\" IS '" + escapedComment + "'";
            case ORACLE -> "COMMENT ON TABLE \"" + databaseName + "\".\"" + tableName + "\" IS '" + escapedComment + "'";
            default -> throw new IllegalArgumentException("Unsupported database type: " + config.getType());
        };
    }

    /**
     * 更新表注释
     */
    public static void updateTableComment(ConnectionConfig config, String databaseName, String tableName, String comment) throws Exception {
        updateTableComment(config, databaseName, null, tableName, comment);
    }

    /**
     * 更新表注释
     * @param schemaName 模式名（PostgreSQL 使用；为 null 时回退用 databaseName 当 schema 名）
     */
    public static void updateTableComment(ConnectionConfig config, String databaseName, String schemaName, String tableName, String comment) throws Exception {
        String sql = generateUpdateTableCommentSql(config, databaseName, schemaName, tableName, comment);
        // PostgreSQL 绑定到具体数据库；MySQL/Oracle 复用主连接
        Connection conn = (config.getType() == ConnectType.POSTGRESQL)
                ? getConnection(config, databaseName)
                : getConnection(config);
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(sql);
        }
    }

    /**
     * 执行DDL语句（CREATE/DROP/ALTER等，无返回结果）
     */
    public static void executeDdl(ConnectionConfig config, String sql) throws Exception {
        try (Statement stmt = getConnection(config).createStatement()) {
            stmt.executeUpdate(sql);
        }
    }

    /**
     * 执行DDL语句（按数据库类型选连接：PostgreSQL绑定到具体数据库，其他用主连接）
     */
    public static void executeDdl(ConnectionConfig config, String databaseName, String sql) throws Exception {
        Connection conn = (config.getType() == ConnectType.POSTGRESQL)
                ? getConnection(config, databaseName)
                : getConnection(config);
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(sql);
        }
    }

    /**
     * 在同一事务中执行多条DDL语句（MySQL/Oracle 主连接）。
     * 全部成功才commit；任意一条失败则rollback，保证原子性。
     * 注意：调用方需自行管理使用锁（acquireUsageLock），避免与查询线程并发使用同一连接。
     */
    public static void executeDdlsInTransaction(ConnectionConfig config, List<String> sqls) throws Exception {
        executeDdlsInTransaction(config, null, sqls);
    }

    /**
     * 在同一事务中执行多条DDL语句（按数据库类型选连接：PostgreSQL绑定到具体数据库，其他用主连接）。
     * 全部成功才commit；任意一条失败则rollback，保证原子性。
     * 注意：调用方需自行管理使用锁（acquireUsageLock），避免与查询线程并发使用同一连接。
     */
    public static void executeDdlsInTransaction(ConnectionConfig config, String databaseName, List<String> sqls) throws Exception {
        if (sqls == null || sqls.isEmpty()) return;
        Connection conn = (config.getType() == ConnectType.POSTGRESQL)
                ? getConnection(config, databaseName)
                : getConnection(config);
        boolean originalAutoCommit = conn.getAutoCommit();
        try (Statement stmt = conn.createStatement()) {
            conn.setAutoCommit(false);
            for (String sql : sqls) {
                if (sql == null || sql.trim().isEmpty()) continue;
                stmt.executeUpdate(sql);
            }
            conn.commit();
        } catch (Exception e) {
            try { conn.rollback(); } catch (Exception ignored) {}
            throw e;
        } finally {
            try { conn.setAutoCommit(originalAutoCommit); } catch (Exception ignored) {}
        }
    }

    /**
     * 创建新表：根据字段列表、选项和注释生成 CREATE TABLE 并执行
     * @param columns 字段列表，每个Map包含：字段名、类型、长度、非空、主键、自增、默认值、注释、
     *                无符号、填充零、字符集、排序规则、键长度、二进制（后几项可选，MySQL专用）
     * @param options 表选项（引擎、字符集、排序规则等，可为null）
     * @param comment 表注释（可为null）
     */
    public static void createTable(ConnectionConfig config, String databaseName, String tableName,
                                    List<Map<String, String>> columns, Map<String, String> options,
                                    String comment) throws Exception {
        createTable(config, databaseName, null, tableName, columns, options, comment);
    }

    /**
     * 创建新表：根据字段列表、选项和注释生成 CREATE TABLE 并执行
     * @param schemaName 模式名（PostgreSQL 使用；为 null 时回退用 databaseName 当 schema 名）
     */
    public static void createTable(ConnectionConfig config, String databaseName, String schemaName, String tableName,
                                    List<Map<String, String>> columns, Map<String, String> options,
                                    String comment) throws Exception {
        String sql = generateCreateTableSql(config, databaseName, schemaName, tableName, columns, options, comment);
        // PostgreSQL 绑定到具体数据库；MySQL/Oracle 复用主连接
        Connection conn = (config.getType() == ConnectType.POSTGRESQL)
                ? getConnection(config, databaseName)
                : getConnection(config);
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(sql);
        }
    }

    /**
     * 生成CREATE TABLE SQL（支持MySQL/PostgreSQL/Oracle）
     */
    public static String generateCreateTableSql(ConnectionConfig config, String databaseName, String tableName,
                                                 List<Map<String, String>> columns, Map<String, String> options,
                                                 String comment) {
        return generateCreateTableSql(config, databaseName, null, tableName, columns, options, comment);
    }

    /**
     * 生成CREATE TABLE SQL（支持MySQL/PostgreSQL/Oracle）
     * @param schemaName 模式名（PostgreSQL 使用；为 null 时回退用 databaseName 当 schema 名）
     */
    public static String generateCreateTableSql(ConnectionConfig config, String databaseName, String schemaName, String tableName,
                                                 List<Map<String, String>> columns, Map<String, String> options,
                                                 String comment) {
        String pgSchema = schemaName != null ? schemaName : databaseName;
        StringBuilder sb = new StringBuilder();
        List<String> primaryKeys = new ArrayList<>();
        List<String> colDefs = new ArrayList<>();

        for (Map<String, String> col : columns) {
            String colName = col.getOrDefault("字段名", "");
            if (colName == null || colName.trim().isEmpty()) continue;

            StringBuilder colDef = new StringBuilder();
            String type = col.getOrDefault("类型", "");
            String length = col.getOrDefault("长度", "");
            String decimal = col.getOrDefault("小数点", "");
            String nullable = col.getOrDefault("非空", "否");
            String autoInc = col.getOrDefault("自增", "否");
            String isPk = col.getOrDefault("主键", "否");
            String defaultValue = col.getOrDefault("默认值", "");
            String colComment = col.getOrDefault("注释", "");
            // 构造类型长度部分：需要小数位的类型生成 (length,decimal)，其他生成 (length)
            String typeSize = buildTypeSize(type, length, decimal);

            switch (config.getType()) {
                case MYSQL -> {
                    colDef.append("    `").append(colName).append("` ").append(type).append(typeSize);
                    if ("是".equals(col.get("无符号"))) colDef.append(" UNSIGNED");
                    if ("是".equals(col.get("填充零"))) colDef.append(" ZEROFILL");
                    String cs = col.get("字符集");
                    if (cs != null && !cs.isEmpty()) colDef.append(" CHARACTER SET ").append(cs);
                    String co = col.get("排序规则");
                    if (co != null && !co.isEmpty()) colDef.append(" COLLATE ").append(co);
                    if ("是".equals(nullable)) colDef.append(" NOT NULL");
                    if ("是".equals(autoInc)) colDef.append(" AUTO_INCREMENT");
                    if (defaultValue != null && !defaultValue.isEmpty()) {
                        if ("NULL".equalsIgnoreCase(defaultValue)) {
                            colDef.append(" DEFAULT NULL");
                        } else if ("CURRENT_TIMESTAMP".equalsIgnoreCase(defaultValue)) {
                            colDef.append(" DEFAULT CURRENT_TIMESTAMP");
                        } else {
                            colDef.append(" DEFAULT '").append(defaultValue.replace("'", "''")).append("'");
                        }
                    }
                    if (colComment != null && !colComment.isEmpty()) {
                        colDef.append(" COMMENT '").append(colComment.replace("'", "''")).append("'");
                    }
                }
                case POSTGRESQL, ORACLE -> {
                    String quote = config.getType() == ConnectType.POSTGRESQL ? "\"" : "\"";
                    colDef.append("    ").append(quote).append(colName).append(quote).append(" ").append(type).append(typeSize);
                    if ("是".equals(nullable)) colDef.append(" NOT NULL");
                }
                default -> throw new IllegalArgumentException("Unsupported database type: " + config.getType());
            }

            colDefs.add(colDef.toString());
            if ("是".equals(isPk)) {
                primaryKeys.add(colName);
            }
        }

        // 表名限定
        switch (config.getType()) {
            case MYSQL -> sb.append("CREATE TABLE `").append(databaseName).append("`.`").append(tableName).append("` (\n");
            case POSTGRESQL -> sb.append("CREATE TABLE \"").append(pgSchema).append("\".\"").append(tableName).append("\" (\n");
            case ORACLE -> sb.append("CREATE TABLE \"").append(databaseName).append("\".\"").append(tableName).append("\" (\n");
            default -> throw new IllegalArgumentException("Unsupported database type: " + config.getType());
        }

        sb.append(String.join(",\n", colDefs));

        // 主键
        if (!primaryKeys.isEmpty()) {
            sb.append(",\n    PRIMARY KEY (");
            for (int i = 0; i < primaryKeys.size(); i++) {
                if (i > 0) sb.append(", ");
                if (config.getType() == ConnectType.MYSQL) {
                    sb.append("`").append(primaryKeys.get(i)).append("`");
                } else {
                    sb.append("\"").append(primaryKeys.get(i)).append("\"");
                }
            }
            sb.append(")");
        }
        sb.append("\n)");

        // MySQL 表选项
        if (config.getType() == ConnectType.MYSQL && options != null) {
            String engine = options.get("引擎");
            if (engine != null && !engine.isEmpty()) sb.append(" ENGINE=").append(engine);
            String charset = options.get("字符集");
            if (charset != null && !charset.isEmpty()) sb.append(" DEFAULT CHARSET=").append(charset);
            String collation = options.get("排序规则");
            if (collation != null && !collation.isEmpty()) sb.append(" COLLATE=").append(collation);
        }

        // 表注释
        if (comment != null && !comment.isEmpty()) {
            String escaped = comment.replace("'", "''");
            switch (config.getType()) {
                case MYSQL -> sb.append(" COMMENT='").append(escaped).append("'");
                case POSTGRESQL, ORACLE -> {} // 使用单独的 COMMENT ON 语句
            }
        }
        sb.append(";");

        // PostgreSQL/Oracle 表注释使用单独语句
        if (comment != null && !comment.isEmpty() && config.getType() != ConnectType.MYSQL) {
            String commentSchema = config.getType() == ConnectType.POSTGRESQL ? pgSchema : databaseName;
            sb.append("\nCOMMENT ON TABLE \"").append(commentSchema).append("\".\"").append(tableName)
              .append("\" IS '").append(comment.replace("'", "''")).append("';");
        }

        return sb.toString();
    }

    /**
     * 判断类型是否需要指定小数位数（精度类型：decimal/numeric/double/float/real）
     */
    public static boolean needsDecimalPlaces(String type) {
        if (type == null) return false;
        String t = type.toLowerCase();
        return t.contains("decimal") || t.contains("numeric")
                || t.contains("double") || t.contains("float") || t.contains("real");
    }

    /**
     * 生成更新列注释的SQL
     * MySQL需要ALTER TABLE MODIFY COLUMN携带完整列定义；PostgreSQL/Oracle使用COMMENT ON COLUMN
     *
     * @param columnTitles 字段表列标题列表（字段名、类型、长度、非空、主键、自增、默认值、注释）
     * @param row 当前行数据
     */
    public static String generateUpdateColumnCommentSql(ConnectionConfig config, String databaseName, String tableName,
                                                        List<String> columnTitles, ObservableList<String> row) throws Exception {
        return generateUpdateColumnCommentSql(config, databaseName, null, tableName, columnTitles, row);
    }

    /**
     * 生成更新列注释的SQL
     * MySQL需要ALTER TABLE MODIFY COLUMN携带完整列定义；PostgreSQL/Oracle使用COMMENT ON COLUMN
     * @param schemaName 模式名（PostgreSQL 使用；为 null 时回退用 databaseName 当 schema 名）
     */
    public static String generateUpdateColumnCommentSql(ConnectionConfig config, String databaseName, String schemaName, String tableName,
                                                        List<String> columnTitles, ObservableList<String> row) throws Exception {
        String columnName = getValue(row, columnTitles, "字段名");
        String comment = getValue(row, columnTitles, "注释");
        String escapedComment = comment != null ? comment.replace("'", "''") : "";
        String pgSchema = schemaName != null ? schemaName : databaseName;

        if (config.getType() == ConnectType.MYSQL) {
            // MySQL: 需要查询原始列类型，构造完整的MODIFY COLUMN语句
            String columnType = getMysqlColumnType(config, databaseName, tableName, columnName);
            String notNull = getValue(row, columnTitles, "非空");
            String autoIncrement = getValue(row, columnTitles, "自增");
            String defaultValue = getValue(row, columnTitles, "默认值");

            StringBuilder sql = new StringBuilder();
            sql.append("ALTER TABLE `").append(databaseName).append("`.`").append(tableName).append("` ");
            sql.append("MODIFY COLUMN `").append(columnName).append("` ");
            sql.append(columnType);
            if ("是".equals(notNull)) {
                sql.append(" NOT NULL");
            } else {
                sql.append(" NULL");
            }
            if (defaultValue != null && !defaultValue.isEmpty()) {
                sql.append(" DEFAULT '").append(defaultValue.replace("'", "''")).append("'");
            }
            if ("是".equals(autoIncrement)) {
                sql.append(" AUTO_INCREMENT");
            }
            sql.append(" COMMENT '").append(escapedComment).append("'");
            return sql.toString();
        } else if (config.getType() == ConnectType.POSTGRESQL) {
            return "COMMENT ON COLUMN \"" + pgSchema + "\".\"" + tableName + "\".\"" + columnName + "\" IS '" + escapedComment + "'";
        } else if (config.getType() == ConnectType.ORACLE) {
            return "COMMENT ON COLUMN \"" + databaseName + "\".\"" + tableName + "\".\"" + columnName + "\" IS '" + escapedComment + "'";
        }
        throw new IllegalArgumentException("Unsupported database type: " + config.getType());
    }

    /**
     * 生成新增列的ALTER SQL（支持MySQL/PostgreSQL/Oracle）。
     * @param columnTitles 字段表列标题列表
     * @param row 当前行数据
     */
    public static String generateAddColumnSql(ConnectionConfig config, String databaseName, String schemaName,
                                              String tableName, List<String> columnTitles, ObservableList<String> row) {
        return generateAddColumnSql(config, databaseName, schemaName, tableName, columnTitles, row, null);
    }

    /**
     * 生成新增列的ALTER SQL（支持MySQL/PostgreSQL/Oracle）。
     * @param afterColumnName 在该列之后插入（仅MySQL生效，用于在已有字段中间插入新列）；
     *                         null或空字符串表示插入到首位（MySQL FIRST）；其他数据库类型忽略该参数（新列固定加到末尾）
     */
    public static String generateAddColumnSql(ConnectionConfig config, String databaseName, String schemaName,
                                              String tableName, List<String> columnTitles, ObservableList<String> row,
                                              String afterColumnName) {
        String pgSchema = schemaName != null ? schemaName : databaseName;
        String columnName = getValue(row, columnTitles, "字段名");
        String type = getValue(row, columnTitles, "类型");
        String length = getValue(row, columnTitles, "长度");
        String decimal = getValue(row, columnTitles, "小数点");
        String nullable = getValue(row, columnTitles, "非空");
        String autoInc = getValue(row, columnTitles, "自增");
        String defaultValue = getValue(row, columnTitles, "默认值");
        String colComment = getValue(row, columnTitles, "注释");
        String typeSize = buildTypeSize(type, length, decimal);

        StringBuilder sql = new StringBuilder();
        switch (config.getType()) {
            case MYSQL -> {
                sql.append("ALTER TABLE `").append(databaseName).append("`.`").append(tableName).append("` ");
                sql.append("ADD COLUMN `").append(columnName).append("` ").append(type).append(typeSize);
                if ("是".equals(getValue(row, columnTitles, "无符号"))) sql.append(" UNSIGNED");
                if ("是".equals(getValue(row, columnTitles, "填充零"))) sql.append(" ZEROFILL");
                String cs = getValue(row, columnTitles, "字符集");
                if (cs != null && !cs.isEmpty()) sql.append(" CHARACTER SET ").append(cs);
                String co = getValue(row, columnTitles, "排序规则");
                if (co != null && !co.isEmpty()) sql.append(" COLLATE ").append(co);
                if ("是".equals(nullable)) sql.append(" NOT NULL");
                if ("是".equals(autoInc)) sql.append(" AUTO_INCREMENT");
                appendDefaultClause(sql, defaultValue);
                if (colComment != null && !colComment.isEmpty()) {
                    sql.append(" COMMENT '").append(colComment.replace("'", "''")).append("'");
                }
                // 指定插入位置：在已有字段中间插入新列时使用，避免被追加到表末尾
                if (afterColumnName == null || afterColumnName.isEmpty()) {
                    sql.append(" FIRST");
                } else {
                    sql.append(" AFTER `").append(afterColumnName).append("`");
                }
            }
            case POSTGRESQL -> {
                sql.append("ALTER TABLE \"").append(pgSchema).append("\".\"").append(tableName).append("\" ");
                sql.append("ADD COLUMN \"").append(columnName).append("\" ").append(type).append(typeSize);
                if ("是".equals(nullable)) sql.append(" NOT NULL");
                appendDefaultClause(sql, defaultValue);
            }
            case ORACLE -> {
                sql.append("ALTER TABLE \"").append(databaseName).append("\".\"").append(tableName).append("\" ");
                sql.append("ADD (\"").append(columnName).append("\" ").append(type).append(typeSize);
                if ("是".equals(nullable)) sql.append(" NOT NULL");
                appendDefaultClause(sql, defaultValue);
                sql.append(")");
            }
            default -> throw new IllegalArgumentException("Unsupported database type: " + config.getType());
        }
        // PostgreSQL/Oracle 列注释使用单独语句
        if (colComment != null && !colComment.isEmpty() && config.getType() != ConnectType.MYSQL) {
            String commentSchema = config.getType() == ConnectType.POSTGRESQL ? pgSchema : databaseName;
            sql.append(";\nCOMMENT ON COLUMN \"").append(commentSchema).append("\".\"").append(tableName)
               .append("\".\"").append(columnName).append("\" IS '").append(colComment.replace("'", "''")).append("'");
        }
        return sql.toString();
    }

    /**
     * 生成删除列的ALTER SQL（支持MySQL/PostgreSQL/Oracle）。
     */
    public static String generateDropColumnSql(ConnectionConfig config, String databaseName, String schemaName,
                                               String tableName, String columnName) {
        String pgSchema = schemaName != null ? schemaName : databaseName;
        switch (config.getType()) {
            case MYSQL -> {
                return "ALTER TABLE `" + databaseName + "`.`" + tableName + "` DROP COLUMN `" + columnName + "`";
            }
            case POSTGRESQL -> {
                return "ALTER TABLE \"" + pgSchema + "\".\"" + tableName + "\" DROP COLUMN \"" + columnName + "\"";
            }
            case ORACLE -> {
                return "ALTER TABLE \"" + databaseName + "\".\"" + tableName + "\" DROP COLUMN \"" + columnName + "\"";
            }
            default -> throw new IllegalArgumentException("Unsupported database type: " + config.getType());
        }
    }

    /**
     * 生成修改列的ALTER SQL（字段名相同但属性变化）。
     * MySQL用单条MODIFY COLUMN；PostgreSQL/Oracle可能生成多条语句（类型/非空/默认值/注释）。
     * @param originalRow 原始行数据快照
     * @param currentRow 当前行数据
     * @return 变更SQL列表，无变更时返回空列表
     */
    public static List<String> generateModifyColumnSql(ConnectionConfig config, String databaseName, String schemaName,
                                                       String tableName, List<String> columnTitles,
                                                       ObservableList<String> originalRow, ObservableList<String> currentRow) {
        String pgSchema = schemaName != null ? schemaName : databaseName;
        String columnName = getValue(currentRow, columnTitles, "字段名");
        List<String> sqlList = new ArrayList<>();

        String type = getValue(currentRow, columnTitles, "类型");
        String length = getValue(currentRow, columnTitles, "长度");
        String decimal = getValue(currentRow, columnTitles, "小数点");
        String typeSize = buildTypeSize(type, length, decimal);

        String oldType = getValue(originalRow, columnTitles, "类型");
        String oldLength = getValue(originalRow, columnTitles, "长度");
        String oldDecimal = getValue(originalRow, columnTitles, "小数点");
        String oldTypeSize = buildTypeSize(oldType, oldLength, oldDecimal);

        String nullable = getValue(currentRow, columnTitles, "非空");
        String oldNullable = getValue(originalRow, columnTitles, "非空");
        String defaultValue = getValue(currentRow, columnTitles, "默认值");
        String oldDefault = getValue(originalRow, columnTitles, "默认值");
        String comment = getValue(currentRow, columnTitles, "注释");
        String oldComment = getValue(originalRow, columnTitles, "注释");

        boolean typeChanged = !type.equals(oldType) || !typeSize.equals(oldTypeSize);
        boolean nullableChanged = !nullable.equals(oldNullable);
        boolean defaultChanged = !defaultValue.equals(oldDefault);
        boolean commentChanged = !comment.equals(oldComment);

        switch (config.getType()) {
            case MYSQL -> {
                // MySQL: 任何列属性变化都用 MODIFY COLUMN（需完整列定义）
                if (typeChanged || nullableChanged || defaultChanged || commentChanged
                        || !getValue(currentRow, columnTitles, "无符号").equals(getValue(originalRow, columnTitles, "无符号"))
                        || !getValue(currentRow, columnTitles, "填充零").equals(getValue(originalRow, columnTitles, "填充零"))
                        || !getValue(currentRow, columnTitles, "自增").equals(getValue(originalRow, columnTitles, "自增"))
                        || !getValue(currentRow, columnTitles, "字符集").equals(getValue(originalRow, columnTitles, "字符集"))
                        || !getValue(currentRow, columnTitles, "排序规则").equals(getValue(originalRow, columnTitles, "排序规则"))) {
                    StringBuilder sql = new StringBuilder();
                    sql.append("ALTER TABLE `").append(databaseName).append("`.`").append(tableName).append("` ");
                    sql.append("MODIFY COLUMN `").append(columnName).append("` ").append(type).append(typeSize);
                    if ("是".equals(getValue(currentRow, columnTitles, "无符号"))) sql.append(" UNSIGNED");
                    if ("是".equals(getValue(currentRow, columnTitles, "填充零"))) sql.append(" ZEROFILL");
                    String cs = getValue(currentRow, columnTitles, "字符集");
                    if (cs != null && !cs.isEmpty()) sql.append(" CHARACTER SET ").append(cs);
                    String co = getValue(currentRow, columnTitles, "排序规则");
                    if (co != null && !co.isEmpty()) sql.append(" COLLATE ").append(co);
                    if ("是".equals(nullable)) sql.append(" NOT NULL");
                    else sql.append(" NULL");
                    String autoInc = getValue(currentRow, columnTitles, "自增");
                    if ("是".equals(autoInc)) sql.append(" AUTO_INCREMENT");
                    appendDefaultClause(sql, defaultValue);
                    if (comment != null && !comment.isEmpty()) {
                        sql.append(" COMMENT '").append(comment.replace("'", "''")).append("'");
                    }
                    sqlList.add(sql.toString());
                }
            }
            case POSTGRESQL -> {
                if (typeChanged) {
                    sqlList.add("ALTER TABLE \"" + pgSchema + "\".\"" + tableName + "\" ALTER COLUMN \"" + columnName
                            + "\" TYPE " + type + typeSize + " USING \"" + columnName + "\"::" + type);
                }
                if (nullableChanged) {
                    if ("是".equals(nullable)) {
                        sqlList.add("ALTER TABLE \"" + pgSchema + "\".\"" + tableName + "\" ALTER COLUMN \"" + columnName + "\" SET NOT NULL");
                    } else {
                        sqlList.add("ALTER TABLE \"" + pgSchema + "\".\"" + tableName + "\" ALTER COLUMN \"" + columnName + "\" DROP NOT NULL");
                    }
                }
                if (defaultChanged) {
                    if (defaultValue == null || defaultValue.isEmpty()) {
                        sqlList.add("ALTER TABLE \"" + pgSchema + "\".\"" + tableName + "\" ALTER COLUMN \"" + columnName + "\" DROP DEFAULT");
                    } else {
                        sqlList.add("ALTER TABLE \"" + pgSchema + "\".\"" + tableName + "\" ALTER COLUMN \"" + columnName + "\" SET " + buildDefaultClause(defaultValue));
                    }
                }
                if (commentChanged) {
                    String escaped = comment != null ? comment.replace("'", "''") : "";
                    sqlList.add("COMMENT ON COLUMN \"" + pgSchema + "\".\"" + tableName + "\".\"" + columnName + "\" IS '" + escaped + "'");
                }
            }
            case ORACLE -> {
                if (typeChanged || nullableChanged || defaultChanged) {
                    StringBuilder sql = new StringBuilder();
                    sql.append("ALTER TABLE \"").append(databaseName).append("\".\"").append(tableName).append("\" ");
                    sql.append("MODIFY (\"").append(columnName).append("\" ").append(type).append(typeSize);
                    if ("是".equals(nullable)) sql.append(" NOT NULL");
                    else sql.append(" NULL");
                    appendDefaultClause(sql, defaultValue);
                    sql.append(")");
                    sqlList.add(sql.toString());
                }
                if (commentChanged) {
                    String escaped = comment != null ? comment.replace("'", "''") : "";
                    sqlList.add("COMMENT ON COLUMN \"" + databaseName + "\".\"" + tableName + "\".\"" + columnName + "\" IS '" + escaped + "'");
                }
            }
            default -> throw new IllegalArgumentException("Unsupported database type: " + config.getType());
        }
        return sqlList;
    }

    /**
     * 生成调整列顺序的ALTER SQL（仅MySQL支持，使用 MODIFY COLUMN ... AFTER/FIRST）。
     * PostgreSQL/Oracle不支持直接调整列顺序，返回null。
     * @param afterColumnName 移动到该列之后；null或空字符串表示移到首位（FIRST）
     * @return ALTER SQL；不支持的数据库类型返回null
     */
    public static String generateReorderColumnSql(ConnectionConfig config, String databaseName, String schemaName,
                                                  String tableName, List<String> columnTitles,
                                                  ObservableList<String> row, String afterColumnName) {
        if (config.getType() != ConnectType.MYSQL) {
            return null;
        }
        String columnName = getValue(row, columnTitles, "字段名");
        String type = getValue(row, columnTitles, "类型");
        String length = getValue(row, columnTitles, "长度");
        String decimal = getValue(row, columnTitles, "小数点");
        String typeSize = buildTypeSize(type, length, decimal);
        String nullable = getValue(row, columnTitles, "非空");
        String autoInc = getValue(row, columnTitles, "自增");
        String defaultValue = getValue(row, columnTitles, "默认值");
        String colComment = getValue(row, columnTitles, "注释");

        StringBuilder sql = new StringBuilder();
        sql.append("ALTER TABLE `").append(databaseName).append("`.`").append(tableName).append("` ");
        sql.append("MODIFY COLUMN `").append(columnName).append("` ").append(type).append(typeSize);
        if ("是".equals(getValue(row, columnTitles, "无符号"))) sql.append(" UNSIGNED");
        if ("是".equals(getValue(row, columnTitles, "填充零"))) sql.append(" ZEROFILL");
        String cs = getValue(row, columnTitles, "字符集");
        if (cs != null && !cs.isEmpty()) sql.append(" CHARACTER SET ").append(cs);
        String co = getValue(row, columnTitles, "排序规则");
        if (co != null && !co.isEmpty()) sql.append(" COLLATE ").append(co);
        if ("是".equals(nullable)) sql.append(" NOT NULL");
        else sql.append(" NULL");
        if ("是".equals(autoInc)) sql.append(" AUTO_INCREMENT");
        appendDefaultClause(sql, defaultValue);
        if (colComment != null && !colComment.isEmpty()) {
            sql.append(" COMMENT '").append(colComment.replace("'", "''")).append("'");
        }
        if (afterColumnName == null || afterColumnName.isEmpty()) {
            sql.append(" FIRST");
        } else {
            sql.append(" AFTER `").append(afterColumnName).append("`");
        }
        return sql.toString();
    }

    /**
     * 构造类型长度部分：(length) 或 (length,decimal)
     * 注意：日期时间类型（timestamp/datetime/date/time/year）的精度为小数秒（DECIMAL_DIGITS，0-6），
     * 不能使用 JDBC 的 COLUMN_SIZE（字符显示宽度，如 TIMESTAMP 返回 19），否则会生成无效的 TIMESTAMP(19) 导致保存失败。
     */
    private static String buildTypeSize(String type, String length, String decimal) {
        if (type != null) {
            String t = type.toLowerCase();
            if (t.contains("date") || (t.contains("time") && !t.contains("interval")) || t.contains("year")) {
                if (decimal != null && !decimal.isEmpty() && !"0".equals(decimal)) {
                    return "(" + decimal + ")";
                }
                return "";
            }
        }
        if (length == null || length.isEmpty()) return "";
        if (needsDecimalPlaces(type) && decimal != null && !decimal.isEmpty()) {
            return "(" + length + "," + decimal + ")";
        }
        return "(" + length + ")";
    }

    /**
     * 向StringBuilder追加DEFAULT子句（处理NULL/CURRENT_TIMESTAMP等特殊值）
     */
    private static void appendDefaultClause(StringBuilder sql, String defaultValue) {
        if (defaultValue == null || defaultValue.isEmpty()) return;
        sql.append(" DEFAULT ").append(buildDefaultClause(defaultValue));
    }

    /**
     * 构造DEFAULT子句内容（特殊值不加引号，其他用单引号包裹）
     */
    private static String buildDefaultClause(String defaultValue) {
        if ("NULL".equalsIgnoreCase(defaultValue)) return "NULL";
        if ("CURRENT_TIMESTAMP".equalsIgnoreCase(defaultValue)) return "CURRENT_TIMESTAMP";
        return "'" + defaultValue.replace("'", "''") + "'";
    }

    /**
     * 更新列注释
     */
    public static void updateColumnComment(ConnectionConfig config, String databaseName, String tableName,
                                           List<String> columnTitles, ObservableList<String> row) throws Exception {
        updateColumnComment(config, databaseName, null, tableName, columnTitles, row);
    }

    /**
     * 更新列注释
     * @param schemaName 模式名（PostgreSQL 使用；为 null 时回退用 databaseName 当 schema 名）
     */
    public static void updateColumnComment(ConnectionConfig config, String databaseName, String schemaName, String tableName,
                                           List<String> columnTitles, ObservableList<String> row) throws Exception {
        String sql = generateUpdateColumnCommentSql(config, databaseName, schemaName, tableName, columnTitles, row);
        // PostgreSQL 绑定到具体数据库；MySQL/Oracle 复用主连接
        Connection conn = (config.getType() == ConnectType.POSTGRESQL)
                ? getConnection(config, databaseName)
                : getConnection(config);
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(sql);
        }
    }

    /**
     * 查询MySQL列的完整类型字符串（如 varchar(255)、int、decimal(10,2)）
     */
    private static String getMysqlColumnType(ConnectionConfig config, String databaseName, String tableName, String columnName) throws Exception {
        Connection conn = getConnection(config);
        String sql = "SELECT COLUMN_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND COLUMN_NAME = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, databaseName);
            stmt.setString(2, tableName);
            stmt.setString(3, columnName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("COLUMN_TYPE");
                }
            }
        }
        throw new RuntimeException("未找到列: " + columnName);
    }

    /**
     * 从行数据中按列标题获取值
     */
    private static String getValue(ObservableList<String> row, List<String> columnTitles, String title) {
        int idx = columnTitles.indexOf(title);
        return idx >= 0 && idx < row.size() ? row.get(idx) : "";
    }

    // ==================== 复制表功能 ====================

    /**
     * 复制表：根据是否同连接选择不同策略
     * @param srcConfig 源连接
     * @param srcDb 源数据库
     * @param srcSchema 源schema（可为null）
     * @param srcTable 源表名
     * @param dstConfig 目标连接
     * @param dstDb 目标数据库
     * @param dstSchema 目标schema（可为null）
     * @param dstTable 目标表名
     * @param copyStructure 是否复制结构
     * @param copyData 是否复制数据
     * @param dropIfExists 目标存在时是否先删除
     */
    public static void copyTable(ConnectionConfig srcConfig, String srcDb, String srcSchema, String srcTable,
                                 ConnectionConfig dstConfig, String dstDb, String dstSchema, String dstTable,
                                 boolean copyStructure, boolean copyData, boolean dropIfExists) throws Exception {
        boolean sameConn = srcConfig.getId() != null && srcConfig.getId().equals(dstConfig.getId());
        if (sameConn) {
            copyTableSameConnection(srcConfig, srcDb, srcSchema, srcTable, dstDb, dstSchema, dstTable,
                    copyStructure, copyData, dropIfExists);
        } else {
            copyTableCrossConnection(srcConfig, srcDb, srcSchema, srcTable, dstConfig, dstDb, dstSchema, dstTable,
                    copyStructure, copyData, dropIfExists);
        }
    }

    /**
     * 批量复制多张表：依次调用 copyTable 完成每张表的复制。
     * 任一张表失败时抛出异常并终止后续复制（已复制的表不会回滚）。
     * @param srcTables 源表名列表（与 dstTables 一一对应）
     * @param dstTables 目标表名列表（与 srcTables 一一对应）
     */
    public static void copyTables(ConnectionConfig srcConfig, String srcDb, String srcSchema, List<String> srcTables,
                                  ConnectionConfig dstConfig, String dstDb, String dstSchema, List<String> dstTables,
                                  boolean copyStructure, boolean copyData, boolean dropIfExists) throws Exception {
        if (srcTables == null || dstTables == null || srcTables.size() != dstTables.size()) {
            throw new IllegalArgumentException("源表名列表与目标表名列表长度不一致");
        }
        Exception lastError = null;
        int successCount = 0;
        int failedCount = 0;
        for (int i = 0; i < srcTables.size(); i++) {
            String srcTable = srcTables.get(i);
            String dstTable = dstTables.get(i);
            try {
                copyTable(srcConfig, srcDb, srcSchema, srcTable,
                        dstConfig, dstDb, dstSchema, dstTable,
                        copyStructure, copyData, dropIfExists);
                successCount++;
            } catch (Exception ex) {
                failedCount++;
                lastError = ex;
                // 继续尝试后续表，记录最后一个错误
            }
        }
        // 全部失败时抛出异常；部分成功时也抛出（提示用户）
        if (failedCount > 0) {
            String msg = "批量复制完成：成功 " + successCount + " 张，失败 " + failedCount + " 张"
                    + (lastError != null ? "；最后错误: " + lastError.getMessage() : "");
            throw new Exception(msg);
        }
    }

    /**
     * 同连接复制表：使用 CREATE TABLE ... LIKE + INSERT INTO ... SELECT（效率更高）
     */
    public static void copyTableSameConnection(ConnectionConfig config,
                                               String srcDb, String srcSchema, String srcTable,
                                               String dstDb, String dstSchema, String dstTable,
                                               boolean copyStructure, boolean copyData, boolean dropIfExists) throws Exception {
        Connection conn = (config.getType() == ConnectType.POSTGRESQL)
                ? getConnection(config, srcDb)
                : getConnection(config);

        try (Statement stmt = conn.createStatement()) {
            // 1. 目标表存在则删除
            if (dropIfExists) {
                String dropSql = buildDropTableSql(config, dstDb, dstSchema, dstTable);
                try { stmt.executeUpdate(dropSql); } catch (Exception ignore) {}
            }

            String srcQualified = buildQualifiedTable(config, srcDb, srcSchema, srcTable);
            String dstQualified = buildQualifiedTable(config, dstDb, dstSchema, dstTable);

            // 2. 复制结构
            if (copyStructure) {
                String createSql;
                if (config.getType() == ConnectType.MYSQL) {
                    createSql = "CREATE TABLE " + dstQualified + " LIKE " + srcQualified;
                } else if (config.getType() == ConnectType.POSTGRESQL) {
                    String pgSrcSchema = srcSchema != null ? srcSchema : srcDb;
                    String pgDstSchema = dstSchema != null ? dstSchema : dstDb;
                    createSql = "CREATE TABLE \"" + pgDstSchema + "\".\"" + dstTable + "\" "
                            + "(LIKE \"" + pgSrcSchema + "\".\"" + srcTable + "\" INCLUDING ALL)";
                } else if (config.getType() == ConnectType.ORACLE) {
                    createSql = "CREATE TABLE " + dstQualified + " AS SELECT * FROM " + srcQualified + " WHERE 1=0";
                } else {
                    throw new IllegalArgumentException("Unsupported database type: " + config.getType());
                }
                stmt.executeUpdate(createSql);
            } else if (copyData) {
                // 只复制数据但不建结构，需要目标表已存在，直接跳过结构步骤
            }

            // 3. 复制数据
            if (copyData) {
                String insertSql;
                if (config.getType() == ConnectType.MYSQL || config.getType() == ConnectType.POSTGRESQL) {
                    insertSql = "INSERT INTO " + dstQualified + " SELECT * FROM " + srcQualified;
                } else if (config.getType() == ConnectType.ORACLE) {
                    insertSql = "INSERT INTO " + dstQualified + " SELECT * FROM " + srcQualified;
                } else {
                    throw new IllegalArgumentException("Unsupported database type: " + config.getType());
                }
                stmt.executeUpdate(insertSql);
            }
        }
    }

    /**
     * 跨连接复制表：
     *  1) 获取源表DDL并在目标端执行（需要适配目标DB类型）
     *  2) 分页读取源表数据，逐行INSERT到目标表
     */
    public static void copyTableCrossConnection(ConnectionConfig srcConfig, String srcDb, String srcSchema, String srcTable,
                                                 ConnectionConfig dstConfig, String dstDb, String dstSchema, String dstTable,
                                                 boolean copyStructure, boolean copyData, boolean dropIfExists) throws Exception {
        java.util.concurrent.locks.ReentrantLock srcLock = acquireUsageLock(srcConfig, srcDb);
        java.util.concurrent.locks.ReentrantLock dstLock = acquireUsageLock(dstConfig, dstDb);
        boolean srcLocked = false, dstLocked = false;
        try {
            srcLock.lock(); srcLocked = true;
            dstLock.lock(); dstLocked = true;

            // 1. 可选：删除目标表
            if (dropIfExists) {
                try {
                    dropTables(dstConfig, dstDb, dstSchema, List.of(dstTable));
                } catch (Exception ignore) {}
            }

            List<Map<String, String>> columns = getTableColumns(srcConfig, srcDb, srcSchema, srcTable);
            List<String> columnNames = new ArrayList<>();
            for (Map<String, String> col : columns) {
                columnNames.add(col.get("字段名"));
            }

            // 2. 复制结构
            if (copyStructure) {
                String ddl = getTableDdl(srcConfig, srcDb, srcSchema, srcTable);
                ddl = adaptDdlForTarget(srcConfig, dstConfig, ddl, srcDb, srcSchema, srcTable, dstDb, dstSchema, dstTable);
                executeDdlOnTarget(dstConfig, dstDb, ddl);
            }

            // 3. 复制数据
            if (copyData) {
                int pageSize = 500;
                int page = 1;
                int totalInserted = 0;
                while (true) {
                    TableRowData pageData = queryTableData(srcConfig, srcDb, srcSchema, srcTable, page, pageSize, null, false);
                    if (pageData == null || pageData.getRows() == null || pageData.getRows().isEmpty()) {
                        break;
                    }
                    List<String> pks = getPrimaryKeys(srcConfig, srcDb, srcSchema, srcTable);
                    int inserted = insertRows(dstConfig, dstDb, dstSchema, dstTable, columnNames, pageData.getRows(), pks);
                    totalInserted += inserted;
                    if (pageData.getRows().size() < pageSize) {
                        break;
                    }
                    page++;
                }
            }
        } finally {
            if (dstLocked) dstLock.unlock();
            if (srcLocked) srcLock.unlock();
        }
    }

    /** 构造全限定表名 */
    private static String buildQualifiedTable(ConnectionConfig config, String db, String schema, String table) {
        String pgSchema = schema != null ? schema : db;
        return switch (config.getType()) {
            case MYSQL -> "`" + db + "`.`" + table + "`";
            case POSTGRESQL -> "\"" + pgSchema + "\".\"" + table + "\"";
            case ORACLE -> "\"" + db + "\".\"" + table + "\"";
            default -> throw new IllegalArgumentException("Unsupported database type: " + config.getType());
        };
    }

    /** 将源DDL适配到目标数据库（仅做基础的标识符引号转换） */
    private static String adaptDdlForTarget(ConnectionConfig srcCfg, ConnectionConfig dstCfg,
                                            String ddl, String srcDb, String srcSchema, String srcTable,
                                            String dstDb, String dstSchema, String dstTable) {
        if (ddl == null || ddl.isEmpty()) return ddl;

        // 去掉源限定符并替换为目标表名
        String result = ddl;
        if (dstCfg.getType() == ConnectType.MYSQL) {
            result = result.replaceAll("\"([^\"]+)\"", "`$1`");
        } else if (dstCfg.getType() == ConnectType.POSTGRESQL || dstCfg.getType() == ConnectType.ORACLE) {
            result = result.replaceAll("`([^`]+)`", "\"$1\"");
        }

        // 将CREATE TABLE后的限定名替换为目标
        // （简化处理：基于源DB类型生成全新的目标限定头）
        String oldHead = "";
        String newHead = "";
        if (srcCfg.getType() == ConnectType.MYSQL) {
            oldHead = "CREATE TABLE `" + srcDb + "`.`" + srcTable + "`";
        } else if (srcCfg.getType() == ConnectType.POSTGRESQL) {
            String pgSrc = srcSchema != null ? srcSchema : srcDb;
            oldHead = "CREATE TABLE \"" + pgSrc + "\".\"" + srcTable + "\"";
        } else if (srcCfg.getType() == ConnectType.ORACLE) {
            oldHead = "CREATE TABLE \"" + srcDb + "\".\"" + srcTable + "\"";
        }
        if (dstCfg.getType() == ConnectType.MYSQL) {
            newHead = "CREATE TABLE `" + dstDb + "`.`" + dstTable + "`";
        } else if (dstCfg.getType() == ConnectType.POSTGRESQL) {
            String pgDst = dstSchema != null ? dstSchema : dstDb;
            newHead = "CREATE TABLE \"" + pgDst + "\".\"" + dstTable + "\"";
        } else if (dstCfg.getType() == ConnectType.ORACLE) {
            newHead = "CREATE TABLE \"" + dstDb + "\".\"" + dstTable + "\"";
        }
        if (!oldHead.isEmpty() && !newHead.isEmpty()) {
            // 先尝试精确匹配
            if (result.contains(oldHead)) {
                result = result.replace(oldHead, newHead);
            } else {
                // 尝试不带限定符的版本（比如 SHOW CREATE TABLE 返回时可能省略部分限定）
                int idx = result.toUpperCase().indexOf("CREATE TABLE");
                if (idx >= 0) {
                    int parenIdx = result.indexOf("(", idx);
                    if (parenIdx > idx) {
                        String prefix = result.substring(0, idx);
                        String suffix = result.substring(parenIdx);
                        result = prefix + newHead + " " + suffix.trim();
                    }
                }
            }
        }
        return result;
    }

    /** 在目标端执行DDL（PostgreSQL用绑定DB的连接） */
    private static void executeDdlOnTarget(ConnectionConfig dstConfig, String dstDb, String ddl) throws Exception {
        Connection conn = (dstConfig.getType() == ConnectType.POSTGRESQL)
                ? getConnection(dstConfig, dstDb)
                : getConnection(dstConfig);
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(ddl);
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 构建JDBC URL
     */
    private static String buildJdbcUrl(ConnectionConfig config, String host, int port, String database) {
        String db = (database != null && !database.isEmpty()) ? database : "";
        return switch (config.getType()) {
            // MySQL 性能优化参数：
            //   useLocalSessionState=true      避免每次执行前查询 @@session.tx_* 等变量
            //   cacheDefaultDatabase=true      缓存当前数据库，避免每次查询 currentDatabase()
            //   cacheServerConfiguration=true  缓存服务器配置，避免重复查询系统变量
            //   maintainTimeStats=false        不维护时间统计，减少额外查询
            //   prepStmtCacheEnabled=true      启用服务端预编译语句缓存
            //   prepStmtCacheSize=250          预编译语句缓存大小
            //   useServerPrepStmts=true        使用服务端预编译
            //   rewriteBatchedStatements=true  批量语句重写优化
            //   connectTimeout=5000            连接握手超时 5s
            //   socketTimeout=30000            socket 读超时 30s
            case MYSQL -> "jdbc:mysql://" + host + ":" + port + "/" + db
                    + "?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
                    + "&useLocalSessionState=true&cacheDefaultDatabase=true&cacheServerConfiguration=true"
                    + "&maintainTimeStats=false&prepStmtCacheEnabled=true&prepStmtCacheSize=250"
                    + "&useServerPrepStmts=true&rewriteBatchedStatements=true"
                    // tinyInt1isBit=false：禁用驱动把 tinyint(1) 当作 BIT 类型的默认行为，
                    // 否则表结构中 tinyint(1) 字段的 TYPE_NAME 会被返回为 "BIT"，
                    // 导致设计表中 tinyint(1) 回显成 bit 类型（与 Navicat 不一致）
                    + "&tinyInt1isBit=false"
                    + "&connectTimeout=5000&socketTimeout=30000";
            case POSTGRESQL -> "jdbc:postgresql://" + host + ":" + port + "/" + db + "?connectTimeout=5&socketTimeout=300";
            case ORACLE -> "jdbc:oracle:thin:@" + host + ":" + port + ":" + db;
            default -> throw new IllegalArgumentException("Unsupported database type: " + config.getType());
        };
    }
}
