package com.tangluobo.tomato.utils;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 最小化的 SQLite 文件读取器（只读）。
 * 不依赖任何外部库，直接解析 SQLite 文件格式。
 * 仅支持读取 ItemTable 中的 key-value 数据。
 */
public class SqliteReader implements AutoCloseable {

    private final RandomAccessFile file;
    private final int pageSize;
    private final int reservedSpace;

    public SqliteReader(Path dbPath) throws IOException {
        this.file = new RandomAccessFile(dbPath.toFile(), "r");
        byte[] header = new byte[100];
        file.readFully(header);
        int ps = ((header[16] & 0xFF) << 8) | (header[17] & 0xFF);
        this.pageSize = (ps == 1) ? 65536 : ps;
        this.reservedSpace = header[20] & 0xFF;
    }

    /**
     * 查询 ItemTable 中指定 key 的 value（作为 UTF-8 字符串）。
     */
    public String getItemTableValue(String key) throws IOException {
        int rootPage = findItemTableRootPage();
        if (rootPage <= 0) return null;
        byte[] keyBytes = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] valueBytes = searchBtree(rootPage, keyBytes);
        if (valueBytes == null) return null;
        return new String(valueBytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 获取 ItemTable 中所有 key-value 对。
     */
    public List<String[]> getAllItemTableEntries() throws IOException {
        int rootPage = findItemTableRootPage();
        if (rootPage <= 0) return List.of();
        List<String[]> results = new ArrayList<>();
        collectAllEntries(rootPage, results);
        return results;
    }

    // ==================== sqlite_master 查找 ====================

    private int findItemTableRootPage() throws IOException {
        List<CellData> cells = new ArrayList<>();
        readPageCells(1, 100, cells);
        for (CellData cell : cells) {
            Object[] values = parseRecord(cell.payload);
            // sqlite_master: type(0), name(1), tbl_name(2), rootpage(3), sql(4)
            if (values.length >= 4 && values[0] instanceof byte[] && values[1] instanceof byte[]) {
                String type = new String((byte[]) values[0], java.nio.charset.StandardCharsets.UTF_8);
                String name = new String((byte[]) values[1], java.nio.charset.StandardCharsets.UTF_8);
                if ("table".equals(type) && "ItemTable".equals(name)) {
                    if (values[3] instanceof Long) {
                        return ((Long) values[3]).intValue();
                    }
                }
            }
        }
        return -1;
    }

    // ==================== B-tree 遍历 ====================

    private byte[] searchBtree(int pageNum, byte[] keyBytes) throws IOException {
        byte[] pageData = readPage(pageNum);
        int pageOffset = (pageNum == 1) ? 100 : 0;
        int pageType = pageData[pageOffset] & 0xFF;

        if (pageType == 0x0D) {
            // Leaf table page
            return searchLeafPage(pageData, pageOffset, keyBytes);
        } else if (pageType == 0x05) {
            // Interior table page - find the child to descend into
            return searchInteriorPage(pageData, pageOffset, keyBytes);
        }
        return null;
    }

    private byte[] searchInteriorPage(byte[] pageData, int pageOffset, byte[] keyBytes) throws IOException {
        int numCells = readU16(pageData, pageOffset + 3);
        // Right-most pointer
        int rightPtr = readU32(pageData, pageOffset + 8);

        // Cell pointer array starts at pageOffset + 12
        int ptrArrayStart = pageOffset + 12;

        for (int i = 0; i < numCells; i++) {
            int cellOffset = readU16(pageData, ptrArrayStart + i * 2);
            // Interior table cell: 4-byte child page number, varint rowid
            int childPage = readU32(pageData, cellOffset);
            long rowid = readVarint(pageData, cellOffset + 4)[0];

            // Compare key with rowid (ItemTable key is TEXT, rowid is integer)
            // For ItemTable, keys are stored as rowid integers (auto-increment)
            // So we need a different approach - just search all leaf pages
            // Actually, for our use case, let's just collect all entries and search
            byte[] result = searchBtree(childPage, keyBytes);
            if (result != null) return result;
        }
        // Search right-most child
        if (rightPtr > 0) {
            return searchBtree(rightPtr, keyBytes);
        }
        return null;
    }

    private byte[] searchLeafPage(byte[] pageData, int pageOffset, byte[] keyBytes) throws IOException {
        int numCells = readU16(pageData, pageOffset + 3);
        int ptrArrayStart = pageOffset + 8;

        for (int i = 0; i < numCells; i++) {
            int cellOffset = readU16(pageData, ptrArrayStart + i * 2);
            // Leaf table cell: varint payload_len, varint rowid, payload, [overflow]
            long[] varintResult = readVarint(pageData, cellOffset);
            long payloadLen = varintResult[0];
            int pos = (int) (cellOffset + varintResult[1]);
            long rowid = readVarint(pageData, pos)[0];
            pos += readVarint(pageData, pos)[1];

            // Read payload (with overflow handling)
            byte[] payload = readPayload(pageData, pos, (int) payloadLen);

            // Parse record: first column is key, second is value
            Object[] values = parseRecord(payload);
            if (values.length >= 2 && values[0] instanceof byte[]) {
                byte[] rowKey = (byte[]) values[0];
                if (java.util.Arrays.equals(rowKey, keyBytes)) {
                    if (values[1] instanceof byte[]) {
                        return (byte[]) values[1];
                    }
                }
            }
        }
        return null;
    }

    private void collectAllEntries(int pageNum, List<String[]> results) throws IOException {
        byte[] pageData = readPage(pageNum);
        int pageOffset = (pageNum == 1) ? 100 : 0;
        int pageType = pageData[pageOffset] & 0xFF;

        if (pageType == 0x0D) {
            // Leaf table page
            int numCells = readU16(pageData, pageOffset + 3);
            int ptrArrayStart = pageOffset + 8;
            for (int i = 0; i < numCells; i++) {
                int cellOffset = readU16(pageData, ptrArrayStart + i * 2);
                long[] vr = readVarint(pageData, cellOffset);
                long payloadLen = vr[0];
                int pos = (int) (cellOffset + vr[1]);
                pos += readVarint(pageData, pos)[1]; // skip rowid
                byte[] payload = readPayload(pageData, pos, (int) payloadLen);
                Object[] values = parseRecord(payload);
                if (values.length >= 2 && values[0] instanceof byte[] && values[1] instanceof byte[]) {
                    String k = new String((byte[]) values[0], java.nio.charset.StandardCharsets.UTF_8);
                    String v = new String((byte[]) values[1], java.nio.charset.StandardCharsets.UTF_8);
                    results.add(new String[]{k, v});
                }
            }
        } else if (pageType == 0x05) {
            // Interior table page
            int numCells = readU16(pageData, pageOffset + 3);
            int rightPtr = readU32(pageData, pageOffset + 8);
            int ptrArrayStart = pageOffset + 12;
            for (int i = 0; i < numCells; i++) {
                int cellOffset = readU16(pageData, ptrArrayStart + i * 2);
                int childPage = readU32(pageData, cellOffset);
                collectAllEntries(childPage, results);
            }
            if (rightPtr > 0) {
                collectAllEntries(rightPtr, results);
            }
        }
    }

    // ==================== Payload / Overflow ====================

    private byte[] readPayload(byte[] pageData, int offset, int payloadLen) throws IOException {
        int U = pageSize - reservedSpace;
        int X = U - 35; // max local for leaf table
        if (payloadLen <= X) {
            // All local
            byte[] result = new byte[payloadLen];
            System.arraycopy(pageData, offset, result, 0, payloadLen);
            return result;
        }
        // Has overflow
        int M = ((U - 12) * 32 / 255) - 23;
        int K = M + ((payloadLen - M) % (U - 4));
        int localLen = (K <= X) ? K : M;

        byte[] result = new byte[payloadLen];
        System.arraycopy(pageData, offset, result, 0, localLen);

        // Read overflow page number (4 bytes after local data)
        int overflowPage = readU32(pageData, offset + localLen);
        int readSoFar = localLen;

        while (overflowPage > 0 && readSoFar < payloadLen) {
            byte[] overflowData = readPage(overflowPage);
            int nextOverflow = readU32(overflowData, 0);
            int chunkSize = Math.min(payloadLen - readSoFar, U - 4);
            System.arraycopy(overflowData, 4, result, readSoFar, chunkSize);
            readSoFar += chunkSize;
            overflowPage = nextOverflow;
        }
        return result;
    }

    // ==================== Record Parsing ====================

    private Object[] parseRecord(byte[] payload) {
        long[] headerResult = readVarint(payload, 0);
        int headerLen = (int) headerResult[0];
        int pos = (int) headerResult[1];

        List<Integer> serialTypes = new ArrayList<>();
        while (pos < headerLen) {
            long[] vr = readVarint(payload, pos);
            serialTypes.add((int) vr[0]);
            pos += vr[1];
        }

        Object[] values = new Object[serialTypes.size()];
        int dataPos = headerLen;
        for (int i = 0; i < serialTypes.size(); i++) {
            int st = serialTypes.get(i);
            int len = getSerialTypeSize(st);
            if (st == 0) {
                values[i] = null;
            } else if (st == 8) {
                values[i] = 0L;
            } else if (st == 9) {
                values[i] = 1L;
            } else if (st >= 13 && st % 2 == 1) {
                // TEXT
                byte[] text = new byte[len];
                System.arraycopy(payload, dataPos, text, 0, len);
                values[i] = text;
            } else if (st >= 12 && st % 2 == 0) {
                // BLOB
                byte[] blob = new byte[len];
                System.arraycopy(payload, dataPos, blob, 0, len);
                values[i] = blob;
            } else if (st >= 1 && st <= 6) {
                // Integer
                values[i] = readInteger(payload, dataPos, len);
            } else if (st == 7) {
                // Float
                values[i] = Double.longBitsToDouble(readU64(payload, dataPos));
            }
            dataPos += len;
        }
        return values;
    }

    private int getSerialTypeSize(int st) {
        return switch (st) {
            case 0, 8, 9 -> 0;
            case 1 -> 1;
            case 2 -> 2;
            case 3 -> 3;
            case 4 -> 4;
            case 5 -> 6;
            case 6, 7 -> 8;
            default -> (st >= 12) ? (st - 12) / 2 : 0;
        };
    }

    // ==================== Low-level reads ====================

    private byte[] readPage(int pageNum) throws IOException {
        long offset = (long) (pageNum - 1) * pageSize;
        byte[] data = new byte[pageSize];
        file.seek(offset);
        file.readFully(data);
        return data;
    }

    private void readPageCells(int pageNum, int pageHeaderOffset, List<CellData> cells) throws IOException {
        byte[] pageData = readPage(pageNum);
        int pageType = pageData[pageHeaderOffset] & 0xFF;

        if (pageType == 0x0D) {
            int numCells = readU16(pageData, pageHeaderOffset + 3);
            int ptrArrayStart = pageHeaderOffset + 8;
            for (int i = 0; i < numCells; i++) {
                int cellOffset = readU16(pageData, ptrArrayStart + i * 2);
                long[] vr = readVarint(pageData, cellOffset);
                long payloadLen = vr[0];
                int pos = (int) (cellOffset + vr[1]);
                pos += readVarint(pageData, pos)[1]; // skip rowid
                byte[] payload = readPayload(pageData, pos, (int) payloadLen);
                cells.add(new CellData(payload));
            }
        } else if (pageType == 0x05) {
            int numCells = readU16(pageData, pageHeaderOffset + 3);
            int rightPtr = readU32(pageData, pageHeaderOffset + 8);
            int ptrArrayStart = pageHeaderOffset + 12;
            for (int i = 0; i < numCells; i++) {
                int cellOffset = readU16(pageData, ptrArrayStart + i * 2);
                int childPage = readU32(pageData, cellOffset);
                readPageCells(childPage, 0, cells);
            }
            if (rightPtr > 0) {
                readPageCells(rightPtr, 0, cells);
            }
        }
    }

    // Varint: 1-9 bytes, big-endian, high bit = continuation
    private static long[] readVarint(byte[] data, int offset) {
        long result = 0;
        int i;
        for (i = 0; i < 8; i++) {
            byte b = data[offset + i];
            if ((b & 0x80) == 0) {
                result = (result << 7) | (b & 0x7F);
                return new long[]{result, i + 1};
            }
            result = (result << 7) | (b & 0x7F);
        }
        // 9th byte uses all 8 bits
        result = (result << 8) | (data[offset + 8] & 0xFF);
        return new long[]{result, 9};
    }

    private static int readU16(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static int readU32(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
    }

    private static long readU64(byte[] data, int offset) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (data[offset + i] & 0xFF);
        }
        return v;
    }

    private static long readInteger(byte[] data, int offset, int len) {
        long v = 0;
        for (int i = 0; i < len; i++) {
            v = (v << 8) | (data[offset + i] & 0xFF);
        }
        // Sign extend
        if (len < 8 && (data[offset] & 0x80) != 0) {
            v |= (-1L << (len * 8));
        }
        return v;
    }

    @Override
    public void close() throws IOException {
        if (file != null) file.close();
    }

    private record CellData(byte[] payload) {}
}
