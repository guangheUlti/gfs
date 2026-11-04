/** IP 防护模式：互斥，同一时刻只有一种名单生效 */
export type IpGuardMode = 'OFF' | 'WHITE' | 'BLACK'

/** IP 防护名单条目 */
export interface IpGuardRule {
  /** IP 或 CIDR 网段，如 203.0.113.9、192.168.1.0/24 */
  ip: string
  /** 备注，仅用于说明这条是谁，不参与匹配 */
  remark?: string
}

/** IP 防护配置（整份读写） */
export interface IpGuardConfig {
  mode: IpGuardMode
  whiteList: IpGuardRule[]
  blackList: IpGuardRule[]
  /** 服务端看到的当前操作者来源 IP，与名单判定口径一致 */
  currentIp?: string
}
