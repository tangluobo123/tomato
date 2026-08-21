package com.tangluobo.tomato.module.connect.service;

import com.tangluobo.tomato.module.connect.ConnectionConfig;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * S3 SigV4 签名辅助类。
 * 配合 java.net.http.HttpClient 实现 S3 上传，绕开 MinIO SDK 在 Java 24 模块系统下
 * 无法访问 okhttp3（unnamed module）的限制。
 *
 * 签名头固定为: content-type;host;x-amz-content-sha256;x-amz-date
 * 载荷哈希固定为: UNSIGNED-PAYLOAD
 */
public class S3AuthHelper {

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final String AWS4_REQUEST = "aws4_request";
    private static final String AWS4 = "AWS4";
    public static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";

    /**
     * 生成 S3 SigV4 签名的 Authorization 头部值
     *
     * @param config           连接配置（username=AccessKey, password=SecretKey）
     * @param method           HTTP 方法（PUT/GET/DELETE）
     * @param encodedCanonicalUri 已按 AWS 规范编码的 URI 路径（见 {@link #uriEncodePath}）
     * @param canonicalQuery   规范化查询串（无参数传 ""）
     * @param amzDate          x-amz-date 值（yyyyMMdd'T'HHmmss'Z'，必须与请求头完全一致！）
     * @param hostHeader       Host 头的值（host 或 host:port，隧道场景是 localhost:port）
     * @param contentType      Content-Type 值
     */
    public static String buildAuthorization(ConnectionConfig config, String method,
                                            String encodedCanonicalUri, String canonicalQuery,
                                            String amzDate, String hostHeader, String contentType) throws Exception {
        String region = config.getRegion() != null && !config.getRegion().trim().isEmpty()
                ? config.getRegion().trim() : "us-east-1";
        String dateStamp = amzDate.substring(0, 8);

        // 规范化头部（按名称排序）
        String canonicalHeaders =
                "content-type:" + contentType + "\n" +
                "host:" + hostHeader + "\n" +
                "x-amz-content-sha256:" + UNSIGNED_PAYLOAD + "\n" +
                "x-amz-date:" + amzDate + "\n";
        String signedHeaders = "content-type;host;x-amz-content-sha256;x-amz-date";

        // 构造规范请求
        String canonicalRequest = method + '\n'
                + encodedCanonicalUri + '\n'
                + canonicalQuery + '\n'
                + canonicalHeaders + '\n'
                + signedHeaders + '\n'
                + UNSIGNED_PAYLOAD;

        // 构造待签字符串
        String credentialScope = dateStamp + '/' + region + "/s3/" + AWS4_REQUEST;
        String stringToSign = AWS4 + "-HMAC-SHA256" + '\n'
                + amzDate + '\n'
                + credentialScope + '\n'
                + sha256Hex(canonicalRequest);

        // 计算签名
        byte[] signingKey = getSignatureKey(config.getPassword(), dateStamp, region, "s3");
        String signature = hmacSha256Hex(signingKey, stringToSign);

        return AWS4 + "-HMAC-SHA256"
                + " Credential=" + config.getUsername() + '/' + credentialScope
                + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + signature;
    }

    /**
     * AWS SigV4 规范的 URI 路径编码：
     * 保留 '/' 分隔符，其余字符除 unreserved（A-Za-z0-9-._~）外全部百分号编码。
     */
    public static String uriEncodePath(String path) {
        if (path == null || path.isEmpty()) return "/";
        StringBuilder sb = new StringBuilder();
        byte[] bytes = path.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            char c = (char) (b & 0xFF);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~' || c == '/') {
                sb.append(c);
            } else {
                sb.append('%').append(String.format("%02X", b & 0xFF));
            }
        }
        return sb.toString();
    }

    private static byte[] getSignatureKey(String key, String dateStamp, String region, String service) throws Exception {
        byte[] kDate = hmacSha256(("AWS4" + key).getBytes(StandardCharsets.UTF_8), dateStamp);
        byte[] kRegion = hmacSha256(kDate, region);
        byte[] kService = hmacSha256(kRegion, service);
        return hmacSha256(kService, AWS4_REQUEST);
    }

    private static byte[] hmacSha256(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance(HMAC_SHA256);
        mac.init(new SecretKeySpec(key, HMAC_SHA256));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String hmacSha256Hex(byte[] key, String data) throws Exception {
        return toHex(hmacSha256(key, data));
    }

    private static String sha256Hex(String data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return toHex(md.digest(data.getBytes(StandardCharsets.UTF_8)));
    }

    private static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
