import type {
  AdminUserBatchMutation,
  AdminUserBatchResponse,
  AdminUserDetail,
  AdminUserListQuery,
  AdminUserMutation,
  AdminUserPasswordMutation,
  AdminUserPage,
} from '~/types/user'

export const useUserApi = () => {
  const runtimeConfig = useRuntimeConfig()
  const adminApiBase = (runtimeConfig.public.adminApiBase as string) || 'http://127.0.0.1:8080/admin/api'
  const { get, post, put, delete: remove } = useHttp(adminApiBase)

  return {
    list(query: AdminUserListQuery) {
      return get<AdminUserPage>('/users', { ...query })
    },

    getOne(userId: number) {
      return get<AdminUserDetail>(`/users/${userId}`)
    },

    create(payload: AdminUserMutation) {
      return post<AdminUserDetail, AdminUserMutation>('/users', payload)
    },

    update(userId: number, payload: AdminUserMutation) {
      return put<AdminUserDetail, AdminUserMutation>(`/users/${userId}`, payload)
    },

    updatePassword(userId: number, payload: AdminUserPasswordMutation) {
      return put<{ id: number; password_changed: true }, AdminUserPasswordMutation>(`/users/${userId}/password`, payload)
    },

    updateBatch(payload: AdminUserBatchMutation) {
      return put<AdminUserBatchResponse, AdminUserBatchMutation>('/users/batch', payload)
    },

    deleteOne(userId: number) {
      return remove<{ id: number; status: 'DELETED'; enabled: false }>(`/users/${userId}`)
    },

    deleteBatch(ids: number[]) {
      return remove<{ ids: number[]; deleted: number }>('/users/batch', { ids })
    },

    purgeOne(userId: number) {
      return remove<{ id: number; status: 'PURGED'; enabled: false }>(`/users/${userId}/purge`)
    },

    purgeBatch(ids: number[]) {
      return remove<{ ids: number[]; purged: number }>('/users/batch/purge', { ids })
    },
  }
}
