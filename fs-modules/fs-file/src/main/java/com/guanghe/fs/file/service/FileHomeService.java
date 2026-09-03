package com.guanghe.fs.file.service;

import com.guanghe.fs.file.domain.qry.FileHomeUsedBytesQry;
import com.guanghe.fs.file.domain.vo.FileHomeVO;

public interface FileHomeService {

    /**
     * 获取文件仪表盘信息
     *
     * @return 文件仪表盘信息
     */
    FileHomeVO getFileHomes(FileHomeUsedBytesQry qry);
}
