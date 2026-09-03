package com.guanghe.fs.framework.preview.strategy.impl;

import com.guanghe.fs.framework.common.enums.FileTypeEnum;
import com.guanghe.fs.framework.preview.core.PreviewContext;
import com.guanghe.fs.framework.preview.strategy.AbstractPreviewStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import java.util.Map;

@Slf4j
@Component
public class CodePreviewStrategy extends AbstractPreviewStrategy {
    private static final Map<String, String> LANGUAGE_MAP = Map.ofEntries(
            Map.entry("java", "java"),
            Map.entry("js", "javascript"),
            Map.entry("jsx", "javascript"),
            Map.entry("ts", "typescript"),
            Map.entry("tsx", "typescript"),
            Map.entry("py", "python"),
            Map.entry("html", "html"),
            Map.entry("htm", "html"),
            Map.entry("css", "css"),
            Map.entry("scss", "css"),
            Map.entry("less", "css"),
            Map.entry("sql", "sql"),
            Map.entry("json", "json"),
            Map.entry("xml", "xml"),
            Map.entry("yaml", "yaml"),
            Map.entry("yml", "yaml"),
            Map.entry("toml", "toml"),
            Map.entry("c", "cpp"),
            Map.entry("h", "hpp")
    );

    @Override
    public boolean support(FileTypeEnum fileType) {
        return fileType == FileTypeEnum.CODE;
    }

    @Override
    public String getTemplatePath() {
        return "preview/code";
    }

    @Override
    protected void fillSpecificModel(PreviewContext context, Model model) {
        String language = LANGUAGE_MAP.getOrDefault(
                context.getExtension() == null ? "" : context.getExtension().toLowerCase(),
                "plaintext"
        );
        model.addAttribute("language", language);
    }

}
