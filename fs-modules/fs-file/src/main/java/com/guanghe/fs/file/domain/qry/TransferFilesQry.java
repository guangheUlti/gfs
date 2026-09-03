package com.guanghe.fs.file.domain.qry;

import lombok.Data;

@Data
public class TransferFilesQry {

    /**
     * 查询状态类型1：上传 2：下载 3：已完成
     */
    private Integer statusType;
}
