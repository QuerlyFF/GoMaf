import { apiRequest } from './client'
import type { ApiData, ApiPage, PlayerGame, ProfileAction, SelfProfile } from './types'

export function getSelfProfile(): Promise<ApiData<SelfProfile>> {
  return apiRequest('/api/v1/profile')
}

export function getSelfProfileGames(page = 0, size = 20): Promise<ApiPage<PlayerGame>> {
  const query = new URLSearchParams({ page: String(page), size: String(size) })
  return apiRequest(`/api/v1/profile/games?${query.toString()}`)
}

export function updateSelfAbout(aboutMe: string): Promise<ApiData<ProfileAction>> {
  return apiRequest('/api/v1/profile/about', {
    method: 'PATCH',
    body: JSON.stringify({ aboutMe }),
  })
}

export function uploadSelfAvatar(file: File): Promise<ApiData<ProfileAction>> {
  const body = new FormData()
  body.set('avatar', file)
  return apiRequest('/api/v1/profile/avatar', {
    method: 'POST',
    body,
  })
}

export function resetSelfAvatar(): Promise<ApiData<ProfileAction>> {
  return apiRequest('/api/v1/profile/avatar', { method: 'DELETE' })
}

export function uploadSelfBanner(file: File): Promise<ApiData<ProfileAction>> {
  const body = new FormData()
  body.set('banner', file)
  return apiRequest('/api/v1/profile/banner', {
    method: 'POST',
    body,
  })
}

export function resetSelfBanner(): Promise<ApiData<ProfileAction>> {
  return apiRequest('/api/v1/profile/banner', { method: 'DELETE' })
}
