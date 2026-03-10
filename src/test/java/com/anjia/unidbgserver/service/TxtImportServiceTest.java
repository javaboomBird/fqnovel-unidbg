package com.anjia.unidbgserver.service;

import com.anjia.unidbgserver.dto.FQNovelBookInfo;
import com.anjia.unidbgserver.dto.FQNovelChapterInfo;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TxtImportService 单元测试（无需 Spring 上下文、无需 Redis）
 */
@Slf4j
@ExtendWith(MockitoExtension.class)
class TxtImportServiceTest {

    @Mock
    private RedisService redisService;

    @InjectMocks
    private TxtImportService txtImportService;

    // ---------------------- 样例 TXT ----------------------

    static final String SAMPLE_TXT =
        "小说名：林深时见鹿\n" +
        "作者：测试作者\n" +
        "内容简介：一部关于山林的故事\n\n" +
        "第1章 开始\n" +
        "这是第一章的正文内容。\n" +
        "林中风光无限好，鹿鸣深处有人家。\n\n" +
        "第2章 相遇\n" +
        "这是第二章的正文内容。\n" +
        "山中岁月悠悠过，不知今夕是何年。\n\n" +
        "第3章 离别\n" +
        "第三章讲述了离别的故事。\n";

    /** 重复标题变体（部分 TXT 工具会把标题写两遍） */
    static final String DUPLICATE_TITLE_TXT =
        "小说名：剑道至尊\n" +
        "作者：剑客\n\n" +
        "第1章 出山\n\n" +
        "第1章 出山\n" +
        "持剑而行，天下任我游。\n\n" +
        "第2章 论剑\n\n" +
        "第2章 论剑\n" +
        "剑无双，意无穷。\n";

    // ---------------------- 测试用例 ----------------------

    @Test
    void testImport_parsesBookMetadata() {
        String result = importSample(SAMPLE_TXT);

        assertTrue(result.startsWith("导入成功"), "应该导入成功，实际: " + result);
        assertTrue(result.contains("林深时见鹿"), "结果应包含书名");
        assertTrue(result.contains("章节数: 3"), "应识别 3 章，实际: " + result);
        log.info("导入结果: {}", result);
    }

    @Test
    void testImport_callsRedisCorrectly() {
        importSample(SAMPLE_TXT);

        // 书籍信息写入
        ArgumentCaptor<FQNovelBookInfo> bookCaptor = ArgumentCaptor.forClass(FQNovelBookInfo.class);
        verify(redisService).saveBookInfo(anyString(), bookCaptor.capture());
        FQNovelBookInfo book = bookCaptor.getValue();
        assertEquals("林深时见鹿", book.getBookName());
        assertEquals("测试作者", book.getAuthor());
        assertEquals(3, book.getTotalChapters());

        // 每章都写入
        verify(redisService, times(3)).saveChapter(anyString(), anyString(), any());

        // 有序章节列表
        ArgumentCaptor<List<String>> listCaptor = ArgumentCaptor.forClass(List.class);
        verify(redisService).saveChapterList(anyString(), listCaptor.capture());
        assertEquals(3, listCaptor.getValue().size());

        // 加入本地索引
        verify(redisService).addLocalBookIndex(anyString());
    }

    @Test
    void testImport_chapterTitlesAndContent() {
        importSample(SAMPLE_TXT);

        ArgumentCaptor<FQNovelChapterInfo> captor = ArgumentCaptor.forClass(FQNovelChapterInfo.class);
        verify(redisService, times(3)).saveChapter(anyString(), anyString(), captor.capture());

        List<FQNovelChapterInfo> chapters = captor.getAllValues();
        assertEquals("第1章 开始", chapters.get(0).getTitle());
        assertEquals("第2章 相遇", chapters.get(1).getTitle());
        assertEquals("第3章 离别", chapters.get(2).getTitle());

        // 内容不应为空
        for (FQNovelChapterInfo ch : chapters) {
            assertNotNull(ch.getTxtContent());
            assertFalse(ch.getTxtContent().isBlank(), "章节内容不应为空: " + ch.getTitle());
            assertTrue(ch.getWordCount() > 0, "字数应大于 0: " + ch.getTitle());
            assertTrue(Boolean.TRUE.equals(ch.getIsFree()));
        }
        log.info("第1章标题: {}, 内容(前50): {}", chapters.get(0).getTitle(),
            chapters.get(0).getTxtContent().substring(0, Math.min(50, chapters.get(0).getTxtContent().length())));
    }

    @Test
    void testImport_chapterIdFormat() {
        importSample(SAMPLE_TXT);

        // 章节ID格式应为 local_XXXXXXXX_ch_N
        ArgumentCaptor<String> chapterIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisService, times(3)).saveChapter(anyString(), chapterIdCaptor.capture(), any());

        List<String> chapterIds = chapterIdCaptor.getAllValues();
        assertTrue(chapterIds.get(0).startsWith("local_"), "chapterId 应以 local_ 开头: " + chapterIds.get(0));
        assertTrue(chapterIds.get(0).endsWith("_ch_1"), "第1章 chapterId 应以 _ch_1 结尾: " + chapterIds.get(0));
        assertTrue(chapterIds.get(2).endsWith("_ch_3"), "第3章 chapterId 应以 _ch_3 结尾: " + chapterIds.get(2));
        log.info("生成的 chapterId 示例: {}", chapterIds.get(0));
    }

    @Test
    void testImport_duplicateTitleVariant() {
        importSample(DUPLICATE_TITLE_TXT);

        ArgumentCaptor<FQNovelChapterInfo> captor = ArgumentCaptor.forClass(FQNovelChapterInfo.class);
        verify(redisService, times(2)).saveChapter(anyString(), anyString(), captor.capture());

        List<FQNovelChapterInfo> chapters = captor.getAllValues();
        assertEquals("第1章 出山", chapters.get(0).getTitle());
        assertEquals("第2章 论剑", chapters.get(1).getTitle());

        // 内容中不应再有重复的标题行
        String ch1Content = chapters.get(0).getTxtContent();
        log.info("第1章内容: [{}]", ch1Content);
        assertFalse(ch1Content.startsWith("第1章 出山"),
            "重复标题应被去除，实际内容: " + ch1Content);
    }

    @Test
    void testImport_sameTxtGivesSameBookId() {
        // 相同书名应生成相同 bookId（MD5 稳定）
        importSample(SAMPLE_TXT);
        ArgumentCaptor<String> bookIdCaptor1 = ArgumentCaptor.forClass(String.class);
        verify(redisService).addLocalBookIndex(bookIdCaptor1.capture());
        String bookId1 = bookIdCaptor1.getValue();

        reset(redisService);

        importSample(SAMPLE_TXT);
        ArgumentCaptor<String> bookIdCaptor2 = ArgumentCaptor.forClass(String.class);
        verify(redisService).addLocalBookIndex(bookIdCaptor2.capture());
        String bookId2 = bookIdCaptor2.getValue();

        assertEquals(bookId1, bookId2, "相同书名应生成相同 bookId");
        log.info("稳定 bookId: {}", bookId1);
    }

    @Test
    void testImport_noChaptersReturnsError() {
        String txt = "小说名：无章节书\n作者：某人\n这里没有任何章节格式\n";
        String result = importSample(txt);

        assertFalse(result.startsWith("导入成功"), "无章节时应报错");
        assertTrue(result.contains("未找到任何章节"), "应包含错误信息: " + result);
        verify(redisService, never()).saveChapter(any(), any(), any());
    }

    // ---------------------- 工具方法 ----------------------

    private String importSample(String txt) {
        return txtImportService.importTxt(txt.getBytes(StandardCharsets.UTF_8));
    }
}
