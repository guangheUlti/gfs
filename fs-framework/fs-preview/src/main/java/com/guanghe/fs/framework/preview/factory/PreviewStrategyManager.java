package com.guanghe.fs.framework.preview.factory;

import com.guanghe.fs.framework.common.enums.FileTypeEnum;
import com.guanghe.fs.framework.preview.core.PreviewStrategy;
import com.guanghe.fs.framework.preview.strategy.impl.UnsupportedPreviewStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 预览策略管理器
 */
@Slf4j
@Component
public class PreviewStrategyManager {

    private final List<PreviewStrategy> sortedStrategies;
    private final PreviewStrategy unsupportedStrategy;
    private final Map<FileTypeEnum, PreviewStrategy> strategyCache = new ConcurrentHashMap<>();

    public PreviewStrategyManager(List<PreviewStrategy> strategies) {
        this.unsupportedStrategy = strategies.stream()
                .filter(s -> s instanceof UnsupportedPreviewStrategy)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("缺少 UnsupportedPreviewStrategy 实现"));
        this.sortedStrategies = strategies.stream()
                .filter(s -> !(s instanceof UnsupportedPreviewStrategy))
                .collect(Collectors.toList());

        log.info("初始化预览策略管理器，已加载 {} 个策略", sortedStrategies.size());
    }

    /**
     * 获取预览策略
     */
    public PreviewStrategy getStrategy(FileTypeEnum fileType) {
        if (fileType == null) return unsupportedStrategy;
        return strategyCache.computeIfAbsent(fileType, type ->
                sortedStrategies.stream()
                        .filter(s -> s.support(type))
                        .findFirst()
                        .orElse(unsupportedStrategy)
        );
    }
}
