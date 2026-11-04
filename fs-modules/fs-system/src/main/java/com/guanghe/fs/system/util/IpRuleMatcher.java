package com.guanghe.fs.system.util;

import java.util.Arrays;

/**
 * IP 名单条目解析与匹配：支持精确 IPv4 / IPv6 与 CIDR 网段（如 {@code 192.168.1.0/24}、{@code 2001:db8::/32}）。
 * <p>
 * 只做字面量解析，绝不走 DNS：名单是安全边界，若允许域名条目，一条反向解析记录就能把边界变成任意 IP。
 *
 * @Author: guangheUlti
 * @Date: 2026/11/4
 */
public final class IpRuleMatcher {

    private IpRuleMatcher() {
    }

    /**
     * 一条已解析的网段：前 prefixBits 位参与比较。
     * <p>
     * 精确条目就是掩码长度取满（IPv4 /32、IPv6 /128），与 CIDR 走同一套匹配代码。
     */
    public record IpNet(byte[] address, int prefixBits) {
    }

    /**
     * 解析名单条目（{@code IP} 或 {@code IP/掩码长度}）；非法返回 null
     */
    public static IpNet parseRule(String entry) {
        if (entry == null) {
            return null;
        }
        String text = entry.trim();
        if (text.isEmpty()) {
            return null;
        }
        int slash = text.indexOf('/');
        String addressPart = slash < 0 ? text : text.substring(0, slash);
        byte[] address = parseAddress(addressPart);
        if (address == null) {
            return null;
        }
        int maxBits = address.length * 8;
        if (slash < 0) {
            return new IpNet(address, maxBits);
        }
        Integer prefix = parsePrefix(text.substring(slash + 1), maxBits);
        if (prefix == null) {
            return null;
        }
        return new IpNet(address, prefix);
    }

    /**
     * 解析 IP 字面量：IPv4 得到 4 字节，IPv6 得到 16 字节；
     * {@code ::ffff:1.2.3.4} 这类映射地址统一展开成 4 字节，避免管理员写 IPv4、对端却报 IPv6 形式而漏匹配。
     *
     * @return 非法输入返回 null
     */
    public static byte[] parseAddress(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        byte[] ipv4 = parseIpv4(trimmed);
        if (ipv4 != null) {
            return ipv4;
        }
        byte[] ipv6 = parseIpv6(trimmed);
        return ipv6 == null ? null : unwrapMapped(ipv6);
    }

    /**
     * 客户端地址是否命中网段；协议族不同（4 字节 vs 16 字节）一律不匹配
     */
    public static boolean matches(byte[] client, IpNet rule) {
        if (client == null || rule == null || client.length != rule.address().length) {
            return false;
        }
        byte[] network = rule.address();
        int fullBytes = rule.prefixBits() / 8;
        for (int i = 0; i < fullBytes; i++) {
            if (client[i] != network[i]) {
                return false;
            }
        }
        int restBits = rule.prefixBits() % 8;
        if (restBits == 0) {
            return true;
        }
        int mask = 0xFF << (8 - restBits) & 0xFF;
        return (client[fullBytes] & mask) == (network[fullBytes] & mask);
    }

    /**
     * 归一化展示用的地址文本（展开 IPv6 的 {@code ::} 压缩），仅用于日志
     */
    public static String normalize(String ip) {
        byte[] parsed = parseAddress(ip);
        if (parsed == null) {
            return ip == null ? "" : ip.trim();
        }
        StringBuilder sb = new StringBuilder();
        if (parsed.length == 4) {
            for (int i = 0; i < 4; i++) {
                sb.append(i == 0 ? "" : ".").append(parsed[i] & 0xFF);
            }
            return sb.toString();
        }
        for (int i = 0; i < 8; i++) {
            sb.append(i == 0 ? "" : ":")
                    .append(Integer.toHexString(((parsed[2 * i] & 0xFF) << 8) | (parsed[2 * i + 1] & 0xFF)));
        }
        return sb.toString();
    }

    private static Integer parsePrefix(String text, int maxBits) {
        if (text.isEmpty() || text.length() > 3) {
            return null;
        }
        int value = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
            value = value * 10 + (c - '0');
        }
        return value > maxBits ? null : value;
    }

    private static byte[] unwrapMapped(byte[] ipv6) {
        for (int i = 0; i < 10; i++) {
            if (ipv6[i] != 0) {
                return ipv6;
            }
        }
        if (ipv6[10] != (byte) 0xFF || ipv6[11] != (byte) 0xFF) {
            return ipv6;
        }
        byte[] ipv4 = new byte[4];
        System.arraycopy(ipv6, 12, ipv4, 0, 4);
        return ipv4;
    }

    private static byte[] parseIpv4(String text) {
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] address = new byte[4];
        for (int i = 0; i < 4; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3) {
                return null;
            }
            int value = 0;
            for (int j = 0; j < part.length(); j++) {
                char c = part.charAt(j);
                if (c < '0' || c > '9') {
                    return null;
                }
                value = value * 10 + (c - '0');
            }
            if (value > 255) {
                return null;
            }
            address[i] = (byte) value;
        }
        return address;
    }

    private static byte[] parseIpv6(String input) {
        String text = input;
        // 链路本地地址会带 %eth0 之类的范围标识，匹配用不上，去掉后再解析
        int zone = text.indexOf('%');
        if (zone >= 0) {
            text = text.substring(0, zone);
        }
        if (text.indexOf(':') < 0) {
            return null;
        }
        int doubleColon = text.indexOf("::");
        boolean compressed = doubleColon >= 0;
        if (compressed && text.indexOf("::", doubleColon + 1) >= 0) {
            // 多个 :: 无法确定零组数量
            return null;
        }
        String headText = compressed ? text.substring(0, doubleColon) : text;
        String tailText = compressed ? text.substring(doubleColon + 2) : null;

        int[] head = toWords(headText);
        if (head == null) {
            return null;
        }
        int[] tail = null;
        if (compressed) {
            tail = toWords(tailText);
            if (tail == null) {
                return null;
            }
        }

        int[] groups = new int[8];
        if (!compressed) {
            if (head.length != 8) {
                return null;
            }
            System.arraycopy(head, 0, groups, 0, 8);
        } else {
            // :: 至少代表一组全零，因此已写出的组数必须小于 8
            if (head.length + tail.length >= 8) {
                return null;
            }
            System.arraycopy(head, 0, groups, 0, head.length);
            System.arraycopy(tail, 0, groups, 8 - tail.length, tail.length);
        }

        byte[] address = new byte[16];
        for (int i = 0; i < 8; i++) {
            address[2 * i] = (byte) (groups[i] >> 8);
            address[2 * i + 1] = (byte) groups[i];
        }
        return address;
    }

    /**
     * 把 IPv6 的一段（不含 {@code ::}）解析成 16 位字数组；末尾的内嵌 IPv4 占两个字
     */
    private static int[] toWords(String segment) {
        if (segment.isEmpty()) {
            return new int[0];
        }
        String[] tokens = segment.split(":", -1);
        int[] words = new int[8];
        int count = 0;
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            if (token.isEmpty()) {
                // 段内连续冒号（如 1:2::3 被切成两段后的空串）已由压缩分支处理，走到这里说明写法非法
                return null;
            }
            if (token.indexOf('.') >= 0) {
                if (i != tokens.length - 1 || count + 2 > 8) {
                    return null;
                }
                byte[] embedded = parseIpv4(token);
                if (embedded == null) {
                    return null;
                }
                words[count++] = ((embedded[0] & 0xFF) << 8) | (embedded[1] & 0xFF);
                words[count++] = ((embedded[2] & 0xFF) << 8) | (embedded[3] & 0xFF);
                continue;
            }
            if (token.length() > 4) {
                return null;
            }
            int value = 0;
            for (int j = 0; j < token.length(); j++) {
                int digit = Character.digit(token.charAt(j), 16);
                if (digit < 0) {
                    return null;
                }
                value = (value << 4) | digit;
            }
            if (count >= 8) {
                return null;
            }
            words[count++] = value;
        }
        return Arrays.copyOf(words, count);
    }
}
