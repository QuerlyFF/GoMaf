import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { App } from './App'
import type { Session } from '../api/types'

const anonymous: Session = { authenticated: false, userId: null, username: null, role: null, moderatorPermissions: [] }
const player: Session = { authenticated: true, userId: 7, username: 'player7', role: 'PLAYER', moderatorPermissions: [] }
const moderator: Session = { authenticated: true, userId: 8, username: 'mod8', role: 'MODERATOR', moderatorPermissions: [] }
const admin: Session = { authenticated: true, userId: 1, username: 'admin', role: 'ADMIN', moderatorPermissions: [] }

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}
function apiError(status: number, message: string) {
  return json({ status, code: `TEST_${status}`, message, fieldErrors: [], requestId: `req-${status}` }, status)
}
function renderApp(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}><App /></MemoryRouter>
    </QueryClientProvider>,
  )
}
function sessionFetch(session: Session, extra: (url: string, init?: RequestInit) => Promise<Response>) {
  return vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    if (url === '/api/v1/session') return json({ data: session })
    if (url === '/api/v1/csrf') return json({ data: { headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'test-csrf' } })
    return extra(url, init)
  })
}
beforeEach(() => vi.unstubAllGlobals())
afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('guarded route states', () => {
  test('anonymous profile redirects to login', async () => {
    vi.stubGlobal('fetch', sessionFetch(anonymous, async (url) => { throw new Error(`Unexpected ${url}`) }))
    renderApp('/profile')
    expect(await screen.findByRole('heading', { name: 'Войти в аккаунт' })).toBeInTheDocument()
  })
  test('player cannot open game management form', async () => {
    vi.stubGlobal('fetch', sessionFetch(player, async (url) => { throw new Error(`Unexpected ${url}`) }))
    renderApp('/games/new')
    expect(await screen.findByText('Недостаточно прав для управления играми.')).toBeInTheDocument()
  })

  test('moderator without permission cannot open tournament form', async () => {
    vi.stubGlobal('fetch', sessionFetch(moderator, async (url) => { throw new Error(`Unexpected ${url}`) }))
    renderApp('/tournaments/new')
    expect(await screen.findByText('Недостаточно прав.')).toBeInTheDocument()
  })

  test('non-admin is redirected away from moderator administration', async () => {
    vi.stubGlobal('fetch', sessionFetch(player, async (url) => {
      if (url.startsWith('/api/v1/players?')) return json({ items: [], page: 0, size: 24, totalItems: 0, totalPages: 0 })
      throw new Error(`Unexpected ${url}`)
    }))
    renderApp('/admin/moderators')
    expect(await screen.findByRole('heading', { name: 'Игроки клуба' })).toBeInTheDocument()
  })
})

describe('detail and edit route errors', () => {
  const cases = [
    ['/players/999', '/api/v1/players/999', 'Игрок отсутствует'],
    ['/games/999', '/api/v1/games/999', 'Игра отсутствует'],
    ['/tournaments/999', '/api/v1/tournaments/999', 'Турнир отсутствует'],
  ] as const
  for (const [path, api, message] of cases) {
    test(`${path} preserves backend 404 message`, async () => {
      vi.stubGlobal('fetch', sessionFetch(anonymous, async (url) => {
        if (url === api || url.startsWith(`${api}/games`)) return apiError(404, message)
        throw new Error(`Unexpected ${url}`)
      }))
      renderApp(path)
      expect(await screen.findByText(message)).toBeInTheDocument()
    })
  }

  test('game edit preserves backend 500 message', async () => {
    vi.stubGlobal('fetch', sessionFetch(admin, async (url) => {
      if (url === '/api/v1/games/42') return apiError(500, 'Не удалось открыть игру')
      throw new Error(`Unexpected ${url}`)
    }))
    renderApp('/games/42/edit')
    expect(await screen.findByText('Не удалось открыть игру')).toBeInTheDocument()
  })

  test('tournament edit preserves backend 409 message', async () => {
    vi.stubGlobal('fetch', sessionFetch(admin, async (url) => {
      if (url === '/api/v1/tournaments/42') return apiError(409, 'Турнир конфликтует')
      throw new Error(`Unexpected ${url}`)
    }))
    renderApp('/tournaments/42/edit')
    expect(await screen.findByText('Турнир конфликтует')).toBeInTheDocument()
  })
})
describe('authenticated profile states', () => {
  const profileBody = { data: { id: 7, username: 'player7', role: 'PLAYER', aboutMe: '', gamesPlayed: 0, wins: 0, losses: 0, winRate: 0, averageScore: 0, rating: 0, avatarUrl: '/api/v1/profile/avatar', bannerUrl: '/api/v1/players/7/banner', gamesUrl: '/api/v1/profile/games' } }

  test('profile exposes backend 500 instead of blank content', async () => {
    vi.stubGlobal('fetch', sessionFetch(player, async (url) => {
      if (url === '/api/v1/profile') return apiError(500, 'Профиль временно недоступен')
      if (url.startsWith('/api/v1/profile/games')) return json({ items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 })
      throw new Error(`Unexpected ${url}`)
    }))
    renderApp('/profile')
    expect(await screen.findByText('Профиль временно недоступен')).toBeInTheDocument()
  })

  test('profile renders empty history explicitly', async () => {
    vi.stubGlobal('fetch', sessionFetch(player, async (url) => {
      if (url === '/api/v1/profile') return json(profileBody)
      if (url.startsWith('/api/v1/profile/games')) return json({ items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 })
      throw new Error(`Unexpected ${url}`)
    }))
    renderApp('/profile')
    expect(await screen.findByText('В доступной истории игр пока нет.')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'player7' })).toBeInTheDocument()
  })

  test('profile exposes history network failure', async () => {
    vi.stubGlobal('fetch', sessionFetch(player, async (url) => {
      if (url === '/api/v1/profile') return json(profileBody)
      if (url.startsWith('/api/v1/profile/games')) throw new TypeError('network down')
      throw new Error(`Unexpected ${url}`)
    }))
    renderApp('/profile')
    expect(await screen.findByText('Не удалось загрузить историю.')).toBeInTheDocument()
  })
})
describe('ADMIN console states', () => {
  test('admin console exposes all query failures', async () => {
    vi.stubGlobal('fetch', sessionFetch(admin, async (url) => {
      if (url === '/api/v1/admin/moderators/defaults') return apiError(500, 'Ошибка разрешений')
      if (url.startsWith('/api/v1/admin/moderators/eligible')) return apiError(429, 'Поиск игроков ограничен')
      if (url.startsWith('/api/v1/admin/moderators?')) return apiError(403, 'Список модераторов недоступен')
      throw new Error(`Unexpected ${url}`)
    }))
    renderApp('/admin/moderators')
    expect(await screen.findByText('Ошибка разрешений')).toBeInTheDocument()
    expect(await screen.findByText('Поиск игроков ограничен')).toBeInTheDocument()
    expect(await screen.findByText('Список модераторов недоступен')).toBeInTheDocument()
  })

  test('admin console exposes explicit empty moderator list', async () => {
    vi.stubGlobal('fetch', sessionFetch(admin, async (url) => {
      if (url === '/api/v1/admin/moderators/defaults') return json({ data: { scopes: [], defaults: {} } })
      if (url.startsWith('/api/v1/admin/moderators/eligible')) return json({ items: [], page: 0, size: 25, totalItems: 0, totalPages: 0 })
      if (url.startsWith('/api/v1/admin/moderators?')) return json({ items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 })
      throw new Error(`Unexpected ${url}`)
    }))
    renderApp('/admin/moderators')
    expect(await screen.findByText('Модераторы не найдены.')).toBeInTheDocument()
  })
})
describe('login and registration error states', () => {
  test('login preserves 429 backend message', async () => {
    const user = userEvent.setup()
    vi.stubGlobal('fetch', sessionFetch(anonymous, async (url) => {
      if (url === '/api/v1/auth/login') return apiError(429, 'Слишком много попыток входа')
      throw new Error(`Unexpected ${url}`)
    }))
    renderApp('/login')
    await user.type(await screen.findByLabelText('Ник'), 'tester')
    await user.type(screen.getByLabelText('Пароль'), 'long-password-123')
    await user.type(screen.getByLabelText('Код с картинки'), 'ABCDE')
    await user.click(screen.getByRole('button', { name: 'Войти' }))
    expect(await screen.findByText('Слишком много попыток входа')).toBeInTheDocument()
  })

  test('registration preserves 409 backend message', async () => {
    const user = userEvent.setup()
    vi.stubGlobal('fetch', sessionFetch(anonymous, async (url) => {
      if (url === '/api/v1/auth/register') return apiError(409, 'Ник уже занят')
      throw new Error(`Unexpected ${url}`)
    }))
    renderApp('/register')
    await user.type(await screen.findByLabelText('Ник'), 'tester')
    await user.type(screen.getByLabelText('Пароль'), 'long-password-123')
    await user.type(screen.getByLabelText('Повторите пароль'), 'long-password-123')
    await user.type(screen.getByLabelText('Код с картинки'), 'ABCDE')
    await user.click(screen.getByRole('button', { name: 'Создать аккаунт' }))
    expect(await screen.findByText('Ник уже занят')).toBeInTheDocument()
  })
})

describe('invalid identifiers fail visibly without network ambiguity', () => {
  test.each([
    ['/players/not-a-number', 'Некорректный идентификатор игрока.'],
    ['/games/not-a-number', 'Некорректный идентификатор игры.'],
  ])('%s', async (path, expected) => {
    vi.stubGlobal('fetch', sessionFetch(anonymous, async (url) => { throw new Error(`Unexpected ${url}`) }))
    renderApp(path)
    expect(await screen.findByText(expected)).toBeInTheDocument()
  })
})
