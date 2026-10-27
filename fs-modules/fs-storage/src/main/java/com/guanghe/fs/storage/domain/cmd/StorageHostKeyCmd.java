package com.guanghe.fs.storage.domain.cmd;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class StorageHostKeyCmd {

    @NotBlank(message = "platformIdentifier不能为空")
    private String platformIdentifier;

    @NotBlank(message = "configData不能为空")
    private String configData;

    /** 用户在界面上确认过的 SHA256 指纹（信任接口必填） */
    private String fingerprint;
}
