package com.guanghe.fs.framework.preview.strategy.impl;

import com.guanghe.fs.framework.common.enums.FileTypeEnum;
import com.guanghe.fs.framework.preview.core.PreviewContext;
import com.guanghe.fs.framework.preview.strategy.AbstractPreviewStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

@Slf4j
@Component
public class TifPreviewStrategy extends AbstractPreviewStrategy {

    @Override
    protected void fillSpecificModel(PreviewContext context, Model model) {

    }

    @Override
    public boolean support(FileTypeEnum type) {
        return type == FileTypeEnum.TIF;
    }

    @Override
    public String getTemplatePath() {
        return "preview/tif";
    }

}
