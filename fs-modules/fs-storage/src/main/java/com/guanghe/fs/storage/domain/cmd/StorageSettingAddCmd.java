package com.guanghe.fs.storage.domain.cmd;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class StorageSettingAddCmd {

    @NotBlank(message = "platformIdentifier不能为空")
    private String platformIdentifier;

    @NotBlank(message = "configData不能为空")
    private String configData;

    private String remark;
}
