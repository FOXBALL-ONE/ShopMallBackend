import type { SystemStatusSnapshot } from '~/types/system-status'

export const useSystemStatusApi = () => {
  const config = useRuntimeConfig()
  const adminApiBase = (config.public.adminApiBase as string) || 'http://127.0.0.1:8080/admin/api'
  const { get } = useHttp(adminApiBase)

  return {
    getStatus() {
      return get<SystemStatusSnapshot>('/system-status')
    },
  }
}
