export type StudioThemeId = 'linen' | 'ocean' | 'rose' | 'forest' | 'sunset'

export interface StudioTheme {
  id: StudioThemeId
  name: string
  description: string
  swatch: string
}

export const studioThemes: StudioTheme[] = [
  { id: 'linen', name: '亚麻暖白', description: '经典的工作室中性色', swatch: '#a18455' },
  { id: 'ocean', name: '海岸蓝', description: '清爽、沉静的蓝绿色', swatch: '#39788a' },
  { id: 'rose', name: '玫瑰粉', description: '柔和而有温度的粉色', swatch: '#b25570' },
  { id: 'forest', name: '森林绿', description: '自然、稳定的绿色', swatch: '#568064' },
  { id: 'sunset', name: '落日橙', description: '明亮、有活力的橙色', swatch: '#bd6947' },
]

const STORAGE_KEY = 'atelier-studio-theme'

export function useTheme() {
  const selectedTheme = useState<StudioThemeId>('atelier-selected-theme', () => 'linen')
  const initialized = useState('atelier-theme-initialized', () => false)

  if (import.meta.client && !initialized.value) {
    const saved = window.localStorage.getItem(STORAGE_KEY) as StudioThemeId | null
    const valid = studioThemes.some((theme) => theme.id === saved)
    selectedTheme.value = valid ? saved! : 'linen'
    document.documentElement.dataset.theme = selectedTheme.value
    initialized.value = true
  }

  function setTheme(id: StudioThemeId) {
    if (!studioThemes.some((theme) => theme.id === id)) return
    selectedTheme.value = id
    if (import.meta.client) {
      document.documentElement.dataset.theme = id
      window.localStorage.setItem(STORAGE_KEY, id)
    }
  }

  return {
    themes: studioThemes,
    selectedTheme,
    setTheme,
  }
}
