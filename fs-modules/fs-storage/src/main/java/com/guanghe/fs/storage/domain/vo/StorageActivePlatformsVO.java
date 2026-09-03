package com.guanghe.fs.storage.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.guanghe.fs.framework.common.utils.DateUtils;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class StorageActivePlatformsVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String settingId;

    private String platformIdentifier;

    private String platformName;

    private String platformIcon;

    private String remark;

    @JsonFormat(pattern = DateUtils.DATE_TIME_PATTERN)
    private LocalDateTime createdAt;

    @JsonFormat(pattern = DateUtils.DATE_TIME_PATTERN)
    private LocalDateTime updatedAt;

    private Boolean isEnabled;
}
