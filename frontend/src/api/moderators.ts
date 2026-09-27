import { apiRequest } from './client'
import type {
  ApiData,
  ApiPage,
  EligibleModeratorPlayer,
  ModeratorAdminAction,
  ModeratorAdminRow,
  ModeratorDefaults,
} from './types'

export function getModeratorDefaults(): Promise<ApiData<ModeratorDefaults>> {
  return apiRequest('/api/v1/admin/moderators/defaults')
}

export function getModerators(q: string, page = 0, size = 20): Promise<ApiPage<ModeratorAdminRow>> {
  const params = new URLSearchParams({ q, page: String(page), size: String(size) })
  return apiRequest(`/api/v1/admin/moderators?${params}`)
}

export function getEligibleModeratorPlayers(q: string, page = 0, size = 25): Promise<ApiPage<EligibleModeratorPlayer>> {
  const params = new URLSearchParams({ q, page: String(page), size: String(size) })
  return apiRequest(`/api/v1/admin/moderators/eligible?${params}`)
}

export function updateModeratorDefaults(defaults: Record<string, boolean>, currentPassword: string): Promise<ApiData<ModeratorAdminAction>> {
  return apiRequest('/api/v1/admin/moderators/defaults', {
    method: 'PUT',
    body: JSON.stringify({ defaults, currentPassword }),
  })
}

export function assignModerator(userId: number, currentPassword: string): Promise<ApiData<ModeratorAdminAction>> {
  return apiRequest(`/api/v1/admin/moderators/${userId}`, {
    method: 'POST',
    body: JSON.stringify({ currentPassword }),
  })
}

export function removeModerator(userId: number, currentPassword: string): Promise<ApiData<ModeratorAdminAction>> {
  return apiRequest(`/api/v1/admin/moderators/${userId}`, {
    method: 'DELETE',
    body: JSON.stringify({ currentPassword }),
  })
}

export function updateModeratorOverrides(
  userId: number,
  scopes: Record<string, 'INHERIT' | 'ALLOW' | 'DENY'>,
  currentPassword: string,
): Promise<ApiData<ModeratorAdminAction>> {
  return apiRequest(`/api/v1/admin/moderators/${userId}/scopes`, {
    method: 'PUT',
    body: JSON.stringify({ scopes, currentPassword }),
  })
}
