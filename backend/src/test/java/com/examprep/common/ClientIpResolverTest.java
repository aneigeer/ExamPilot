package com.examprep.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ClientIpResolver} 单测 —— 阻断项 B（XFF 可伪造绕过限流）的判定核心。
 *
 * <p><b>这组用例守的是什么</b>：限流分桶的 key 必须来自不可伪造的信息。
 * {@code X-Forwarded-For} 是客户端完全可控的请求头，只有在 TCP 对端本身就是我们的代理时才有意义。
 * 一旦这里判断错，攻击者打满 429 后换个假 IP 就能继续撞库（第 9 周已实测过这个绕过）。
 *
 * <p>用例覆盖：直连伪造 / 可信代理单段 / 多段 / 空白 / 非法 / IPv6（两种写法）/ 空可信列表 /
 * 空 remoteAddr / CIDR 网段 / 布尔边界。所有用例都断言「无法确定时回退真实 remoteAddr」，
 * 即错也错在安全侧。
 */
@DisplayName("客户端 IP 解析：XFF 仅对可信代理生效，非法值一律回退真实地址")
class ClientIpResolverTest {

    private static final List<String> LOOPBACK = List.of("127.0.0.1", "::1");

    @Test
    @DisplayName("① 直连（对端不在可信代理内）+ 伪造 XFF → 仍用真实 remoteAddr")
    void directClientSpoofingIsIgnored() {
        // 攻击者最常用的手法：自己带 X-Forwarded-For 换个桶
        assertEquals("203.0.113.7",
                ClientIpResolver.resolve("203.0.113.7", "10.9.9.9", LOOPBACK),
                "直连请求的 XFF 必须被完全忽略，否则限流可被绕过");
        assertEquals("203.0.113.7",
                ClientIpResolver.resolve("203.0.113.7", "1.1.1.1, 2.2.2.2", LOOPBACK));
    }

    @Test
    @DisplayName("② 回环代理 + 单段 XFF → 采信该客户端 IP")
    void trustedProxySingleValue() {
        assertEquals("198.51.100.23", ClientIpResolver.resolve("127.0.0.1", "198.51.100.23", LOOPBACK));
    }

    @Test
    @DisplayName("③ 回环代理 + 多段 XFF → 取第一个逗号段（去空白）")
    void trustedProxyMultipleValuesTakesFirst() {
        assertEquals("198.51.100.23",
                ClientIpResolver.resolve("127.0.0.1", " 198.51.100.23 , 10.0.0.1 , 10.0.0.2", LOOPBACK));
    }

    @Test
    @DisplayName("④ 回环代理 + 空白 / null XFF → 回退 remoteAddr")
    void blankForwardedForFallsBack() {
        assertEquals("127.0.0.1", ClientIpResolver.resolve("127.0.0.1", "   ", LOOPBACK));
        assertEquals("127.0.0.1", ClientIpResolver.resolve("127.0.0.1", "", LOOPBACK));
        assertEquals("127.0.0.1", ClientIpResolver.resolve("127.0.0.1", null, LOOPBACK));
    }

    @Test
    @DisplayName("⑤ 回环代理 + 非法 XFF（unknown / 越界 / 段数不对 / 前导零）→ 回退 remoteAddr")
    void illegalForwardedForFallsBack() {
        for (String illegal : new String[] {"unknown", "999.1.1.1", "1.2.3", "010.0.0.1", "1.2.3.4.5", ":::"}) {
            assertEquals("127.0.0.1", ClientIpResolver.resolve("127.0.0.1", illegal, LOOPBACK),
                    "非法 XFF 不得作为分桶 key，实际输入：" + illegal);
        }
    }

    @Test
    @DisplayName("⑥ IPv6 回环代理（两种写法都认）+ IPv6 客户端地址原样采信")
    void ipv6LoopbackAndClient() {
        // Tomcat/Linux 常把 ::1 报成 0:0:0:0:0:0:0:1，两种写法都必须识别为可信代理
        assertEquals("2001:db8::42",
                ClientIpResolver.resolve("::1", "2001:db8::42", LOOPBACK));
        assertEquals("2001:db8::42",
                ClientIpResolver.resolve("0:0:0:0:0:0:0:1", "2001:db8::42", LOOPBACK),
                "IPv6 回环的展开写法也必须命中可信代理，否则 nginx 场景会退化成共用桶");
    }

    @Test
    @DisplayName("⑦ 可信代理列表为空 / null → 一律用 remoteAddr（默认拒绝 XFF）")
    void emptyTrustedListIgnoresForwardedFor() {
        assertEquals("203.0.113.7", ClientIpResolver.resolve("203.0.113.7", "10.9.9.9", Set.of()));
        assertEquals("203.0.113.7", ClientIpResolver.resolve("203.0.113.7", "10.9.9.9", List.of()));
        assertEquals("127.0.0.1", ClientIpResolver.resolve("127.0.0.1", "10.9.9.9", null));
    }

    @Test
    @DisplayName("⑧ remoteAddr 为空 / null → 返回空串，不抛异常")
    void nullRemoteAddressIsSafe() {
        assertEquals("", ClientIpResolver.resolve(null, "10.9.9.9", LOOPBACK));
        assertEquals("", ClientIpResolver.resolve("", "10.9.9.9", LOOPBACK));
        assertEquals("", ClientIpResolver.resolve("  ", null, null));
    }

    @Test
    @DisplayName("⑨ CIDR：Docker 下后端看到的 nginx 容器 IP 必须显式按网段信任")
    void cidrTrustedProxy() {
        List<String> trusted = List.of("172.18.0.0/16");
        assertEquals("198.51.100.23",
                ClientIpResolver.resolve("172.18.0.5", "198.51.100.23", trusted),
                "同网段代理应被信任，取真实客户端 IP");
        assertEquals("172.19.0.5",
                ClientIpResolver.resolve("172.19.0.5", "198.51.100.23", trusted),
                "不同网段不得信任，回退 remoteAddr");
    }

    @Test
    @DisplayName("⑩ IP 字面量校验边界（isValidIp / IPv4 / IPv6）")
    void literalValidation() {
        assertTrue(ClientIpResolver.isValidIp("127.0.0.1"));
        assertTrue(ClientIpResolver.isValidIp("255.255.255.255"));
        assertTrue(ClientIpResolver.isValidIp("::1"));
        assertTrue(ClientIpResolver.isValidIp("2001:db8::42"));
        assertTrue(ClientIpResolver.isValidIp("::ffff:192.0.2.128"), "内嵌 IPv4 的 IPv6 合法");

        assertFalse(ClientIpResolver.isValidIp("256.0.0.1"));
        assertFalse(ClientIpResolver.isValidIp("1.2.3"));
        assertFalse(ClientIpResolver.isValidIp("01.2.3.4"), "前导零属歧义写法，按非法处理");
        assertFalse(ClientIpResolver.isValidIp("1.2.3.4/24"), "带掩码不是合法地址");
        assertFalse(ClientIpResolver.isValidIp("unknown"));
        assertFalse(ClientIpResolver.isValidIp(""));
        assertFalse(ClientIpResolver.isValidIp(null));
        assertFalse(ClientIpResolver.isValidIp("1:2:3:4:5:6:7:8:9"), "超过 8 组非法");
        assertFalse(ClientIpResolver.isValidIp("1::2::3"), "至多一个 ::");
    }
}
