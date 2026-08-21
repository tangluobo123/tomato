package com.tangluobo.tomato.module.connect.service;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.gson.JsonParseException;
import com.tangluobo.tomato.module.connect.ConfigManager;
import com.tangluobo.tomato.module.connect.ConnectionConfig;
import com.tangluobo.tomato.module.connect.SshTunnel;
import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveBucketArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.errors.*;
import io.minio.messages.Bucket;
import io.minio.messages.Item;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * S3存储服务：基于MinIO SDK，支持AWS S3、MinIO等S3兼容存储
 */
public class S3Service {

    // SSH隧道缓存：configId + "_" + targetHost:targetPort -> SshTunnel
    private static final Map<String, SshTunnel> tunnelCache = new ConcurrentHashMap<>();

    /**
     * 创建MinIO客户端连接
     */
    public static MinioClient createClient(ConnectionConfig config) {
        // region 为空时回退到默认值（MinIO等S3兼容服务通常不需要真实region）
        String region = config.getRegion();
        if (region == null || region.trim().isEmpty()) {
            region = "us-east-1";
        }

        // 构造端点：优先使用自定义端点，否则按region构造AWS S3端点
        String originalEndpoint = config.getEndpoint();
        if (originalEndpoint == null || originalEndpoint.isEmpty()) {
            originalEndpoint = "https://s3." + region + ".amazonaws.com";
        } else if (!originalEndpoint.startsWith("http://") && !originalEndpoint.startsWith("https://")) {
            originalEndpoint = "http://" + originalEndpoint;
        }

        // 解析原始端点，用于判断 SSH 隧道场景下的 HTTPS 证书处理
        URI originalUri = URI.create(originalEndpoint);
        String originalScheme = originalUri.getScheme() != null ? originalUri.getScheme() : "http";

        String endpoint = originalEndpoint;
        boolean viaSshTunnel = false;

        // SSH隧道（引用方式：根据 sshTunnelHostId 查找SSH主机配置建立端口转发）
        if (config.isUseSshTunnel() && config.getSshTunnelHostId() != null) {
            try {
                endpoint = setupSshTunnel(config, originalEndpoint);
                viaSshTunnel = true;
            } catch (Exception e) {
                throw new RuntimeException("建立SSH隧道失败: " + e.getMessage(), e);
            }
        }

        MinioClient.Builder minioBuilder = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(config.getUsername(), config.getPassword())
                .region(region);

        MinioClient client = minioBuilder.build();

        // SSH隧道场景下：
        // - HTTP：无需替换，MinIO 直接以 localhost:隧道端口 作为端点，签名与 Host 头一致
        // - HTTPS：需替换 OkHttpClient 跳过主机名验证（证书域名与 localhost 不匹配）
        if (viaSshTunnel && "https".equalsIgnoreCase(originalScheme)) {
            replaceOkHttpClient(client);
        }

        return client;
    }

    /**
     * 通过反射替换 MinioClient 内部的 OkHttpClient（HTTPS SSH 隧道场景）。
     * 隧道端点为 localhost:端口，与服务器证书域名不匹配，需基于现有 OkHttpClient
     * 的 newBuilder() 创建副本并跳过主机名验证。全程使用反射，避免 tomato 模块
     * 直接引用 unnamed module 上的 OkHttp 类。
     *
     * 注意：不覆盖 Host 头。MinIO 在 S3Base.executeAsync() 中对请求签名（SigV4
     * 包含 Host 头），签名发生在请求进入 OkHttp 拦截器链之前；若在拦截器中改写
     * Host 头，服务器计算的签名会与请求中的 Authorization 不匹配。隧道端点
     * localhost:port 同时用于签名和实际 Host 头，二者一致即可通过校验，
     * SSH 隧道负责将 TCP 连接转发到真实目标。
     */
    private static void replaceOkHttpClient(MinioClient client) {
        try {
            ClassLoader cl = client.getClass().getClassLoader();

            // 1. 查找 MinioClient 中的 OkHttpClient 字段（位于 S3Base.httpClient）
            FieldRef ref = findOkHttpClientField(client);
            if (ref == null) {
                throw new RuntimeException("SSH隧道: MinioClient及其嵌套对象中未找到OkHttpClient");
            }
            Object existingClient = ref.field.get(ref.owner);

            // 2. 基于现有 OkHttpClient 创建 Builder（保留超时、Dispatcher 等配置）
            Class<?> okHttpClientClass = Class.forName("okhttp3.OkHttpClient", true, cl);
            Class<?> builderClass = Class.forName("okhttp3.OkHttpClient$Builder", true, cl);
            Method newBuilderMethod = okHttpClientClass.getMethod("newBuilder");
            Object builder = newBuilderMethod.invoke(existingClient);

            // 3. 跳过主机名验证（隧道端点 localhost:port 与证书域名不匹配）
            final SSLContext sslContext = SSLContext.getInstance("TLS");
            final X509TrustManager trustManager = new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            sslContext.init(null, new TrustManager[]{trustManager}, new java.security.SecureRandom());

            Method sslMethod = builderClass.getMethod("sslSocketFactory", SSLSocketFactory.class, X509TrustManager.class);
            sslMethod.invoke(builder, sslContext.getSocketFactory(), trustManager);

            Method hvMethod = builderClass.getMethod("hostnameVerifier", HostnameVerifier.class);
            hvMethod.invoke(builder, (HostnameVerifier) (hostname, session) -> true);

            // 4. 构建新的 OkHttpClient 并替换
            Method buildMethod = builderClass.getMethod("build");
            Object newOkHttpClient = buildMethod.invoke(builder);
            ref.field.set(ref.owner, newOkHttpClient);
        } catch (Exception e) {
            throw new RuntimeException("SSH隧道: 替换OkHttpClient失败: " + e.getMessage(), e);
        }
    }

    /** OkHttpClient 字段引用：持有字段所在对象和字段本身，用于读取原值/设置新值 */
    private static final class FieldRef {
        final Object owner;
        final Field field;
        FieldRef(Object owner, Field field) { this.owner = owner; this.field = field; }
    }

    /**
     * 递归查找对象中的 OkHttpClient 字段。
     * 遍历对象所属类及其所有父类的字段（MinioClient → MinioAsyncClient → S3Base，
     * httpClient 字段声明在 S3Base 上）。
     */
    private static FieldRef findOkHttpClientField(Object obj) {
        return findOkHttpClientField(obj, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
    }

    private static FieldRef findOkHttpClientField(Object obj, java.util.Set<Object> visited) {
        if (obj == null || visited.contains(obj)) return null;
        visited.add(obj);

        Class<?> clazz = obj.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field f : clazz.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object val = f.get(obj);
                    if (val == null) continue;

                    String valClassName = val.getClass().getName();
                    if (valClassName.startsWith("okhttp3.OkHttpClient")) {
                        return new FieldRef(obj, f);
                    }
                    // 递归进入 MinIO / OkHttp 嵌套对象继续查找
                    if (valClassName.startsWith("io.minio.") || valClassName.startsWith("okhttp3.")) {
                        FieldRef found = findOkHttpClientField(val, visited);
                        if (found != null) return found;
                    }
                } catch (Exception ignored) {
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    /**
     * 建立/复用 SSH 隧道，返回指向本地转发端口的 endpoint。
     * 隧道通过引用的 SSH 主机（sshTunnelHostId）建立，目标为 S3 endpoint 解析出的 host:port。
     */
    private static String setupSshTunnel(ConnectionConfig config, String originalEndpoint) throws Exception {
        URI uri = URI.create(originalEndpoint);
        String targetHost = uri.getHost();
        int targetPort = uri.getPort();
        if (targetPort < 0) {
            targetPort = "https".equals(uri.getScheme()) ? 443 : 80;
        }
        String scheme = uri.getScheme() != null ? uri.getScheme() : "http";

        String tunnelKey = config.getId() + "_" + targetHost + ":" + targetPort;
        SshTunnel tunnel = tunnelCache.get(tunnelKey);
        if (tunnel != null && tunnel.isActive()) {
            return scheme + "://localhost:" + tunnel.getForwardedLocalPort();
        }

        // 查找引用的 SSH 主机配置
        ConnectionConfig sshHost = findSshHostConfig(config.getSshTunnelHostId());
        if (sshHost == null) {
            throw new RuntimeException("找不到引用的SSH主机配置(ID: " + config.getSshTunnelHostId() + ")");
        }

        // 用 SSH 主机的认证信息建立隧道，目标为 S3 endpoint 的 host:port
        List<String> keyPaths = sshHost.isUseKey() ? sshHost.getPrivateKeyPaths() : null;
        String password = sshHost.isUsePassword() ? sshHost.getPassword() : null;
        if (!sshHost.isUsePassword() && sshHost.isUseKey() && sshHost.getPassword() != null) {
            password = sshHost.getPassword();
        }

        tunnel = new SshTunnel(
            sshHost.getHost(),
            sshHost.getPort(),
            sshHost.getUsername(),
            password,
            keyPaths,
            targetHost,
            targetPort
        );
        int localPort = tunnel.connect();
        tunnelCache.put(tunnelKey, tunnel);

        return scheme + "://localhost:" + localPort;
    }

    /**
     * 根据 sshTunnelHostId 查找引用的 SSH 主机配置
     */
    private static ConnectionConfig findSshHostConfig(String hostId) {
        if (hostId == null) return null;
        try {
            List<ConnectionConfig> all = ConfigManager.loadConnections();
            for (ConnectionConfig c : all) {
                if (hostId.equals(c.getId())) return c;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 关闭指定连接的所有 SSH 隧道（关闭S3标签页时调用）
     */
    public static void closeSshTunnel(String configId) {
        tunnelCache.entrySet().removeIf(entry -> {
            if (entry.getKey().startsWith(configId + "_")) {
                entry.getValue().disconnect();
                return true;
            }
            return false;
        });
    }

    /**
     * 获取Bucket列表
     */
    public static List<String> listBuckets(ConnectionConfig config) throws Exception {
        MinioClient client = createClient(config);
        List<String> bucketNames = new ArrayList<>();
        for (Bucket bucket : client.listBuckets()) {
            bucketNames.add(bucket.name());
        }
        return bucketNames;
    }

    /**
     * 获取Bucket中的对象列表
     */
    public static List<S3ObjectInfo> listObjects(ConnectionConfig config, String bucketName, String prefix) throws Exception {
        MinioClient client = createClient(config);
        try {
            ListObjectsArgs.Builder argsBuilder = ListObjectsArgs.builder()
                    .bucket(bucketName)
                    .recursive(false);
            if (prefix != null && !prefix.isEmpty()) {
                argsBuilder.prefix(prefix);
            }

            List<S3ObjectInfo> objects = new ArrayList<>();
            Iterable<Result<Item>> results = client.listObjects(argsBuilder.build());

            for (Result<Item> result : results) {
                try {
                    Item item = result.get();
                    S3ObjectInfo objInfo = new S3ObjectInfo();
                    objInfo.setKey(item.objectName());
                    // S3 没有"真正的目录"，目录是通过 key 中的 / 隐含的
                    // 以 / 结尾的对象（目录占位对象）也视为目录，否则空目录会被显示为文件
                    boolean isDir = item.isDir() || item.objectName().endsWith("/");
                    objInfo.setDirectory(isDir);
                    objInfo.setSize(isDir ? 0 : item.size());
                    if (item.lastModified() != null) {
                        objInfo.setLastModified(item.lastModified().toInstant());
                    }
                    objects.add(objInfo);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            return objects;
        } finally {
            // MinioClient 无需显式关闭
        }
    }

    /**
     * 获取对象输入流
     */
    public static InputStream getObjectStream(ConnectionConfig config, String bucketName, String key) throws Exception {
        MinioClient client = createClient(config);
        GetObjectResponse response = client.getObject(GetObjectArgs.builder()
                .bucket(bucketName)
                .object(key)
                .build());
        return response;
    }

    /**
     * 上传字符串内容到指定对象（覆盖已存在对象）
     * @param content 文本内容（UTF-8）
     */
    public static void putObject(ConnectionConfig config, String bucketName, String key, String content) throws Exception {
        MinioClient client = createClient(config);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
        client.putObject(PutObjectArgs.builder()
                .bucket(bucketName)
                .object(key)
                .stream(bis, bytes.length+0L, -1L)
                .contentType("text/markdown; charset=utf-8")
                .build());
    }

    /**
     * 上传文件流到指定对象（覆盖已存在对象）
     * @param stream 输入流
     * @param size 文件大小（字节），未知可传 -1
     * @param contentType MIME 类型，为空则使用 application/octet-stream
     */
    public static void uploadFile(ConnectionConfig config, String bucketName, String key,
                                  InputStream stream, long size, String contentType) throws Exception {
        MinioClient client = createClient(config);
        if (contentType == null || contentType.isEmpty()) {
            contentType = "application/octet-stream";
        }
        long partSize = size > 0 ? size : -1L;
        client.putObject(PutObjectArgs.builder()
                .bucket(bucketName)
                .object(key)
                .stream(stream, partSize, -1L)
                .contentType(contentType)
                .build());
    }

    /**
     * 用 OkHttp 原生 API 实现文件上传（绕开 MinIO 在 Java 24 下的反射访问限制）。
     * @param config S3连接配置
     * @param bucketName 目标Bucket
     * @param key 目标Key
     * @param file 本地临时文件
     * @param fileSize 文件大小
     * @param contentType 内容类型
     * @param progressCallback 进度回调，可为null
     */
    public static void uploadFileDirect(ConnectionConfig config, String bucketName, String key,
                                         java.io.File file, long fileSize, String contentType,
                                         ProgressCallback progressCallback) throws Exception {
        if (contentType == null || contentType.isEmpty()) {
            contentType = "application/octet-stream";
        }
        putObjectDirect(config, bucketName, key, file, fileSize, progressCallback);
    }

    /**
     * 创建目录（S3 中通过创建以 / 结尾的空对象实现）
     * @param prefix 目录前缀，无需以 / 结尾（方法内部会补全）
     */
    public static void createDirectory(ConnectionConfig config, String bucketName, String prefix) throws Exception {
        MinioClient client = createClient(config);
        if (prefix == null) prefix = "";
        if (!prefix.endsWith("/")) {
            prefix = prefix + "/";
        }
        ByteArrayInputStream empty = new ByteArrayInputStream(new byte[0]);
        client.putObject(PutObjectArgs.builder()
                .bucket(bucketName)
                .object(prefix)
                .stream(empty, 0L, -1L)
                .contentType("application/x-directory")
                .build());
    }

    /**
     * 删除对象
     */
    public static void deleteObject(ConnectionConfig config, String bucketName, String key) throws Exception {
        MinioClient client = createClient(config);
        client.removeObject(RemoveObjectArgs.builder()
                .bucket(bucketName)
                .object(key)
                .build());
    }

    /**
     * 服务端复制对象（用于 S3 重命名/移动）。同 bucket 内复制。
     */
    public static void copyObject(ConnectionConfig config, String bucketName, String sourceKey, String destKey) throws Exception {
        MinioClient client = createClient(config);
        client.copyObject(CopyObjectArgs.builder()
                .bucket(bucketName)
                .object(destKey)
                .source(CopySource.builder().bucket(bucketName).object(sourceKey).build())
                .build());
    }

    /**
     * 重命名对象（同 bucket 内：复制到新 key 后删除原对象）。仅适用于文件。
     */
    public static void renameObject(ConnectionConfig config, String bucketName, String sourceKey, String destKey) throws Exception {
        copyObject(config, bucketName, sourceKey, destKey);
        deleteObject(config, bucketName, sourceKey);
    }

    /**
     * 重命名目录（递归复制 prefix 下所有对象到新 prefix，再删除原对象及原目录占位）。
     * @param sourcePrefix 原 prefix（含末尾 /）
     * @param destPrefix 目标 prefix（含末尾 /）
     */
    public static void renameDirectory(ConnectionConfig config, String bucketName, String sourcePrefix, String destPrefix) throws Exception {
        List<S3ObjectInfo> objects = listObjectsRecursive(config, bucketName, sourcePrefix);
        for (S3ObjectInfo obj : objects) {
            String oldKey = obj.getKey();
            String relPath = oldKey.substring(sourcePrefix.length());
            String newKey = destPrefix + relPath;
            copyObject(config, bucketName, oldKey, newKey);
            deleteObject(config, bucketName, oldKey);
        }
        // 删除原目录占位对象（0 字节，以 / 结尾），不存在则无影响
        deleteObject(config, bucketName, sourcePrefix);
        // 创建新目录占位
        createDirectory(config, bucketName, destPrefix);
    }

    /**
     * 跨S3实例复制文件（临时文件两阶段：MinIO下载 + OkHttp上传）
     * MinIO 的 getObject 能用（GET请求不触发Headers反射），
     * 但 putObject 在 Java 24 下触发 okhttp3.Headers 反射访问被拦截，
     * 因此上传阶段改用 OkHttp 原生 API + S3 SigV4 签名实现。
     */
    public static void copyAcrossS3(ConnectionConfig sourceConfig, String sourceBucket, String sourceKey,
                                     ConnectionConfig destConfig, String destBucket, String destKey,
                                     ProgressCallback progressCallback) throws Exception {
        long startTime = System.currentTimeMillis();
        System.out.println("[S3-Copy] 开始: " + sourceBucket + "/" + sourceKey + " -> " + destBucket + "/" + destKey);

        // ===== 第1阶段：用 MinIO 下载到本地临时文件（getObject 是 GET 请求，能正常工作）=====
        long totalSize = -1;
        java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("s3copy-", ".part");
        try {
            MinioClient sourceClient = createClient(sourceConfig);

            try {
                io.minio.StatObjectResponse stat = sourceClient.statObject(io.minio.StatObjectArgs.builder()
                        .bucket(sourceBucket).object(sourceKey).build());
                totalSize = stat.size();
            } catch (Exception e) {
                System.out.println("[S3-Copy] statObject失败(忽略): " + e.getMessage());
            }

            GetObjectResponse response = sourceClient.getObject(GetObjectArgs.builder()
                    .bucket(sourceBucket)
                    .object(sourceKey)
                    .build());

            // 注意：所有读/关操作必须经由 InputStream 接口调用 ——
            // GetObjectResponse 继承自 okhttp3.Response，named module tomato 直接调用
            // 其声明的方法（headers()/close()等）会抛 IllegalAccessError（cannot read unnamed module）。
            // 文件大小已由上方 statObject 获取（statObject.size() 是 MinIO 声明的方法，安全）。
            InputStream responseStream = response;

            if (progressCallback != null) progressCallback.onPhase("下载");
            try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int len;
                long transferred = 0;
                long lastReportTime = 0;
                while ((len = responseStream.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                    transferred += len;
                    long now = System.currentTimeMillis();
                    if (progressCallback != null && now - lastReportTime > 200) {
                        lastReportTime = now;
                        progressCallback.onProgress(transferred, totalSize);
                    }
                }
            } finally {
                try { responseStream.close(); } catch (Exception e) { /* ignore */ }
            }
            System.out.println("[S3-Copy] 下载完成: " + java.nio.file.Files.size(tempFile)
                    + " bytes, 耗时 " + (System.currentTimeMillis() - startTime) + "ms");

            // ===== 第2阶段：用 OkHttp 原生 API 上传（绕开 MinIO 的反射访问问题）=====
            long fileSize = java.nio.file.Files.size(tempFile);
            if (progressCallback != null) {
                progressCallback.onPhase("上传");
                progressCallback.onProgress(0, fileSize);
            }

            putObjectDirect(destConfig, destBucket, destKey, tempFile.toFile(), fileSize, progressCallback);
            System.out.println("[S3-Copy] OkHttp上传完成, 总耗时: " + (System.currentTimeMillis() - startTime) + "ms");
        } finally {
            try { java.nio.file.Files.deleteIfExists(tempFile); } catch (Exception e) { /* ignore */ }
        }

        if (progressCallback != null) {
            progressCallback.onComplete();
        }
    }

    /**
     * 用 java.net.http.HttpClient 实现 S3 PUT 上传（绕开 MinIO 在 Java 24 下的反射访问限制）。
     * HttpClient 是 JDK 内置模块，无模块访问限制。
     * 注意：JDK HttpClient 禁止手动设置 Host/Content-Length（受限头），由其自动生成；
     * ofFile 的 BodyPublisher 带已知长度，HttpClient 自动设置 Content-Length（非 chunked）。
     */
    private static void putObjectDirect(ConnectionConfig config, String bucket, String key,
                                         java.io.File file, long fileSize,
                                         ProgressCallback progressCallback) throws Exception {
        // ===== 端点解析（与 createClient 相同逻辑，含 SSH 隧道；tunnelCache 保证幂等）=====
        String region = config.getRegion();
        if (region == null || region.trim().isEmpty()) {
            region = "us-east-1";
        }
        String endpoint = config.getEndpoint();
        if (endpoint == null || endpoint.isEmpty()) {
            endpoint = "https://s3." + region + ".amazonaws.com";
        } else if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            endpoint = "http://" + endpoint;
        }
        if (config.isUseSshTunnel() && config.getSshTunnelHostId() != null) {
            endpoint = setupSshTunnel(config, endpoint);
        }

        URI uri = URI.create(endpoint);
        boolean pathStyle = !endpoint.contains("amazonaws.com");
        int port = uri.getPort(); // -1 表示未指定端口

        // Host 头值（JDK HttpClient 自动设置，规则：非默认端口才带端口）
        String hostNoPort = pathStyle ? uri.getHost() : bucket + "." + uri.getHost();
        String hostHeader = port > 0 ? hostNoPort + ":" + port : hostNoPort;

        // URL 与规范路径（AWS 规范编码）
        String encodedKey = S3AuthHelper.uriEncodePath(key);
        String url;
        String canonicalUri;
        if (pathStyle) {
            url = uri.getScheme() + "://" + hostNoPort + (port > 0 ? ":" + port : "")
                    + "/" + bucket + "/" + encodedKey;
            canonicalUri = S3AuthHelper.uriEncodePath("/" + bucket + "/" + key);
        } else {
            url = uri.getScheme() + "://" + hostNoPort + (port > 0 ? ":" + port : "")
                    + "/" + encodedKey;
            canonicalUri = encodedKey.startsWith("/") ? encodedKey : "/" + encodedKey;
        }

        // amzDate 只生成一次：签名与请求头必须一致，否则 403
        String amzDate = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now());
        String contentType = "application/octet-stream";
        String authorization = S3AuthHelper.buildAuthorization(config, "PUT", canonicalUri, "",
                amzDate, hostHeader, contentType);

        // ofFile 的 publisher 带已知 contentLength，HttpClient 会设置 Content-Length 头
        java.net.http.HttpRequest.BodyPublisher filePublisher =
                java.net.http.HttpRequest.BodyPublishers.ofFile(file.toPath());

        // 包装 publisher 统计已发送字节，实现上传进度回调
        java.net.http.HttpRequest.BodyPublisher countingPublisher =
                new java.net.http.HttpRequest.BodyPublisher() {
                    @Override
                    public long contentLength() {
                        return filePublisher.contentLength();
                    }

                    @Override
                    public void subscribe(java.util.concurrent.Flow.Subscriber<? super java.nio.ByteBuffer> subscriber) {
                        filePublisher.subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
                            private long uploaded = 0;
                            private long lastReportTime = 0;

                            @Override
                            public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
                                subscriber.onSubscribe(s);
                            }

                            @Override
                            public void onNext(java.nio.ByteBuffer buffer) {
                                uploaded += buffer.remaining();
                                long now = System.currentTimeMillis();
                                if (progressCallback != null && now - lastReportTime > 200) {
                                    lastReportTime = now;
                                    progressCallback.onProgress(uploaded, fileSize);
                                }
                                subscriber.onNext(buffer);
                            }

                            @Override
                            public void onError(Throwable t) {
                                subscriber.onError(t);
                            }

                            @Override
                            public void onComplete() {
                                if (progressCallback != null) {
                                    progressCallback.onProgress(fileSize, fileSize);
                                }
                                subscriber.onComplete();
                            }
                        });
                    }
                };

        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(15))
                .build();

        // 禁止设置 Host/Content-Length —— JDK HttpClient 受限头，会抛 IllegalArgumentException
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", contentType)
                .header("x-amz-content-sha256", S3AuthHelper.UNSIGNED_PAYLOAD)
                .header("x-amz-date", amzDate)
                .header("Authorization", authorization)
                .PUT(countingPublisher)
                .timeout(java.time.Duration.ofSeconds(1800))
                .build();

        java.net.http.HttpResponse<String> response = httpClient.send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("S3上传失败: HTTP " + response.statusCode()
                    + " - " + response.body());
        }
        System.out.println("[S3-Copy] HttpClient直传成功: HTTP " + response.statusCode());
    }

    /**
     * 服务端复制对象（同连接，支持跨Bucket）。目标Bucket与源Bucket可不同。
     */
    public static void copyObjectAcrossBucket(ConnectionConfig config, String sourceBucket, String sourceKey,
                                               String destBucket, String destKey) throws Exception {
        MinioClient client = createClient(config);
        client.copyObject(CopyObjectArgs.builder()
                .bucket(destBucket)
                .object(destKey)
                .source(CopySource.builder().bucket(sourceBucket).object(sourceKey).build())
                .build());
    }

    /**
     * 进度回调接口
     */
    public interface ProgressCallback {
        /** 传输进度回调（transferred已传输字节，totalSize总大小，-1表示未知） */
        default void onProgress(long transferred, long totalSize) {}
        /** 阶段变化回调（"下载"/"上传"） */
        default void onPhase(String phase) {}
        default void onComplete() {}
    }

    /**
     * 递归列出 prefix 下所有对象（用于目录删除/重命名/移动）。
     */
    public static List<S3ObjectInfo> listObjectsRecursive(ConnectionConfig config, String bucketName, String prefix) throws Exception {
        MinioClient client = createClient(config);
        ListObjectsArgs.Builder argsBuilder = ListObjectsArgs.builder()
                .bucket(bucketName)
                .recursive(true);
        if (prefix != null && !prefix.isEmpty()) {
            argsBuilder.prefix(prefix);
        }
        List<S3ObjectInfo> objects = new ArrayList<>();
        Iterable<Result<Item>> results = client.listObjects(argsBuilder.build());
        for (Result<Item> result : results) {
            try {
                Item item = result.get();
                S3ObjectInfo objInfo = new S3ObjectInfo();
                objInfo.setKey(item.objectName());
                objInfo.setDirectory(item.isDir());
                objInfo.setSize(item.isDir() ? 0 : item.size());
                if (item.lastModified() != null) {
                    objInfo.setLastModified(item.lastModified().toInstant());
                }
                objects.add(objInfo);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return objects;
    }

    /**
     * 创建Bucket
     */
    public static void createBucket(ConnectionConfig config, String bucketName) throws Exception {
        MinioClient client = createClient(config);
        client.makeBucket(MakeBucketArgs.builder()
                .bucket(bucketName)
                .build());
    }

    /**
     * 删除Bucket（Bucket必须为空）
     */
    public static void deleteBucket(ConnectionConfig config, String bucketName) throws Exception {
        MinioClient client = createClient(config);
        client.removeBucket(RemoveBucketArgs.builder()
                .bucket(bucketName)
                .build());
    }

    /**
     * S3对象信息
     */
    public static class S3ObjectInfo {
        private String key;
        private boolean isDirectory;
        private long size;
        private Instant lastModified;

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }

        public boolean isDirectory() { return isDirectory; }
        public void setDirectory(boolean directory) { this.isDirectory = directory; }

        public long getSize() { return size; }
        public void setSize(long size) { this.size = size; }

        public Instant getLastModified() { return lastModified; }
        public void setLastModified(Instant lastModified) { this.lastModified = lastModified; }

        /**
         * 获取显示名称（去掉前缀路径的最后一部分）
         */
        public String getDisplayName() {
            if (key == null) return "";
            String displayKey = key;
            // 去掉末尾的/
            if (displayKey.endsWith("/")) {
                displayKey = displayKey.substring(0, displayKey.length() - 1);
            }
            // 取最后一个/后面的部分
            int lastSlash = displayKey.lastIndexOf('/');
            if (lastSlash >= 0) {
                return displayKey.substring(lastSlash + 1);
            }
            return displayKey;
        }

        /**
         * 获取子路径前缀（用于进入子目录时）
         */
        public String getPrefix() {
            return key;
        }

        /**
         * 格式化文件大小
         */
        public String getFormattedSize() {
            if (isDirectory) return "";
            if (size < 1024) return size + " B";
            if (size < 1024 * 1024) return (size / 1024) + " KB";
            if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024));
            return String.format("%.1f GB", size / (1024.0 * 1024 * 1024));
        }
    }
}
