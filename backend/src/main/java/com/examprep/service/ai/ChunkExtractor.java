package com.examprep.service.ai;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.examprep.common.QuestionType;
import com.examprep.entity.AiChunk;
import com.examprep.entity.FileRecord;
import com.examprep.entity.KnowledgePoint;
import com.examprep.entity.Question;
import com.examprep.entity.QuestionAnswer;
import com.examprep.entity.Resource;
import com.examprep.entity.Subject;
import com.examprep.mapper.FileRecordMapper;
import com.examprep.mapper.KnowledgePointMapper;
import com.examprep.mapper.QuestionAnswerMapper;
import com.examprep.mapper.QuestionMapper;
import com.examprep.mapper.ResourceMapper;
import com.examprep.mapper.SubjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 语料抽取器：把业务表的可检索文本抽取为 {@link AiChunk}（设计方案 A.5）
 *
 * <p>抽取规则：
 * <table border="1">
 *   <tr><th>source_type</th><th>抽取内容</th><th>分块</th></tr>
 *   <tr><td>QUESTION</td><td>stem + material + analysis + tags + 主观题 reference/keywords</td><td>不分块</td></tr>
 *   <tr><td>RESOURCE</td><td>title + description + tags；ext=pdf 时追加 PDF 正文</td><td>PDF 按 400/80 切块</td></tr>
 *   <tr><td>KNOWLEDGE</td><td>知识点 name + 其下题目 stem 摘要（最多 3 条）</td><td>不分块</td></tr>
 * </table>
 *
 * <p><b>防泄题硬约束（风险 R7）</b>：客观题答案（结构化选项）<b>禁止</b>入索引，
 * 只取主观题的 {@code reference}。否则 AI 会把选择题答案直接吐给用户。
 * 该约束在 {@link #extractQuestion} 中以「题型判定 + JSON 字段白名单」双重落实。
 */
@Slf4j
@Component
public class ChunkExtractor {

    private static final Pattern PAGE_HEADER = Pattern.compile(
            "(第\\s*\\d+\\s*页|Page\\s*\\d+(\\s*(of|/)\\s*\\d+)?|—\\s*\\d+\\s*—)", Pattern.CASE_INSENSITIVE);
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\u3000]+");
    private static final Pattern PAGE_BREAK = Pattern.compile("(?<=[。！？!?；;\\n])");

    /** 主观题才允许取参考答案的题型集合（其余视为客观题，答案一律不入索引） */
    private static final java.util.Set<String> SUBJECTIVE_TYPES = java.util.Set.of(
            "SHORT_ANSWER", "TERM_EXPLAIN", "CALCULATION", "APPLICATION",
            "WRITING", "TRANSLATION", "ESSAY", "PROOF", "CASE_ANALYSIS", "PROGRAMMING");

    private final QuestionMapper questionMapper;
    private final QuestionAnswerMapper questionAnswerMapper;
    private final ResourceMapper resourceMapper;
    private final KnowledgePointMapper knowledgePointMapper;
    private final SubjectMapper subjectMapper;
    private final FileRecordMapper fileRecordMapper;
    private final ObjectMapper objectMapper;
    private final AiProperties properties;
    private final Path uploadRoot;

    public ChunkExtractor(QuestionMapper questionMapper,
                          QuestionAnswerMapper questionAnswerMapper,
                          ResourceMapper resourceMapper,
                          KnowledgePointMapper knowledgePointMapper,
                          SubjectMapper subjectMapper,
                          FileRecordMapper fileRecordMapper,
                          ObjectMapper objectMapper,
                          AiProperties properties,
                          @Value("${app.upload.dir:./uploads}") String uploadDir) {
        this.questionMapper = questionMapper;
        this.questionAnswerMapper = questionAnswerMapper;
        this.resourceMapper = resourceMapper;
        this.knowledgePointMapper = knowledgePointMapper;
        this.subjectMapper = subjectMapper;
        this.fileRecordMapper = fileRecordMapper;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    // ==================================================================
    // 题目
    // ==================================================================

    /**
     * 抽取单道已审核题目的语料块（不分块，chunk_no 恒为 0）
     */
    public List<AiChunk> extractQuestion(Question question) {
        if (question == null) {
            return List.of();
        }
        StringBuilder sb = new StringBuilder();
        appendSection(sb, "题干", question.getStem());
        appendSection(sb, "材料", question.getMaterial());
        appendSection(sb, "解析", question.getAnalysis());
        appendSection(sb, "标签", question.getTags());
        // ★ R7 防泄题：仅主观题取参考答案，客观题答案绝不进索引
        String reference = extractSubjectiveReference(question);
        appendSection(sb, "参考答案", reference);

        String content = sb.toString().trim();
        if (!StringUtils.hasText(content)) {
            return List.of();
        }

        AiChunk chunk = new AiChunk();
        chunk.setSourceType(AiChunk.SOURCE_QUESTION);
        chunk.setSourceId(question.getId());
        chunk.setChunkNo(0);
        chunk.setTitle(truncate(plainStem(question.getStem()), 190));
        chunk.setContent(content);
        chunk.setSubjectId(question.getSubjectId());
        chunk.setExamCategoryId(examCategoryOfSubject(question.getSubjectId()));
        chunk.setKnowledgeId(firstKnowledgeIdOfQuestion(question.getId()));
        chunk.setDifficulty(question.getDifficulty() == null ? 3 : question.getDifficulty());
        chunk.setRefType(AiChunk.SOURCE_QUESTION);
        chunk.setRefId(question.getId());
        chunk.setPageNo(null);
        chunk.setContentHash(md5(content));
        chunk.setSourceUpdatedAt(question.getUpdatedAt() == null ? LocalDateTime.now() : question.getUpdatedAt());
        chunk.setStatus(AiChunk.STATUS_ACTIVE);
        return List.of(chunk);
    }

    /**
     * 题目关联的第一个知识点 ID（question_knowledge 一题多知识点时取 ID 最小的一条）
     */
    public Long firstKnowledgeIdOfQuestion(Long questionId) {
        return questionId == null ? null : questionMapper.selectFirstKnowledgeIdByQuestionId(questionId);
    }

    /**
     * ★ R7 防泄题核心：只有主观题才返回参考答案文本
     *
     * <p>客观题（SINGLE_CHOICE / MULTI_CHOICE / TRUE_FALSE / FILL_BLANK / MATCHING / ORDERING 等）
     * 的 {@code question_answer.answer_content} 存的是结构化答案（如 {@code {"options":["A"]}}），
     * 入索引等于泄题，因此这里以题型白名单硬拦截，不做任何内容嗅探。
     */
    private String extractSubjectiveReference(Question question) {
        if (!SUBJECTIVE_TYPES.contains(upper(question.getType()))) {
            return null;
        }
        if (QuestionType.isObjective(question.getType())) {
            return null;
        }
        QuestionAnswer answer = questionAnswerMapper.selectOne(
                Wrappers.<QuestionAnswer>lambdaQuery()
                        .eq(QuestionAnswer::getQuestionId, question.getId())
                        .last("LIMIT 1"));
        if (answer == null || answer.getAnswerContent() == null) {
            return null;
        }
        try {
            String json = answer.getAnswerContent() instanceof String s
                    ? s
                    : objectMapper.writeValueAsString(answer.getAnswerContent());
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isObject()) {
                return null;
            }
            // 字段白名单：只取 reference（简答/论述/计算题参考答案）与 keywords
            StringBuilder sb = new StringBuilder();
            JsonNode reference = node.get("reference");
            if (reference != null && reference.isTextual() && StringUtils.hasText(reference.asText())) {
                sb.append(reference.asText().trim());
            }
            JsonNode modelEssay = node.get("modelEssay");
            if (sb.isEmpty() && modelEssay != null && modelEssay.isTextual()
                    && StringUtils.hasText(modelEssay.asText())) {
                sb.append(modelEssay.asText().trim());
            }
            JsonNode translation = node.get("referenceTranslation");
            if (sb.isEmpty() && translation != null && translation.isTextual()
                    && StringUtils.hasText(translation.asText())) {
                sb.append(translation.asText().trim());
            }
            JsonNode keywords = node.get("keywords");
            if (keywords != null && keywords.isArray() && !keywords.isEmpty()) {
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                List<String> kws = new ArrayList<>();
                keywords.forEach(k -> kws.add(k.asText()));
                sb.append("关键词：").append(String.join("、", kws));
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.warn("[AI] 题目 #{} 答案 JSON 解析失败，跳过参考答案抽取: {}", question.getId(), e.getMessage());
            return null;
        }
    }

    // ==================================================================
    // 资料
    // ==================================================================

    /**
     * 抽取单份已审核资料的语料块
     *
     * <p>基础块（标题 + 简介 + 标签）恒有；若当前版本文件是 PDF 且能抽出文本，
     * 额外按 400 字 / 重叠 80 切块并保留 {@code page_no}。
     */
    public List<AiChunk> extractResource(Resource resource) {
        if (resource == null) {
            return List.of();
        }
        List<AiChunk> chunks = new ArrayList<>();

        StringBuilder base = new StringBuilder();
        appendSection(base, "简介", resource.getDescription());
        appendSection(base, "标签", resource.getTags());
        String baseContent = base.toString().trim();
        // 基础块即使没有简介也要有内容（否则索引里只有标题，检索价值过低）
        if (!StringUtils.hasText(baseContent)) {
            baseContent = "资料：" + resource.getTitle();
        }

        List<String> pdfPages = extractPdfPages(resource);

        if (pdfPages.isEmpty()) {
            // 无 PDF 正文：单块，chunk_no = 0
            chunks.add(buildResourceChunk(resource, 0, null, baseContent));
            return chunks;
        }

        // 有 PDF 正文：基础信息并入第 0 块，其余为纯正文块，避免同一份资料出现两个语义重复的第 0 块
        int chunkNo = 0;
        int size = properties.getKnowledge().getChunkSize();
        int overlap = properties.getKnowledge().getOverlap();
        int maxChunks = properties.getKnowledge().getMaxChunksPerSource();

        for (int i = 0; i < pdfPages.size() && chunkNo < maxChunks; i++) {
            String pageText = pdfPages.get(i);
            if (!StringUtils.hasText(pageText)) {
                continue;
            }
            for (String piece : splitIntoChunks(pageText, size, overlap)) {
                if (chunkNo >= maxChunks) {
                    log.warn("[AI] 资料 #{} 文本过长，切块数超过上限 {}，已截断", resource.getId(), maxChunks);
                    break;
                }
                String content = chunkNo == 0
                        ? baseContent + "\n" + piece
                        : piece;
                chunks.add(buildResourceChunk(resource, chunkNo, i + 1, content.trim()));
                chunkNo++;
            }
        }
        if (chunks.isEmpty()) {
            chunks.add(buildResourceChunk(resource, 0, null, baseContent));
        }
        return chunks;
    }

    private AiChunk buildResourceChunk(Resource resource, int chunkNo, Integer pageNo, String content) {
        AiChunk chunk = new AiChunk();
        chunk.setSourceType(AiChunk.SOURCE_RESOURCE);
        chunk.setSourceId(resource.getId());
        chunk.setChunkNo(chunkNo);
        chunk.setTitle(truncate(resource.getTitle(), 190));
        chunk.setContent(content);
        chunk.setExamCategoryId(resource.getExamCategoryId());
        chunk.setSubjectId(resource.getSubjectId());
        chunk.setKnowledgeId(resource.getKnowledgeId());
        chunk.setDifficulty(3);
        chunk.setRefType(AiChunk.SOURCE_RESOURCE);
        chunk.setRefId(resource.getId());
        chunk.setPageNo(pageNo);
        chunk.setContentHash(md5(content));
        chunk.setSourceUpdatedAt(resource.getUpdatedAt() == null ? LocalDateTime.now() : resource.getUpdatedAt());
        chunk.setStatus(AiChunk.STATUS_ACTIVE);
        return chunk;
    }

    /**
     * 抽取 PDF 逐页文本
     *
     * <p>用 Spring 自带的 PDFBox（项目已有 3.0.7 依赖，pom 一行不加）。
     * <b>当前 file_record 表无 PDF 行</b>（资料本体是 PDFBox 转出的逐页 PNG），
     * 本方法是为「资料一旦改成真 PDF 就自动生效」预留的增强层，抽取失败只记 warn 不抛。
     *
     * @return 每页一段文本；无 PDF / 文件缺失 / 抽不出文本时返回空 list
     */
    private List<String> extractPdfPages(Resource resource) {
        if (resource.getCurrentFileId() == null) {
            return List.of();
        }
        FileRecord file = fileRecordMapper.selectById(resource.getCurrentFileId());
        if (file == null || !"pdf".equalsIgnoreCase(file.getExt()) || !StringUtils.hasText(file.getStoredPath())) {
            return List.of();
        }
        Path pdfPath = uploadRoot.resolve(file.getStoredPath()).normalize();
        if (!pdfPath.startsWith(uploadRoot) || !Files.isRegularFile(pdfPath)) {
            log.warn("[AI] 资料 #{} 的 PDF 路径不存在或越界: {}", resource.getId(), pdfPath);
            return List.of();
        }
        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            List<String> pages = new ArrayList<>();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                pages.add(normalizeText(stripper.getText(document)));
            }
            return pages;
        } catch (IOException | RuntimeException e) {
            log.warn("[AI] 资料 #{} 的 PDF 文本抽取失败（不影响基础块入库）: {}", resource.getId(), e.getMessage());
            return List.of();
        }
    }

    // ==================================================================
    // 知识点
    // ==================================================================

    /**
     * 抽取单个知识点的语料块：知识点名 + 其下题目 stem 摘要（最多 3 条）
     */
    public List<AiChunk> extractKnowledge(KnowledgePoint knowledge,
                                          List<Question> childQuestions) {
        if (knowledge == null) {
            return List.of();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("知识点：").append(knowledge.getName()).append('\n');
        int maxSnippets = properties.getKnowledge().getKnowledgeSnippetCount();
        int count = 0;
        for (Question q : childQuestions == null ? List.<Question>of() : childQuestions) {
            if (count >= maxSnippets) {
                break;
            }
            String stem = plainStem(q.getStem());
            if (!StringUtils.hasText(stem)) {
                continue;
            }
            sb.append("相关题目：").append(stem).append('\n');
            count++;
        }
        String content = sb.toString().trim();
        AiChunk chunk = new AiChunk();
        chunk.setSourceType(AiChunk.SOURCE_KNOWLEDGE);
        chunk.setSourceId(knowledge.getId());
        chunk.setChunkNo(0);
        chunk.setTitle(truncate(knowledge.getName(), 190));
        chunk.setContent(content);
        chunk.setSubjectId(knowledge.getSubjectId());
        chunk.setExamCategoryId(examCategoryOfSubject(knowledge.getSubjectId()));
        chunk.setKnowledgeId(knowledge.getId());
        chunk.setDifficulty(3);
        chunk.setRefType(AiChunk.SOURCE_KNOWLEDGE);
        chunk.setRefId(knowledge.getId());
        chunk.setPageNo(null);
        chunk.setContentHash(md5(content));
        chunk.setSourceUpdatedAt(knowledge.getUpdatedAt() == null ? LocalDateTime.now() : knowledge.getUpdatedAt());
        chunk.setStatus(AiChunk.STATUS_ACTIVE);
        return List.of(chunk);
    }

    // ==================================================================
    // 文本处理工具
    // ==================================================================

    /**
     * 切块算法（设计方案 A.5）
     *
     * <ol>
     *   <li>归一化：连续空白/换行 → 单空格；去除页眉页脚（"第 X 页"、"Page X of Y"）</li>
     *   <li>按中英文句读切句，累积到 CHUNK_SIZE 为止</li>
     *   <li>相邻块重叠 OVERLAP 字符，避免答案被切断</li>
     *   <li>块数上限由调用方（{@code maxChunksPerSource}）控制</li>
     * </ol>
     */
    public List<String> splitIntoChunks(String text, int chunkSize, int overlap) {
        String normalized = normalizeText(text);
        if (!StringUtils.hasText(normalized)) {
            return List.of();
        }
        if (normalized.length() <= chunkSize) {
            return List.of(normalized);
        }
        // 按句读切句，保留句末标点，避免把句子拦腰截断
        List<String> sentences = new ArrayList<>();
        for (String s : PAGE_BREAK.split(normalized)) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) {
                sentences.add(trimmed);
            }
        }
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String sentence : sentences) {
            // 单句超长（无句读的连续文本）时硬切，保证一定有进展
            if (sentence.length() > chunkSize) {
                if (!current.isEmpty()) {
                    chunks.add(current.toString());
                    current.setLength(0);
                }
                for (int i = 0; i < sentence.length(); i += chunkSize) {
                    chunks.add(sentence.substring(i, Math.min(sentence.length(), i + chunkSize)));
                }
                continue;
            }
            if (current.length() + sentence.length() + 1 > chunkSize && !current.isEmpty()) {
                chunks.add(current.toString());
                // 保留尾部 overlap 字符作为下一块的开头
                String prev = chunks.get(chunks.size() - 1);
                int tail = Math.min(overlap, prev.length());
                current.append(prev, prev.length() - tail, prev.length()).append('\n');
                if (current.length() >= chunkSize) {
                    chunks.add(current.toString());
                    current.setLength(0);
                }
            }
            current.append(sentence).append('\n');
        }
        if (!current.isEmpty()) {
            chunks.add(current.toString().trim());
        }
        return chunks;
    }

    /** 归一化：去页眉页脚 + 连续空白折叠为单空格 */
    public String normalizeText(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String noHeader = PAGE_HEADER.matcher(raw).replaceAll(" ");
        return WHITESPACE.matcher(noHeader).replaceAll(" ").trim();
    }

    /** 暴露配置给同包的 {@link ChunkSyncService}，避免重复注入 */
    public AiProperties properties() {
        return properties;
    }

    /** 追加带标签的段落，空值自动跳过 */
    private void appendSection(StringBuilder sb, String label, String text) {
        if (!StringUtils.hasText(text)) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append('\n');
        }
        sb.append(label).append('：').append(text.trim());
    }

    /** 取纯题干（去掉括号内的补充说明，用于标题冗余存储） */
    private String plainStem(String stem) {
        if (!StringUtils.hasText(stem)) {
            return "";
        }
        return stem.replaceAll("[（(].*?[)）]", "").trim();
    }

    private Long examCategoryOfSubject(Long subjectId) {
        if (subjectId == null) {
            return null;
        }
        Subject subject = subjectMapper.selectById(subjectId);
        return subject == null ? null : subject.getExamCategoryId();
    }

    private String upper(String value) {
        return value == null ? "" : value.toUpperCase();
    }

    private String truncate(String text, int max) {
        if (!StringUtils.hasText(text)) {
            return "未命名";
        }
        String t = text.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    /** MD5(content) 作为幂等判据（32 位小写十六进制） */
    public static String md5(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(content == null
                    ? new byte[0]
                    : content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // JDK 规范要求 MD5 必须存在，走到这里说明运行环境异常
            throw new IllegalStateException("MD5 不可用", e);
        }
    }

    // ==================================================================
    // 批量查询辅助（供 ChunkSyncService 使用）
    // ==================================================================

    public List<Question> approvedQuestions() {
        return questionMapper.selectList(Wrappers.<Question>lambdaQuery()
                .eq(Question::getAuditStatus, "APPROVED")
                .orderByAsc(Question::getId));
    }

    public List<Resource> approvedResources() {
        return resourceMapper.selectList(Wrappers.<Resource>lambdaQuery()
                .eq(Resource::getAuditStatus, "APPROVED")
                .orderByAsc(Resource::getId));
    }

    public List<KnowledgePoint> enabledKnowledgePoints() {
        return knowledgePointMapper.selectList(Wrappers.<KnowledgePoint>lambdaQuery()
                .eq(KnowledgePoint::getStatus, 1)
                .orderByAsc(KnowledgePoint::getId));
    }

    /** 取某知识点下的题目（按 ID 升序，最多 maxSnippets 条） */
    public List<Question> questionsOfKnowledge(Long knowledgeId, int maxSnippets) {
        List<Long> ids = questionMapper.selectQuestionIdsByKnowledgeId(knowledgeId);
        if (ids.isEmpty()) {
            return Collections.emptyList();
        }
        return questionMapper.selectList(Wrappers.<Question>lambdaQuery()
                .in(Question::getId, ids)
                .eq(Question::getAuditStatus, "APPROVED")
                .orderByAsc(Question::getId)
                .last("LIMIT " + Math.max(1, maxSnippets)));
    }
}
