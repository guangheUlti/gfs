import type { PermissionCodeType } from '@/types/permission'

/** 侧边栏导航：默认 Line，选中时 Fill（@remixicon/react） */
type SidebarNavIconPair = {
  line: React.ElementType<{ className?: string }>
  fill: React.ElementType<{ className?: string }>
}

type User = {
  name: string
  avatar: string
}

type Team = {
  name: string
  logo: React.ElementType
  plan: string
}

type BaseNavItem = {
  /** i18n key under `layout` namespace, e.g. sidebar.nav.allFiles */
  titleKey: string
  badge?: string
  icon?: SidebarNavIconPair
  permission?: PermissionCodeType
  /** 受全局功能开关控制的项（如收藏/历史），开关关闭时从侧边栏隐藏 */
  featureKey?: string
}

type NavLink = BaseNavItem & {
  /** 动作项（有 onClick）没有 url；路由项必有 url */
  url?: string
  /** 非路由动作项（如打开设置弹窗）：有 onClick 时渲染按钮而不是链接 */
  onClick?: () => void
  items?: never
}

type NavCollapsible = BaseNavItem & {
  items: (BaseNavItem & { url: string })[]
  url?: never
}

type NavItem = NavCollapsible | NavLink

type NavGroup = {
  titleKey: string
  items: NavItem[]
}

type SidebarData = {
  user: User
  teams: Team[]
  navGroups: NavGroup[]
}

export type {
  SidebarData,
  NavGroup,
  NavItem,
  NavCollapsible,
  NavLink,
  SidebarNavIconPair,
}
