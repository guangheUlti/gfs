package com.guanghe.fs.file.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class VerifyShareCodeCmd {

    @NotBlank(message = "分享ID不能为空")
    private String shareId;

    @NotBlank(message = "分享验证码不能为空")
    private String shareCode;
}
