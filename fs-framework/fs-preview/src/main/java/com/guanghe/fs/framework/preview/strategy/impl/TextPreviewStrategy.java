package com.guanghe.fs.framework.preview.strategy.impl;

import com.guanghe.fs.framework.common.enums.FileTypeEnum;
import com.guanghe.fs.framework.preview.core.PreviewContext;
import com.guanghe.fs.framework.preview.strategy.AbstractPreviewStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

@Slf4j
@Component
public class TextPreviewStrategy extends AbstractPreviewStrategy {

    @Override
    public boolean support(FileTypeEnum fileType) {
        return fileType == FileTypeEnum.TEXT;
    }

    @Override
    public String getTemplatePath() {
        return "preview/code";
    }

    @Override
    protected void fillSpecificModel(PreviewContext context, Model model) {
        // 根据扩展名设置语言（用于轻微的语法高亮）
        String language = detectLanguage(context.getExtension());
        model.addAttribute("language", language);
    }

    /**
     * 检测语言类型
     */
    private String detectLanguage(String extension) {
        if (extension == null || extension.isEmpty()) {
            return "plaintext";
        }
        String ext = extension.toLowerCase();
        return switch (ext) {
            case "yaml", "yml" -> "yaml";
            case "properties" -> "properties";
            case "ini", "conf" -> "ini";
            case "log" -> "log";
            default -> "plaintext";  // 纯文本，无高亮
        };
    }
}
