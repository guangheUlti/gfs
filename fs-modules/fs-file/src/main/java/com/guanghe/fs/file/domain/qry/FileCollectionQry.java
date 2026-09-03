package com.guanghe.fs.file.domain.qry;

import com.guanghe.fs.framework.common.domain.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class FileCollectionQry extends PageQuery {
    private String keyword;
    private String status;
}
