import { request } from './request'

/**
 * 功能开关 API：读取对所有登录用户开放（侧边栏显隐依赖），修改仅系统管理员
 */
export const featureApi = {
  getToggles: () => {
    return request.get<Record<string, boolean>>('/apis/feature/toggles')
  },

  updateToggles: (toggles: Record<string, boolean>) => {
    return request.put<unknown>('/apis/feature/toggles', { toggles })
  },
}
