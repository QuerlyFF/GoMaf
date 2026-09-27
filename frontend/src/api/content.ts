import { apiRequest } from './client'
import type { ApiData, ContentAction, NewsItem, RulesPageData } from './types'

export function getNews(): Promise<NewsItem[]> { return apiRequest('/api/v1/news') }
export function createNews(title: string, content: string): Promise<ApiData<ContentAction>> { return apiRequest('/api/v1/news', { method: 'POST', body: JSON.stringify({ title, content }) }) }
export function updateNews(id: number, title: string, content: string): Promise<ApiData<ContentAction>> { return apiRequest(`/api/v1/news/${id}`, { method: 'PUT', body: JSON.stringify({ title, content }) }) }
export function deleteNews(id: number): Promise<ApiData<ContentAction>> { return apiRequest(`/api/v1/news/${id}`, { method: 'DELETE' }) }
export function getRules(): Promise<ApiData<RulesPageData>> { return apiRequest('/api/v1/rules') }
export function updateRules(title: string, content: string): Promise<ApiData<ContentAction>> { return apiRequest('/api/v1/rules', { method: 'PUT', body: JSON.stringify({ title, content }) }) }
