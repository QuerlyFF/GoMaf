import { apiRequest } from './client'
import type { ApiData, TournamentAction, TournamentDetail, TournamentListItem, TournamentWrite } from './types'

export function getTournaments(view: 'upcoming' | 'completed'): Promise<TournamentListItem[]> {
  return apiRequest(`/api/v1/tournaments?view=${view}`)
}
export function getTournament(id: number): Promise<ApiData<TournamentDetail>> { return apiRequest(`/api/v1/tournaments/${id}`) }
export function createTournament(body: TournamentWrite): Promise<ApiData<TournamentAction>> { return apiRequest('/api/v1/tournaments', { method: 'POST', body: JSON.stringify(body) }) }
export function updateTournament(id: number, body: TournamentWrite): Promise<ApiData<TournamentAction>> { return apiRequest(`/api/v1/tournaments/${id}`, { method: 'PUT', body: JSON.stringify(body) }) }
export function deleteTournament(id: number): Promise<ApiData<TournamentAction>> { return apiRequest(`/api/v1/tournaments/${id}`, { method: 'DELETE' }) }
export function toggleTournamentParticipation(id: number): Promise<ApiData<TournamentAction>> { return apiRequest(`/api/v1/tournaments/${id}/participation`, { method: 'POST' }) }
export function inviteTournamentPlayer(id: number, username: string): Promise<ApiData<TournamentAction>> { return apiRequest(`/api/v1/tournaments/${id}/invitations`, { method: 'POST', body: JSON.stringify({ username }) }) }
export function removeTournamentInvitation(id: number, userId: number): Promise<ApiData<TournamentAction>> { return apiRequest(`/api/v1/tournaments/${id}/invitations/${userId}`, { method: 'DELETE' }) }
