import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { getSchedule } from '../api/schedule'
import type { ScheduleEntry } from '../api/types'
import { SchedulePage } from './SchedulePage'

vi.mock('../api/schedule', () => ({
  getSchedule: vi.fn(),
  createScheduleEntry: vi.fn(),
  updateScheduleEntry: vi.fn(),
  deleteScheduleEntry: vi.fn(),
  toggleScheduleParticipation: vi.fn(),
}))

beforeEach(() => {
  cleanup()
  vi.clearAllMocks()
})

afterEach(cleanup)

function weekEntry(offset: number, hour: string, overrides: Partial<ScheduleEntry>): ScheduleEntry {
  const now = new Date()
  const monday = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  monday.setDate(monday.getDate() - ((monday.getDay() + 6) % 7) + offset)
  const year = monday.getFullYear()
  const month = String(monday.getMonth() + 1).padStart(2, '0')
  const date = String(monday.getDate()).padStart(2, '0')
  return {
    id: offset + 1,
    entryType: 'GAME',
    title: 'Тестовая игра',
    scheduledAt: year + '-' + month + '-' + date + 'T' + hour + ':00',
    host: 'Ведущий',
    description: '',
    rated: true,
    mafiaFormat: 'CLASSIC',
    mafiaFormatDisplayName: 'Классическая мафия',
    participants: [],
    participating: false,
    manageable: false,
    ...overrides,
  }
}

function renderPage(entries: ScheduleEntry[]) {
  vi.mocked(getSchedule).mockResolvedValue(entries)
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <SchedulePage session={undefined} />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

test('schedule requests only the visible week and refetches the next week range', async () => {
  const user = userEvent.setup()
  renderPage([])

  const now = new Date()
  const monday = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  monday.setDate(monday.getDate() - ((monday.getDay() + 6) % 7))
  const nextMonday = new Date(monday)
  nextMonday.setDate(nextMonday.getDate() + 7)
  const mondayAfter = new Date(nextMonday)
  mondayAfter.setDate(mondayAfter.getDate() + 7)

  const key = (value: Date) => {
    const year = String(value.getFullYear())
    const month = String(value.getMonth() + 1).padStart(2, '0')
    const day = String(value.getDate()).padStart(2, '0')
    return year + '-' + month + '-' + day + 'T00:00:00'
  }

  await waitFor(() => expect(getSchedule).toHaveBeenCalledWith(key(monday), key(nextMonday)))

  await user.click(screen.getByRole('button', { name: 'Следующая' }))
  await waitFor(() => expect(getSchedule).toHaveBeenCalledWith(key(nextMonday), key(mondayAfter)))
})

test('schedule renders the current week, filters entries and switches by week', async () => {
  const user = userEvent.setup()
  renderPage([
    weekEntry(0, '19:00', {}),
    weekEntry(1, '20:30', { id: 16, entryType: 'EVENT', title: 'Клубный вечер' }),
  ])

  expect(await screen.findByRole('heading', { name: 'РАСПИСАНИЕ' })).toBeInTheDocument()
  expect(screen.getByRole('region', { name: 'Календарь расписания' })).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Эта неделя' })).toBeInTheDocument()
  expect(await screen.findByRole('button', { name: /Тестовая игра/ })).toBeInTheDocument()
  expect(screen.getByRole('button', { name: /Клубный вечер/ })).toBeInTheDocument()

  await user.click(screen.getByRole('button', { name: 'Мероприятия' }))
  expect(screen.queryByRole('button', { name: /Тестовая игра/ })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: /Клубный вечер/ })).toBeInTheDocument()

  await user.click(screen.getByRole('button', { name: 'Следующая' }))
  expect(screen.queryByRole('button', { name: /Клубный вечер/ })).not.toBeInTheDocument()

  await user.click(screen.getByRole('button', { name: 'Эта неделя' }))
  expect(screen.getByRole('button', { name: /Клубный вечер/ })).toBeInTheDocument()
})

test('schedule entry opens a detail dialog', async () => {
  const user = userEvent.setup()
  renderPage([weekEntry(2, '19:30', { description: 'Описание тестовой игры' })])

  const item = await screen.findByRole('button', { name: /Тестовая игра/ })
  await user.click(item)

  expect(screen.getByRole('dialog')).toBeInTheDocument()
  expect(screen.getByText('Описание тестовой игры')).toBeInTheDocument()
  expect(screen.getByRole('link', { name: 'Войти для участия' })).toHaveAttribute('href', '/login')
})
