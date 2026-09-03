package com.guanghe.fs.framework.preview.strategy.impl.archive;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class ArchiveTreeNode {
    /**
     * 节点名称（不带路径，如 "src"）
     */
    private String name;

    /**
     * 完整路径（用于区分重名文件夹，如 "project/src"）
     */
    private String fullPath;

    /**
     * 是否是目录
     */
    private Boolean isDirectory;

    /**
     * 子节点列表（只有目录才有）
     */
    private List<ArchiveTreeNode> children;

    // 辅助方法：添加子节点
    public void addChild(ArchiveTreeNode child) {
        if (children == null) {
            children = new ArrayList<>();
        }
        children.add(child);
    }
}
