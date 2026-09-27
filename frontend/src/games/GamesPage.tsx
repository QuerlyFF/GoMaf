import type { ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { FormEvent, useMemo } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { getGames } from '../api/games'
import type { Session } from '../api/types'
import { ApiClientError } from '../api/client'

export function GamesPage({ session }: { session: Session | undefined }) {
  const [params, setParams] = useSearchParams()
  const q = params.get('q') ?? ''
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0)
  const query = useQuery({ queryKey: ['games', q, page], queryFn: () => getGames(q, page, 20) })
  const canManage = session?.role === 'ADMIN' || session?.moderatorPermissions.includes('GAMES_MANAGE') === true

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    const next = String(form.get('q') ?? '').trim()
    setParams(next ? { q: next, page: '0' } : { page: '0' })
  }

  const rows = useMemo(() => query.data?.items ?? [], [query.data])
  return (
    <main className="page-shell">
      <div className="section-heading">
        <div><p className="eyebrow">Архив</p><h1>Игры</h1></div>
        {canManage && <Link className="button primary" to="/games/new">Добавить игру</Link>}
      </div>
      <form className="search-bar" onSubmit={submit}>
        <label className="field grow"><span>Поиск</span><input name="q" defaultValue={q} maxLength={80} placeholder="Название или ведущий" /></label>
        <button className="button secondary">Найти</button>
      </form>
      {query.isPending && <Status>Загружаем игры...</Status>}
      {query.isError && <Status error>{message(query.error, 'Не удалось загрузить игры.')}</Status>}
      {query.data && rows.length === 0 && <Status>Игр по этому запросу нет.</Status>}
      {rows.length > 0 && <div className="cards-list">
        {rows.map((game) => <article className="content-card" key={game.id}>
          <div>
            <p className="eyebrow">{formatDate(game.gameDate)}</p>
            <h2><Link to={`/games/${game.id}`}>{game.name}</Link></h2>
            <p>Ведущий: {game.host}</p>
            {game.tournamentName && <p>Турнир: {game.tournamentName}</p>}
          </div>
          <strong>{game.winnerDisplayName}</strong>
        </article>)}
      </div>}
      {query.data && <nav className="pagination" aria-label="Страницы игр">
        <button className="button secondary" disabled={query.data.page <= 0} onClick={() => setParams(q ? { q, page: String(page - 1) } : { page: String(page - 1) })}>Назад</button>
        <span>Страница {query.data.totalPages === 0 ? 0 : page + 1} из {query.data.totalPages}</span>
        <button className="button secondary" disabled={page + 1 >= query.data.totalPages} onClick={() => setParams(q ? { q, page: String(page + 1) } : { page: String(page + 1) })}>Вперёд</button>
      </nav>}
    </main>
  )
}

function Status({ children, error = false }: { children: ReactNode; error?: boolean }) { return <section className={`status-panel${error ? ' error-panel' : ''}`}>{children}</section> }
function message(error: unknown, fallback: string) { return error instanceof ApiClientError ? error.details.message : fallback }
function formatDate(value: string) { const d = new Date(`${value}T00:00:00`); return Number.isNaN(d.getTime()) ? value : new Intl.DateTimeFormat('ru-RU').format(d) }
