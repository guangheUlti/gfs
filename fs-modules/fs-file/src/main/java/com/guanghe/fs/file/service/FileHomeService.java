package com.guanghe.fs.file.service;

import com.guanghe.fs.file.domain.qry.FileHomeUsedBytesQry;
import com.guanghe.fs.file.domain.vo.FileHomeVO;
import com.guanghe.fs.file.domain.vo.StorageCapacityVO;

public interface FileHomeService {

    /**
     * 获取文件仪表盘信息
     *
     * @return 文件仪表盘信息
     */
    FileHomeVO getFileHomes(FileHomeUsedBytesQry qry);

    /**
     * 获取当前存储平台的容量信息（已用 / 剩余 / 总量）
     *
     * @return 存储容量信息
     */
    StorageCapacityVO getStorageCapacity();
}
