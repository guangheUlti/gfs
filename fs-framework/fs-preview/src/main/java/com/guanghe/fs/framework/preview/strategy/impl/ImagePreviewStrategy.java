package com.guanghe.fs.framework.preview.strategy.impl;

import com.guanghe.fs.framework.common.enums.FileTypeEnum;
import com.guanghe.fs.framework.preview.core.PreviewContext;
import com.guanghe.fs.framework.preview.strategy.AbstractPreviewStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

/**
 * 图片预览策略
 */
@Slf4j
@Component
public class ImagePreviewStrategy extends AbstractPreviewStrategy {

    @Override
    public boolean support(FileTypeEnum fileType) {
        return fileType == FileTypeEnum.IMAGE;
    }

    @Override
    public String getTemplatePath() {
        return "preview/image";
    }

    @Override
    protected void fillSpecificModel(PreviewContext context, Model model) {
    }

}
