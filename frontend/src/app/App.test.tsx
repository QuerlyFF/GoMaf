import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, expect, test, vi } from 'vitest'
import { App } from './App'
import { getSession } from '../api/client'
import { getPlayer, getPlayerAdministration, getPlayerGames, getPlayers } from '../api/players'

vi.mock('../api/client', async (importOriginal) => {
  const original = await importOriginal<typeof import('../api/client')>()
  return {
    ...original,
    getSession: vi.fn(),
    logout: vi.fn(async () => undefined),
  }
})

vi.mock('../api/players', () => ({
  getPlayers: vi.fn(),
  getPlayer: vi.fn(),
  getPlayerGames: vi.fn(),
  getPlayerAdministration: vi.fn(),
  updatePlayerRating: vi.fn(),
  updatePlayerUsername: vi.fn(),
  resetPlayerPassword: vi.fn(),
  resetPlayerAvatar: vi.fn(),
  updatePlayerProfile: vi.fn(),
  deletePlayer: vi.fn(),
}))

beforeEach(() => {
  vi.mocked(getSession).mockResolvedValue({
    data: { authenticated: false, userId: null, username: null, role: null, moderatorPermissions: [] },
  })
  vi.mocked(getPlayers).mockResolvedValue({
    items: [], page: 0, size: 24, totalItems: 0, totalPages: 0,
  })
  vi.mocked(getPlayerGames).mockResolvedValue({
    items: [], page: 0, size: 20, totalItems: 0, totalPages: 0,
  })
})

test('renders URL-backed players list with long username safely', async () => {
  const longUsername = 'Very_Long_Player_Name_1234567890'
  vi.mocked(getPlayers).mockResolvedValue({
    items: [{
      id: 42,
      username: longUsername,
      gamesPlayed: 12,
      wins: 7,
      losses: 5,
      winRate: 7 / 12,
      averageScore: 2.4,
      rating: 1337,
      avatarUrl: '/api/v1/players/42/avatar',
    }],
    page: 1,
    size: 24,
    totalItems: 30,
    totalPages: 2,
  })

  renderApp('/players?q=Very&sort=username%2Casc&page=1')

  expect(await screen.findByRole('link', { name: longUsername })).toBeInTheDocument()
  expect(screen.getByDisplayValue('Very')).toBeInTheDocument()
  expect(screen.getByDisplayValue('Ник: А-Я')).toBeInTheDocument()
  expect(screen.getByText('Страница 2 из 2')).toBeInTheDocument()
  expect(screen.getByText('Средний доп. балл')).toBeInTheDocument()
  expect(screen.getByText('2,4')).toBeInTheDocument()
})

test('profile shows backend-owned admin controls for authenticated admin', async () => {
  vi.mocked(getSession).mockResolvedValue({
    data: { authenticated: true, userId: 1, username: 'admin', role: 'ADMIN', moderatorPermissions: [] },
  })
  vi.mocked(getPlayer).mockResolvedValue({
    data: {
      id: 77,
      username: 'target_player',
      aboutMe: 'Профиль',
      gamesPlayed: 4,
      wins: 2,
      losses: 2,
      winRate: 0.5,
      averageScore: 1.5,
      rating: 1200,
      avatarUrl: '/api/v1/players/77/avatar',
      bannerUrl: '/api/v1/players/77/banner',
      gamesUrl: '/api/v1/players/77/games',
    },
  })
  vi.mocked(getPlayerAdministration).mockResolvedValue({
    data: {
      manageable: true,
      rating: true,
      username: true,
      password: true,
      avatarReset: true,
      profileEdit: true,
      deleteAccount: true,
      sensitiveActionsRequireCurrentPassword: true,
    },
  })

  renderApp('/players/77')

  expect(await screen.findByRole('heading', { name: 'target_player' })).toBeInTheDocument()
  expect(await screen.findByRole('heading', { name: 'Администрирование игрока' })).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Сохранить рейтинг' })).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Изменить ник' })).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Сменить пароль' })).toBeInTheDocument()
})

function renderApp(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <App />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}
