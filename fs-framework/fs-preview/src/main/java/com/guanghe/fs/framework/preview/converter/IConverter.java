package com.guanghe.fs.framework.preview.converter;

import java.io.InputStream;

public interface IConverter {
    /**
     * 转换文件流
     */
    InputStream convert(InputStream sourceStream, String sourceExtension);

    /**
     * 转换后的文件扩展名
     */
    String getTargetExtension();
}
