import { type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { RiArrowRightSLine } from '@remixicon/react'
import { Link, useLocation } from 'react-router-dom'
import { cn } from '@/lib/utils'
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from '@/components/ui/collapsible'
import {
  SidebarGroup,
  SidebarGroupLabel,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarMenuSub,
  SidebarMenuSubButton,
  SidebarMenuSubItem,
  useSidebar,
} from '@/components/ui/sidebar'
import { Badge } from '../ui/badge'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '../ui/dropdown-menu'
import {
  type NavCollapsible,
  type NavItem,
  type NavLink,
  type NavGroup as NavGroupProps,
  type SidebarNavIconPair,
} from './types'

function NavItemIcon({
  icon,
  active,
  className,
}: {
  icon: SidebarNavIconPair
  active: boolean
  className?: string
}) {
  const Cmp = active ? icon.fill : icon.line
  return <Cmp className={cn('size-4 shrink-0', className)} />
}

export function NavGroup({ titleKey, items }: NavGroupProps) {
  const { t } = useTranslation('layout')
  const { state, isMobile } = useSidebar()
  const location = useLocation()
  const href = location.pathname + location.search

  return (
    <SidebarGroup>
      <SidebarGroupLabel>{t(titleKey)}</SidebarGroupLabel>
      <SidebarMenu>
        {items.map((item) => {
          const key = `${item.titleKey}-${item.url}`

          if (!item.items)
            return <SidebarMenuLink key={key} item={item} href={href} />

          if (state === 'collapsed' && !isMobile)
            return (
              <SidebarMenuCollapsedDropdown key={key} item={item} href={href} />
            )

          return <SidebarMenuCollapsible key={key} item={item} href={href} />
        })}
      </SidebarMenu>
    </SidebarGroup>
  )
}

function NavBadge({ children }: { children: ReactNode }) {
  return <Badge className='rounded-full px-1 py-0 text-xs'>{children}</Badge>
}

function SidebarMenuLink({
  item,
  href,
}: {
  item: NavLink
  href: string
}) {
  const { t } = useTranslation('layout')
  const { setOpenMobile } = useSidebar()
  const active = checkIsActive(href, item)
  const label = t(item.titleKey)
  const content = (
    <>
      {item.icon && <NavItemIcon icon={item.icon} active={active} />}
      <span className='sidebar-nav-label'>{label}</span>
      {item.badge && <NavBadge>{item.badge}</NavBadge>}
    </>
  )

  // 动作项（如「设置」）：不是路由，渲染按钮；手机侧栏点完要收起
  if (item.onClick || !item.url) {
    return (
      <SidebarMenuItem>
        <SidebarMenuButton
          isActive={active}
          tooltip={label}
          onClick={() => {
            item.onClick?.()
            setOpenMobile(false)
          }}
        >
          {content}
        </SidebarMenuButton>
      </SidebarMenuItem>
    )
  }

  return (
    <SidebarMenuItem>
      <SidebarMenuButton
        asChild
        isActive={active}
        tooltip={label}
      >
        <Link to={item.url} onClick={() => setOpenMobile(false)}>
          {content}
        </Link>
      </SidebarMenuButton>
    </SidebarMenuItem>
  )
}

function SidebarMenuCollapsible({
  item,
  href,
}: {
  item: NavCollapsible
  href: string
}) {
  const { t } = useTranslation('layout')
  const { setOpenMobile } = useSidebar()
  const parentActive = checkIsActive(href, item, true)
  const label = t(item.titleKey)
  return (
    <Collapsible
      asChild
      defaultOpen={parentActive}
      className='group/collapsible'
    >
      <SidebarMenuItem>
        <CollapsibleTrigger asChild>
          <SidebarMenuButton tooltip={label}>
            {item.icon && <NavItemIcon icon={item.icon} active={parentActive} />}
            <span className='sidebar-nav-label'>{label}</span>
            {item.badge && <NavBadge>{item.badge}</NavBadge>}
            <RiArrowRightSLine className='ms-auto size-4 shrink-0 transition-transform duration-200 group-data-[state=open]/collapsible:rotate-90 rtl:rotate-180' />
          </SidebarMenuButton>
        </CollapsibleTrigger>
        <CollapsibleContent className='CollapsibleContent'>
          <SidebarMenuSub>
            {item.items.map((subItem) => (
              <SidebarMenuSubItem key={subItem.titleKey}>
                <SidebarMenuSubButton
                  asChild
                  isActive={checkIsActive(href, subItem)}
                >
                  <Link
                    to={subItem.url}
                    onClick={() => setOpenMobile(false)}
                  >
                    {subItem.icon && (
                      <NavItemIcon
                        icon={subItem.icon}
                        active={checkIsActive(href, subItem)}
                      />
                    )}
                    <span className='sidebar-nav-label'>
                      {t(subItem.titleKey)}
                    </span>
                    {subItem.badge && <NavBadge>{subItem.badge}</NavBadge>}
                  </Link>
                </SidebarMenuSubButton>
              </SidebarMenuSubItem>
            ))}
          </SidebarMenuSub>
        </CollapsibleContent>
      </SidebarMenuItem>
    </Collapsible>
  )
}

function SidebarMenuCollapsedDropdown({
  item,
  href,
}: {
  item: NavCollapsible
  href: string
}) {
  const { t } = useTranslation('layout')
  const parentActive = checkIsActive(href, item)
  const label = t(item.titleKey)
  return (
    <SidebarMenuItem>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <SidebarMenuButton
            tooltip={label}
            isActive={parentActive}
          >
            {item.icon && (
              <NavItemIcon icon={item.icon} active={parentActive} />
            )}
            <span className='sidebar-nav-label'>{label}</span>
            {item.badge && <NavBadge>{item.badge}</NavBadge>}
            <RiArrowRightSLine className='ms-auto size-4 shrink-0 transition-transform duration-200 group-data-[state=open]/collapsible:rotate-90' />
          </SidebarMenuButton>
        </DropdownMenuTrigger>
        <DropdownMenuContent side='right' align='start' sideOffset={4}>
          <DropdownMenuLabel>
            {label} {item.badge ? `(${item.badge})` : ''}
          </DropdownMenuLabel>
          <DropdownMenuSeparator />
          {item.items.map((sub) => (
            <DropdownMenuItem key={`${sub.titleKey}-${sub.url}`} asChild>
              <Link
                to={sub.url}
                className={`${checkIsActive(href, sub) ? 'bg-secondary' : ''}`}
              >
                {sub.icon && (
                  <NavItemIcon
                    icon={sub.icon}
                    active={checkIsActive(href, sub)}
                  />
                )}
                <span className='max-w-52 text-wrap'>{t(sub.titleKey)}</span>
                {sub.badge && (
                  <span className='ms-auto text-xs'>{sub.badge}</span>
                )}
              </Link>
            </DropdownMenuItem>
          ))}
        </DropdownMenuContent>
      </DropdownMenu>
    </SidebarMenuItem>
  )
}

function checkIsActive(href: string, item: NavItem, mainNav = false) {
  // 折叠父项：任一子项命中即激活
  if (item.items?.some((i) => i.url === href)) return true

  const itemUrl = item.url
  if (!itemUrl) return false

  const [itemPath, itemQuery = ''] = itemUrl.split('?')
  const [hrefPath, hrefQuery = ''] = href.split('?')

  // 同一路径下按 view 参数区分视图身份（文件/收藏/历史/分享/回收站互斥）：
  // 「文件」不带 view，在带 view 的页面上不激活；带 view 的项只在自己的 view 下激活。
  // href 上多余的参数（如搜索 keyword）不影响激活状态
  const samePath = itemPath === hrefPath
  const sameView =
    new URLSearchParams(itemQuery).get('view') ===
    new URLSearchParams(hrefQuery).get('view')

  return (samePath && sameView) || (mainNav && samePath)
}
