import React from 'react'

/** alist 风格描边输入框容器：图标 + 无边框输入 */
export default function FieldBox({
  icon,
  children,
}: {
  icon: React.ReactNode
  children: React.ReactNode
}) {
  return (
    <div className='flex h-12 w-full items-center gap-2 rounded-xl border border-[#E9E9E9] px-3 transition-[border-color,box-shadow] focus-within:border-[#3573FF] focus-within:shadow-[0_0_0_1px_#3573FF] dark:border-neutral-700'>
      {icon}
      {children}
    </div>
  )
}

/** alist 风格无边框输入框类名，配合 FieldBox 使用 */
export const fieldInputClass =
  'h-full flex-1 border-0 bg-transparent px-0 shadow-none focus-visible:border-0 focus-visible:ring-0 focus-visible:shadow-none dark:bg-transparent'
