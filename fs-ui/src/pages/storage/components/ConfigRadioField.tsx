import { AlertCircle } from 'lucide-react'
import type { ConfigScheme } from '@/types/storage'
import { Label } from '@/components/ui/label'
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group'

interface ConfigRadioFieldProps {
  field: ConfigScheme
  value: string
  onChange: (value: string) => void
  error?: string
  idPrefix?: string
}

/**
 * schema 驱动的单选项字段（如 SFTP 认证方式）：选中项决定其余字段的显隐。
 */
export function ConfigRadioField({
  field,
  value,
  onChange,
  error,
  idPrefix = '',
}: ConfigRadioFieldProps) {
  return (
    <>
      <Label>
        {field.validation.required && (
          <span className='relative top-0.5 text-red-500'>* </span>
        )}
        {field.label}
      </Label>
      <RadioGroup
        value={value}
        onValueChange={onChange}
        className='flex flex-wrap gap-x-6 gap-y-2'
      >
        {(field.options ?? []).map((option) => {
          const optionId = `${idPrefix}${field.identifier}-${option.value}`
          return (
            <div key={option.value} className='flex items-center gap-2'>
              <RadioGroupItem id={optionId} value={option.value} />
              <Label htmlFor={optionId} className='cursor-pointer font-normal'>
                {option.label}
              </Label>
            </div>
          )
        })}
      </RadioGroup>
      {field.description && (
        <p className='text-xs text-muted-foreground'>{field.description}</p>
      )}
      {error && (
        <div className='flex items-center gap-1 text-sm text-red-500'>
          <AlertCircle className='h-3 w-3' />
          <span>{error}</span>
        </div>
      )}
    </>
  )
}
