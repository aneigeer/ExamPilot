package com.examprep.service.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 模块配置（{@code app.ai.*}）
 *
 * <p><b>设计约束</b>：全部参数化，使用时可现场改一个数看效果；
 * 密钥类配置默认值留空（对齐 {@code app.jwt.secret} 的安全规范），
 * 空值不报错、走降级链路。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.ai")
public class AiProperties {

    /** 大模型客户端配置 */
    private Llm llm = new Llm();

    /** FastAPI AI 微服务配置（T09） */
    private FastApi fastapi = new FastApi();

    /** 向量检索配置（T09） */
    private Vector vector = new Vector();

    /** 检索层配置 */
    private Retrieval retrieval = new Retrieval();

    /** 学情画像配置 */
    private Profile profile = new Profile();

    /** 生成配置 */
    private Prompt prompt = new Prompt();

    /** 语料抽取配置 */
    private Knowledge knowledge = new Knowledge();

    /**
     * 对象存储配置（T10）
     *
     * <p><b>铁律：上传永远不能失败。</b>MinIO 不可用时自动落本地磁盘，
     * 使用照常上传。默认值全部走本地，因此 T10 未启用时行为与现在完全一致。
     */
    private Storage storage = new Storage();

    @Data
    public static class Storage {

        /** 是否启用 MinIO。false = 纯本地磁盘（当前默认，行为不变） */
        private boolean enabled = false;

        /** 提供方标识：minio / local（local 为降级值） */
        private String provider = "local";

        /** MinIO 端点 */
        private String endpoint = "http://127.0.0.1:9000";

        /** 访问密钥（默认留空，空值即视为未配置 → 走本地） */
        private String accessKey = "";

        /** 私有密钥（默认留空） */
        private String secretKey = "";

        /** 桶名 */
        private String bucket = "exam-prep";

        /** 启动时自动建桶（幂等） */
        private boolean autoCreateBucket = true;

        /** 连接 / 读超时（毫秒） */
        private int timeoutMillis = 3000;

        /** 连续失败多少次熔断 */
        private int circuitBreakerThreshold = 3;

        /** 熔断持续时间（毫秒） */
        private long circuitBreakerOpenMillis = 60_000L;

        /**
         * 双写开关（迁移期同时写本地）
         *
         * <p>true = 新文件同时落 MinIO 与本地（安全但冗余，使用一段时间后关掉）
         */
        private boolean dualWriteLocal = true;

        /** 路径前缀：MinIO 对象路径以此开头（与现有 stored_path 前缀区分） */
        public String schemePrefix() {
            return "minio://";
        }

        public boolean hasCredential() {
            return accessKey != null && !accessKey.isBlank()
                    && secretKey != null && !secretKey.isBlank();
        }
    }

    @Data
    public static class Llm {
        /** 客户端标识：dashscope / zhipu / extractive */
        private String provider = "extractive";

        /**
         * API Key。<b>默认留空</b>，为空时所有大模型客户端 available() 返回 false，
         * 自动走抽取式兜底，接口仍返回 200。
         */
        private String apiKey = "";

        /** 调用超时（毫秒） */
        private int timeoutMillis = 8000;

        /** 连续失败多少次后熔断 */
        private int circuitBreakerThreshold = 3;

        /** 熔断持续时间（毫秒） */
        private long circuitBreakerOpenMillis = 60_000L;

        /** 候选模型（按 provider 取对应项） */
        private String dashscopeModel = "qwen-plus";

        private String zhipuModel = "glm-4-flash";

        public boolean hasKey() {
            return apiKey != null && !apiKey.isBlank();
        }

        public String keyOrEmpty() {
            return hasKey() ? apiKey.trim() : "";
        }
    }

    /**
     * FastAPI AI 微服务（T09）
     *
     * <p><b>安全底线：{@code enabled=false} 或服务不可用时，整个向量通道静默跳过</b>，
     * 问答自动退化为 ngram 字面检索 + 抽取式生成，<b>接口仍返回 200</b>。
     * 使用场景即使 Docker 全停、Python 没装，系统功能不减。
     */
    @Data
    public static class FastApi {

        /** 向量通道总开关。false = 完全不调 FastAPI（省一次无谓的连接超时） */
        private boolean enabled = false;

        /** 服务地址（Python 侧 uvicorn 默认端口 8000） */
        private String baseUrl = "http://127.0.0.1:8000";

        /**
         * 路径前缀。
         * Python 侧把路由挂在 {@code /v1} 下（见 ai-service/app/routers/search.py 的
         * {@code @router.post("/v1/search")}），健康检查是 {@code GET /v1/health}。
         */
        private String pathPrefix = "/v1";

        /** 检索 / 索引同步超时（毫秒）。设计预算：向量检索阶段 1200ms */
        private int searchTimeoutMillis = 1200;

        /** 健康检查超时（毫秒） */
        private int healthTimeoutMillis = 1000;

        /** 索引推送超时（毫秒）。批量 upsert 比检索慢 */
        private int indexTimeoutMillis = 5000;

        /** 连续失败多少次熔断 */
        private int circuitBreakerThreshold = 3;

        /** 熔断持续时间（毫秒） */
        private long circuitBreakerOpenMillis = 60_000L;

        /** 健康检查缓存有效期（毫秒），避免每次问答都打一次 health */
        private long healthCacheMillis = 10_000L;

        /** 去掉尾部斜杠，避免与 pathPrefix 拼出双斜杠 */
        public String normalizedBaseUrl() {
            if (baseUrl == null || baseUrl.isBlank()) {
                return "http://127.0.0.1:8000";
            }
            String trimmed = baseUrl.trim();
            while (trimmed.endsWith("/")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            return trimmed;
        }

        /** 拼接完整路径，如 {@code /v1/search} */
        public String path(String endpoint) {
            String prefix = pathPrefix == null ? "" : pathPrefix.trim();
            while (prefix.endsWith("/")) {
                prefix = prefix.substring(0, prefix.length() - 1);
            }
            if (!endpoint.startsWith("/")) {
                endpoint = "/" + endpoint;
            }
            return prefix + endpoint;
        }
    }

    /**
     * 向量检索（T09）
     *
     * <p><b>与 ngram 通道的关系是「并存融合」而非替换</b>：
     * 向量负责语义召回（"内存不够用" → "动态分配内存"），
     * ngram 负责字面精确（专有名词、代码标识符、数字），
     * 两路去重融合后走同一套五因子重排。
     */
    @Data
    public static class Vector {

        /** 向量通道开关（与 fastapi.enabled 独立，便于只关检索不关索引同步） */
        private boolean enabled = false;

        /** 向量召回条数（与 ngram 通道的 recallLimit 同量级，便于公平融合） */
        private int recallLimit = 20;

        /**
         * 双通道加成：同一个 chunk 被<b>两路都命中</b>时的分数加成系数。
         *
         * <p>两路都命中说明该 chunk 既语义相关又字面相关，可信度更高——
         * 这等于免费得到「混合检索（RRF 思想的简化版）」。
         */
        private double dualHitBoost = 0.25;

        /**
         * 向量分归一化下限。
         *
         * <p>Qdrant 余弦相似度范围 [-1,1]，而 ngram 的 {@code ft_score} 是任意正数，
         * 两者<b>不同量纲不能直接相加</b>。统一映射到 [0,1] 再参与重排。
         */
        private double scoreFloor = -1.0;

        private double scoreCeiling = 1.0;

        /**
         * ★ 向量分在融合总权重中的占比（其余给 ngram 分）
         *
         * <p>两路分数量纲不同：ngram 的 {@code ft_score} 量级常在 10-40，
         * 向量余弦相似度在 [-1,1]。<b>必须各自归一化到 [0,1] 后再线性混合</b>，
         * 直接相加会让向量分被淹没。
         */
        private double scoreWeight = 0.5;
    }

    @Data
    public static class Retrieval {
        /** 召回条数（重排前的候选池大小） */
        private int recallLimit = 50;

        /** 最终返回给生成环节的块数 K */
        private int topK = 5;

        /** 拒答阈值：finalScore 低于此值直接拒答，不进生成环节（反幻觉第一道闸门） */
        private double rejectThreshold = 0.15;

        /** 查询串最大长度，超出截断，防止超长查询拖垮索引 */
        private int maxQueryLength = 100;

        /** term 最小长度，低于此值丢弃（ngram_token_size 默认 2，单字成不了词） */
        private int minTermLength = 2;

        /**
         * ★ 词项覆盖率下限（bigram OR 模式的精度补偿）
         *
         * <p>OR 语义是「任一 bigram 命中即召回」，精度低。实测「量子力学波函数如何求解」
         * 会因命中 bigram「函数」被误召回，因此要求「命中 bigram 数 / 查询 bigram 总数」
         * 不低于此值，低于则剔除。
         *
         * <p><b>0.25 的来历（实测标定）</b>：
         * <ul>
         *   <li>无关问题「量子力学波函数如何求解」（11 bigram）只命中「函数」→ 覆盖率 0.09 → 剔除</li>
         *   <li>相关问题「值传递和地址传递有什么区别」（12 bigram）命中 5 个 → 覆盖率 0.42 → 保留</li>
         *   <li>相关问题「动态分配内存的函数」（8 bigram）命中 5 个 → 覆盖率 0.63 → 保留</li>
         * </ul>
         * 低于 0.2 会漏掉真实的口语化提问，高于 0.3 会误杀边界case，故取 0.25。
         */
        private double minTermCoverage = 0.25;

        /**
         * ★ 向量通道相似度阈值（Qdrant 余弦相似度，范围 [-1,1]）
         *
         * <p><b>与 {@link #minTermCoverage} 分工不同，两道闸门各管一路：</b>
         * <pre>
         *   ngram 通道 → minTermCoverage（词项覆盖率）：字面检索，没有词面重叠就不该命中
         *   向量通道 → minVectorScore（相似度）：语义检索，用词面重叠评判是自相矛盾
         * </pre>
         * 「函数传参有哪些方式」这一题：ngram 覆盖率 0.125（字面确实该剔），
         * 但向量 score 0.5884（语义上就是同一件事，必须放行）。
         * <b>共用一道闸门会把向量唯一的优势一起吞掉</b>——那向量通道就没有存在意义了。
         *
         * <p><b>0.55 的实测标定依据</b>（Java + Python + Qdrant + bge 全链路在线，
         * 逐条 curl 打 {@code POST /v1/search} 取得原始分数）：
         * <pre>
         *   查询「函数传参有哪些方式」   0.6328 chunk24「函数定义与调用」
         *                              0.5884 chunk5 「值传递与地址传递」  ← 正确答案
         *                              0.5802 chunk23「函数」
         *   查询「值传递和地址传递…」   0.7235 chunk5（正确答案）
         *   查询「量子力学波函数如何求解」0.5126 chunk23「函数」  ← 噪声，必须拦
         *                              0.5079 / 0.4781 / 0.4737 …
         *   查询「量子纠缠实验怎么做」   0.4184 最高             ← 噪声，必须拦
         * </pre>
         * 无关问题的最高分是 <b>0.5126</b>，相关问题的最低有效分是 <b>0.5802</b>，
         * 故阈值取 <b>0.55</b>（落在两者之间，两侧余量 0.037 / 0.030）。
         *
         * <p><b>⚠️ 为什么不能只看「量子纠缠」那道题：</b>它的最高分只有 0.4184，
         * 用它标定会得到 0.45 这样的阈值——而 0.45 < 0.5126，
         * <b>「量子力学波函数如何求解」会漏过闸门并给出空壳答案</b>。
         * 这道题才是真正的难题（共享「函数」这个语义锚点），必须一起参与标定。
         *
         * <p><b>⚠️ 语料扩充后必须重新标定</b>：当前仅 42 个 chunk，
         * 余弦分数被压在 0.47~0.72 的窄带里，阈值余量只有 0.03 左右，很脆。
         * 语料扩充后分布会明显拉开，届时应重跑上面的标定表再定阈值。
         */
        private double minVectorScore = 0.55;

        /**
         * ★ 向量相似度<b>相对阈值</b>系数（自适应语料规模）
         *
         * <p>最终闸门 = {@code max(minVectorScore, ratio × 本次最高分)}。
         *
         * <p><b>为什么绝对阈值不够</b>：余弦分数的分布会随语料规模整体漂移。
         * 语料从 42 块扩到几千块后，同主题块的相似度普遍抬高，
         * 原来卡住的噪声会集体越过绝对阈值——闸门形同虚设。
         * 加上相对量之后，「不够像本次最高分的那条，差太远的一律不要」，
         * 无论分布整体抬高还是下降都有效。
         *
         * <p>0.8 的含义：低于本次最高分 80% 的候选一律剔除。
         * 语料少时由绝对阈值兜底（本项目 42 块时就是 0.55 生效），
         * 语料多时相对阈值自动接管。
         *
         * <p><b>使用说明</b>：「绝对阈值保底 + 相对阈值自适应语料规模，
         * 这样我们扩充语料后不需要重新调参。」
         */
        private double vectorScoreRatio = 0.8;

        /** 时间衰减底数：fresh = decayBase ^ ageDays */
        private double decayBase = 0.85;

        /** 标题命中权重 */
        private double titleWeight = 0.30;

        /** 知识点加权权重 */
        private double knowledgeWeight = 0.25;

        /** 时间衰减权重 */
        private double freshWeight = 0.15;

        /** 热度权重 */
        private double hotWeight = 0.10;

        /** 全文相关度权重 */
        private double fulltextWeight = 1.0;
    }

    @Data
    public static class Profile {
        /** 时间衰减半衰期（天）：decay = 0.5 ^ (Δdays / halfLifeDays) */
        private int halfLifeDays = 30;

        /** 难度权重基址：w = base + step * difficulty */
        private double difficultyWeightBase = 0.6;

        /** 难度权重步长 */
        private double difficultyWeightStep = 0.1;

        /** 错题本加权因子：wrongBoost = 1 + factor * MIN(wrong_count, cap) */
        private double wrongBoostFactor = 0.5;

        /** 错题次数上限（防止某题被反复做错导致权重爆炸） */
        private int wrongBoostCap = 3;

        /** 置信度饱和所需作答数：confidence = MIN(1, attempts / threshold) */
        private int confidenceThreshold = 3;

        /** 检索时薄弱知识点加权（对应 A.7 ③） */
        private double weakKpSearchBoost = 0.15;

        /** 冷启动判定：有效作答少于该值时走热门兜底 */
        private int coldStartAttempts = 3;
    }

    @Data
    public static class Prompt {
        /** 系统提示词：强制编号引用 + 禁止推测（对应 L2 防线） */
        private String systemTemplate = """
                你是一名备考助教。请严格依据用户提供的【参考资料】回答问题。

                硬性要求：
                1. 只允许依据 [1][2][3] 中的内容作答，每句话末尾必须标注来源编号，如 [1] 或 [1][2]。
                2. 若参考资料不足以回答，只输出「未找到相关资料」六个字。
                3. 禁止推测、禁止补充常识、禁止编造资料中不存在的结论。
                4. 回答使用简体中文，条理清晰，控制在 400 字以内。
                """;

        /** 温度（越小越稳，越不容易发散） */
        private double temperature = 0.2;

        /** 单次返回的最大 token 数 */
        private int maxTokens = 800;
    }

    @Data
    public static class Knowledge {
        /** 单块最大字符数 */
        private int chunkSize = 400;

        /** 相邻块重叠字符数（避免答案被切断） */
        private int overlap = 80;

        /** 单个来源最大块数（防超大 PDF 拖垮索引） */
        private int maxChunksPerSource = 200;

        /** 抽取式兜底：句子覆盖率阈值 */
        private double extractiveCoverageThreshold = 0.3;

        /** 抽取式兜底：最多保留几句 */
        private int extractiveMaxSentences = 5;

        /** 知识点块最多引用几条题目摘要 */
        private int knowledgeSnippetCount = 3;

        /** 用户问题最大长度 */
        private int maxQuestionLength = 200;

        /** 抽取式关键词停用词（避免「的」「是」这类高频虚词参与打分） */
        private List<String> stopWords = new ArrayList<>(List.of(
                "的", "了", "是", "在", "和", "与", "或", "及", "有", "吗", "呢", "吧",
                "请", "问", "什么", "怎么", "如何", "为什么", "哪些", "哪个", "一下",
                "介绍", "讲讲", "说说", "帮我", "我想", "想要", "可以", "能否", "需要"));
    }
}
