package com.guanghe.fs.system.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IP 名单条目解析与匹配：白/黑名单的判定边界，错一条就可能放行不该放行的地址。
 * <p>
 * 覆盖：CIDR 边界、协议族隔离、IPv6 压缩与内嵌 IPv4、映射地址展开、非法条目一律拒绝解析。
 */
class IpRuleMatcherTest {

    @Test
    void exactIpv4MatchesOnlyItself() {
        IpRuleMatcher.IpNet rule = IpRuleMatcher.parseRule("10.0.0.7");
        assertNotNull(rule);
        assertEquals(32, rule.prefixBits());
        assertTrue(matches("10.0.0.7", rule));
        assertFalse(matches("10.0.0.8", rule));
        assertFalse(matches("110.0.0.7", rule));
    }

    @Test
    void cidrCoversWholeSubnetAndStopsAtBoundary() {
        IpRuleMatcher.IpNet rule = IpRuleMatcher.parseRule("192.168.137.0/24");
        assertTrue(matches("192.168.137.1", rule));
        assertTrue(matches("192.168.137.255", rule));
        assertFalse(matches("192.168.138.0", rule));
        assertFalse(matches("192.167.137.9", rule));

        // 非 /24 的掩码按位比较，主机位可不为零
        IpRuleMatcher.IpNet wide = IpRuleMatcher.parseRule("10.128.3.7/10");
        assertTrue(matches("10.191.255.255", wide));
        assertFalse(matches("10.192.0.1", wide));
    }

    @Test
    void prefixZeroMatchesEveryAddressOfThatFamily() {
        IpRuleMatcher.IpNet allIpv4 = IpRuleMatcher.parseRule("0.0.0.0/0");
        assertNotNull(allIpv4);
        assertTrue(matches("8.8.8.8", allIpv4));
        assertTrue(matches("255.255.255.255", allIpv4));
        // 但够不到 IPv6：不会因为写了一条 v4 全网段就把 v6 也放进来
        assertFalse(matches("2001:db8::1", allIpv4));
    }

    @Test
    void differentProtocolFamilyNeverMatches() {
        assertFalse(matches("2001:db8::1", IpRuleMatcher.parseRule("10.0.0.1")));
        assertFalse(matches("10.0.0.1", IpRuleMatcher.parseRule("fe80::1/64")));
        assertFalse(matches(null, IpRuleMatcher.parseRule("10.0.0.1")));
        assertFalse(matches("10.0.0.1", null));
    }

    @Test
    void compressedIpv6AndCidr() {
        IpRuleMatcher.IpNet rule = IpRuleMatcher.parseRule("2001:db8::/32");
        assertTrue(matches("2001:0db8:abcd:0000:0000:0000:0000:0001", rule));
        assertFalse(matches("2001:db9::1", rule));

        IpRuleMatcher.IpNet exact = IpRuleMatcher.parseRule("::1");
        assertEquals(128, exact.prefixBits());
        assertTrue(matches("0:0:0:0:0:0:0:1", exact));
        assertFalse(matches("::2", exact));

        IpRuleMatcher.IpNet any = IpRuleMatcher.parseRule("::/0");
        assertTrue(matches("fe80::1", any));
        assertFalse(matches("10.0.0.1", any));
    }

    @Test
    void embeddedIpv4TailAndZoneIdentifier() {
        IpRuleMatcher.IpNet withTail = IpRuleMatcher.parseRule("64:ff9b::192.0.2.1");
        assertNotNull(withTail);
        assertTrue(matches("64:ff9b::192.0.2.1", withTail));
        assertFalse(matches("64:ff9b::192.0.3.1", withTail));

        // 链路本地地址的 %eth0 范围标识不参与匹配，去掉后仍按同一地址比较
        IpRuleMatcher.IpNet zoneless = IpRuleMatcher.parseRule("fe80::1%eth0");
        assertNotNull(zoneless);
        assertTrue(matches("fe80::1", zoneless));
    }

    @Test
    void mappedIpv6FormsUnwrapToIpv4() {
        // 对端上报 ::ffff:1.2.3.4 时，管理员写的 1.2.3.4 必须仍能命中
        IpRuleMatcher.IpNet rule = IpRuleMatcher.parseRule("1.2.3.4");
        assertTrue(matches("::ffff:1.2.3.4", rule));
        assertTrue(matches("::FFFF:0102:0304", rule));
        assertEquals(4, IpRuleMatcher.parseAddress("::ffff:1.2.3.4").length);
        // 只有 ffff 标记后的才是映射地址，不能把别的 v6 也当 v4
        assertEquals(16, IpRuleMatcher.parseAddress("2001:db8::1.2.3.4").length);
    }

    @Test
    void invalidEntriesFailToParse() {
        String[] bad = {
                null, "", "   ", "abc", "10.0.0", "10.0.0.0.0", "256.1.1.1", "10.0.0.999", "10.0.0.1.",
                "10.0.0.1/33", "10.0.0.1/", "10.0.0.1/abc", "10.0.0.1/-1", "10.0.0.1/1024",
                "1:2:3:4:5:6:7:8:9", "1:2::3:4:5:6:7:8", "::1::2", "1:2:3:4:5:6:7:8::",
                "10.0.0.0/8/8", "2001:db8::/129", "0000g::1", "1.2.3.4:80"
        };
        for (String entry : bad) {
            assertNull(IpRuleMatcher.parseRule(entry), "应判定为非法条目: " + entry);
        }
    }

    @Test
    void surroundingWhitespaceIsIgnored() {
        assertNotNull(IpRuleMatcher.parseRule("  10.0.0.1  "));
        assertTrue(matches("10.0.0.1", IpRuleMatcher.parseRule(" 10.0.0.1 ")));
    }

    @Test
    void normalizeExpandsIpv6Groups() {
        assertEquals("10.0.0.1", IpRuleMatcher.normalize("010.000.000.001"));
        assertEquals("2001:db8:0:0:0:0:0:1", IpRuleMatcher.normalize("2001:db8::1"));
        // 非法输入原样返回，日志不会因为一条坏配置再抛异常
        assertEquals("not-an-ip", IpRuleMatcher.normalize("not-an-ip"));
        assertEquals("", IpRuleMatcher.normalize(null));
    }

    private static boolean matches(String client, IpRuleMatcher.IpNet rule) {
        return IpRuleMatcher.matches(IpRuleMatcher.parseAddress(client), rule);
    }
}
