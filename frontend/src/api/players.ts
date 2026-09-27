import { apiRequest } from './client'
import type {
  ApiData,
  ApiPage,
  PlayerAdministrationAction,
  PlayerAdministrationCapabilities,
  PlayerGame,
  PlayerListItem,
  PlayerProfile,
} from './types'

export type PlayersListParams = {
  q: string
  page: number
  size?: number
  sort: string
}

export function getPlayers(params: PlayersListParams): Promise<ApiPage<PlayerListItem>> {
  const query = new URLSearchParams({
    q: params.q,
    page: String(params.page),
    sort: params.sort,
  })
  if (params.size != null) query.set('size', String(params.size))
  return apiRequest(`/api/v1/players?${query.toString()}`)
}

export function getPlayer(id: number): Promise<ApiData<PlayerProfile>> {
  return apiRequest(`/api/v1/players/${id}`)
}

export function getPlayerGames(id: number, page = 0, size = 20): Promise<ApiPage<PlayerGame>> {
  const query = new URLSearchParams({ page: String(page), size: String(size) })
  return apiRequest(`/api/v1/players/${id}/games?${query.toString()}`)
}

export function getPlayerAdministration(id: number): Promise<ApiData<PlayerAdministrationCapabilities>> {
  return apiRequest(`/api/v1/players/${id}/administration`)
}

export function updatePlayerRating(id: number, rating: number): Promise<ApiData<PlayerAdministrationAction>> {
  return apiRequest(`/api/v1/players/${id}/rating`, {
    method: 'PATCH',
    body: JSON.stringify({ rating }),
  })
}

export function updatePlayerUsername(
  id: number,
  username: string,
  currentPassword: string,
): Promise<ApiData<PlayerAdministrationAction>> {
  return apiRequest(`/api/v1/players/${id}/username`, {
    method: 'PATCH',
    body: JSON.stringify({ username, currentPassword }),
  })
}

export function resetPlayerPassword(
  id: number,
  password: string,
  currentPassword: string,
): Promise<ApiData<PlayerAdministrationAction>> {
  return apiRequest(`/api/v1/players/${id}/password`, {
    method: 'PUT',
    body: JSON.stringify({ password, currentPassword }),
  })
}

export function resetPlayerAvatar(id: number, currentPassword: string): Promise<ApiData<PlayerAdministrationAction>> {
  return apiRequest(`/api/v1/players/${id}/avatar`, {
    method: 'DELETE',
    body: JSON.stringify({ currentPassword }),
  })
}

export function updatePlayerProfile(id: number, aboutMe: string): Promise<ApiData<PlayerAdministrationAction>> {
  return apiRequest(`/api/v1/players/${id}/profile`, {
    method: 'PATCH',
    body: JSON.stringify({ aboutMe }),
  })
}

export function deletePlayer(
  id: number,
  currentPassword: string,
  confirmation: string,
): Promise<ApiData<PlayerAdministrationAction>> {
  return apiRequest(`/api/v1/players/${id}`, {
    method: 'DELETE',
    body: JSON.stringify({ currentPassword, confirmation }),
  })
}
