package com.examprep.service.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * P3：提示注入「疑似指令」侦测器 —— <b>只做观测，不改判定</b>
 *
 * <p><b>它解决什么</b>：阶段二验收项 A5（语料内藏指令）目前的结论是「样本未突破，但缺
 * 结构性隔离、缺任何告警」（基线缺口 G2/G4）。这一版只补 <b>G4 的观测面</b>：
 * 语料正文里出现「忽略以上规则」「输出系统提示词」这类模式时，打一条 WARN 日志，
 * 让运维/答辩前巡检能看见"这批课件里有可疑片段"，<b>而不是</b>去改变问答行为。
 *
 * <p><b>覆盖范围（别扩大解释）</b>：本检测器由 {@link PromptBuilder#userPrompt} 调用，
 * 因此只覆盖 <b>RAG 生成链路</b>（{@code /api/ai/ask} 与 {@code /internal/agent/answer} 都经过它）。
 * <b>Wiki 热层命中不经过 {@code userPrompt()} </b>（{@code AiQaService} 提前 return 预编译页），
 * 所以「注入文本藏在 Wiki 页正文里」当前<b>不会</b>被告警 —— 对外只能声称
 * 「RAG 生成链路具备注入告警」，不能说「全链路」。
 *
 * <p><b>为什么不改判定</b>（重要，别顺手改）：
 * <ol>
 *   <li>剥离/改写语料正文会污染「溯源可验证」这一核心承诺——用户点回来源时看到的应是原文；</li>
 *   <li>误判代价不对称：把正常的"请忽略上述错误选项"剥掉，会直接造成答案缺证据；</li>
 *   <li>现有指标（拒答率、引用有效率）都在当前行为下标定，加拦截会一次性打乱基线。</li>
 * </ol>
 * 真要拦截，应当另立设计（结构化分隔符 + system 声明 + 人工评审），不是在这里加正则。
 *
 * <p><b>误报控制（设计要点，改模式前必读）</b>：英文技术课件里
 * {@code act as a buffer/cache/proxy}、{@code you are now able to…} 是<b>常态表述</b>
 * （本库 COURSEWARE 有近万块，英文文档不少）。如果把这些宽模式直接当信号，告警会被淹没，
 * 等于没有告警。因此 {@code ROLE_HIJACK_EN} 采用<b>共现判据</b>：必须同时出现
 * {@code instruction / prompt / admin / assistant / unrestricted / restriction / jailbreak}
 * 这类"劫持语义词"才算命中。宁可漏几条英文角色劫持，也不能让中文主线告警失真。
 *
 * @see PromptBuilder#userPrompt(String, List) 调用点（命中后只打日志）
 */
public final class InjectionSignalDetector {

    /** 单条文本最多扫描的字符数（防异常长文本） */
    private static final int MAX_SCAN_CHARS = 4000;

    /** 单条文本最多返回的命中数（避免日志刷屏） */
    private static final int MAX_HITS = 5;

    /** 采集上限：先收集再排序截断，防止病态输入产生过多临时对象 */
    private static final int COLLECT_LIMIT = 50;

    /** 一条命中：模式名 + 命中位置 + 命中原串（截断） */
    public record Signal(String name, int index, String matched) {
        @Override
        public String toString() {
            return name + "@" + index + "(" + matched + ")";
        }
    }

    /**
     * 模式与名字绑定在一起（{@code record}），避免"改正则忘了改名"导致日志聚合错配
     *
     * <p>早期实现用正则前缀反查名字（{@code startsWith} 一串 if），改一条模式的写法就会
     * 静默张冠李戴，而日志聚合全靠这个名字 —— 名字必须和正则写在同一个字面量里。
     */
    private record Probe(String name, Pattern pattern) {
    }

    /** 模式表：名称写清楚用途，便于日志聚合与后续调参 */
    private static final List<Probe> PROBES = List.of(
            // 1. 中文「忽略/无视 + 以上/上述 + 规则/指令」
            new Probe("IGNORE_PREVIOUS", Pattern.compile(
                    "(?:忽略|无视|忘记|忘掉|跳过)[^。\\n]{0,12}(?:以上|上述|前面|之前|上面)"
                            + "[^。\\n]{0,12}(?:规则|指令|要求|提示|设定|约束)")),
            // 2. 英文 ignore (all) previous instructions
            new Probe("IGNORE_PREVIOUS_EN", Pattern.compile(
                    "(?i)ignore\\s+(?:all\\s+)?(?:the\\s+)?(?:previous|above|prior)\\s+"
                            + "(?:instruction|prompt|rule)s?")),
            // 3. 「以上资料/规则作废、失效」
            new Probe("OVERRIDE_RULES", Pattern.compile(
                    "(?:以上|上述)[^。\\n]{0,8}(?:作废|失效|无效|不再适用|取消)")),
            // 4. 中文「输出/复述 + 系统提示词」
            new Probe("REVEAL_PROMPT", Pattern.compile(
                    "(?:输出|打印|复述|告诉|显示|泄露|重复|回显)[^。\\n]{0,10}"
                            + "(?:系统提示|系统指令|提示词|初始指令|prompt)")),
            // 5. 英文 reveal/print + system prompt
            new Probe("REVEAL_PROMPT_EN", Pattern.compile(
                    "(?i)(?:reveal|print|repeat|show|output|leak|echo)[^\\n]{0,20}"
                            + "(?:system\\s*prompt|initial\\s*prompt|your\\s*prompt|instructions?)")),
            // 6. 中文角色劫持：★ 分"有锚点"与"裸短语"两类（2026-10-31 两轮取证后的定稿）
            //    · 有锚点（开始/新身份/扮演/假装）语义已足够强，直接命中；
            //    · 裸短语（你现在是 / 现在你是 / 你不再是）在中文题干里是**正常情景题**写法：
            //      「现在你是项目经理，项目延期 3 天，你怎么排期？」—— 这类不是注入。
            //      因此裸短语必须与"劫持语义词"共现才算（与英文 ROLE_HIJACK_EN 对称）。
            new Probe("ROLE_HIJACK", Pattern.compile(
                    "(?:从现在开始你是|现在开始你是|现在开始你扮演|你现在扮演|你的新身份是|假装你是"
                            + "|(?:你现在是|现在你是|你不再是)[^。\\n]{0,20}?"
                            + "(?:限制|规则|约束|不受|无限制|没有任何|管理员|助手|越狱|开发者模式"
                            + "|只服从|无条件))")),
            // 7. 英文角色劫持：★ 共现判据（见类注释"误报控制"）—— act as a buffer/cache/proxy 不算
            new Probe("ROLE_HIJACK_EN", Pattern.compile(
                    "(?i)(?:you\\s+are\\s+now|act\\s+as)\\b[^\\n]{0,40}?"
                            + "\\b(?:instruction|prompt|admin|assistant|unrestricted"
                            + "|restriction|without\\s+limits|no\\s+limits|jailbreak)")),
            // 8. 越狱相关
            new Probe("JAILBREAK", Pattern.compile(
                    "(?i)(?:越狱|jailbreak|开发者模式已开启|\\bDAN\\s*模式\\b)"))
    );

    private InjectionSignalDetector() {
        // 工具类
    }

    /**
     * 侦测疑似指令。
     *
     * <p>命中先按位置排序再截断到 {@link #MAX_HITS} 条 —— 保证返回的是"文中最早出现的几条"，
     * 而不是"模式表里排在前面的模式"。日志与巡检都按位置读，顺序必须稳定。
     *
     * @param text 待扫描文本（可为 null）
     * @return 命中列表，按位置升序；≤ {@link #MAX_HITS} 条；无命中返回空列表（不为 null）
     */
    public static List<Signal> detect(String text) {
        if (text == null || text.isBlank()) {
            return new ArrayList<>();
        }
        String scoped = text.length() > MAX_SCAN_CHARS ? text.substring(0, MAX_SCAN_CHARS) : text;
        List<Signal> collected = new ArrayList<>();
        for (Probe probe : PROBES) {
            var matcher = probe.pattern().matcher(scoped);
            while (matcher.find()) {
                collected.add(new Signal(probe.name(), matcher.start(), brief(matcher.group())));
                if (collected.size() >= COLLECT_LIMIT) {
                    break;
                }
            }
            if (collected.size() >= COLLECT_LIMIT) {
                break;
            }
        }
        collected.sort((a, b) -> Integer.compare(a.index(), b.index()));
        return collected.size() <= MAX_HITS
                ? collected
                : new ArrayList<>(collected.subList(0, MAX_HITS));
    }

    /**
     * 是否含疑似指令（日志聚合用的快捷方法）。
     *
     * @param text 待扫描文本
     * @return true 表示命中至少一条模式
     */
    public static boolean suspicious(String text) {
        return !detect(text).isEmpty();
    }

    /**
     * 压平空白并截断（用于日志里的命中串与 UGC 标题）。
     *
     * <p>标题来自用户上传的课件，<b>可能很长，也可能含换行</b>：
     * 换行会伪造出一条日志行（log injection），把告警日志的可信度毁掉。
     * 所有写进日志的 UGC 文本都必须先过这里。
     *
     * @param text 原文
     * @return 单行、≤40 字符（超出以 … 结尾）
     */
    public static String briefForLog(String text) {
        return brief(text);
    }

    /** 命中原串截断，避免把整段课件写进日志（数据最小化） */
    private static String brief(String matched) {
        if (matched == null) {
            return "";
        }
        String flat = matched.replaceAll("\\s+", " ").trim();
        return flat.length() <= 40 ? flat : flat.substring(0, 40) + "…";
    }
}
