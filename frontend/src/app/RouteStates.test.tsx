import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { App } from './App'

type RouteCase = {
  path: string
  api: string
  loading: string
  empty: string
  emptyBody: unknown
}

const anonymousSession = {
  data: {
    authenticated: false,
    userId: null,
    username: null,
    role: null,
    moderatorPermissions: [],
  },
}

const page = { items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 }

const ROUTES: RouteCase[] = [
  { path: '/players', api: '/api/v1/players', loading: 'Загружаем список игроков...', empty: 'По этому запросу игроков нет.', emptyBody: { ...page, size: 24 } },
  { path: '/games', api: '/api/v1/games', loading: 'Загружаем игры...', empty: 'Игр по этому запросу нет.', emptyBody: page },
  { path: '/schedule', api: '/api/v1/schedule', loading: 'Загружаем расписание...', empty: 'Расписание пока пустое.', emptyBody: [] },
  { path: '/tournaments', api: '/api/v1/tournaments', loading: 'Загружаем турниры...', empty: 'Турниров в этом разделе нет.', emptyBody: [] },
  { path: '/rating', api: '/api/v1/rating', loading: 'Загружаем рейтинг...', empty: 'Пока нет данных для рейтинга.', emptyBody: { ...page, size: 50 } },
  { path: '/news', api: '/api/v1/news', loading: 'Загружаем новости...', empty: 'Новостей пока нет.', emptyBody: [] },
]

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function errorResponse(status: number, message: string) {
  return jsonResponse({
    status,
    code: `TEST_${status}`,
    message,
    fieldErrors: [],
    requestId: `request-${status}`,
  }, status)
}

function renderRoute(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <App />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function fetchFor(api: string, body: unknown, status = 200) {
  return vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    if (url === '/api/v1/session') return jsonResponse(anonymousSession)
    if (url.startsWith(api)) return status === 200 ? jsonResponse(body) : errorResponse(status, String(body))
    throw new Error(`Unexpected fetch: ${url}`)
  })
}

beforeEach(() => {
  vi.unstubAllGlobals()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('public route states', () => {
  for (const route of ROUTES) {
    test(`${route.path} shows loading state`, async () => {
      let resolveTarget!: (value: Response) => void
      const target = new Promise<Response>((resolve) => { resolveTarget = resolve })
      const fetchMock = vi.fn((input: RequestInfo | URL) => {
        const url = String(input)
        if (url === '/api/v1/session') return Promise.resolve(jsonResponse(anonymousSession))
        if (url.startsWith(route.api)) return target
        return Promise.reject(new Error(`Unexpected fetch: ${url}`))
      })
      vi.stubGlobal('fetch', fetchMock)
      renderRoute(route.path)
      expect(await screen.findByText(route.loading)).toBeInTheDocument()
      resolveTarget(jsonResponse(route.emptyBody))
    })

    test(`${route.path} shows empty state`, async () => {
      vi.stubGlobal('fetch', fetchFor(route.api, route.emptyBody))
      renderRoute(route.path)
      expect(await screen.findByText(route.empty)).toBeInTheDocument()
    })

    test(`${route.path} shows backend error`, async () => {
      const message = `Ошибка загрузки ${route.path}`
      vi.stubGlobal('fetch', fetchFor(route.api, message, 500))
      renderRoute(route.path)
      expect(await screen.findByText(message)).toBeInTheDocument()
    })
  }
})
describe('HTTP and network error matrix', () => {
  for (const status of [401, 403, 404, 409, 429, 500]) {
    test(`/players preserves ${status} backend message`, async () => {
      const message = `Backend ${status}`
      vi.stubGlobal('fetch', fetchFor('/api/v1/players', message, status))
      renderRoute('/players')
      expect(await screen.findByText(message)).toBeInTheDocument()
    })
  }

  test('/players shows safe fallback on network failure', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/v1/session') return jsonResponse(anonymousSession)
      if (url.startsWith('/api/v1/players')) throw new TypeError('network down')
      throw new Error(`Unexpected fetch: ${url}`)
    }))
    renderRoute('/players')
    expect(await screen.findByText('Не удалось загрузить игроков.')).toBeInTheDocument()
  })
})

describe('session and rules failures', () => {
  test('header exposes session lookup failure', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/v1/session') throw new TypeError('session unavailable')
      if (url.startsWith('/api/v1/players')) return jsonResponse({ ...page, size: 24 })
      throw new Error(`Unexpected fetch: ${url}`)
    }))
    renderRoute('/players')
    expect((await screen.findAllByRole('alert', { name: '' })).length).toBeGreaterThan(0)
    expect((await screen.findAllByText('Не удалось проверить сессию.')).length).toBeGreaterThan(0)
  })

  test('/rules exposes loading and safe error state', async () => {
    let resolveRules!: (value: Response) => void
    const rules = new Promise<Response>((resolve) => { resolveRules = resolve })
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/v1/session') return Promise.resolve(jsonResponse(anonymousSession))
      if (url === '/api/v1/rules') return rules
      return Promise.reject(new Error(`Unexpected fetch: ${url}`))
    }))
    renderRoute('/rules')
    expect(await screen.findByText('Загружаем правила...')).toBeInTheDocument()
    resolveRules(errorResponse(500, 'internal'))
    expect(await screen.findByText('Не удалось загрузить правила.')).toBeInTheDocument()
  })
})
