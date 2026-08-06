import { useDialog } from 'naive-ui'

interface DeleteConfirmationOptions {
  title: string
  content: string
  positiveText: string
  tone?: 'warning' | 'error'
  onConfirm: () => void | Promise<void>
}

export function useDeleteConfirmation() {
  const dialog = useDialog()

  function confirmDeleteRequest(options: DeleteConfirmationOptions) {
    const openDialog = options.tone === 'error' ? dialog.error : dialog.warning
    openDialog({
      title: options.title,
      content: options.content,
      positiveText: options.positiveText,
      negativeText: '取消',
      onPositiveClick: options.onConfirm,
    })
  }

  return { confirmDeleteRequest }
}
