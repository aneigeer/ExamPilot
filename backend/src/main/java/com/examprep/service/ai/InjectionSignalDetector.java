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
 * <p><b>为什么不改判定</b>（重要，别顺手改）：
 * <ol>
 *   <li>剥离/改写语料正文会污染「溯源可验证」这一核心承诺——用户点回来源时看到的应是原文；</li>
 *   <li>误判代价不对称：把正常的"请忽略上述错误选项"剥掉，会直接造成答案缺证据；</li>
 *   <li>现有指标（拒答率、引用有效率）都在当前行为下标定，加拦截会一次性打乱基线。</li>
 * </ol>
 * 真要拦截，应当另立设计（结构化分隔符 + system 声明 + 人工评审），不是在这里加正则。
 *
 * <p><b>扫描代价</b>：每条语料最多扫 {@link #MAX_SCAN_CHARS} 字符（语料块本就 ≤ 400 字，
 * 这里只是防止异常长文本拖慢链路），7 条正则、无回溯爆炸风险的模式，单次开销可忽略。
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

    /** 模式表：名称写清楚用途，便于日志聚合与后续调参 */
    private static final Pattern[] PATTERNS = {
            // 中文「忽略/无视 + 以上/上述 + 规则/指令」
            Pattern.compile("(?:忽略|无视|忘记|忘掉|跳过)[^。\\n]{0,12}(?:以上|上述|前面|之前|上面)"
                    + "[^。\\n]{0,12}(?:规则|指令|要求|提示|设定|约束)"),
            // 英文 ignore (all) previous instructions
            Pattern.compile("(?i)ignore\\s+(?:all\\s+)?(?:the\\s+)?(?:previous|above|prior)\\s+"
                    + "(?:instruction|prompt|rule)s?"),
            // 「以上资料/规则作废、失效」
            Pattern.compile("(?:以上|上述)[^。\\n]{0,8}(?:作废|失效|无效|不再适用|取消)"),
            // 中文「输出/复述/告诉我 + 系统提示词」
            Pattern.compile("(?:输出|打印|复述|告诉|显示|泄露|重复|回显)[^。\\n]{0,10}"
                    + "(?:系统提示|系统指令|提示词|初始指令|prompt)", Pattern.CASE_INSENSITIVE),
            // 英文 reveal/print/repeat + system prompt
            Pattern.compile("(?i)(?:reveal|print|repeat|show|output|leak|echo)[^\\n]{0,20}"
                    + "(?:system\\s*prompt|initial\\s*prompt|your\\s*prompt|instructions?)"),
            // 角色劫持
            Pattern.compile("(?i)(?:你现在是|从现在开始你是|现在开始你扮演|假装你是|you\\s+are\\s+now"
                    + "|act\\s+as\\s+(?:a|an|the))"),
            // 越狱相关
            Pattern.compile("(?i)(?:越狱|jailbreak|开发者模式已开启|\\bDAN\\s*模式\\b)"),
    };

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
        for (Pattern pattern : PATTERNS) {
            var matcher = pattern.matcher(scoped);
            while (matcher.find()) {
                collected.add(new Signal(patternName(pattern), matcher.start(), brief(matcher.group())));
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

    /** 模式名：用正则前缀做稳定标识，避免额外维护一张名字表 */
    private static String patternName(Pattern pattern) {
        String src = pattern.pattern();
        if (src.startsWith("(?i)ignore")) {
            return "IGNORE_PREVIOUS_EN";
        }
        if (src.startsWith("(?i)(?:reveal")) {
            return "REVEAL_PROMPT_EN";
        }
        if (src.startsWith("(?i)(?:你现在是")) {
            return "ROLE_HIJACK";
        }
        if (src.startsWith("(?i)(?:越狱")) {
            return "JAILBREAK";
        }
        if (src.startsWith("(?:忽略")) {
            return "IGNORE_PREVIOUS";
        }
        if (src.startsWith("(?:以上")) {
            return "OVERRIDE_RULES";
        }
        return "REVEAL_PROMPT";
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
