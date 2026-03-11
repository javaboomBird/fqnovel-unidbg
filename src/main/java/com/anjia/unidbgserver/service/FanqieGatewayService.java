package com.anjia.unidbgserver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

/**
 * Fanqie Novel Gateway 下载服务
 * 通过 Tomato Gateway REST API 下载小说 TXT，再交由 TxtImportService 导入 Redis。
 *
 * 流程：
 *   1. POST /v1/downloads  → 创建下载任务，获取 job_id
 *   2. GET  /v1/downloads/{job_id} 轮询直到 state == "completed"
 *   3. GET  /v1/downloads/{job_id}/artifacts → 取第一个 .txt 的 direct_url
 *   4. 下载 TXT 字节，调用 TxtImportService.importTxt()
 */
@Slf4j
@Service
public class FanqieGatewayService {

    @Value("${fq.gateway.base-url:https://linjinpeng-tomato-gateway.hf.space}")
    private String gatewayBaseUrl;

    @Value("${fq.gateway.token:fanqie123}")
    private String gatewayToken;

    /** 下载轮询最大等待时间（秒） */
    @Value("${fq.gateway.timeout-seconds:1800}")
    private int timeoutSeconds;

    /** 轮询间隔（秒） */
    private static final int POLL_INTERVAL_SECONDS = 5;

    @Resource
    private TxtImportService txtImportService;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 根据 bookId 从 Fanqie Gateway 下载 TXT 并导入 Redis。
     *
     * @param bookId 番茄小说 bookId（数字字符串）
     * @return 导入结果描述（"导入成功..." 或 "下载失败..."）
     */
    public String downloadAndImport(String bookId) throws Exception {
        String normalizedBase = gatewayBaseUrl.replaceAll("/+$", "");

        // Step 1: 创建下载任务
        String jobId = createDownloadJob(normalizedBase, bookId);
        log.info("Fanqie Gateway 下载任务已创建 - bookId: {}, jobId: {}", bookId, jobId);

        // Step 2: 轮询任务状态
        waitForCompletion(normalizedBase, jobId);
        log.info("Fanqie Gateway 下载完成 - jobId: {}", jobId);

        // Step 3: 获取 artifact URL
        String txtUrl = getArtifactUrl(normalizedBase, jobId);
        log.info("获取到 TXT 下载链接 - jobId: {}, url: {}", jobId, txtUrl);

        // Step 4: 下载 TXT
        byte[] txtBytes = downloadTxt(txtUrl);
        log.info("TXT 下载完成 - jobId: {}, size: {} bytes", jobId, txtBytes.length);

        // Step 5: 导入 Redis
        return txtImportService.importTxt(txtBytes);
    }

    // ---------- 内部方法 ----------

    private String createDownloadJob(String base, String bookId) throws Exception {
        HttpHeaders headers = buildHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new HashMap<>();
        body.put("book_id", bookId);
        body.put("file_format", "txt");

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(
            base + "/v1/downloads", request, String.class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException("创建下载任务失败，HTTP " + response.getStatusCode());
        }

        JsonNode node = objectMapper.readTree(response.getBody());
        String jobId = node.path("id").asText(null);
        if (jobId == null || jobId.isEmpty()) {
            throw new RuntimeException("创建下载任务失败，无法解析 job_id: " + response.getBody());
        }
        return jobId;
    }

    private void waitForCompletion(String base, String jobId) throws Exception {
        HttpEntity<Void> request = new HttpEntity<>(buildHeaders());
        int elapsed = 0;

        while (elapsed < timeoutSeconds) {
            Thread.sleep(POLL_INTERVAL_SECONDS * 1000L);
            elapsed += POLL_INTERVAL_SECONDS;

            ResponseEntity<String> response = restTemplate.exchange(
                base + "/v1/downloads/" + jobId, HttpMethod.GET, request, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new RuntimeException("查询下载状态失败，HTTP " + response.getStatusCode());
            }

            JsonNode node = objectMapper.readTree(response.getBody());
            String state = node.path("state").asText("");
            int savedChapters = node.path("progress").path("saved_chapters").asInt(0);
            int totalChapters = node.path("progress").path("chapter_total").asInt(0);
            String message = node.path("message").asText("");

            log.info("下载进度 - jobId: {}, state: {}, chapters: {}/{}, msg: {}",
                jobId, state, savedChapters, totalChapters, message);

            if ("completed".equals(state) || "done".equals(state)) {
                return;
            }
            if ("failed".equals(state) || "cancelled".equals(state) || "error".equals(state)) {
                throw new RuntimeException("下载失败，state: " + state + ", message: " + message);
            }
        }
        throw new RuntimeException("下载超时，jobId: " + jobId + "，已等待 " + timeoutSeconds + " 秒");
    }

    private String getArtifactUrl(String base, String jobId) throws Exception {
        HttpEntity<Void> request = new HttpEntity<>(buildHeaders());
        ResponseEntity<String> response = restTemplate.exchange(
            base + "/v1/downloads/" + jobId + "/artifacts",
            HttpMethod.GET, request, String.class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException("获取 artifacts 失败，HTTP " + response.getStatusCode());
        }

        JsonNode root = objectMapper.readTree(response.getBody());
        JsonNode matched = root.path("matched");

        // 找第一个 .txt 文件
        for (JsonNode item : matched) {
            String name = item.path("name").asText("");
            String directUrl = item.path("direct_url").asText(null);
            if (directUrl != null && (name.endsWith(".txt") || "txt".equalsIgnoreCase(item.path("ext").asText("")))) {
                return directUrl;
            }
        }

        // 兜底：取第一个非目录的文件
        for (JsonNode item : matched) {
            if (!item.path("is_dir").asBoolean(true)) {
                String directUrl = item.path("direct_url").asText(null);
                if (directUrl != null) {
                    return directUrl;
                }
            }
        }

        throw new RuntimeException("未找到 TXT 文件，artifacts: " + response.getBody());
    }

    private byte[] downloadTxt(String url) {
        // direct_url may use http:// — upgrade to https:// to match gateway
        String fullUrl;
        if (url.startsWith("http://")) {
            fullUrl = "https://" + url.substring("http://".length());
        } else if (url.startsWith("https://")) {
            fullUrl = url;
        } else {
            // relative path
            fullUrl = gatewayBaseUrl.replaceAll("/+$", "") + url;
        }

        HttpHeaders headers = buildHeaders();
        HttpEntity<Void> request = new HttpEntity<>(headers);
        ResponseEntity<byte[]> response = restTemplate.exchange(
            fullUrl, HttpMethod.GET, request, byte[].class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException("下载 TXT 文件失败，HTTP " + response.getStatusCode());
        }
        return response.getBody();
    }

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        String token = gatewayToken != null ? gatewayToken.trim() : "";
        if (!token.isEmpty()) {
            headers.set("Authorization", "Bearer " + token);
        }
        return headers;
    }
}
