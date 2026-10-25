package com.guanghe.fs.service.oss;

import cn.hutool.core.util.RandomUtil;
import com.guanghe.fs.service.domain.OssAccessKey;
import com.guanghe.fs.service.mapper.OssAccessKeyMapper;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

import static com.guanghe.fs.service.domain.table.OssAccessKeyTableDef.OSS_ACCESS_KEY;

/**
 * OSS 访问密钥业务：每用户可签发多对 SigV4 密钥。
 * <ul>
 *   <li>accessKey：20 位随机（对外可见，唯一）；</li>
 *   <li>secretKey：40 位随机，AES-GCM 加密入库，明文仅在创建响应中返回一次；</li>
 *   <li>吊销（status=1）后立即失效。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class OssAccessKeyService {

    private final OssAccessKeyMapper mapper;
    private final OssAesCipher aesCipher;

    /** 创建并返回含明文 Secret Key 的记录（明文仅此一次） */
    public Created create(String userId, String remark) {
        OssAccessKey entity = new OssAccessKey();
        entity.setId(cn.hutool.core.util.IdUtil.fastSimpleUUID());
        entity.setUserId(userId);
        entity.setAccessKey("GFS" + RandomUtil.randomString(RandomUtil.BASE_CHAR_NUMBER.toLowerCase(), 17));
        String secret = RandomUtil.randomString(RandomUtil.BASE_CHAR_NUMBER + "_", 40);
        entity.setSecretKey(aesCipher.encrypt(secret));
        entity.setRemark(remark);
        entity.setStatus(0);
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        mapper.insert(entity);
        return new Created(entity.getId(), entity.getAccessKey(), secret, now);
    }

    /** 用户的密钥列表（不含 Secret Key 密文） */
    public List<OssAccessKey> listByUser(String userId) {
        return mapper.selectListByQuery(new QueryWrapper()
                .where(OSS_ACCESS_KEY.USER_ID.eq(userId))
                .orderBy(OSS_ACCESS_KEY.CREATED_AT.desc()));
    }

    /** 吊销密钥 */
    public boolean revoke(String userId, String keyId) {
        OssAccessKey key = mapper.selectOneById(keyId);
        if (key == null || !userId.equals(key.getUserId())) {
            return false;
        }
        key.setStatus(1);
        key.setUpdatedAt(LocalDateTime.now());
        return mapper.update(key) > 0;
    }

    /**
     * 永久删除密钥记录：只允许删已吊销的（status=1）。
     * <p>
     * 未吊销的直接物理删除会让客户端凭据「无声失效」且无法追溯，所以仍要求先吊销——
     * 这个接口只是清理列表里的历史记录，不承担吊销职能。
     *
     * @return true 删除成功；false 不存在、非本人、或尚未吊销
     */
    public boolean purge(String userId, String keyId) {
        OssAccessKey key = mapper.selectOneById(keyId);
        if (key == null || !userId.equals(key.getUserId()) || key.getStatus() == null || key.getStatus() != 1) {
            return false;
        }
        return mapper.deleteById(keyId) > 0;
    }

    /** 按 Access Key 查有效密钥（状态正常） */
    public OssAccessKey findActiveByAccessKey(String accessKey) {
        if (accessKey == null || accessKey.isEmpty()) {
            return null;
        }
        return mapper.selectOneByQuery(new QueryWrapper()
                .where(OSS_ACCESS_KEY.ACCESS_KEY.eq(accessKey))
                .and(OSS_ACCESS_KEY.STATUS.eq(0))
                .limit(1));
    }

    /** 还原 Secret Key 明文；解密失败（主密钥变更）返回 null */
    public String revealSecret(OssAccessKey key) {
        return aesCipher.decrypt(key.getSecretKey());
    }

    /** 鉴权成功后刷新最后使用时间（节流 1 分钟，失败忽略） */
    public void touchLastUsed(String keyId) {
        try {
            OssAccessKey patch = new OssAccessKey();
            patch.setId(keyId);
            patch.setLastUsedAt(LocalDateTime.now());
            mapper.update(patch);
        } catch (Exception ignored) {
        }
    }

    /** 创建结果：secretKey 明文仅在此对象中出现一次 */
    public record Created(String id, String accessKey, String secretKey, LocalDateTime createdAt) {
    }
}
