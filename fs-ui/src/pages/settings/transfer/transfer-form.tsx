import { useState, useEffect, useRef, useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { z } from 'zod'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useUserStore } from '@/store/user'
import { toast } from 'sonner'
import { userApi } from '@/api/user'
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormMessage,
} from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { SettingsRow } from '../components/settings-row'

type TransferFormValues = {
  enableDownloadSpeedLimit: boolean
  downloadSpeedLimit?: number
  concurrentUploadQuantity: number
  concurrentDownloadQuantity: number
  chunkSize: number
}

export function TransferForm() {
  const { t } = useTranslation('settings')
  const transferFormSchema = useMemo(
    () =>
      z.object({
        enableDownloadSpeedLimit: z.boolean(),
        downloadSpeedLimit: z.number().min(1).max(200).optional(),
        concurrentUploadQuantity: z.number().min(1).max(3),
        concurrentDownloadQuantity: z.number().min(1).max(3),
        chunkSize: z.number(),
      }),
    [t]
  )

  const [loading, setLoading] = useState(false)
  /** 服务端数据已 reset 进表单后才允许自动保存（避免依赖会延迟更新的 formState.isValid） */
  const settingsReadyRef = useRef(false)
  const { loadTransferSetting } = useUserStore()
  const saveTimeoutRef = useRef<NodeJS.Timeout | null>(null)

  const form = useForm<TransferFormValues>({
    resolver: zodResolver(transferFormSchema),
    defaultValues: {
      enableDownloadSpeedLimit: false,
      downloadSpeedLimit: 5,
      concurrentUploadQuantity: 3,
      concurrentDownloadQuantity: 3,
      chunkSize: 5 * 1024 * 1024,
    },
  })

  const enableSpeedLimit = form.watch('enableDownloadSpeedLimit')

  useEffect(() => {
    loadSettings()
  }, [])

  async function loadSettings() {
    setLoading(true)
    settingsReadyRef.current = false
    try {
      const settings = await userApi.getTransferSetting()

      form.reset({
        enableDownloadSpeedLimit: settings.downloadSpeedLimit > 0,
        downloadSpeedLimit:
          settings.downloadSpeedLimit > 0 ? settings.downloadSpeedLimit : 5,
        concurrentUploadQuantity: settings.concurrentUploadQuantity || 3,
        concurrentDownloadQuantity: settings.concurrentDownloadQuantity || 3,
        chunkSize: settings.chunkSize || 5 * 1024 * 1024,
      })
      settingsReadyRef.current = true
    } catch {
      toast.error(t('transfer.loadFailed'))
    } finally {
      setLoading(false)
    }
  }

  async function saveAfterValidation() {
    if (!settingsReadyRef.current) return
    const ok = await form.trigger()
    if (!ok) return
    await saveSettings(form.getValues())
  }

  async function saveSettings(data: TransferFormValues) {
    if (!settingsReadyRef.current) return

    setLoading(true)
    try {
      await userApi.updateTransferSetting({
        downloadSpeedLimit: data.enableDownloadSpeedLimit
          ? data.downloadSpeedLimit || 5
          : -1,
        concurrentUploadQuantity: data.concurrentUploadQuantity,
        concurrentDownloadQuantity: data.concurrentDownloadQuantity,
        chunkSize: data.chunkSize,
      })
      toast.success(t('transfer.saved'))
      await loadTransferSetting()
    } catch (error) {
      toast.error(t('transfer.saveFailed'))
    } finally {
      setLoading(false)
    }
  }

  const handleFieldChange = () => {
    if (saveTimeoutRef.current) {
      clearTimeout(saveTimeoutRef.current)
    }

    saveTimeoutRef.current = setTimeout(() => {
      void saveAfterValidation()
    }, 800)
  }

  const handleImmediateChange = (callback: () => void) => {
    callback()
    if (saveTimeoutRef.current) {
      clearTimeout(saveTimeoutRef.current)
    }
    setTimeout(() => {
      void saveAfterValidation()
    }, 100)
  }

  /** 选「不限制」立即提交；选「上限」在限速值 onBlur 时提交 */
  const handleSpeedLimitModeChange = (
    value: string,
    fieldOnChange: (v: boolean) => void
  ) => {
    const enable = value === 'true'
    fieldOnChange(enable)
    if (!enable) {
      if (saveTimeoutRef.current) {
        clearTimeout(saveTimeoutRef.current)
      }
      setTimeout(() => {
        void saveAfterValidation()
      }, 100)
    }
  }

  useEffect(() => {
    return () => {
      if (saveTimeoutRef.current) {
        clearTimeout(saveTimeoutRef.current)
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <Form {...form}>
      <div>
        <div className='divide-y divide-border'>
          <div className='py-5'>
            <FormField
              control={form.control}
              name='enableDownloadSpeedLimit'
              render={({ field }) => (
                <FormItem className='space-y-0'>
                  <SettingsRow
                    className='py-0'
                    label={t('transfer.speedLimit')}
                    description={t('transfer.speedLimitDesc')}
                  >
                    <FormControl>
                      <RadioGroup
                        onValueChange={(value: string) => {
                          handleSpeedLimitModeChange(value, field.onChange)
                        }}
                        value={field.value ? 'true' : 'false'}
                        className='flex flex-row flex-wrap items-center justify-end gap-6'
                        disabled={loading}
                      >
                        <label className='flex cursor-pointer items-center gap-2'>
                          <RadioGroupItem value='false' id='speed-off' />
                          <span className='text-sm'>{t('transfer.unlimited')}</span>
                        </label>
                        <label className='flex cursor-pointer items-center gap-2'>
                          <RadioGroupItem value='true' id='speed-on' />
                          <span className='text-sm'>{t('transfer.capped')}</span>
                        </label>
                      </RadioGroup>
                    </FormControl>
                  </SettingsRow>
                </FormItem>
              )}
            />

            {enableSpeedLimit && (
              <FormField
                control={form.control}
                name='downloadSpeedLimit'
                render={({ field }) => (
                  <FormItem className='space-y-0'>
                    <SettingsRow
                      className='py-0 pt-6'
                      label={t('transfer.speedValue')}
                      description={t('transfer.speedValueDesc')}
                    >
                      <div className='flex items-center gap-2 self-end'>
                        <FormControl>
                          <Input
                            type='number'
                            min={1}
                            max={200}
                            className='w-24 font-mono tabular-nums sm:w-28'
                            {...field}
                            onChange={(e) => {
                              field.onChange(Number(e.target.value))
                            }}
                            onBlur={() => {
                              field.onBlur()
                              void (async () => {
                                if (!form.getValues('enableDownloadSpeedLimit'))
                                  return
                                await saveAfterValidation()
                              })()
                            }}
                            disabled={loading}
                          />
                        </FormControl>
                        <span className='shrink-0 text-sm text-muted-foreground'>
                          MB/s
                        </span>
                      </div>
                    </SettingsRow>
                    <FormMessage className='pt-1' />
                  </FormItem>
                )}
              />
            )}
          </div>

          <FormField
            control={form.control}
            name='concurrentUploadQuantity'
            render={({ field }) => (
              <FormItem className='space-y-0'>
                <SettingsRow
                  label={t('transfer.concurrentUpload')}
                  description={t('transfer.concurrentUploadDesc')}
                >
                  <Select
                    onValueChange={(value) => {
                      handleImmediateChange(() => field.onChange(Number(value)))
                    }}
                    value={String(field.value)}
                    disabled={loading}
                  >
                    <FormControl>
                      <SelectTrigger className='w-fit max-w-full self-end'>
                        <SelectValue />
                      </SelectTrigger>
                    </FormControl>
                    <SelectContent>
                      {[1, 2, 3].map((num) => (
                        <SelectItem key={num} value={String(num)}>
                          {t('transfer.unitCount', { count: num })}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </SettingsRow>
                <FormMessage className='pt-2 pb-1' />
              </FormItem>
            )}
          />

          <FormField
            control={form.control}
            name='concurrentDownloadQuantity'
            render={({ field }) => (
              <FormItem className='space-y-0'>
                <SettingsRow
                  label={t('transfer.concurrentDownload')}
                  description={t('transfer.concurrentDownloadDesc')}
                >
                  <Select
                    onValueChange={(value) => {
                      handleImmediateChange(() => field.onChange(Number(value)))
                    }}
                    value={String(field.value)}
                    disabled={loading}
                  >
                    <FormControl>
                      <SelectTrigger className='w-fit max-w-full self-end'>
                        <SelectValue />
                      </SelectTrigger>
                    </FormControl>
                    <SelectContent>
                      {[1, 2, 3].map((num) => (
                        <SelectItem key={num} value={String(num)}>
                          {t('transfer.unitCount', { count: num })}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </SettingsRow>
                <FormMessage className='pt-2 pb-1' />
              </FormItem>
            )}
          />

          <FormField
            control={form.control}
            name='chunkSize'
            render={({ field }) => (
              <FormItem className='space-y-0'>
                <SettingsRow
                  label={t('transfer.chunkSize')}
                  description={t('transfer.chunkSizeDesc')}
                >
                  <Select
                    onValueChange={(value) => {
                      handleImmediateChange(() => field.onChange(Number(value)))
                    }}
                    value={String(field.value)}
                    disabled={loading}
                  >
                    <FormControl>
                      <SelectTrigger className='w-fit max-w-full self-end'>
                        <SelectValue />
                      </SelectTrigger>
                    </FormControl>
                    <SelectContent>
                      <SelectItem value={String(2 * 1024 * 1024)}>2 MB</SelectItem>
                      <SelectItem value={String(5 * 1024 * 1024)}>5 MB</SelectItem>
                      <SelectItem value={String(10 * 1024 * 1024)}>
                        10 MB
                      </SelectItem>
                    </SelectContent>
                  </Select>
                </SettingsRow>
                <FormMessage className='pt-2 pb-1' />
              </FormItem>
            )}
          />
        </div>
      </div>
    </Form>
  )
}
