import type { ConfigScheme } from '@/types/storage'

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

/**
 * 是否为布尔开关字段（渲染为 Switch 而非文本输入）
 */
export function isBooleanField(field: ConfigScheme): boolean {
  return field.dataType === 'boolean'
}

/**
 * 条件显隐：showIf 引用的开关当前值等于目标值时才展示
 * formData 值统一为字符串（开关存 "true"/"false"，后端可能回填布尔 true）
 */
export function isFieldVisible(
  field: ConfigScheme,
  formData: Record<string, string>
): boolean {
  if (!field.showIf) return true
  const current = (formData[field.showIf.identifier] ?? '')
    .toString()
    .toLowerCase()
  return current === field.showIf.value.toLowerCase()
}

/**
 * 表单初始值：布尔字段默认 "false"，其余为空字符串
 */
export function buildInitialFormData(schemes: ConfigScheme[]): Record<string, string> {
  const initialData: Record<string, string> = {}
  schemes.forEach((field) => {
    initialData[field.identifier] = isBooleanField(field) ? 'false' : ''
  })
  return initialData
}

/**
 * 已保存配置回填归一化：布尔值（boolean/"true"）统一为 "true"/"false" 字符串
 */
export function normalizeConfigValue(
  field: ConfigScheme,
  raw: unknown
): string {
  if (raw === null || raw === undefined) {
    return isBooleanField(field) ? 'false' : ''
  }
  if (isBooleanField(field)) {
    return raw === true || String(raw).toLowerCase() === 'true' ? 'true' : 'false'
  }
  return String(raw)
}
