package com.tangluobo.tomato.module.connect.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import com.tangluobo.tomato.module.connect.ConnectType;
import com.tangluobo.tomato.module.connect.ConnectionConfig;
import com.tangluobo.tomato.module.connect.dialog.BackupDialog;
import javafx.application.Platform;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.io.IOUtils;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

/**
 * 数据库备份与还原服务
 * nb3文件格式：tar归档，内部包含gzip压缩的JSON元数据和SQL数据文件（Navicat兼容格式）
 */
public class BackupService {

    private static final String APP_DIR = System.getProperty("user.home") + "/.tomato";
    private static final String BACKUP_DIR = "backup";
    private static final String CHARSET = "UTF-8";
    private static final String RECORD_SEPARATOR = "\u001E\n";

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateFormat SDF = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private static final Gson GSON_META = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Gson GSON_DATA = new GsonBuilder().disableHtmlEscaping().create();
    private static final Gson GSON = new Gson();

    // ==================== 备份 ====================

    public static String createBackup(ConnectionConfig config, String databaseName,
                                      List<BackupDialog.BackupObject> objects, String comment,
                                      boolean lockTables, boolean singleTransaction,
                                      String filename, String path, BackupDialog.BackupTask task) throws Exception {

        Path dir = resolveBackupDir(config.getName(), databaseName, path);
        Files.createDirectories(dir);
        Path file = dir.resolve(filename);

        try (FileOutputStream fos = new FileOutputStream(file.toFile());
             TarArchiveOutputStream backupTar = new TarArchiveOutputStream(fos)) {

            backupTar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);

            // 构建备份元数据
            Nb3Meta navicatBackupMeta = new Nb3Meta();
            navicatBackupMeta.setMetaVersion("30100");
            navicatBackupMeta.setDatabaseType(getDatabaseType(config));
            navicatBackupMeta.setCatalog("");
            navicatBackupMeta.setSchema(databaseName);
            navicatBackupMeta.setStartTime(String.valueOf(System.currentTimeMillis() / 1000));
            navicatBackupMeta.setEndTime(String.valueOf(System.currentTimeMillis() / 1000));
            navicatBackupMeta.setEncryption("None");
            navicatBackupMeta.setComment(comment != null ? comment : "");

            // 备份所有对象
            List<Nb3Object> metaObjects = new ArrayList<>();
            int processed = 0;

            for (BackupDialog.BackupObject obj : objects) {
                if (task.isCancelled()) {
                    throw new Exception("备份已取消");
                }

                task.log("处理 " + obj.getType().getDisplayName() + ": " + obj.getName());

                switch (obj.getType()) {
                    case TABLE -> {
                        Nb3Object nb3Obj = backupTable(config, databaseName, obj, backupTar, task);
                        metaObjects.add(nb3Obj);
                    }
                    case VIEW -> {
                        Nb3Object nb3Obj = backupView(config, databaseName, obj, backupTar);
                        metaObjects.add(nb3Obj);
                    }
                    case FUNCTION -> {
                        Nb3Object nb3Obj = backupFunction(config, databaseName, obj, backupTar);
                        metaObjects.add(nb3Obj);
                    }
                    case EVENT -> {
                        Nb3Object nb3Obj = backupEvent(config, databaseName, obj, backupTar);
                        metaObjects.add(nb3Obj);
                    }
                }

                processed++;
                task.updateProgress(processed);
            }

            navicatBackupMeta.setObjects(metaObjects);

            // 更新结束时间
            navicatBackupMeta.setEndTime(String.valueOf(System.currentTimeMillis() / 1000));

            // 写入 meta.json
            byte[] metaBytes = GSON_META.toJson(navicatBackupMeta).getBytes(CHARSET);
            addToTar(backupTar, "meta.json", metaBytes);
        }

        return file.toAbsolutePath().toString();
    }

    private static Nb3Object backupTable(ConnectionConfig config, String databaseName,
                                          BackupDialog.BackupObject obj,
                                          TarArchiveOutputStream backupTar,
                                          BackupDialog.BackupTask task) throws Exception {
        String tableName = obj.getName();
        String uuid = UUID.randomUUID().toString().toUpperCase();

        Connection conn = DatabaseService.getConnection(config, databaseName);

        // 获取DDL
        String ddl = getCreateTableSQL(config, databaseName, tableName);

        // 获取列信息
        List<String> columnNames = new ArrayList<>();
        String selectSql = buildSelectSql(config, databaseName, tableName);
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(selectSql)) {
            ResultSetMetaData meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                columnNames.add(meta.getColumnLabel(i));
            }
        }

        // 查询行数
        long count = 0;
        String countQuery = buildCountSql(config, databaseName, tableName);
        try (PreparedStatement countStmt = conn.prepareStatement(countQuery);
             ResultSet rs = countStmt.executeQuery()) {
            if (rs.next()) {
                count = rs.getLong(1);
            }
        }

        int pageSize = 1000;
        int pageCount = (int) Math.ceil((double) count / pageSize);
        Nb3TableData[] backupTableData = new Nb3TableData[pageCount];

        if (count > 0) {
            for (int pageNumber = 1; pageNumber <= pageCount; pageNumber++) {
                if (task.isCancelled()) break;

                String pageSql = buildPageSql(config, databaseName, tableName, pageNumber, pageSize);
                List<Map<String, Object>> records = new ArrayList<>();
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(pageSql)) {
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (String col : columnNames) {
                            Object val = rs.getObject(col);
                            if (rs.wasNull()) {
                                row.put(col, null);
                            } else {
                                row.put(col, val);
                            }
                        }
                        records.add(row);
                    }
                }

                byte[] tableDataGzipBytes = toGzipBytes(records);
                String dataGzipName = String.format("%s.data.%05d.sql.gz", uuid, pageNumber - 1);
                addToTar(backupTar, dataGzipName, tableDataGzipBytes);
                backupTableData[pageNumber - 1] = new Nb3TableData(dataGzipName, DigestUtils.sha1Hex(new ByteArrayInputStream(tableDataGzipBytes)));

                task.incrementRecordCount(records.size());
            }
        }

        // 构建表元数据
        Nb3TableMeta tableMeta = new Nb3TableMeta();
        tableMeta.setData(backupTableData);
        tableMeta.setMetaVersion("30100");
        tableMeta.setName(tableName);
        tableMeta.setType("Table");
        tableMeta.setDdl(ddl);
        tableMeta.setSubddl(new String[]{});
        tableMeta.setAutoIncrement("");
        tableMeta.setFields(columnNames.toArray(new String[0]));
        tableMeta.setTriggerDDL(new String[]{});
        tableMeta.setIndexDDL(new String[]{});

        byte[] tableMetaGzipBytes = gzipBytes(GSON_META.toJson(tableMeta));
        String tableMetaGzipName = String.format("%s.meta.json.gz", uuid);
        addToTar(backupTar, tableMetaGzipName, tableMetaGzipBytes);

        Nb3Object nb3Obj = new Nb3Object();
        nb3Obj.setName(tableName);
        nb3Obj.setType("Table");
        nb3Obj.setUuid(uuid);
        nb3Obj.setRows(String.valueOf(count));
        Nb3MetaData metadata = new Nb3MetaData();
        metadata.setFilename(tableMetaGzipName);
        metadata.setChecksum(DigestUtils.sha1Hex(new ByteArrayInputStream(tableMetaGzipBytes)));
        nb3Obj.setMetadata(metadata);

        return nb3Obj;
    }

    private static Nb3Object backupView(ConnectionConfig config, String databaseName,
                                         BackupDialog.BackupObject obj,
                                         TarArchiveOutputStream backupTar) throws Exception {
        String viewName = obj.getName();
        String uuid = UUID.randomUUID().toString().toUpperCase();

        String ddl = getCreateViewSQL(config, databaseName, viewName);

        Nb3TableMeta tableMeta = new Nb3TableMeta();
        tableMeta.setMetaVersion("30100");
        tableMeta.setName(viewName);
        tableMeta.setType("View");
        tableMeta.setDdl(ddl);
        tableMeta.setSubddl(new String[]{});
        tableMeta.setAutoIncrement("");
        tableMeta.setFields(new String[]{});
        tableMeta.setTriggerDDL(new String[]{});
        tableMeta.setIndexDDL(new String[]{});
        tableMeta.setData(new Nb3TableData[]{});

        byte[] tableMetaGzipBytes = gzipBytes(GSON_META.toJson(tableMeta));
        String tableMetaGzipName = String.format("%s.meta.json.gz", uuid);
        addToTar(backupTar, tableMetaGzipName, tableMetaGzipBytes);

        Nb3Object nb3Obj = new Nb3Object();
        nb3Obj.setName(viewName);
        nb3Obj.setType("View");
        nb3Obj.setUuid(uuid);
        nb3Obj.setRows("0");
        Nb3MetaData metadata = new Nb3MetaData();
        metadata.setFilename(tableMetaGzipName);
        metadata.setChecksum(DigestUtils.sha1Hex(new ByteArrayInputStream(tableMetaGzipBytes)));
        nb3Obj.setMetadata(metadata);

        return nb3Obj;
    }

    private static Nb3Object backupFunction(ConnectionConfig config, String databaseName,
                                              BackupDialog.BackupObject obj,
                                              TarArchiveOutputStream backupTar) throws Exception {
        String funcName = obj.getName();
        String uuid = UUID.randomUUID().toString().toUpperCase();

        String ddl = getCreateFunctionSQL(config, databaseName, funcName);

        Nb3TableMeta tableMeta = new Nb3TableMeta();
        tableMeta.setMetaVersion("30100");
        tableMeta.setName(funcName);
        tableMeta.setType("Function");
        tableMeta.setDdl(ddl);
        tableMeta.setSubddl(new String[]{});
        tableMeta.setAutoIncrement("");
        tableMeta.setFields(new String[]{});
        tableMeta.setTriggerDDL(new String[]{});
        tableMeta.setIndexDDL(new String[]{});
        tableMeta.setData(new Nb3TableData[]{});

        byte[] tableMetaGzipBytes = gzipBytes(GSON_META.toJson(tableMeta));
        String tableMetaGzipName = String.format("%s.meta.json.gz", uuid);
        addToTar(backupTar, tableMetaGzipName, tableMetaGzipBytes);

        Nb3Object nb3Obj = new Nb3Object();
        nb3Obj.setName(funcName);
        nb3Obj.setType("Function");
        nb3Obj.setUuid(uuid);
        nb3Obj.setRows("0");
        Nb3MetaData metadata = new Nb3MetaData();
        metadata.setFilename(tableMetaGzipName);
        metadata.setChecksum(DigestUtils.sha1Hex(new ByteArrayInputStream(tableMetaGzipBytes)));
        nb3Obj.setMetadata(metadata);

        return nb3Obj;
    }

    private static Nb3Object backupEvent(ConnectionConfig config, String databaseName,
                                          BackupDialog.BackupObject obj,
                                          TarArchiveOutputStream backupTar) throws Exception {
        String eventName = obj.getName();
        String uuid = UUID.randomUUID().toString().toUpperCase();

        String ddl = getCreateEventSQL(config, databaseName, eventName);

        Nb3TableMeta tableMeta = new Nb3TableMeta();
        tableMeta.setMetaVersion("30100");
        tableMeta.setName(eventName);
        tableMeta.setType("Event");
        tableMeta.setDdl(ddl);
        tableMeta.setSubddl(new String[]{});
        tableMeta.setAutoIncrement("");
        tableMeta.setFields(new String[]{});
        tableMeta.setTriggerDDL(new String[]{});
        tableMeta.setIndexDDL(new String[]{});
        tableMeta.setData(new Nb3TableData[]{});

        byte[] tableMetaGzipBytes = gzipBytes(GSON_META.toJson(tableMeta));
        String tableMetaGzipName = String.format("%s.meta.json.gz", uuid);
        addToTar(backupTar, tableMetaGzipName, tableMetaGzipBytes);

        Nb3Object nb3Obj = new Nb3Object();
        nb3Obj.setName(eventName);
        nb3Obj.setType("Event");
        nb3Obj.setUuid(uuid);
        nb3Obj.setRows("0");
        Nb3MetaData metadata = new Nb3MetaData();
        metadata.setFilename(tableMetaGzipName);
        metadata.setChecksum(DigestUtils.sha1Hex(new ByteArrayInputStream(tableMetaGzipBytes)));
        nb3Obj.setMetadata(metadata);

        return nb3Obj;
    }

    // ==================== 还原 ====================

    public static void restoreBackup(ConnectionConfig config, String databaseName,
                                      String backupName, String path, RestoreTask task) throws Exception {
        Path file = resolveBackupDir(config.getName(), databaseName, path).resolve(backupName + ".nb3");
        if (!Files.exists(file)) {
            throw new IOException("备份文件不存在: " + file);
        }

        // 读取tar归档中的所有条目
        Map<String, byte[]> entries = new HashMap<>();
        try (TarArchiveInputStream inputStream = new TarArchiveInputStream(new FileInputStream(file.toFile()))) {
            ArchiveEntry entry = inputStream.getNextEntry();
            while (entry != null) {
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                IOUtils.copy(inputStream, outputStream);
                entries.put(entry.getName(), outputStream.toByteArray());
                entry = inputStream.getNextTarEntry();
            }
        }

        // 解析meta.json
        byte[] metaBytes = entries.get("meta.json");
        if (metaBytes == null) {
            throw new IOException("无效的nb3文件：缺少meta.json");
        }
        String metaJson = new String(metaBytes, CHARSET);
        Nb3Meta nb3Meta = GSON.fromJson(metaJson, Nb3Meta.class);

        // 解压其余条目
        Map<String, byte[]> decompressedEntries = new HashMap<>();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (!entry.getKey().equals("meta.json")) {
                try (GzipCompressorInputStream is = new GzipCompressorInputStream(new ByteArrayInputStream(entry.getValue()))) {
                    decompressedEntries.put(entry.getKey(), IOUtils.toByteArray(is));
                }
            }
        }

        Connection conn = DatabaseService.getConnection(config, databaseName);
        int processed = 0;

        for (Nb3Object object : nb3Meta.getObjects()) {
            if (task != null && task.isCancelled()) {
                throw new Exception("还原已取消");
            }

            String metaContent = new String(decompressedEntries.get(object.getMetadata().getFilename()), CHARSET);
            Nb3TableMeta tableMeta = GSON.fromJson(metaContent, Nb3TableMeta.class);
            String tableName = tableMeta.getName();
            String ddl = tableMeta.getDdl();

            if (task != null) task.log("还原 " + tableMeta.getType() + ": " + tableName);

            // 删除已存在的对象
            try (Statement stmt = conn.createStatement()) {
                String dropSql = switch (tableMeta.getType()) {
                    case "Table" -> switch (config.getType()) {
                        case MYSQL -> "DROP TABLE IF EXISTS `" + databaseName + "`.`" + tableName + "`";
                        case POSTGRESQL -> "DROP TABLE IF EXISTS \"" + databaseName + "\".\"" + tableName + "\"";
                        case ORACLE -> "DROP TABLE \"" + databaseName + "\".\"" + tableName + "\"";
                        default -> "DROP TABLE IF EXISTS " + tableName;
                    };
                    case "View" -> switch (config.getType()) {
                        case MYSQL -> "DROP VIEW IF EXISTS `" + databaseName + "`.`" + tableName + "`";
                        case POSTGRESQL -> "DROP VIEW IF EXISTS \"" + databaseName + "\".\"" + tableName + "\"";
                        case ORACLE -> "DROP VIEW \"" + databaseName + "\".\"" + tableName + "\"";
                        default -> "DROP VIEW IF EXISTS " + tableName;
                    };
                    case "Function" -> switch (config.getType()) {
                        case MYSQL -> "DROP FUNCTION IF EXISTS `" + databaseName + "`.`" + tableName + "`";
                        case POSTGRESQL -> "DROP FUNCTION IF EXISTS \"" + databaseName + "\".\"" + tableName + "\"";
                        case ORACLE -> "DROP FUNCTION \"" + databaseName + "\".\"" + tableName + "\"";
                        default -> "DROP FUNCTION IF EXISTS " + tableName;
                    };
                    case "Event" -> switch (config.getType()) {
                        case MYSQL -> "DROP EVENT IF EXISTS `" + databaseName + "`.`" + tableName + "`";
                        default -> "DROP EVENT IF EXISTS " + tableName;
                    };
                    default -> "";
                };
                if (!dropSql.isEmpty()) {
                    stmt.execute(dropSql);
                }
                if (ddl != null && !ddl.isEmpty()) {
                    stmt.execute(ddl);
                }
            }

            // 还原表数据
            if ("Table".equals(tableMeta.getType()) && tableMeta.getData() != null) {
                String[] fields = tableMeta.getFields();
                String quotedFields = buildQuotedColumns(config, fields);

                int batchSize = 0;
                try (Statement stmt = conn.createStatement()) {
                    for (Nb3TableData dataEntry : tableMeta.getData()) {
                        if (task != null && task.isCancelled()) break;

                        byte[] dataBytes = decompressedEntries.get(dataEntry.getFilename());
                        if (dataBytes == null) continue;

                        String dataContent = new String(dataBytes, CHARSET);
                        // 数据以RECORD_SEPARATOR分隔，每段是一个INSERT值组
                        String[] valueGroups = dataContent.split(RECORD_SEPARATOR.replace("\n", ""));
                        for (String valueGroup : valueGroups) {
                            if (valueGroup.trim().isEmpty()) continue;

                            String insertSql = switch (config.getType()) {
                                case MYSQL -> "INSERT INTO `" + databaseName + "`.`" + tableName + "` (" + quotedFields + ") VALUES " + valueGroup.replace("\u001E", ",");
                                case POSTGRESQL -> "INSERT INTO \"" + databaseName + "\".\"" + tableName + "\" (" + quotedFields + ") VALUES " + valueGroup.replace("\u001E", ",");
                                case ORACLE -> "INSERT INTO \"" + databaseName + "\".\"" + tableName + "\" (" + quotedFields + ") VALUES " + valueGroup.replace("\u001E", ",");
                                default -> "INSERT INTO " + tableName + " (" + quotedFields + ") VALUES " + valueGroup.replace("\u001E", ",");
                            };

                            stmt.addBatch(insertSql);
                            batchSize++;

                            if (batchSize >= 1000) {
                                stmt.executeBatch();
                                if (task != null) task.incrementRecordCount(batchSize);
                                batchSize = 0;
                            }
                        }
                    }

                    if (batchSize > 0) {
                        stmt.executeBatch();
                        if (task != null) task.incrementRecordCount(batchSize);
                    }
                }
            }

            processed++;
            if (task != null) {
                task.updateProgress(processed);
                task.log(tableMeta.getType() + " " + tableName + " 还原完成");
            }
        }
    }

    // ==================== 文件管理 ====================

    public static List<String> listBackups(String connectionName, String dbName, String path) {
        Path dir = resolveBackupDir(connectionName, dbName, path);
        List<String> backups = new ArrayList<>();
        if (!Files.isDirectory(dir)) return backups;

        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".nb3"))
                  .forEach(p -> {
                      String fileName = p.getFileName().toString();
                      backups.add(fileName.substring(0, fileName.length() - 4));
                  });
        } catch (IOException e) {
            System.err.println("加载备份列表失败: " + e.getMessage());
        }
        return backups;
    }

    /** 列出备份目录下的子目录名 */
    public static List<String> listBackupDirs(String connectionName, String dbName, String path) {
        Path dir = resolveBackupDir(connectionName, dbName, path);
        List<String> dirs = new ArrayList<>();
        if (!Files.isDirectory(dir)) return dirs;

        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isDirectory)
                  .forEach(p -> dirs.add(p.getFileName().toString()));
        } catch (IOException e) {
            System.err.println("加载备份子目录失败: " + e.getMessage());
        }
        return dirs;
    }

    public static void deleteBackupFile(String connectionName, String dbName, String backupName, String path) {
        Path file = resolveBackupDir(connectionName, dbName, path).resolve(backupName + ".nb3");
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            System.err.println("删除备份文件失败: " + e.getMessage());
        }
    }

    /** 递归删除备份目录（磁盘上的子目录及其所有内容） */
    public static void deleteBackupDir(String connectionName, String dbName, String path) {
        Path dir = resolveBackupDir(connectionName, dbName, path);
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
        } catch (IOException e) {
            System.err.println("删除备份目录失败: " + e.getMessage());
        }
    }

    public static void renameBackupFile(String connectionName, String dbName,
                                         String oldName, String newName, String path) throws IOException {
        String sanitizedNew = sanitizeFileName(newName);

        Path dir = resolveBackupDir(connectionName, dbName, path);
        Path oldFile = dir.resolve(oldName + ".nb3");
        Path newFile = dir.resolve(sanitizedNew + ".nb3");

        if (Files.exists(oldFile)) {
            Files.move(oldFile, newFile);
        }
    }

    /** 解析备份目录：~/.tomato/<conn>/<db>/backup/<path> */
    public static Path resolveBackupDir(String connectionName, String dbName, String path) {
        String sanitizedConn = sanitizeFileName(connectionName);
        String sanitizedDb = sanitizeFileName(dbName);
        Path dir = Paths.get(APP_DIR, sanitizedConn, sanitizedDb, BACKUP_DIR);
        if (path != null && !path.isEmpty()) {
            for (String part : path.split("/")) {
                dir = dir.resolve(sanitizeFileName(part));
            }
        }
        return dir;
    }

    // ==================== 工具方法 ====================

    private static byte[] toGzipBytes(List<Map<String, Object>> records) throws IOException {
        StringBuilder sb = new StringBuilder();
        int recordIndex = 0;
        for (Map<String, Object> record : records) {
            if (recordIndex > 0) {
                sb.append(RECORD_SEPARATOR);
            }
            sb.append("(");
            int index = 0;
            for (Map.Entry<String, Object> entry : record.entrySet()) {
                Object value = entry.getValue();
                if (index > 0) {
                    sb.append(", ");
                }
                if (value == null) {
                    sb.append("NULL");
                } else if (value instanceof String s) {
                    sb.append("'").append(s.replace("'", "\\'")).append("'");
                } else if (value instanceof Map || value instanceof List) {
                    String json = GSON_DATA.toJson(GSON_DATA.toJson(value));
                    json = "'" + json.substring(1, json.length() - 1).replace("'", "\\'") + "'";
                    sb.append(json);
                } else if (value instanceof java.util.Date d) {
                    sb.append("'").append(SDF.format(d)).append("'");
                } else if (value instanceof LocalDateTime ldt) {
                    sb.append("'").append(ldt.format(DATE_TIME_FORMATTER)).append("'");
                } else {
                    sb.append(value);
                }
                index++;
            }
            sb.append(")");
            recordIndex++;
        }
        return gzipBytes(sb.toString());
    }

    private static byte[] gzipBytes(String data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(baos)) {
            gzip.write(data.getBytes(CHARSET));
        }
        return baos.toByteArray();
    }

    private static void addToTar(TarArchiveOutputStream backupTar, String name, byte[] bytes) throws IOException {
        TarArchiveEntry archiveEntry = new TarArchiveEntry(name);
        archiveEntry.setSize(bytes.length);
        backupTar.putArchiveEntry(archiveEntry);
        backupTar.write(bytes);
        backupTar.flush();
        backupTar.closeArchiveEntry();
    }

    private static String getCreateTableSQL(ConnectionConfig config, String databaseName, String tableName) throws Exception {
        Connection conn = DatabaseService.getConnection(config, databaseName);
        if (config.getType() == ConnectType.MYSQL) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SHOW CREATE TABLE `" + tableName + "`")) {
                if (rs.next()) {
                    return rs.getString("Create Table");
                }
            }
        }
        return "";
    }

    private static String getCreateViewSQL(ConnectionConfig config, String databaseName, String viewName) throws Exception {
        Connection conn = DatabaseService.getConnection(config, databaseName);
        if (config.getType() == ConnectType.MYSQL) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SHOW CREATE VIEW `" + viewName + "`")) {
                if (rs.next()) {
                    return rs.getString("Create View");
                }
            }
        }
        return "";
    }

    private static String getCreateFunctionSQL(ConnectionConfig config, String databaseName, String funcName) throws Exception {
        Connection conn = DatabaseService.getConnection(config, databaseName);
        if (config.getType() == ConnectType.MYSQL) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SHOW CREATE FUNCTION `" + funcName + "`")) {
                if (rs.next()) {
                    return rs.getString("Create Function");
                }
            }
        }
        return "";
    }

    private static String getCreateEventSQL(ConnectionConfig config, String databaseName, String eventName) throws Exception {
        Connection conn = DatabaseService.getConnection(config, databaseName);
        if (config.getType() == ConnectType.MYSQL) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SHOW CREATE EVENT `" + eventName + "`")) {
                if (rs.next()) {
                    return rs.getString("Create Event");
                }
            }
        }
        return "";
    }

    private static String buildSelectSql(ConnectionConfig config, String databaseName, String tableName) {
        return switch (config.getType()) {
            case MYSQL -> "SELECT * FROM `" + databaseName + "`.`" + tableName + "`";
            case POSTGRESQL -> "SELECT * FROM \"" + databaseName + "\".\"" + tableName + "\"";
            case ORACLE -> "SELECT * FROM \"" + databaseName + "\".\"" + tableName + "\"";
            default -> "SELECT * FROM " + tableName;
        };
    }

    private static String buildCountSql(ConnectionConfig config, String databaseName, String tableName) {
        return switch (config.getType()) {
            case MYSQL -> "SELECT COUNT(*) FROM `" + databaseName + "`.`" + tableName + "`";
            case POSTGRESQL -> "SELECT COUNT(*) FROM \"" + databaseName + "\".\"" + tableName + "\"";
            case ORACLE -> "SELECT COUNT(*) FROM \"" + databaseName + "\".\"" + tableName + "\"";
            default -> "SELECT COUNT(*) FROM " + tableName;
        };
    }

    private static String buildPageSql(ConnectionConfig config, String databaseName, String tableName, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        return switch (config.getType()) {
            case MYSQL -> "SELECT * FROM `" + databaseName + "`.`" + tableName + "` LIMIT " + pageSize + " OFFSET " + offset;
            case POSTGRESQL -> "SELECT * FROM \"" + databaseName + "\".\"" + tableName + "\" LIMIT " + pageSize + " OFFSET " + offset;
            case ORACLE -> "SELECT * FROM (SELECT a.*, ROWNUM rn FROM \"" + databaseName + "\".\"" + tableName + "\" a WHERE ROWNUM <= " + (offset + pageSize) + ") WHERE rn > " + offset;
            default -> "SELECT * FROM " + tableName + " LIMIT " + pageSize + " OFFSET " + offset;
        };
    }

    private static String buildQuotedColumns(ConnectionConfig config, String[] columns) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < columns.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(switch (config.getType()) {
                case MYSQL -> "`" + columns[i] + "`";
                case POSTGRESQL, ORACLE -> "\"" + columns[i] + "\"";
                default -> columns[i];
            });
        }
        return sb.toString();
    }

    private static String getDatabaseType(ConnectionConfig config) {
        return switch (config.getType()) {
            case MYSQL -> "MYSQL";
            case POSTGRESQL -> "POSTGRESQL";
            case ORACLE -> "ORACLE";
            default -> "UNKNOWN";
        };
    }

    private static String sanitizeFileName(String name) {
        if (name == null || name.isEmpty()) return "unnamed";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_")
                   .replaceAll("\\s+", "_")
                   .replaceAll("_{2,}", "_")
                   .replaceAll("^_|_$", "");
    }

    // ==================== nb3 元数据模型 ====================

    public static class Nb3Meta {
        @SerializedName("MetaVersion")
        private String metaVersion;
        @SerializedName("DatabaseType")
        private String databaseType;
        @SerializedName("Catalog")
        private String catalog;
        @SerializedName("Schema")
        private String schema;
        @SerializedName("StartTime")
        private String startTime;
        @SerializedName("EndTime")
        private String endTime;
        @SerializedName("Encryption")
        private String encryption;
        @SerializedName("Comment")
        private String comment;
        @SerializedName("Objects")
        private List<Nb3Object> objects;

        public String getMetaVersion() { return metaVersion; }
        public void setMetaVersion(String metaVersion) { this.metaVersion = metaVersion; }
        public String getDatabaseType() { return databaseType; }
        public void setDatabaseType(String databaseType) { this.databaseType = databaseType; }
        public String getCatalog() { return catalog; }
        public void setCatalog(String catalog) { this.catalog = catalog; }
        public String getSchema() { return schema; }
        public void setSchema(String schema) { this.schema = schema; }
        public String getStartTime() { return startTime; }
        public void setStartTime(String startTime) { this.startTime = startTime; }
        public String getEndTime() { return endTime; }
        public void setEndTime(String endTime) { this.endTime = endTime; }
        public String getEncryption() { return encryption; }
        public void setEncryption(String encryption) { this.encryption = encryption; }
        public String getComment() { return comment; }
        public void setComment(String comment) { this.comment = comment; }
        public List<Nb3Object> getObjects() { return objects; }
        public void setObjects(List<Nb3Object> objects) { this.objects = objects; }
    }

    public static class Nb3Object {
        @SerializedName("UUID")
        private String uuid;
        @SerializedName("Type")
        private String type;
        @SerializedName("Name")
        private String name;
        @SerializedName("Rows")
        private String rows;
        @SerializedName("Metadata")
        private Nb3MetaData metadata;

        public String getUuid() { return uuid; }
        public void setUuid(String uuid) { this.uuid = uuid; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getRows() { return rows; }
        public void setRows(String rows) { this.rows = rows; }
        public Nb3MetaData getMetadata() { return metadata; }
        public void setMetadata(Nb3MetaData metadata) { this.metadata = metadata; }
    }

    public static class Nb3MetaData {
        @SerializedName("Filename")
        private String filename;
        @SerializedName("Checksum")
        private String checksum;

        public String getFilename() { return filename; }
        public void setFilename(String filename) { this.filename = filename; }
        public String getChecksum() { return checksum; }
        public void setChecksum(String checksum) { this.checksum = checksum; }
    }

    public static class Nb3TableMeta {
        @SerializedName("MetaVersion")
        private String metaVersion;
        @SerializedName("Name")
        private String name;
        @SerializedName("Type")
        private String type;
        @SerializedName("DDL")
        private String ddl;
        @SerializedName("SubDDL")
        private String[] subddl;
        @SerializedName("AutoIncrement")
        private String autoIncrement;
        @SerializedName("Fields")
        private String[] fields;
        @SerializedName("TriggerDDL")
        private String[] triggerDDL;
        @SerializedName("IndexDDL")
        private String[] indexDDL;
        @SerializedName("Data")
        private Nb3TableData[] data;

        public String getMetaVersion() { return metaVersion; }
        public void setMetaVersion(String metaVersion) { this.metaVersion = metaVersion; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getDdl() { return ddl; }
        public void setDdl(String ddl) { this.ddl = ddl; }
        public String[] getSubddl() { return subddl; }
        public void setSubddl(String[] subddl) { this.subddl = subddl; }
        public String getAutoIncrement() { return autoIncrement; }
        public void setAutoIncrement(String autoIncrement) { this.autoIncrement = autoIncrement; }
        public String[] getFields() { return fields; }
        public void setFields(String[] fields) { this.fields = fields; }
        public String[] getTriggerDDL() { return triggerDDL; }
        public void setTriggerDDL(String[] triggerDDL) { this.triggerDDL = triggerDDL; }
        public String[] getIndexDDL() { return indexDDL; }
        public void setIndexDDL(String[] indexDDL) { this.indexDDL = indexDDL; }
        public Nb3TableData[] getData() { return data; }
        public void setData(Nb3TableData[] data) { this.data = data; }
    }

    public static class Nb3TableData {
        @SerializedName("Filename")
        private String filename;
        @SerializedName("Checksum")
        private String checksum;

        public Nb3TableData(String filename, String checksum) {
            this.filename = filename;
            this.checksum = checksum;
        }

        public String getFilename() { return filename; }
        public void setFilename(String filename) { this.filename = filename; }
        public String getChecksum() { return checksum; }
        public void setChecksum(String checksum) { this.checksum = checksum; }
    }

    // ==================== 还原任务 ====================

    public static class RestoreTask extends javafx.concurrent.Task<Void> {
        private final ConnectionConfig config;
        private final String databaseName;
        private final String backupName;
        private final String path;
        private int totalObjects;
        private long recordCount = 0;
        private long startTime;

        private final javafx.beans.property.SimpleLongProperty recordCountProp = new javafx.beans.property.SimpleLongProperty();
        private final javafx.beans.property.SimpleStringProperty runningTime = new javafx.beans.property.SimpleStringProperty();

        public RestoreTask(ConnectionConfig config, String databaseName, String backupName, String path) {
            this.config = config;
            this.databaseName = databaseName;
            this.backupName = backupName;
            this.path = path == null ? "" : path;
        }

        public javafx.beans.property.SimpleLongProperty recordCountProperty() { return recordCountProp; }
        public javafx.beans.property.SimpleStringProperty runningTimeProperty() { return runningTime; }

        public void setTotalObjects(int total) { this.totalObjects = total; }

        @Override
        protected Void call() throws Exception {
            startTime = System.currentTimeMillis();
            updateMessage("开始还原备份 " + backupName);
            BackupService.restoreBackup(config, databaseName, backupName, path, this);
            long elapsed = System.currentTimeMillis() - startTime;
            Platform.runLater(() -> runningTime.set(String.format("%d.%d 秒", elapsed / 1000, (elapsed % 1000) / 100)));
            updateMessage("还原完成");
            return null;
        }

        public void incrementRecordCount(long count) {
            recordCount += count;
            Platform.runLater(() -> recordCountProp.set(recordCount));
        }

        public void log(String msg) {
            updateMessage(msg);
        }

        public void updateProgress(int done) {
            updateProgress(done, totalObjects);
        }
    }
}
