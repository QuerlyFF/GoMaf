import type { ReactNode } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { FormEvent, useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ApiClientError } from '../api/client'
import { deleteTournament, getTournament, inviteTournamentPlayer, removeTournamentInvitation, toggleTournamentParticipation } from '../api/tournaments'
import type { Session } from '../api/types'

export function TournamentDetailPage({ session }: { session: Session | undefined }) {
  const id = Number(useParams().id)
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const [notice, setNotice] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const tournament = useQuery({ queryKey: ['tournament', id], queryFn: () => getTournament(id), enabled: Number.isInteger(id) && id > 0 })
  const detailTitle = tournament.data?.data.name
  useEffect(() => {
    if (detailTitle) document.title = `${detailTitle} — GoMaf`
  }, [detailTitle])

  const refresh = async () => { await Promise.all([queryClient.invalidateQueries({ queryKey: ['tournament', id] }), queryClient.invalidateQueries({ queryKey: ['tournaments'] })]) }
  const participation = useMutation({ mutationFn: () => toggleTournamentParticipation(id), onSuccess: async (r) => { setNotice(r.data.message); setError(null); await refresh() }, onError: (e) => fail(e, setNotice, setError) })
  const invite = useMutation({ mutationFn: (username: string) => inviteTournamentPlayer(id, username), onSuccess: async (r) => { setNotice(r.data.message); setError(null); await refresh() }, onError: (e) => fail(e, setNotice, setError) })
  const removeInvite = useMutation({ mutationFn: (userId: number) => removeTournamentInvitation(id, userId), onSuccess: async (r) => { setNotice(r.data.message); setError(null); await refresh() }, onError: (e) => fail(e, setNotice, setError) })
  const remove = useMutation({ mutationFn: () => deleteTournament(id), onSuccess: async () => { await queryClient.invalidateQueries({ queryKey: ['tournaments'] }); navigate('/tournaments', { replace: true }) }, onError: (e) => fail(e, setNotice, setError) })
  if (tournament.isPending) return <main className="page-shell"><Status>Загружаем турнир...</Status></main>
  if (tournament.isError) return <main className="page-shell"><Status error>{message(tournament.error, 'Турнир не найден.')}</Status></main>
  const data = tournament.data.data
  return <main className="page-shell">
    <Link className="back-link" to="/tournaments">К турнирам</Link>
    <div className="section-heading"><div><p className="eyebrow">Турнир</p><h1>{data.name}</h1><p className="large-date-line">{data.dates.map(formatDate).join(' · ')}</p></div>{data.manageable && <div className="button-row"><Link className="button primary" to={`/games/new?tournamentId=${data.id}`}>Добавить игру</Link><Link className="button secondary" to={`/tournaments/${data.id}/edit`}>Редактировать</Link></div>}</div>
    {notice && <Status>{notice}</Status>}{error && <Status error>{error}</Status>}
    <section className="profile-stats"><Stat label="Игр" value={String(data.gameCount)} /><Stat label="Участие" value={data.paid ? formatNumber(data.participationCost ?? 0) : 'Бесплатно'} /><Stat label="Участников" value={String(data.participantCount)} /></section>
    {data.description && <section className="section-block"><p className="pre-wrap">{data.description}</p></section>}
    {session?.authenticated ? <div className="button-row"><button className={`button ${data.participating ? 'secondary' : 'primary'}`} disabled={participation.isPending} onClick={() => participation.mutate()}>{data.participating ? 'Отменить участие' : 'Участвовать'}</button>{data.paid && !data.invited && !data.participating && <span className="muted">Для платного турнира требуется приглашение.</span>}</div> : <Link className="button primary" to="/login" state={{ returnTo: `/tournaments/${data.id}` }}>Войти для участия</Link>}
    {data.manageable && data.paid && <InvitationPanel rows={data.invitationRows} pending={invite.isPending || removeInvite.isPending} onInvite={(u) => invite.mutate(u)} onRemove={(u) => removeInvite.mutate(u)} />}
    <section className="section-block"><div className="section-heading"><div><h2>Игры турнира</h2></div></div>{data.games.length === 0 ? <Status>Доступных игр турнира пока нет.</Status> : <div className="cards-list">{data.games.map((g) => <article className="content-card" key={g.id}><div><p className="eyebrow">{formatDate(g.gameDate)}</p><h3>{g.name}</h3><p>Ведущий: {g.host}</p></div><div className="button-row">{g.hiddenFromPublic && <span className="tag">Скрытая</span>}<Link className="button secondary" to={`/games/${g.id}`}>Открыть</Link></div></article>)}</div>}</section>
    {data.manageable && <div className="danger-zone"><button className="button danger" disabled={remove.isPending} onClick={() => { if (window.confirm(`Удалить турнир «${data.name}»?`)) remove.mutate() }}>Удалить турнир</button></div>}
  </main>
}

function InvitationPanel({ rows, pending, onInvite, onRemove }: { rows: Array<{ userId: number; username: string; participating: boolean }>; pending: boolean; onInvite: (u: string) => void; onRemove: (id: number) => void }) {
  function submit(e: FormEvent<HTMLFormElement>) { e.preventDefault(); const fd = new FormData(e.currentTarget); const u = String(fd.get('username') ?? '').trim(); if (u) onInvite(u) }
  return <section className="section-block"><h2>Приглашения</h2><form className="search-bar" onSubmit={submit}><label className="field grow"><span>Точный ник</span><input name="username" required maxLength={32} /></label><button className="button primary" disabled={pending}>Пригласить</button></form><div className="participant-admin-list">{rows.map((r) => <div className="content-card" key={r.userId}><Link to={`/players/${r.userId}`}>{r.username}</Link><span>{r.participating ? 'Участвует' : 'Приглашён'}</span><button className="button secondary" disabled={pending} onClick={() => onRemove(r.userId)}>Снять приглашение</button></div>)}</div></section>
}
function fail(e: unknown, n: (v: string | null) => void, s: (v: string | null) => void) { n(null); s(message(e, 'Операция не выполнена.')) }
function message(e: unknown, f: string) { return e instanceof ApiClientError ? e.details.message : f }
function Status({ children, error = false }: { children: ReactNode; error?: boolean }) { return <section className={`status-panel${error ? ' error-panel' : ''}`}>{children}</section> }
function Stat({ label, value }: { label: string; value: string }) { return <div><span>{label}</span><strong>{value}</strong></div> }
function formatDate(v: string) { const d = new Date(`${v}T00:00:00`); return Number.isNaN(d.getTime()) ? v : new Intl.DateTimeFormat('ru-RU').format(d) }
function formatNumber(v: number) { return new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 2 }).format(v) }
