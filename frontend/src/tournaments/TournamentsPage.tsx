import type { ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router-dom'
import { ApiClientError } from '../api/client'
import { getTournaments } from '../api/tournaments'
import type { Session } from '../api/types'

export function TournamentsPage({ session }: { session: Session | undefined }) {
  const [params, setParams] = useSearchParams()
  const view = params.get('view') === 'completed' ? 'completed' : 'upcoming'
  const query = useQuery({ queryKey: ['tournaments', view], queryFn: () => getTournaments(view) })
  const canManage = session?.role === 'ADMIN' || session?.moderatorPermissions.includes('GAMES_MANAGE') === true
  return <main className="page-shell">
    <div className="section-heading"><div><p className="eyebrow">Соревнования</p><h1>Турниры</h1></div>{canManage && <Link className="button primary" to="/tournaments/new">Новый турнир</Link>}</div>
    <div className="segmented"><button className={view === 'upcoming' ? 'active' : ''} onClick={() => setParams({ view: 'upcoming' })}>Предстоящие</button><button className={view === 'completed' ? 'active' : ''} onClick={() => setParams({ view: 'completed' })}>Завершённые</button></div>
    {query.isPending && <Status>Загружаем турниры...</Status>}
    {query.isError && <Status error>{message(query.error, 'Не удалось загрузить турниры.')}</Status>}
    {query.data?.length === 0 && <Status>Турниров в этом разделе нет.</Status>}
    <div className="cards-list">{query.data?.map((item) => <article className="content-card" key={item.id}><div><p className="eyebrow">{item.dates.map(formatDate).join(' · ')}</p><h2><Link to={`/tournaments/${item.id}`}>{item.name}</Link></h2><p>{item.gameCount} игр · {item.paid ? `${formatNumber(item.participationCost ?? 0)} стоимость участия` : 'Без стоимости'}</p></div><span className="tag">{item.completed ? 'Завершён' : 'Предстоящий'}</span></article>)}</div>
  </main>
}
function Status({ children, error = false }: { children: ReactNode; error?: boolean }) { return <section className={`status-panel${error ? ' error-panel' : ''}`} role={error ? 'alert' : undefined}>{children}</section> }
function message(error: unknown, fallback: string) { return error instanceof ApiClientError ? error.details.message : fallback }
function formatDate(value: string) { const d = new Date(`${value}T00:00:00`); return Number.isNaN(d.getTime()) ? value : new Intl.DateTimeFormat('ru-RU').format(d) }
function formatNumber(value: number) { return new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 2 }).format(value) }
