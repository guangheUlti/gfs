import React from 'react'
import { useTranslation } from 'react-i18next'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'

export type LegalType = 'terms' | 'privacy' | null

interface LegalDialogProps {
  type: LegalType
  onOpenChange: (open: boolean) => void
}

/**
 * 登录页法律协议弹窗（用户协议 / 隐私政策）
 * 通过 type 控制显示哪个协议，null 表示关闭
 */
const LegalDialog: React.FC<LegalDialogProps> = ({ type, onOpenChange }) => {
  const { t } = useTranslation('login')
  const isTerms = type === 'terms'
  const sections = t(
    isTerms ? 'userAgreementDialog.sections' : 'privacyPolicyDialog.sections',
    {
      returnObjects: true,
    }
  ) as string[]

  return (
    <Dialog open={type !== null} onOpenChange={onOpenChange}>
      <DialogContent className='max-h-[85vh] overflow-y-auto bg-white sm:max-w-lg dark:bg-neutral-900'>
        <DialogHeader>
          <DialogTitle className='text-xl font-bold'>
            {t(isTerms ? 'userAgreementDialog.title' : 'privacyPolicyDialog.title')}
          </DialogTitle>
          <DialogDescription>
            {t(isTerms ? 'userAgreementDialog.updated' : 'privacyPolicyDialog.updated')}
          </DialogDescription>
        </DialogHeader>
        <div className='flex flex-col gap-3 text-sm leading-relaxed text-neutral-600 dark:text-neutral-300'>
          {Array.isArray(sections) &&
            sections.map((section, index) => <p key={index}>{section}</p>)}
        </div>
      </DialogContent>
    </Dialog>
  )
}

export default LegalDialog
