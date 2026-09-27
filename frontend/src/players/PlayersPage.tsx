import { useQuery } from '@tanstack/react-query'
import { FormEvent, useState, type ReactNode } from 'react'
import { Link, useLocation, useSearchParams } from 'react-router-dom'
import { getPlayers } from '../api/players'
import { ApiClientError } from '../api/client'

const SORT_OPTIONS = [
  ['rating,desc', 'Рейтинг: по убыванию'],
  ['rating,asc', 'Рейтинг: по возрастанию'],
  ['username,asc', 'Ник: А-Я'],
  ['username,desc', 'Ник: Я-А'],
  ['gamesplayed,desc', 'Игр: больше сначала'],
  ['wins,desc', 'Побед: больше сначала'],
] as const

export function PlayersPage() {
  const location = useLocation()
  const [searchParams, setSearchParams] = useSearchParams()
  const q = searchParams.get('q') ?? ''
  const sort = searchParams.get('sort') ?? 'rating,desc'
  const rawPage = Number(searchParams.get('page') ?? '0')
  const page = Number.isInteger(rawPage) && rawPage >= 0 ? rawPage : 0
  const [draftQuery, setDraftQuery] = useState(q)

  const players = useQuery({
    queryKey: ['players', q, page, sort],
    queryFn: () => getPlayers({ q, page, sort, size: 24 }),
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    setSearchParams(nextParams(draftQuery, sort, 0))
  }

  function changeSort(value: string) {
    setSearchParams(nextParams(q, value, 0))
  }

  const data = players.data
  const error = players.error instanceof ApiClientError ? players.error.details.message : 'Не удалось загрузить игроков.'

  return (
    <main className="page-shell">
      <section className="page-heading">
        <p className="eyebrow">Игроки</p>
        <h1>Игроки клуба</h1>
        <p>Игроки клуба, их рейтинг и статистика.</p>
      </section>

      <form className="player-toolbar" onSubmit={submit} aria-label="Поиск и сортировка игроков">
        <label className="field grow">
          <span>Поиск по нику</span>
          <input
            value={draftQuery}
            onChange={(event) => setDraftQuery(event.target.value)}
            maxLength={64}
            placeholder="Введите ник"
          />
        </label>
        <label className="field sort-field">
          <span>Сортировка</span>
          <select value={sort} onChange={(event) => changeSort(event.target.value)}>
            {SORT_OPTIONS.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select>
        </label>
        <button className="button primary toolbar-button" type="submit">Найти</button>
      </form>

      {players.isPending && <StatusPanel>Загружаем список игроков...</StatusPanel>}
      {players.isError && <StatusPanel error>{error}</StatusPanel>}
      {data && data.items.length === 0 && <StatusPanel>По этому запросу игроков нет.</StatusPanel>}

      {data && data.items.length > 0 && (
        <>
          <div className="players-grid" aria-label="Список игроков">
            {data.items.map((player) => (
              <article className="player-card" key={player.id}>
                <img className="avatar" src={player.avatarUrl} alt="" width="64" height="64" loading="lazy" decoding="async" />
                <div className="player-card-main">
                  <h2 className="player-name">
                    <Link to={`/players/${player.id}`} state={{ returnTo: `${location.pathname}${location.search}` }}>
                      {player.username}
                    </Link>
                  </h2>
                  <div className="stat-row" aria-label={`Статистика ${player.username}`}>
                    <Stat label="Рейтинг" value={formatNumber(player.rating)} />
                    <Stat label="Игр" value={String(player.gamesPlayed)} />
                    <Stat label="Побед" value={String(player.wins)} />
                    <Stat label="Win rate" value={`${Math.round(player.winRate * 100)}%`} />
                    <Stat label="Средний доп. балл" value={formatNumber(player.averageScore)} />
                  </div>
                </div>
              </article>
            ))}
          </div>

          <nav className="pagination" aria-label="Страницы игроков">
            <button
              className="button secondary"
              disabled={data.page <= 0}
              onClick={() => setSearchParams(nextParams(q, sort, Math.max(0, data.page - 1)))}
            >
              Назад
            </button>
            <span>Страница {data.totalPages === 0 ? 0 : data.page + 1} из {data.totalPages}</span>
            <button
              className="button secondary"
              disabled={data.page + 1 >= data.totalPages}
              onClick={() => setSearchParams(nextParams(q, sort, data.page + 1))}
            >
              Вперёд
            </button>
          </nav>
        </>
      )}
    </main>
  )
}

function nextParams(q: string, sort: string, page: number) {
  const params = new URLSearchParams()
  const normalized = q.trim()
  if (normalized) params.set('q', normalized)
  if (sort !== 'rating,desc') params.set('sort', sort)
  if (page > 0) params.set('page', String(page))
  return params
}

function StatusPanel({ children, error = false }: { children: ReactNode; error?: boolean }) {
  return <section className={`status-panel${error ? ' error-panel' : ''}`} aria-live="polite">{children}</section>
}

function Stat({ label, value }: { label: string; value: string }) {
  return <div><span>{label}</span><strong>{value}</strong></div>
}

function formatNumber(value: number) {
  return new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 2 }).format(value)
}
