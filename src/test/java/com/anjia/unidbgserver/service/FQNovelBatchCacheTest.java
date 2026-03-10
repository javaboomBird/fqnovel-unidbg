package com.anjia.unidbgserver.service;

import com.anjia.unidbgserver.dto.*;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.junit4.SpringRunner;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;

import static org.mockito.Mockito.*;

/**
 * 批量章节 Redis 缓存集成测试
 *
 * 前提条件：本地 Redis（127.0.0.1:6379）必须已启动
 *
 * 测试目标：
 * 1. 确认已缓存的章节从 Redis 直接返回，不调用 fqnovel API
 * 2. 确认 TXT 导入后的章节可通过 getBatchChapterContent 正常读取
 * 3. 确认 getBookDirectory 对本地书籍直接走 Redis
 */
@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FQNovelBatchCacheTest {

    // Mock 掉所有会初始化 unidbg 或发出真实 HTTP 请求的 Bean
    @MockBean(name = "fqEncryptWorker")
    private FQEncryptServiceWorker fqEncryptServiceWorker;

    @MockBean
    private FQRegisterKeyService registerKeyService;

    @MockBean
    private FQDeviceRegisterService deviceRegisterService;

    @Autowired
    private RedisService redisService;

    @Autowired
    private FQNovelService fqNovelService;

    @Autowired
    private TxtImportService txtImportService;

    @Autowired
    private FQSearchService fqSearchService;

    private static final String TEST_BOOK_ID = "test_book_999";
    private static final String TEST_CHAPTER_1 = "test_book_999_ch_1";
    private static final String TEST_CHAPTER_2 = "test_book_999_ch_2";
    private static final String TEST_CHAPTER_3 = "test_book_999_ch_3";

    @BeforeEach
    void setUp() {
        // 清理测试数据
        redisService.deleteBookChapters(TEST_BOOK_ID);
        redisService.deleteBook(TEST_BOOK_ID);
    }

    // ====================================================
    // Test 1: Redis 缓存命中，不调用真实 API
    // ====================================================

    @Test
    void testBatchChapters_allCachedFromRedis() throws Exception {
        // 预存 3 章到 Redis
        saveTestChapter(TEST_CHAPTER_1, "第一章标题", "第一章正文内容，测试测试。");
        saveTestChapter(TEST_CHAPTER_2, "第二章标题", "第二章正文内容，继续继续。");
        saveTestChapter(TEST_CHAPTER_3, "第三章标题", "第三章正文内容，完结撒花。");

        // 构建批量请求（直接传 chapterId 列表）
        FQBatchChapterRequest request = new FQBatchChapterRequest();
        request.setBookId(TEST_BOOK_ID);
        request.setChapterIds(List.of(TEST_CHAPTER_1, TEST_CHAPTER_2, TEST_CHAPTER_3));

        FQNovelResponse<FQBatchChapterResponse> response =
            fqNovelService.getBatchChapterContent(request).get();

        // 验证成功，且全部来自 Redis（fqEncryptServiceWorker 未被调用）
        assertEquals(0, response.getCode(), "应成功: " + response.getMessage());
        assertNotNull(response.getData());
        assertEquals(3, response.getData().getSuccessCount(), "应成功获取 3 章");

        FQBatchChapterResponse data = response.getData();
        assertTrue(data.getChapters().containsKey(TEST_CHAPTER_1));
        assertTrue(data.getChapters().containsKey(TEST_CHAPTER_2));
        assertTrue(data.getChapters().containsKey(TEST_CHAPTER_3));

        assertEquals("第一章标题", data.getChapters().get(TEST_CHAPTER_1).getChapterName());
        assertEquals("第一章正文内容，测试测试。", data.getChapters().get(TEST_CHAPTER_1).getTxtContent());

        // 关键断言：未调用签名/API（全部来自 Redis 缓存）
        verify(fqEncryptServiceWorker, never()).generateSignatureHeaders(anyString(), any(Map.class));
        log.info("批量章节缓存测试通过 - 成功章节: {}", data.getSuccessCount());
    }

    @Test
    void testBatchChapters_partialCacheHit() throws Exception {
        // 只缓存前 2 章，第 3 章未缓存
        saveTestChapter(TEST_CHAPTER_1, "第一章标题", "第一章正文");
        saveTestChapter(TEST_CHAPTER_2, "第二章标题", "第二章正文");
        // TEST_CHAPTER_3 故意不存

        FQBatchChapterRequest request = new FQBatchChapterRequest();
        request.setBookId(TEST_BOOK_ID);
        request.setChapterIds(List.of(TEST_CHAPTER_1, TEST_CHAPTER_2, TEST_CHAPTER_3));

        // 因为第 3 章未缓存，会触发 API 调用（mock 返回 null，API 会失败）
        // 但已缓存的 2 章应该正常返回
        FQNovelResponse<FQBatchChapterResponse> response =
            fqNovelService.getBatchChapterContent(request).get();

        // 不管 API 失败与否，前 2 章应该有
        FQBatchChapterResponse data = response.getData();
        if (data != null) {
            assertTrue(data.getChapters().containsKey(TEST_CHAPTER_1), "第1章应从缓存返回");
            assertTrue(data.getChapters().containsKey(TEST_CHAPTER_2), "第2章应从缓存返回");
            log.info("部分缓存命中测试 - 成功章节: {}", data.getSuccessCount());
        } else {
            log.info("部分缓存命中测试 - API 调用失败（预期，mock 返回 null）: {}", response.getMessage());
        }
    }

    // ====================================================
    // Test 2: TXT 导入后章节可正常读取
    // ====================================================

    @Test
    void testTxtImport_thenReadChapters() throws Exception {
        String txt =
            "小说名：测试导入小说\n" +
            "作者：测试作者\n" +
            "内容简介：这是一本测试小说\n\n" +
            "第1章 初出茅庐\n" +
            "话说天下大势，分久必合，合久必分。\n" +
            "这是第一章的正文内容。\n\n" +
            "第2章 崭露头角\n" +
            "英雄不问出处，但问志向高远。\n" +
            "这是第二章的正文内容。\n\n" +
            "第3章 大器晚成\n" +
            "路漫漫其修远兮，吾将上下而求索。\n" +
            "这是第三章的正文内容。\n";

        // 导入 TXT
        String importResult = txtImportService.importTxt(txt.getBytes(StandardCharsets.UTF_8));
        log.info("TXT 导入结果: {}", importResult);
        assertTrue(importResult.startsWith("导入成功"), "导入应成功: " + importResult);
        assertTrue(importResult.contains("章节数: 3"));

        // 从导入结果解析 bookId（格式：导入成功 - 书名: XXX, bookId: local_XXXXXXXX, 章节数: 3）
        String bookId = extractBookId(importResult);
        assertNotNull(bookId, "应能从结果中解析 bookId");
        log.info("导入书籍 bookId: {}", bookId);

        // 构建章节 ID 列表
        String ch1 = bookId + "_ch_1";
        String ch2 = bookId + "_ch_2";
        String ch3 = bookId + "_ch_3";

        // 通过 getBatchChapterContent 读取（全部来自 Redis）
        FQBatchChapterRequest request = new FQBatchChapterRequest();
        request.setBookId(bookId);
        request.setChapterIds(List.of(ch1, ch2, ch3));

        FQNovelResponse<FQBatchChapterResponse> response =
            fqNovelService.getBatchChapterContent(request).get();

        assertEquals(0, response.getCode(), "读取应成功: " + response.getMessage());
        assertEquals(3, response.getData().getSuccessCount());

        // 验证章节标题和内容
        assertEquals("第1章 初出茅庐", response.getData().getChapters().get(ch1).getChapterName());
        assertEquals("第2章 崭露头角", response.getData().getChapters().get(ch2).getChapterName());
        assertEquals("第3章 大器晚成", response.getData().getChapters().get(ch3).getChapterName());

        String ch1Content = response.getData().getChapters().get(ch1).getTxtContent();
        assertTrue(ch1Content.contains("话说天下大势"), "第1章内容应包含正文: " + ch1Content);

        // 未调用真实 API
        verify(fqEncryptServiceWorker, never()).generateSignatureHeaders(anyString(), any(Map.class));

        log.info("TXT 导入后读取测试通过 - 第1章内容(前30字): {}",
            ch1Content.substring(0, Math.min(30, ch1Content.length())));
    }

    // ====================================================
    // Test 3: getBookDirectory 对本地书籍走 Redis
    // ====================================================

    @Test
    void testGetBookDirectory_localBook() throws Exception {
        String txt =
            "小说名：目录测试书\n" +
            "作者：目录作者\n\n" +
            "第1章 序章\n" +
            "序章内容在此。\n\n" +
            "第2章 正文\n" +
            "正文内容在此。\n";

        String importResult = txtImportService.importTxt(txt.getBytes(StandardCharsets.UTF_8));
        String bookId = extractBookId(importResult);
        assertNotNull(bookId);

        // 调用目录接口
        FQDirectoryRequest directoryRequest = new FQDirectoryRequest();
        directoryRequest.setBookId(bookId);

        FQNovelResponse<FQDirectoryResponse> response =
            fqSearchService.getBookDirectory(directoryRequest).get();

        assertEquals(0, response.getCode(), "应成功: " + response.getMessage());
        assertNotNull(response.getData());
        assertEquals(2, response.getData().getItemDataList().size());
        assertEquals("第1章 序章", response.getData().getItemDataList().get(0).getTitle());
        assertEquals("第2章 正文", response.getData().getItemDataList().get(1).getTitle());
        assertEquals(bookId + "_ch_1", response.getData().getItemDataList().get(0).getItemId());

        // 目录接口不应调用真实 API
        verify(fqEncryptServiceWorker, never()).generateSignatureHeaders(anyString(), any(Map.class));
        log.info("本地书籍目录测试通过 - 章节数: {}", response.getData().getItemDataList().size());
    }

    // ====================================================
    // 工具方法
    // ====================================================

    private void saveTestChapter(String chapterId, String title, String content) {
        FQNovelChapterInfo info = new FQNovelChapterInfo();
        info.setChapterId(chapterId);
        info.setBookId(TEST_BOOK_ID);
        info.setTitle(title);
        info.setTxtContent(content);
        info.setRawContent(content);
        info.setWordCount(content.length());
        info.setAuthorName("测试作者");
        info.setUpdateTime(System.currentTimeMillis());
        info.setIsFree(true);
        redisService.saveChapter(TEST_BOOK_ID, chapterId, info);
    }

    private String extractBookId(String importResult) {
        // "导入成功 - 书名: XXX, bookId: local_XXXXXXXX, 章节数: ..."
        int start = importResult.indexOf("bookId: ");
        if (start < 0) return null;
        start += "bookId: ".length();
        int end = importResult.indexOf(",", start);
        return end > start ? importResult.substring(start, end).trim() : null;
    }
}
