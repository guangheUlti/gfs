import React from 'react'
import { z } from 'zod'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { getAppLang, type AppLang } from '@/i18n'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from '@/components/ui/form'
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { useTheme } from '@/components/theme-provider'
import { useAppearanceStore } from '@/store/appearance'
import { SettingsBlock, SettingsRow } from '../components/settings-row'

type AppearanceFormValues = {
  theme: 'light' | 'dark'
  language: AppLang
  thumbnails: boolean
}

export function AppearanceForm() {
  const { t } = useTranslation('settings')
  const { i18n } = useTranslation()
  const { theme, setTheme } = useTheme()
  const thumbnailsEnabled = useAppearanceStore(
    (state) => state.thumbnailsEnabled
  )
  const setThumbnailsEnabled = useAppearanceStore(
    (state) => state.setThumbnailsEnabled
  )

  const appearanceFormSchema = React.useMemo(
    () =>
      z.object({
        theme: z.enum(['light', 'dark'], {
          message: t('appearance.validation.theme'),
        }),
        language: z.enum(['zh', 'en'], {
          message: t('appearance.validation.language'),
        }),
        thumbnails: z.boolean(),
      }),
    [t]
  )

  const form = useForm<AppearanceFormValues>({
    resolver: zodResolver(appearanceFormSchema),
    defaultValues: {
      theme: (theme as 'light' | 'dark') || 'light',
      language: getAppLang(),
      thumbnails: thumbnailsEnabled,
    },
  })

  // 语言从别处切换（如登录页）后同步到表单
  React.useEffect(() => {
    form.setValue('language', getAppLang())
  }, [i18n.language, form])

  // 监听主题变化，同步更新表单
  React.useEffect(() => {
    if (theme === 'light' || theme === 'dark') {
      form.setValue('theme', theme)
    }
  }, [theme, form])

  const handleThemeChange = (theme: 'light' | 'dark') => {
    setTheme(theme, false)
    toast.success(t('appearance.themeUpdated'))
  }

  const handleLanguageChange = async (lang: AppLang) => {
    await i18n.changeLanguage(lang)
    // changeLanguage 完成后再用 i18n.t，否则 toast 仍是切换前的语言
    toast.success(
      i18n.t('appearance.languageUpdated', { ns: 'settings' })
    )
  }

  const handleThumbnailsChange = (enabled: boolean) => {
    setThumbnailsEnabled(enabled)
    toast.success(t('appearance.thumbnailsUpdated'))
  }

  return (
    <Form {...form}>
      <div>
        <div className='divide-y divide-border'>
          <FormField
            control={form.control}
            name='language'
            render={({ field }) => (
              <FormItem className='space-y-0'>
                <SettingsRow
                  label={t('appearance.languageLabel')}
                  description={t('appearance.languageDescription')}
                >
                  <Select
                    value={field.value}
                    onValueChange={(value) => {
                      field.onChange(value)
                      void handleLanguageChange(value as AppLang)
                    }}
                  >
                    <FormControl>
                      <SelectTrigger className='h-11 w-fit max-w-full self-end bg-background'>
                        <SelectValue />
                      </SelectTrigger>
                    </FormControl>
                    <SelectContent>
                      <SelectItem value='zh'>
                        {t('appearance.langZh')}
                      </SelectItem>
                      <SelectItem value='en'>
                        {t('appearance.langEn')}
                      </SelectItem>
                    </SelectContent>
                  </Select>
                </SettingsRow>
                <FormMessage className='pt-1' />
              </FormItem>
            )}
          />

          <FormField
            control={form.control}
            name='theme'
            render={({ field }) => (
              <FormItem className='space-y-0'>
                <SettingsBlock
                  label={t('appearance.themeLabel')}
                  description={t('appearance.themeDescription')}
                >
                  <RadioGroup
                    onValueChange={(value) => {
                      field.onChange(value)
                      handleThemeChange(value as 'light' | 'dark')
                    }}
                    value={field.value}
                    className='grid w-full max-w-2xl grid-cols-2 gap-4 sm:gap-6'
                  >
                    <FormItem>
                      <FormLabel className='cursor-pointer [&:has([data-state=checked])_.theme-card]:border-primary'>
                        <FormControl>
                          <RadioGroupItem value='light' className='sr-only' />
                        </FormControl>
                        <div className='space-y-2'>
                          <div className='theme-card h-32 items-center rounded-lg border-2 border-muted p-3 transition-colors hover:border-accent'>
                            <div className='flex h-full flex-col justify-center space-y-2 rounded-sm bg-[#ecedef] p-3'>
                              <div className='space-y-2 rounded-md bg-white p-3 shadow-sm'>
                                <div className='h-2.5 w-[120px] rounded-lg bg-[#ecedef]' />
                                <div className='h-2.5 w-[150px] rounded-lg bg-[#ecedef]' />
                              </div>
                            </div>
                          </div>
                          <span className='block w-full text-center font-normal'>
                            {t('appearance.light')}
                          </span>
                        </div>
                      </FormLabel>
                    </FormItem>
                    <FormItem>
                      <FormLabel className='cursor-pointer [&:has([data-state=checked])_.theme-card]:border-primary'>
                        <FormControl>
                          <RadioGroupItem value='dark' className='sr-only' />
                        </FormControl>
                        <div className='space-y-2'>
                          <div className='theme-card h-32 items-center rounded-lg border-2 border-muted bg-popover p-3 transition-colors hover:bg-accent hover:text-accent-foreground'>
                            {/* 预览缩略图跟实际暗黑主题保持一致：中性灰黑而非 slate 的蓝调 */}
                            <div className='flex h-full flex-col justify-center space-y-2 rounded-sm bg-neutral-950 p-3'>
                              <div className='space-y-2 rounded-md bg-neutral-800 p-3 shadow-sm'>
                                <div className='h-2.5 w-[120px] rounded-lg bg-neutral-400' />
                                <div className='h-2.5 w-[150px] rounded-lg bg-neutral-400' />
                              </div>
                            </div>
                          </div>
                          <span className='block w-full text-center font-normal'>
                            {t('appearance.dark')}
                          </span>
                        </div>
                      </FormLabel>
                    </FormItem>
                  </RadioGroup>
                </SettingsBlock>
                <FormMessage className='pt-2 pb-1' />
              </FormItem>
            )}
          />

          <FormField
            control={form.control}
            name='thumbnails'
            render={({ field }) => (
              <FormItem className='space-y-0'>
                <SettingsRow
                  label={t('appearance.thumbnailsLabel')}
                  description={t('appearance.thumbnailsDescription')}
                >
                  <FormControl>
                    <RadioGroup
                      onValueChange={(value) => {
                        const enabled = value === 'true'
                        field.onChange(enabled)
                        handleThumbnailsChange(enabled)
                      }}
                      value={field.value ? 'true' : 'false'}
                      className='flex flex-row flex-wrap items-center justify-end gap-6'
                    >
                      <label className='flex cursor-pointer items-center gap-2'>
                        <RadioGroupItem value='true' id='thumbnails-on' />
                        <span className='text-sm'>
                          {t('appearance.thumbnailsOn')}
                        </span>
                      </label>
                      <label className='flex cursor-pointer items-center gap-2'>
                        <RadioGroupItem value='false' id='thumbnails-off' />
                        <span className='text-sm'>
                          {t('appearance.thumbnailsOff')}
                        </span>
                      </label>
                    </RadioGroup>
                  </FormControl>
                </SettingsRow>
              </FormItem>
            )}
          />
        </div>
      </div>
    </Form>
  )
}
