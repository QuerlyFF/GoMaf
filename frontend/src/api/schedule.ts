import { apiRequest } from './client'
import type { ApiData, ScheduleAction, ScheduleEntry, ScheduleWrite } from './types'

export function getSchedule(from?: string, to?: string): Promise<ScheduleEntry[]> {
  if (!from && !to) return apiRequest('/api/v1/schedule')
  const params = new URLSearchParams()
  if (from) params.set('from', from)
  if (to) params.set('to', to)
  return apiRequest('/api/v1/schedule?' + params.toString())
}

export function createScheduleEntry(body: ScheduleWrite): Promise<ApiData<ScheduleAction>> {
  return apiRequest('/api/v1/schedule', { method: 'POST', body: JSON.stringify(body) })
}

export function updateScheduleEntry(id: number, body: ScheduleWrite): Promise<ApiData<ScheduleAction>> {
  return apiRequest(`/api/v1/schedule/${id}`, { method: 'PUT', body: JSON.stringify(body) })
}

export function deleteScheduleEntry(id: number): Promise<ApiData<ScheduleAction>> {
  return apiRequest(`/api/v1/schedule/${id}`, { method: 'DELETE' })
}

export function toggleScheduleParticipation(id: number): Promise<ApiData<ScheduleAction>> {
  return apiRequest(`/api/v1/schedule/${id}/participation`, { method: 'POST' })
}
