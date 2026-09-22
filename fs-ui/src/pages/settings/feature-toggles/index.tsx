import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { featureApi } from '@/api/feature'
import { Switch } from '@/components/ui/switch'
import { useFeatureStore } from '@/store/feature'
import {
  SettingsPageDescription,
  SettingsPageTitle,
} from '../components/settings-page-header'
import { SettingsRow } from '../components/settings-row'

const TOGGLE_KEYS = ['favorite', 'history', 'recycleBin'] as const

export function SettingsFeatureToggles() {
  const { t } = useTranslation('settings')
  const toggles = useFeatureStore((s) => s.toggles)
  const setToggle = useFeatureStore((s) => s.setToggle)
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    void loadToggles()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  async function loadToggles() {
    setLoading(true)
    try {
      const data = await featureApi.getToggles()
      Object.entries(data ?? {}).forEach(([key, value]) =>
        setToggle(key, !!value)
      )
    } catch {
      toast.error(t('featureToggles.loadFailed'))
    } finally {
      setLoading(false)
    }
  }

  async function handleToggle(key: string, value: boolean) {
    const prev = !!toggles[key]
    setToggle(key, value)
    setSaving(true)
    try {
      await featureApi.updateToggles({ [key]: value })
    } catch {
      setToggle(key, prev)
      toast.error(t('featureToggles.saveFailed'))
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className='flex flex-1 flex-col'>
      <header className='flex-none'>
        <SettingsPageTitle>
          {t('featureToggles.pageTitle')}
        </SettingsPageTitle>
        <SettingsPageDescription>
          {t('featureToggles.pageDescription')}
        </SettingsPageDescription>
      </header>
      <div className='mt-8 flex-1'>
        <div className='divide-y divide-border'>
          {TOGGLE_KEYS.map((key) => (
            <SettingsRow
              key={key}
              label={t(`featureToggles.${key}`)}
              description={t(`featureToggles.${key}Desc`)}
            >
              <Switch
                checked={!!toggles[key]}
                disabled={loading || saving}
                onCheckedChange={(value) => handleToggle(key, value)}
              />
            </SettingsRow>
          ))}
        </div>
      </div>
    </div>
  )
}
