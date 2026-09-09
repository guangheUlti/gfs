/**
 * 判断配置字段是否为敏感字段（与后端 isSensitiveKey 完全同规则）：
 * 小写后含 password/secret/token，或同时含 access 和 key
 */
export function isSensitiveField(identifier: string): boolean {
  if (!identifier) return false
  const lower = identifier.toLowerCase()
  const hasAccessKey = lower.includes('access') && lower.includes('key')
  const isSecretKey =
    lower.includes('password') ||
    lower.includes('secret') ||
    lower.includes('token')
  return isSecretKey && !hasAccessKey ? true : hasAccessKey
}
