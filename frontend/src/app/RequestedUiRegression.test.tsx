import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { RulesPage } from '../content/RulesPage'
import { GameDetailPage } from '../games/GameDetailPage'
import { GameFormPage } from '../games/GameFormPage'
import { PlayersPage } from '../players/PlayersPage'
import { PlayerProfilePage } from '../players/PlayerProfilePage'
import { getRules } from '../api/content'
import { getGame } from '../api/games'
import { getPlayer, getPlayerGames, getPlayers } from '../api/players'

vi.mock('../api/content', () => ({ getRules: vi.fn(), updateRules: vi.fn() }))
vi.mock('../api/games', () => ({ getGame: vi.fn(), deleteGame: vi.fn(), createGame: vi.fn(), updateGame: vi.fn() }))
vi.mock('../api/players', () => ({
  getPlayers: vi.fn(), getPlayer: vi.fn(), getPlayerGames: vi.fn(),
  getPlayerAdministration: vi.fn(), updatePlayerRating: vi.fn(), updatePlayerUsername: vi.fn(),
  resetPlayerPassword: vi.fn(), resetPlayerAvatar: vi.fn(), updatePlayerProfile: vi.fn(), deletePlayer: vi.fn(),
}))

beforeEach(() => { cleanup(); vi.clearAllMocks() })
afterEach(cleanup)

function shell(node: React.ReactNode, path = '/', routePath = '*') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}><Routes><Route path={routePath} element={node} /></Routes></MemoryRouter></QueryClientProvider>)
}
test('rules keep Java-era section/article navigation structure', async () => {
  vi.mocked(getRules).mockResolvedValue({ data: {
    title: 'Правила', editableContent: null, manageable: false,
    document: {
      introLines: ['CRISTALIX MAFIA', 'Официальные правила', 'Редакция 2026'],
      sections: [{
        anchor: 'section-1', title: 'Раздел 1',
        navigation: [{ anchor: 'article-1', title: 'Статья 1' }],
        blocks: [
          { type: 'article', anchor: 'article-1', label: 'Статья 1', text: '' },
          { type: 'clause', anchor: '', label: '1.1.', text: 'Текст пункта' },
          { type: 'continuation', anchor: '', label: '', text: 'Продолжение пункта' },
        ],
      }],
    },
  } })
  shell(<RulesPage />, '/rules')
  expect(await screen.findByText('Навигация по правилам')).toBeInTheDocument()
  expect(screen.getByRole('link', { name: 'Раздел 1' })).toHaveAttribute('href', '#section-1')
  expect(screen.getByRole('link', { name: 'Статья 1' })).toHaveAttribute('href', '#article-1')
  expect(screen.getByRole('link', { name: 'Наверх' })).toHaveAttribute('href', '#rules-top')
  expect(screen.getByText('Продолжение пункта').closest('.rule-continuation')).not.toBeNull()
})
test('players expose average additional score', async () => {
  vi.mocked(getPlayers).mockResolvedValue({
    items: [{ id: 7, username: 'Player7', gamesPlayed: 10, wins: 6, losses: 4, winRate: .6, averageScore: 1.75, rating: 1500, avatarUrl: '/avatar/7' }],
    page: 0, size: 24, totalItems: 1, totalPages: 1,
  })
  shell(<PlayersPage />, '/players')
  expect(await screen.findByRole('link', { name: 'Player7' })).toBeInTheDocument()
  expect(screen.getByText('Средний доп. балл')).toBeInTheDocument()
  expect(screen.getByText('1,75')).toBeInTheDocument()
})

test('player history links each visible game to its detail page', async () => {
  vi.mocked(getPlayer).mockResolvedValue({ data: {
    id: 7, username: 'Player7', aboutMe: '', gamesPlayed: 1, wins: 1, losses: 0,
    winRate: 1, averageScore: 2, rating: 1500, avatarUrl: '/avatar/7', bannerUrl: '/api/v1/players/7/banner', gamesUrl: '/api/v1/players/7/games',
  } })
  vi.mocked(getPlayerGames).mockResolvedValue({
    items: [{ gameId: 501, name: 'Игра 501', gameDate: '2026-09-16', role: 'CIVILIAN', roleDisplayName: 'Мирный', totalPoints: 2, additionalPoints: 1, won: true }],
    page: 0, size: 20, totalItems: 1, totalPages: 1,
  })
  const { container } = shell(<PlayerProfilePage session={undefined} />, '/players/7', '/players/:id')
  expect(await screen.findByRole('link', { name: 'Игра 501' })).toHaveAttribute('href', '/games/501')
  const banner = container.querySelector('img.profile-banner')
  const avatar = container.querySelector('img.profile-avatar')
  expect(banner).toHaveAttribute('src', '/api/v1/players/7/banner?width=1280')
  expect(banner).toHaveAttribute('fetchpriority', 'high')
  expect(banner).toHaveAttribute('loading', 'eager')
  const avatarPicture = avatar?.closest('picture')
  expect(banner).toHaveAttribute('srcset', '/api/v1/players/7/banner?width=640 640w, /api/v1/players/7/banner?width=768 768w, /api/v1/players/7/banner?width=960 960w, /api/v1/players/7/banner?width=1280 1280w')
  expect(banner).toHaveAttribute('sizes', '(max-width: 640px) calc(100vw - 26px), (max-width: 720px) calc(100vw - 42px), (max-width: 1024px) 960px, 1280px')
  expect(avatar).toHaveAttribute('src', '/avatar/7?size=256')
  expect(avatar).toHaveAttribute('srcset', '/avatar/7?size=256 1x, /avatar/7?size=512 2x')
  expect(avatarPicture?.querySelector('source[media="(max-width: 430px)"]')).toHaveAttribute('srcset', '/avatar/7?size=128 1x, /avatar/7?size=256 2x')
})
test('game detail uses the requested score table with PU and LH', async () => {
  vi.mocked(getGame).mockResolvedValue({ data: {
    id: 501, name: 'Игра 501', gameDate: '2026-09-16', host: 'Host', winner: 'CIVILIANS', winnerDisplayName: 'Мирные',
    summary: '', hiddenFromPublic: false, tournamentId: null, tournamentName: null, videoLinks: [], visibilityUsernames: [], manageable: false,
    players: [{
      seatNumber: 1, userId: 7, displayName: 'Player7', role: 'CIVILIAN', roleDisplayName: 'Мирный', firstKilled: true,
      bestMove1: 2, bestMove2: 3, bestMove3: 4, victoryPoints: 1, firstKilledPoints: 0.5, bestMovePoints: 1,
      bonusPoints: 0, penaltyPoints: 0, additionalPoints: 1.5, totalPoints: 2.5, won: true,
    }],
  } })
  shell(<GameDetailPage />, '/games/501', '/games/:id')
  expect(await screen.findByRole('heading', { name: 'Игра 501' })).toBeInTheDocument()
  expect(screen.getByTitle('Первый убитый')).toHaveTextContent('ПУ')
  expect(screen.getByTitle('Лучший ход')).toHaveTextContent('ЛХ')
  expect(screen.getByRole('region', { name: 'Таблица игроков и очков' })).toBeInTheDocument()
  expect(screen.getByText('2, 3, 4')).toBeInTheDocument()
  expect(screen.getByRole('link', { name: 'Player7' })).toHaveAttribute('href', '/players/7')
})

test('game creation restores Java-style table and registered-player picker', async () => {
  vi.mocked(getPlayers).mockResolvedValue({
    items: [
      { id: 7, username: 'RegisteredOne', gamesPlayed: 10, wins: 6, losses: 4, winRate: .6, averageScore: 1.75, rating: 1500, avatarUrl: '/avatar/7' },
      { id: 8, username: 'RegisteredTwo', gamesPlayed: 3, wins: 1, losses: 2, winRate: 1 / 3, averageScore: .5, rating: 1200, avatarUrl: '/avatar/8' },
    ],
    page: 0, size: 100, totalItems: 2, totalPages: 1,
  })
  const session = { authenticated: true, userId: 1, username: 'admin', role: 'ADMIN' as const, moderatorPermissions: [] }
  const { container } = shell(<GameFormPage session={session} />, '/games/new', '/games/new')
  expect(await screen.findByRole('heading', { name: 'Добавить игру' })).toBeInTheDocument()
  await screen.findByRole('heading', { name: 'Игроки' })
  await new Promise((resolve) => setTimeout(resolve, 0))
  expect(container.querySelector('datalist#registered-game-players option[value="RegisteredOne"]')).not.toBeNull()
  expect(screen.getByRole('columnheader', { name: '№' })).toBeInTheDocument()
  expect(screen.getByRole('columnheader', { name: 'Игрок' })).toBeInTheDocument()
  expect(screen.getByRole('columnheader', { name: 'ПУ' })).toBeInTheDocument()
  expect(screen.getByRole('columnheader', { name: 'ЛХ 1' })).toBeInTheDocument()
  expect(screen.getByLabelText('Игрок 1')).toHaveAttribute('list', 'registered-game-players')
  expect(screen.getAllByLabelText(/Игрок \d+/)).toHaveLength(10)
  expect(getPlayers).toHaveBeenCalledWith({ q: '', page: 0, size: 100, sort: 'username,asc' })
})
