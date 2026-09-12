import {
  RiArrowLeftRightFill,
  RiArrowLeftRightLine,
  RiDeleteBinFill,
  RiDeleteBinLine,
  RiFolderOpenFill,
  RiFolderOpenLine,
  RiHistoryFill,
  RiHistoryLine,
  RiPlugFill,
  RiPlugLine,
  RiServerFill,
  RiServerLine,
  RiShareFill,
  RiShareLine,
  RiStarFill,
  RiStarLine,
} from '@remixicon/react'
import { type SidebarData } from '../types'

export const sidebarData: SidebarData = {
  user: {
    name: '',
    avatar: '/avatars/default.jpg',
  },
  teams: [],
  // 文件分组在上，系统分组在下；
  // 「设置」是全局弹窗而非路由，由 app-sidebar.tsx 在系统分组末尾注入
  navGroups: [
    {
      titleKey: 'sidebar.groups.files',
      items: [
        {
          titleKey: 'sidebar.nav.allFiles',
          url: '/files',
          icon: { line: RiFolderOpenLine, fill: RiFolderOpenFill },
          permission: 'file:read',
        },
        {
          titleKey: 'sidebar.nav.transfer',
          url: '/transfer',
          icon: { line: RiArrowLeftRightLine, fill: RiArrowLeftRightFill },
        },
        {
          titleKey: 'sidebar.nav.favorites',
          url: '/files?view=favorites',
          icon: { line: RiStarLine, fill: RiStarFill },
          featureKey: 'favorite',
        },
        {
          titleKey: 'sidebar.nav.recents',
          url: '/files?view=recents',
          icon: { line: RiHistoryLine, fill: RiHistoryFill },
          featureKey: 'history',
        },
        {
          titleKey: 'sidebar.nav.shares',
          url: '/files?view=shares',
          icon: { line: RiShareLine, fill: RiShareFill },
          permission: 'file:share',
        },
        {
          titleKey: 'sidebar.nav.recycle',
          url: '/files?view=recycle',
          icon: { line: RiDeleteBinLine, fill: RiDeleteBinFill },
          permission: 'file:write',
        },
      ],
    },
    {
      titleKey: 'sidebar.groups.system',
      items: [
        {
          titleKey: 'sidebar.nav.storage',
          url: '/storage',
          icon: { line: RiServerLine, fill: RiServerFill },
          permission: 'storage:manage',
        },
        {
          titleKey: 'sidebar.nav.services',
          url: '/services',
          icon: { line: RiPlugLine, fill: RiPlugFill },
          permission: 'service:manage',
        },
      ],
    },
  ],
}
