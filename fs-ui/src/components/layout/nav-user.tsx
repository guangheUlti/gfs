import { useTranslation } from 'react-i18next'
import { useAuth } from '@/contexts/auth-context'
import { RiLogoutBoxLine } from '@remixicon/react'
import { useNavigate } from 'react-router-dom'
import { getAvatarFallback } from '@/utils/avatar'
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import {
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  useSidebar,
} from '@/components/ui/sidebar'

interface NavUserProps {
  user: {
    name: string
    avatar: string
  }
}

export function NavUser({ user }: NavUserProps) {
  const { t } = useTranslation('layout')
  const navigate = useNavigate()
  const { logout } = useAuth()
  const { state } = useSidebar()

  const handleLogout = () => {
    logout()
    navigate('/login')
  }

  const avatarFallback = getAvatarFallback(user.name)

  return (
    <SidebarMenu>
      <SidebarMenuItem>
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <SidebarMenuButton
              size='lg'
              className='border-0 ring-0 group-data-[collapsible=icon]:h-16! group-data-[collapsible=icon]:w-16! group-data-[collapsible=icon]:justify-center! group-data-[collapsible=icon]:p-2! focus-visible:ring-0 data-[state=open]:bg-sidebar-accent data-[state=open]:text-sidebar-accent-foreground'
            >
              <Avatar className='h-8 w-8 rounded-lg group-data-[collapsible=icon]:h-10 group-data-[collapsible=icon]:w-10 group-data-[collapsible=icon]:rounded-full'>
                <AvatarImage src={user.avatar} alt={user.name} />
                <AvatarFallback className='rounded-lg bg-sidebar-accent font-medium text-sidebar-accent-foreground group-data-[collapsible=icon]:rounded-full'>
                  {avatarFallback}
                </AvatarFallback>
              </Avatar>
              {state === 'expanded' && (
                <span className='truncate text-sm font-semibold'>
                  {user.name}
                </span>
              )}
            </SidebarMenuButton>
          </DropdownMenuTrigger>
          <DropdownMenuContent
            className='w-(--radix-dropdown-menu-trigger-width) rounded-lg'
            side='bottom'
            sideOffset={4}
          >
            {/* 只留退出登录：头像/用户名已在触发器上，账户设置移到了侧栏「系统」分组 */}
            <DropdownMenuItem
              onClick={handleLogout}
              className='text-red-600 focus:text-red-600'
            >
              <RiLogoutBoxLine className='mr-2 h-4 w-4' />
              <span>{t('navUser.logout')}</span>
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </SidebarMenuItem>
    </SidebarMenu>
  )
}
