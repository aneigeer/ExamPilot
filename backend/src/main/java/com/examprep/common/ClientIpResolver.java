package com.examprep.common;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * 客户端真实 IP 解析 —— <b>限流分桶的唯一可信来源</b>。
 *
 * <h2>为什么需要它（阻断项 B：XFF 可伪造绕过限流）</h2>
 * 旧实现（{@code RateLimitFilter.clientIp}）无条件取 {@code X-Forwarded-For} 的第一个逗号段，
 * 没有代理白名单。后果：
 * <ul>
 *   <li>客户端自己带一个 {@code X-Forwarded-For: 1.2.3.4}，就能落到新桶，<b>打满 429 后换个假 IP 立刻 200</b>
 *       （第 9 周已实测）；</li>
 *   <li>即使 nginx 用 {@code $proxy_add_x_forwarded_for} 追加，它也是把客户端自带的值拼在最前面，
 *       后端取「第一段」拿到的仍是攻击者可控串。</li>
 * </ul>
 * 正确做法只有一条：<b>只有当 TCP 对端本身就是我们自己的代理时，才采信它写的 XFF</b>。
 *
 * <h2>解析规则（严格按此顺序）</h2>
 * <ol>
 *   <li>{@code remoteAddr} 为空 → 返回空串（调用方拼 key 不会 NPE）；</li>
 *   <li>{@code trustedProxies} 为空/为 null，或 {@code remoteAddr} <b>不在</b>可信代理内 →
 *       <b>直接返回 remoteAddr，完全忽略 XFF</b>（这是本修复的核心：直连 8080 的伪造头一律无效）；</li>
 *   <li>{@code remoteAddr} 属可信代理且 XFF 非空 → 取第一个逗号段（去空白），
 *       <b>通过 IPv4/IPv6 合法性校验才采信</b>，非法（如 {@code unknown}、{@code 999.1.1.1}）回退 {@code remoteAddr}；</li>
 *   <li>其余情况一律回退 {@code remoteAddr}。</li>
 * </ol>
 *
 * <p><b>为什么校验 IP 字面量</b>：代理配置写错（如把客户端头透传）时，若什么串都收，
 * 攻击者仍可用任意字符串换桶。校验失败一律回退到真实 TCP 地址，是「错也要错在安全侧」。
 *
 * <h2>可信代理的匹配方式</h2>
 * 支持三种写法（逗号分隔，见 {@code app.rate-limit.trusted-proxies}）：
 * <ul>
 *   <li>精确 IPv4 / IPv6：{@code 127.0.0.1}、{@code ::1}；</li>
 *   <li>IPv4 CIDR：{@code 172.18.0.0/16} —— Docker 编排下后端看到的对端是 nginx <b>容器</b> IP，
 *       宿主回环地址匹配不到，必须按容器网段配置；</li>
 *   <li>IPv6 比较做规范化（{@code ::1} 与 {@code 0:0:0:0:0:0:0:1} 视为同一个），
 *       因为不同 JDK/OS 上报 IPv6 回环的写法并不一致。</li>
 * </ul>
 *
 * <p>纯静态、无 Spring 依赖，便于直接单测；所有方法对 null 安全，不抛异常。
 */
public final class ClientIpResolver {

    /** IPv4 字面量最大长度（255.255.255.255 = 15） */
    private static final int MAX_IPV4_LENGTH = 15;

    /** IPv6 字面量最大长度（含内嵌 IPv4 的 45 字符，见 RFC 4291） */
    private static final int MAX_IPV6_LENGTH = 45;

    /** IPv6 的组数 */
    private static final int IPV6_GROUPS = 8;

    private ClientIpResolver() {
        // 工具类禁止实例化
    }

    /**
     * 解析客户端真实 IP。
     *
     * @param remoteAddr     TCP 对端地址（{@code HttpServletRequest#getRemoteAddr()}），不可伪造
     * @param forwardedFor   请求头 {@code X-Forwarded-For} 原值，可能为 null/空白/多段/垃圾
     * @param trustedProxies 可信代理列表（IP 或 IPv4 CIDR）；null/空表示「不信任任何代理」
     * @return 非 null 的 IP 串；无法确定时返回规范化后的 remoteAddr（可能为空串）
     */
    public static String resolve(String remoteAddr, String forwardedFor, Collection<String> trustedProxies) {
        String remote = normalizeAddress(remoteAddr);
        // ① 没有对端地址，或没有任何可信代理配置 → XFF 一律不可信
        if (remote.isEmpty() || trustedProxies == null || trustedProxies.isEmpty()) {
            return remote;
        }
        // ② 对端不是我们自己的代理 → 忽略 XFF（修复点：直连客户端伪造头在此被丢弃）
        if (!isTrustedProxy(remote, trustedProxies)) {
            return remote;
        }
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return remote;
        }
        // ③ 对端可信：取第一个逗号段，且必须像 IP 才采信
        int comma = forwardedFor.indexOf(',');
        String first = (comma >= 0 ? forwardedFor.substring(0, comma) : forwardedFor).trim();
        return isValidIp(first) ? first : remote;
    }

    /**
     * 判断是否为合法 IP 字面量（IPv4 或 IPv6）。
     *
     * @param ip 待校验串，可为 null
     * @return true 表示形如合法 IPv4/IPv6
     */
    public static boolean isValidIp(String ip) {
        return isValidIpv4(ip) || isValidIpv6(ip);
    }

    /**
     * 判断是否为合法 IPv4 字面量。
     *
     * <p>拒绝前导零（{@code 010.0.0.1}）与越界段（{@code 999.1.1.1}）：
     * 这类写法只可能来自伪造头，按非法处理并回退到真实地址。
     *
     * @param ip 待校验串，可为 null
     * @return true 表示形如 a.b.c.d 且每段 0-255
     */
    public static boolean isValidIpv4(String ip) {
        if (ip == null) {
            return false;
        }
        String s = ip.trim();
        if (s.isEmpty() || s.length() > MAX_IPV4_LENGTH) {
            return false;
        }
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (!isDigit(part.charAt(i))) {
                    return false;
                }
            }
            if (part.length() > 1 && part.charAt(0) == '0') {
                return false; // 前导零：歧义写法，按非法处理
            }
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断是否为合法 IPv6 字面量（支持 {@code ::} 压缩、内嵌 IPv4、{@code %zone} 后缀）。
     *
     * @param ip 待校验串，可为 null
     * @return true 表示形如合法 IPv6
     */
    public static boolean isValidIpv6(String ip) {
        if (ip == null) {
            return false;
        }
        String s = ip.trim();
        if (s.length() < 2 || s.length() > MAX_IPV6_LENGTH) {
            return false;
        }
        int zone = s.indexOf('%');
        if (zone >= 0) {
            s = s.substring(0, zone);
        }
        if (s.indexOf(':') < 0) {
            return false;
        }
        if ("::".equals(s)) {
            return true;
        }
        if (s.contains(":::")) {
            return false;
        }
        int dc = s.indexOf("::");
        if (dc >= 0 && s.indexOf("::", dc + 1) >= 0) {
            return false; // 至多一个 "::"

        }
        String head = dc >= 0 ? s.substring(0, dc) : s;
        String tail = dc >= 0 ? s.substring(dc + 2) : "";
        List<String> headGroups = parseIpv6Groups(head);
        List<String> tailGroups = parseIpv6Groups(tail);
        if (headGroups == null || tailGroups == null) {
            return false;
        }
        int known = headGroups.size() + tailGroups.size();
        // 压缩形式允许省略至少 1 组；非压缩形式必须正好 8 组
        return dc >= 0 ? known <= IPV6_GROUPS - 1 : known == IPV6_GROUPS;
    }

    /**
     * 判断某个对端地址是否属于可信代理集合。
     *
     * @param remoteAddr  已规范化的对端地址
     * @param trusted     可信代理配置（IP 或 IPv4 CIDR）
     * @return true 表示可信
     */
    private static boolean isTrustedProxy(String remoteAddr, Collection<String> trusted) {
        String remoteKey = canonicalize(remoteAddr);
        for (String rawEntry : trusted) {
            if (rawEntry == null) {
                continue;
            }
            String entry = rawEntry.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int slash = entry.indexOf('/');
            if (slash > 0) {
                if (matchesIpv4Cidr(remoteAddr, entry)) {
                    return true;
                }
            } else if (canonicalize(entry).equals(remoteKey)) {
                return true;
            }
        }
        return false;
    }

    /**
     * IPv4 CIDR 匹配（如 {@code 172.18.0.0/16}）。IPv6 与非法写法一律不匹配。
     *
     * @param ip   对端地址
     * @param cidr 形如 {@code a.b.c.d/0-32} 的网段
     * @return true 表示命中该网段
     */
    private static boolean matchesIpv4Cidr(String ip, String cidr) {
        int slash = cidr.indexOf('/');
        String network = cidr.substring(0, slash).trim();
        String prefixText = cidr.substring(slash + 1).trim();
        long prefix;
        try {
            prefix = Long.parseLong(prefixText);
        } catch (NumberFormatException e) {
            return false;
        }
        long addrBits = ipv4ToLong(ip);
        long networkBits = ipv4ToLong(network);
        if (addrBits < 0 || networkBits < 0 || prefix < 0 || prefix > 32) {
            return false;
        }
        if (prefix == 0) {
            return true;
        }
        long mask = (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
        return (addrBits & mask) == (networkBits & mask);
    }

    /**
     * IPv4 字面量转 32 位无符号整数。
     *
     * @param ip IPv4 字面量
     * @return 0..4294967295；非法返回 -1
     */
    private static long ipv4ToLong(String ip) {
        if (!isValidIpv4(ip)) {
            return -1L;
        }
        String[] parts = ip.trim().split("\\.", -1);
        return (Long.parseLong(parts[0]) << 24)
                | (Long.parseLong(parts[1]) << 16)
                | (Long.parseLong(parts[2]) << 8)
                | Long.parseLong(parts[3]);
    }

    /**
     * 规范化地址用于比较：去空白、去 {@code []}、去 {@code %zone}、IPv6 展开为 8 组小写无前导零。
     *
     * <p>例：{@code ::1} 与 {@code 0:0:0:0:0:0:0:1} 规范化后相等；
     * {@code FE80::1%eth0} → {@code fe80:0:0:0:0:0:0:1}。
     *
     * @param ipRaw 原始地址串，可为 null
     * @return 规范化后的串；无法解析时返回小写去空白原串
     */
    static String canonicalize(String ipRaw) {
        String ip = normalizeAddress(ipRaw);
        if (ip.indexOf(':') < 0) {
            return ip;
        }
        String lower = ip.toLowerCase(Locale.ROOT);
        int dc = lower.indexOf("::");
        String head = dc >= 0 ? lower.substring(0, dc) : lower;
        String tail = dc >= 0 ? lower.substring(dc + 2) : "";
        List<String> headGroups = parseIpv6Groups(head);
        List<String> tailGroups = parseIpv6Groups(tail);
        if (headGroups == null || tailGroups == null) {
            return lower;
        }
        int known = headGroups.size() + tailGroups.size();
        List<String> all = new ArrayList<>(IPV6_GROUPS);
        all.addAll(headGroups);
        if (dc >= 0) {
            for (int i = known; i < IPV6_GROUPS; i++) {
                all.add("0");
            }
        }
        all.addAll(tailGroups);
        return String.join(":", all);
    }

    /**
     * 拆解一段 IPv6 文本为「组」列表；内嵌 IPv4 记为两组。
     *
     * @param part 不含 {@code ::} 的一段（可为空串）
     * @return 组列表；非法返回 null
     */
    private static List<String> parseIpv6Groups(String part) {
        List<String> groups = new ArrayList<>(IPV6_GROUPS);
        if (part == null || part.isEmpty()) {
            return groups;
        }
        String[] segments = part.split(":", -1);
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (segment.isEmpty()) {
                return null;
            }
            if (segment.indexOf('.') >= 0) {
                // 内嵌 IPv4 只能出现在该段的最后
                if (i != segments.length - 1 || !isValidIpv4(segment)) {
                    return null;
                }
                String[] v4 = segment.split("\\.", -1);
                int high = Integer.parseInt(v4[0]) * 256 + Integer.parseInt(v4[1]);
                int low = Integer.parseInt(v4[2]) * 256 + Integer.parseInt(v4[3]);
                groups.add(Integer.toHexString(high));
                groups.add(Integer.toHexString(low));
            } else {
                if (segment.length() > 4) {
                    return null;
                }
                for (int k = 0; k < segment.length(); k++) {
                    if (Character.digit(segment.charAt(k), 16) < 0) {
                        return null;
                    }
                }
                groups.add(stripLeadingZeros(segment.toLowerCase(Locale.ROOT)));
            }
        }
        return groups;
    }

    /** 去掉前导零但保留单个 "0" */
    private static String stripLeadingZeros(String hex) {
        int i = 0;
        while (i < hex.length() - 1 && hex.charAt(i) == '0') {
            i++;
        }
        String trimmed = hex.substring(i);
        return trimmed.isEmpty() ? "0" : trimmed;
    }

    /** 去空白、去一层 {@code []}、去 {@code %zone}；null → 空串 */
    private static String normalizeAddress(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.length() > 2 && s.charAt(0) == '[' && s.charAt(s.length() - 1) == ']') {
            s = s.substring(1, s.length() - 1).trim();
        }
        int zone = s.indexOf('%');
        if (zone > 0) {
            s = s.substring(0, zone);
        }
        return s;
    }

    /** ASCII 数字判断（不用 Character.isDigit，避免接受阿拉伯-印度数字等非 ASCII 数字） */
    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
