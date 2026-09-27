import { apiRequest } from './client'
import type { ApiData, ApiPage, GameAction, GameDetail, GameListItem, GameWrite } from './types'

export function getGames(q = '', page = 0, size = 20): Promise<ApiPage<GameListItem>> {
  const query = new URLSearchParams({ q, page: String(page), size: String(size) })
  return apiRequest(`/api/v1/games?${query.toString()}`)
}

export function getGame(id: number): Promise<ApiData<GameDetail>> {
  return apiRequest(`/api/v1/games/${id}`)
}

export function createGame(body: GameWrite): Promise<ApiData<GameAction>> {
  return apiRequest('/api/v1/games', { method: 'POST', body: JSON.stringify(body) })
}

export function updateGame(id: number, body: GameWrite): Promise<ApiData<GameAction>> {
  return apiRequest(`/api/v1/games/${id}`, { method: 'PUT', body: JSON.stringify(body) })
}

export function deleteGame(id: number): Promise<ApiData<GameAction>> {
  return apiRequest(`/api/v1/games/${id}`, { method: 'DELETE' })
}
