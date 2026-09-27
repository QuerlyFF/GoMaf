import type { ReactNode } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { FormEvent } from 'react'
import { Link, Navigate, useNavigate, useParams } from 'react-router-dom'
import { ApiClientError } from '../api/client'
import { createTournament, getTournament, updateTournament } from '../api/tournaments'
import type { Session, TournamentWrite } from '../api/types'

export function TournamentFormPage({ session }: { session: Session | undefined }) {
  const params = useParams()
  const id = params.id ? Number(params.id) : null
  const editing = id != null
  const canManage = session?.role === 'ADMIN' || session?.moderatorPermissions.includes('GAMES_MANAGE') === true
  const existing = useQuery({ queryKey: ['tournament', id], queryFn: () => getTournament(id!), enabled: editing && Number.isInteger(id) && id! > 0 })
  const navigate = useNavigate(); const qc = useQueryClient()
  const mutation = useMutation({ mutationFn: (b: TournamentWrite) => editing ? updateTournament(id!, b) : createTournament(b), onSuccess: async (r) => { await qc.invalidateQueries({ queryKey: ['tournaments'] }); navigate(`/tournaments/${r.data.tournamentId}`, { replace: true }) } })
  if (session && !session.authenticated) return <Navigate to="/login" replace />
  if (!session) return <main className="page-shell"><Status>Проверяем права...</Status></main>
  if (!canManage) return <main className="page-shell"><Status error>Недостаточно прав.</Status></main>
  if (editing && existing.isPending) return <main className="page-shell"><Status>Загружаем турнир...</Status></main>
  if (editing && existing.isError) return <main className="page-shell"><Status error>{message(existing.error, 'Турнир не найден.')}</Status></main>
  const data = existing.data?.data
  function submit(e: FormEvent<HTMLFormElement>) { e.preventDefault(); const fd = new FormData(e.currentTarget); mutation.mutate({ name: String(fd.get('name') ?? '').trim(), dates: lines(String(fd.get('dates') ?? '')), gameCount: Number(fd.get('gameCount')), participationCost: String(fd.get('participationCost') ?? '').trim(), description: String(fd.get('description') ?? '') }) }
  return <main className="page-shell"><Link className="back-link" to={editing ? `/tournaments/${id}` : '/tournaments'}>Отмена</Link><h1>{editing ? 'Редактировать турнир' : 'Новый турнир'}</h1>{mutation.isError && <Status error>{message(mutation.error, 'Не удалось сохранить турнир.')}</Status>}<form className="form-card" onSubmit={submit}><label className="field"><span>Название</span><input name="name" required maxLength={160} defaultValue={data?.name ?? ''} /></label><label className="field"><span>Даты, по одной на строку</span><textarea name="dates" rows={5} required defaultValue={data?.dates.join('\n') ?? ''} /></label><div className="form-grid two-columns"><label className="field"><span>Количество игр</span><input name="gameCount" type="number" min={1} max={1000} required defaultValue={data?.gameCount ?? 1} /></label><label className="field"><span>Стоимость участия, пусто = бесплатно</span><input name="participationCost" defaultValue={data?.participationCost ?? ''} /></label></div><label className="field"><span>Описание</span><textarea name="description" rows={8} maxLength={20000} defaultValue={data?.description ?? ''} /></label><button className="button primary" disabled={mutation.isPending}>Сохранить турнир</button></form></main>
}
function lines(v: string) { return v.split(/\r?\n|,|;/).map((x) => x.trim()).filter(Boolean) }
function message(e: unknown, f: string) { return e instanceof ApiClientError ? e.details.message : f }
function Status({ children, error = false }: { children: ReactNode; error?: boolean }) { return <section className={`status-panel${error ? ' error-panel' : ''}`}>{children}</section> }
