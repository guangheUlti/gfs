package com.guanghe.fs.file.domain.qry;

import com.guanghe.fs.framework.common.domain.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
public class FileRecycleQry extends PageQuery {

    private String keyword;
}
