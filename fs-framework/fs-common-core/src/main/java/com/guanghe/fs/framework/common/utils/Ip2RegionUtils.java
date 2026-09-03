package com.guanghe.fs.framework.common.utils;

import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.StrUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.lionsoul.ip2region.xdb.Searcher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/**
 * IP 地址归属地查询工具类
 * 基于 ip2region 实现
 */
@Slf4j
@Component
public class Ip2RegionUtils {

    /**
     * xdb 文件路径（放在 resources 目录下）
     */
    private static final String DB_PATH = "ip2region_v4.xdb";

    /**
     * 查询对象（线程安全，可并发使用）
     */
    private static Searcher searcher;

    /**
     * 初始化方法，Spring 容器启动时自动执行
     */
    @PostConstruct
    public void init() {
        try {
            // 从 classpath 加载 xdb 文件
            ClassPathResource resource = new ClassPathResource(DB_PATH);
            InputStream inputStream = resource.getInputStream();
            byte[] bytes = IoUtil.readBytes(inputStream);
            // 创建基于内存的查询对象
            searcher = Searcher.newWithBuffer(bytes);
            log.info("IP2Region 工具类初始化成功");
        } catch (Exception e) {
            log.error("IP2Region 工具类初始化失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 销毁方法，Spring 容器关闭时自动执行
     */
    @PreDestroy
    public void destroy() {
        if (searcher != null) {
            try {
                searcher.close();
                log.info("IP2Region 资源已释放");
            } catch (Exception e) {
                log.error("关闭 IP2Region 资源失败: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * 查询 IP 归属地
     *
     * @param ip IP 地址（支持 IPv4 和 IPv6）
     * @return 归属地信息，格式：国家|区域|省份|城市|ISP
     */
    public static String search(String ip) {
        if (StrUtil.isBlank(ip)) {
            return "未知";
        }

        if (searcher == null) {
            log.warn("IP2Region 查询对象未初始化");
            return "未知";
        }

        try {
            return searcher.search(ip);
        } catch (Exception e) {
            log.error("查询 IP [{}] 归属地失败: {}", ip, e.getMessage());
            return "未知";
        }
    }

    /**
     * 查询 IP 归属地（简化版，只返回省份和城市）
     *
     * @param ip IP 地址
     * @return 省份-城市
     */
    public static String searchSimple(String ip) {
        String result = search(ip);
        if ("未知".equals(result)) {
            return result;
        }

        try {
            // 格式：国家|区域|省份|城市|ISP
            String[] parts = result.split("\\|");
            if (parts.length >= 4) {
                String province = "0".equals(parts[2]) ? "" : parts[2];
                String city = "0".equals(parts[3]) ? "" : parts[3];

                if (StrUtil.isNotBlank(province) && StrUtil.isNotBlank(city)) {
                    return province + "-" + city;
                } else if (StrUtil.isNotBlank(province)) {
                    return province;
                } else if (StrUtil.isNotBlank(city)) {
                    return city;
                }
            }
        } catch (Exception e) {
            log.error("解析 IP 归属地信息失败: {}", e.getMessage());
        }

        return "未知";
    }

    /**
     * 获取国家
     */
    public static String getCountry(String ip) {
        return getPart(ip, 0);
    }

    /**
     * 获取省份
     */
    public static String getProvince(String ip) {
        return getPart(ip, 2);
    }

    /**
     * 获取城市
     */
    public static String getCity(String ip) {
        return getPart(ip, 3);
    }

    /**
     * 获取 ISP
     */
    public static String getIsp(String ip) {
        return getPart(ip, 4);
    }

    /**
     * 获取指定位置的信息
     *
     * @param ip    IP 地址
     * @param index 位置索引（0:国家 1:区域 2:省份 3:城市 4:ISP）
     * @return 信息内容
     */
    private static String getPart(String ip, int index) {
        String result = search(ip);
        if ("未知".equals(result)) {
            return "未知";
        }

        try {
            String[] parts = result.split("\\|");
            if (parts.length > index) {
                String part = parts[index];
                return "0".equals(part) ? "未知" : part;
            }
        } catch (Exception e) {
            log.error("解析 IP 归属地信息失败: {}", e.getMessage());
        }

        return "未知";
    }
}
