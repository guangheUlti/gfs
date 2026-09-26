package com.guanghe.fs.service.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import com.guanghe.fs.framework.common.domain.Result;
import com.guanghe.fs.framework.common.exception.BusinessException;
import com.guanghe.fs.framework.common.utils.I18nUtils;
import com.guanghe.fs.service.domain.OssAccessKey;
import com.guanghe.fs.service.oss.OssAccessKeyService;
import com.mybatisflex.core.query.QueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

import static com.guanghe.fs.service.domain.table.OssAccessKeyTableDef.OSS_ACCESS_KEY;

/**
 * OSS 访问密钥管理 API（/apis/service/oss/keys，走登录态；列表/创建/吊销仅本人可见，
 * 管理员在服务页同样只管自己的密钥，避免跨用户泄露 Secret Key）。
 */
@Tag(name = "OSS 访问密钥")
@RestController
@RequestMapping("/apis/service/oss/keys")
@RequiredArgsConstructor
public class OssAccessKeyController {

    private final OssAccessKeyService ossAccessKeyService;

    @Operation(summary = "当前用户密钥列表（不含 Secret Key）")
    @GetMapping
    public Result<List<OssAccessKeyVO>> list() {
        String userId = StpUtil.getLoginIdAsString();
        List<OssAccessKeyVO> result = ossAccessKeyService.listByUser(userId).stream()
                .map(OssAccessKeyVO::from)
                .toList();
        return Result.ok(result);
    }

    @Operation(summary = "创建密钥（Secret Key 明文仅本次返回）")
    @PostMapping
    @Transactional(rollbackFor = Exception.class)
    public Result<OssAccessKeyCreatedVO> create(@RequestBody(required = false) CreateOssKeyCmd cmd) {
        String userId = StpUtil.getLoginIdAsString();
        String remark = cmd == null ? null : StrUtil.trimToNull(cmd.getRemark());
        OssAccessKeyService.Created created = ossAccessKeyService.create(userId, remark);
        return Result.ok(new OssAccessKeyCreatedVO(created.id(), created.accessKey(),
                created.secretKey(), created.createdAt()));
    }

    @Operation(summary = "吊销密钥（立即失效，不可恢复）")
    @DeleteMapping("/{id}")
    public Result<Void> revoke(@PathVariable("id") String id) {
        String userId = StpUtil.getLoginIdAsString();
        if (!ossAccessKeyService.revoke(userId, id)) {
            throw new BusinessException(I18nUtils.getMessage("oss.key.not.found"));
        }
        return Result.ok();
    }

    /** 列表项：secretKey 不出库 */
    @Data
    public static class OssAccessKeyVO {

        private String id;
        private String accessKey;
        /** 掩码显示：GFSab****ef */
        private String accessKeyMasked;
        private String remark;
        private Integer status;
        private LocalDateTime lastUsedAt;
        private LocalDateTime createdAt;

        static OssAccessKeyVO from(OssAccessKey key) {
            OssAccessKeyVO vo = new OssAccessKeyVO();
            vo.setId(key.getId());
            vo.setAccessKey(key.getAccessKey());
            String ak = key.getAccessKey();
            vo.setAccessKeyMasked(ak.length() <= 7 ? ak
                    : ak.substring(0, 5) + "****" + ak.substring(ak.length() - 2));
            vo.setRemark(key.getRemark());
            vo.setStatus(key.getStatus());
            vo.setLastUsedAt(key.getLastUsedAt());
            vo.setCreatedAt(key.getCreatedAt());
            return vo;
        }
    }

    /** 创建结果：secretKey 明文仅此响应出现一次 */
    @Data
    public static class OssAccessKeyCreatedVO {

        private String id;
        private String accessKey;
        private String secretKey;
        private LocalDateTime createdAt;

        public OssAccessKeyCreatedVO(String id, String accessKey, String secretKey, LocalDateTime createdAt) {
            this.id = id;
            this.accessKey = accessKey;
            this.secretKey = secretKey;
            this.createdAt = createdAt;
        }
    }

    @Data
    public static class CreateOssKeyCmd {

        /** 备注（可选） */
        private String remark;
    }
}
