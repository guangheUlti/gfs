package com.guanghe.fs.storage.domain.cmd;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class StorageSettingEditCmd {

    @NotBlank(message = "settingId不能为空")
    private String settingId;

    @NotBlank(message = "platformIdentifier不能为空")
    private String platformIdentifier;

    @NotBlank(message = "configData不能为空")
    private String configData;

    private String remark;
}
