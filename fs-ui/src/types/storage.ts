/**
 * 存储平台类型定义
 */
export interface StoragePlatform {
  id: number
  name: string
  identifier: string
  configScheme: string
  icon: string
  link: string
  desc: string
  isDefault: number
}

/**
 * 用户存储配置类型定义
 */
export interface StorageSetting {
  id: number
  storagePlatform: StoragePlatform
  configData: string
  enabled: number
  userId: string
  remark?: string
}

/**
 * 存储配置的文件占用信息（删除前提示用）
 */
export interface StorageFileUsage {
  /** 该存储下的文件记录数（含回收站） */
  fileCount: number
  /** 删除时是否只清网盘索引（挂载式存储为 true，真实文件保留） */
  indexOnly: boolean
}

/**
 * 活跃存储平台类型定义
 */
export interface ActiveStoragePlatform {
  settingId: string
  platformIdentifier: string
  platformName: string
  platformIcon: string
  isEnabled: boolean
  /** 无状态远程（SFTP/SMB/FTP/WebDAV/内存/本地存储）：文件直接操作远端，不落库，不支持回收站/收藏/最近 */
  statelessRemote?: boolean
  remark?: string
  createdAt?: string
  updatedAt?: string
}

/**
 * 添加存储设置参数
 */
export interface AddStorageSettingParams {
  platformIdentifier: string
  configData: string
  remark?: string
}

/**
 * 更新存储设置参数
 */
export interface UpdateStorageSettingParams {
  settingId: string
  platformIdentifier: string
  configData: string
  remark?: string
}

/**
 * 配置方案字段
 */
export interface ConfigScheme {
  identifier: string
  label: string
  dataType: string
  validation: {
    required: boolean
  }
  /** 字段说明（渲染在输入框下方的小字提示） */
  description?: string
  /** 输入框占位文案（缺省用 i18n 默认） */
  placeholder?: string
  /** 条件显隐：仅当 formData[identifier] === value 时展示该字段 */
  showIf?: {
    identifier: string
    value: string
  }
  /** 单选项（dataType 为 radio 时使用） */
  options?: Array<{ label: string; value: string }>
  /** 默认值（新增配置时预填） */
  defaultValue?: string
}

/**
 * 存储服务器主机密钥信息（SFTP 指纹信任）
 */
export interface HostKeyInfo {
  host: string
  port: number
  keyType: string
  fingerprint: string
  knownHostsPath: string
  trusted: boolean
}
