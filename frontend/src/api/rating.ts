import { apiRequest } from './client'
import type { ApiPage, RatingRow } from './types'

export function getRating(sort: 'points' | 'weighted', page = 0, size = 50): Promise<ApiPage<RatingRow>> {
  const query = new URLSearchParams({ sort, page: String(page), size: String(size) })
  return apiRequest(`/api/v1/rating?${query.toString()}`)
}
