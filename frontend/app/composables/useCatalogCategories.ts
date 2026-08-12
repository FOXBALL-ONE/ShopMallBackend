import type { CatalogCategory } from '~/data/catalog'

export function useCatalogCategories() {
  const catalogApi = useCatalogApi()

  return useAsyncData<CatalogCategory[]>(
    'catalog-categories',
    () => catalogApi.listCategories(),
    { default: () => [] }
  )
}
