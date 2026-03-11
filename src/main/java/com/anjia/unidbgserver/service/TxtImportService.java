package com.anjia.unidbgserver.service;

import com.anjia.unidbgserver.dto.FQNovelBookInfo;
import com.anjia.unidbgserver.dto.FQNovelChapterInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TXT 小说导入服务
 * 将 TXT 格式的小说文件解析后存入 Redis，使其可通过同一书源访问
 */
@Slf4j
@Service
public class TxtImportService {

    private static final Pattern CHAPTER_PATTERN = Pattern.compile(
        "^第(\\d+)章\\s+(.+)$", Pattern.MULTILINE);

    @Resource
    private RedisService redisService;

    /**
     * 导入 TXT 文件字节内容
     *
     * @param bytes TXT 文件原始字节（UTF-8 编码）
     * @return 导入结果描述
     */
    public String importTxt(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n").replace("\r", "\n");
        return doImport(text);
    }

    private String doImport(String text) {
        // 解析头部（前几行）
        String bookName = "";
        String author = "";
        String description = "";

        String[] lines = text.split("\n", 20);
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("小说名：") || line.startsWith("小说名:") ||
                    line.startsWith("书名：") || line.startsWith("书名:")) {
                bookName = line.substring(line.indexOf('：') >= 0 ? line.indexOf('：') + 1 : line.indexOf(':') + 1).trim();
            } else if (line.startsWith("作者：") || line.startsWith("作者:")) {
                author = line.substring(line.indexOf('：') >= 0 ? line.indexOf('：') + 1 : line.indexOf(':') + 1).trim();
            } else if (line.startsWith("内容简介：") || line.startsWith("内容简介:") ||
                    line.startsWith("简介：") || line.startsWith("简介:")) {
                description = line.substring(line.indexOf('：') >= 0 ? line.indexOf('：') + 1 : line.indexOf(':') + 1).trim();
            }
        }

        if (bookName.isEmpty()) {
            // 尝试用第一行作为书名
            bookName = lines[0].trim();
        }
        if (bookName.isEmpty()) {
            return "导入失败：无法解析书名";
        }

        // 生成稳定的本地 bookId
        String bookId = "local_" + md5Short(bookName);
        log.info("开始导入 TXT 书籍 - 书名: {}, bookId: {}, 作者: {}", bookName, bookId, author);

        // 分割章节
        List<Chapter> chapters = splitChapters(text);
        if (chapters.isEmpty()) {
            return "导入失败：未找到任何章节（请确认章节标题格式为 '第N章 标题'）";
        }

        // 存储书籍信息
        FQNovelBookInfo bookInfo = new FQNovelBookInfo();
        bookInfo.setBookId(bookId);
        bookInfo.setBookName(bookName);
        bookInfo.setAuthor(author);
        bookInfo.setDescription(description);
        bookInfo.setTotalChapters(chapters.size());
        bookInfo.setStatus(1); // 已完结
        redisService.saveBookInfo(bookId, bookInfo);

        // 存储各章节并构建 chapterId 列表
        List<String> chapterIds = new ArrayList<>();
        for (int i = 0; i < chapters.size(); i++) {
            Chapter chapter = chapters.get(i);
            String chapterId = bookId + "_ch_" + (i + 1);
            chapterIds.add(chapterId);

            FQNovelChapterInfo chapterInfo = new FQNovelChapterInfo();
            chapterInfo.setChapterId(chapterId);
            chapterInfo.setBookId(bookId);
            chapterInfo.setTitle(chapter.title);
            chapterInfo.setTxtContent(chapter.content);
            chapterInfo.setRawContent(chapter.content);
            chapterInfo.setChapterIndex(i + 1);
            chapterInfo.setWordCount(chapter.content.length());
            chapterInfo.setAuthorName(author);
            chapterInfo.setUpdateTime(System.currentTimeMillis());
            chapterInfo.setIsFree(true);

            redisService.saveChapter(bookId, chapterId, chapterInfo);
        }

        // 存储有序 chapterId 列表（用于目录）
        redisService.saveChapterList(bookId, chapterIds);

        // 加入本地书籍索引（供搜索使用）
        redisService.addLocalBookIndex(bookId);

        log.info("TXT 导入完成 - 书名: {}, bookId: {}, 章节数: {}", bookName, bookId, chapters.size());
        return String.format("导入成功 - 书名: %s, bookId: %s, 章节数: %d", bookName, bookId, chapters.size());
    }

    /**
     * 将 TXT 正文按章节拆分
     * 支持两种变体：
     *   - 单标题：第N章 标题\n内容...
     *   - 重复标题：第N章 标题\n\n第N章 标题\n内容...
     */
    private List<Chapter> splitChapters(String text) {
        List<Chapter> chapters = new ArrayList<>();
        Matcher matcher = CHAPTER_PATTERN.matcher(text);

        List<int[]> headerPositions = new ArrayList<>(); // [start, end, titleStart, titleEnd]
        List<String> titles = new ArrayList<>();

        while (matcher.find()) {
            headerPositions.add(new int[]{matcher.start(), matcher.end()});
            titles.add("第" + matcher.group(1) + "章 " + matcher.group(2).trim());
        }

        for (int i = 0; i < headerPositions.size(); i++) {
            int contentStart = headerPositions.get(i)[1];
            int contentEnd = (i + 1 < headerPositions.size()) ? headerPositions.get(i + 1)[0] : text.length();
            String rawContent = text.substring(contentStart, contentEnd).trim();

            // 检测重复标题变体：内容以相同标题开头（允许中间有空行）
            String title = titles.get(i);
            // strip leading blank lines then check duplicate title
            String stripped = rawContent.replaceFirst("^\\s+", "");
            if (stripped.startsWith(title)) {
                rawContent = stripped.substring(title.length()).trim();
            }

            // 跳过空内容的章节（重复标题变体中的首次占位行）
            if (rawContent.isBlank()) {
                continue;
            }

            chapters.add(new Chapter(title, rawContent));
        }

        return chapters;
    }

    private static String md5Short(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) { // 取前4字节 = 8位hex
                sb.append(String.format("%02x", digest[i] & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    private static class Chapter {
        final String title;
        final String content;

        Chapter(String title, String content) {
            this.title = title;
            this.content = content;
        }
    }
}
