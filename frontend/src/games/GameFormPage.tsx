import type { ReactNode } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { FormEvent, useEffect, useState } from 'react'
import { Link, Navigate, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { ApiClientError } from '../api/client'
import { createGame, getGame, updateGame } from '../api/games'
import { getPlayers } from '../api/players'
import type { GamePlayerWrite, GameWrite, PlayerListItem, Session } from '../api/types'

const emptyRows = (): GamePlayerWrite[] => Array.from({ length: 10 }, (_, index) => ({
  seatNumber: index + 1,
  username: '',
  role: 'CIVILIAN',
  firstKilled: false,
  bestMove1: null,
  bestMove2: null,
  bestMove3: null,
  bonusPoints: '0',
  penaltyPoints: '0',
}))

export function GameFormPage({ session }: { session: Session | undefined }) {
  const params = useParams()
  const [search] = useSearchParams()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const id = params.id ? Number(params.id) : null
  const editing = id != null
  const canManage = session?.role === 'ADMIN' || session?.moderatorPermissions.includes('GAMES_MANAGE') === true
  const existing = useQuery({ queryKey: ['game', id], queryFn: () => getGame(id!), enabled: editing && Number.isInteger(id) && id! > 0 })
  const registeredPlayers = useQuery({
    queryKey: ['game-form', 'registered-players'],
    queryFn: loadRegisteredPlayers,
    enabled: canManage,
    staleTime: 5 * 60 * 1000,
  })
  const [rows, setRows] = useState<GamePlayerWrite[]>(emptyRows)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!existing.data) return
    const mapped = emptyRows()
    for (const player of existing.data.data.players) {
      mapped[player.seatNumber - 1] = {
        seatNumber: player.seatNumber,
        username: player.displayName,
        role: player.role,
        firstKilled: player.firstKilled,
        bestMove1: player.bestMove1,
        bestMove2: player.bestMove2,
        bestMove3: player.bestMove3,
        bonusPoints: String(player.bonusPoints),
        penaltyPoints: String(player.penaltyPoints),
      }
    }
    setRows(mapped)
  }, [existing.data])

  const mutation = useMutation({
    mutationFn: (body: GameWrite) => editing ? updateGame(id!, body) : createGame(body),
    onSuccess: async (response) => {
      await Promise.all([queryClient.invalidateQueries({ queryKey: ['games'] }), queryClient.invalidateQueries({ queryKey: ['game', response.data.gameId] })])
      navigate(`/games/${response.data.gameId}`, { replace: true })
    },
    onError: (value) => setError(message(value, 'Не удалось сохранить игру.')),
  })

  if (session && !session.authenticated) return <Navigate to="/login" replace state={{ returnTo: editing ? `/games/${id}/edit` : '/games/new' }} />
  if (session && !canManage) return <main className="page-shell"><Status error>Недостаточно прав для управления играми.</Status></main>
  if (!session) return <main className="page-shell"><Status>Проверяем права...</Status></main>
  if (editing && (!Number.isInteger(id) || id! < 1)) return <main className="page-shell"><Status error>Некорректный идентификатор игры.</Status></main>
  if (editing && existing.isPending) return <main className="page-shell"><Status>Загружаем игру...</Status></main>
  if (editing && existing.isError) return <main className="page-shell"><Status error>{message(existing.error, 'Игра не найдена.')}</Status></main>

  const game = existing.data?.data
  const tournamentId = game?.tournamentId ?? parseId(search.get('tournamentId'))
  const defaults = {
    name: game?.name ?? '',
    gameDate: game?.gameDate ?? new Date().toISOString().slice(0, 10),
    host: game?.host ?? '',
    winner: game?.winner ?? 'CIVILIANS',
    summary: game?.summary ?? '',
    videoLinks: game?.videoLinks.join('\n') ?? '',
    hiddenFromPublic: game?.hiddenFromPublic ?? false,
    visibilityUsernames: game?.visibilityUsernames.join('\n') ?? '',
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    const activeRows = rows.filter((row) => row.username.trim())
    if (activeRows.length === 0) { setError('Добавьте хотя бы одного игрока.'); return }
    const firstKilled = activeRows.filter((row) => row.firstKilled)
    if (firstKilled.length > 1) { setError('Можно выбрать только одного первого убитого.'); return }
    const body: GameWrite = {
      name: String(form.get('name') ?? '').trim(),
      gameDate: String(form.get('gameDate') ?? ''),
      host: String(form.get('host') ?? '').trim(),
      winner: String(form.get('winner') ?? ''),
      summary: String(form.get('summary') ?? ''),
      videoLinks: lines(String(form.get('videoLinks') ?? '')),
      tournamentId,
      hiddenFromPublic: form.get('hiddenFromPublic') === 'on',
      visibilityUsernames: lines(String(form.get('visibilityUsernames') ?? '')),
      players: activeRows,
    }
    setError(null)
    mutation.mutate(body)
  }

  function selectFirstKilled(seatNumber: number | null) {
    setRows((current) => current.map((row) => ({ ...row, firstKilled: seatNumber === row.seatNumber })))
  }

  const playerOptions = registeredPlayers.data ?? []
  return <main className="page-shell">
    <Link className="back-link" to={editing ? `/games/${id}` : '/games'}>Отмена</Link>
    <div className="section-heading"><div><p className="eyebrow">Управление</p><h1>{editing ? 'Редактировать игру' : 'Добавить игру'}</h1></div></div>
    {error && <Status error>{error}</Status>}
    <section className="status-panel scoring-info"><h2>Начисление баллов</h2><p>Победившая команда получает по 2.5 балла каждому игроку. Первый убитый мирный или шериф получает +0.5. За лучший ход: 2 из 3 чёрных — +0.5, 3 из 3 — +1. Поля + и − применяются дополнительно вручную.</p></section>
    {registeredPlayers.isPending && <Status>Загружаем список зарегистрированных игроков...</Status>}
    {registeredPlayers.isError && <Status error>Не удалось загрузить список зарегистрированных игроков. Ники всё равно можно ввести вручную.</Status>}
    <datalist id="registered-game-players">{playerOptions.map((player) => <option key={player.id} value={player.username} />)}</datalist>
    <form className="domain-form" onSubmit={submit}>
      <section className="form-card">
        <div className="form-grid game-meta-grid">
          <label className="field"><span>Название игры / номер</span><input name="name" required maxLength={120} defaultValue={defaults.name} placeholder="Например: Игра 6" /></label>
          <label className="field"><span>Дата</span><input name="gameDate" type="date" required defaultValue={defaults.gameDate} /></label>
          <label className="field"><span>Ведущий</span><input name="host" required maxLength={80} defaultValue={defaults.host} list="registered-game-players" autoComplete="off" /></label>
          <label className="field"><span>Кто победил</span><select name="winner" defaultValue={defaults.winner}><option value="CIVILIANS">Мирные</option><option value="BLACKS">Чёрные</option><option value="DRAW">Ничья</option></select></label>
        </div>
        <label className="field"><span>Краткая статистика</span><textarea name="summary" rows={4} maxLength={10000} defaultValue={defaults.summary} /></label>
        <label className="field"><span>Ссылки на видеозаписи</span><textarea name="videoLinks" rows={4} maxLength={5100} defaultValue={defaults.videoLinks} placeholder={'https://youtube.com/...\nhttps://youtu.be/...'} /><small className="field-help">До 10 HTTPS-ссылок, по одной на строку.</small></label>
        {tournamentId && <div className="status-panel">Игра привязана к турниру #{tournamentId}.</div>}
        {tournamentId && <>
          <label className="check-field"><input name="hiddenFromPublic" type="checkbox" defaultChecked={defaults.hiddenFromPublic} /> Скрыть игру из публичного доступа</label>
          <label className="field"><span>Кому разрешён доступ к скрытой игре, ники по одному на строку</span><textarea name="visibilityUsernames" rows={3} defaultValue={defaults.visibilityUsernames} /></label>
        </>}
      </section>
      <section className="form-card">
        <div className="section-heading"><div><p className="eyebrow">10 мест</p><h2>Игроки</h2><p className="muted">Можно выбрать зарегистрированного игрока из списка по нику. Если игрок не зарегистрирован, ник можно ввести вручную.</p></div></div>
        <label className="first-killed-none"><input type="radio" name="firstKilledSeat" checked={!rows.some((row) => row.firstKilled)} onChange={() => selectFirstKilled(null)} /> Первый убитый не указан</label>
        <div className="game-entry-table">
          <table className="data-table game-entry-data-table">
            <thead><tr><th>№</th><th>Игрок</th><th>Роль</th><th><abbr title="Первый убитый">ПУ</abbr></th><th>ЛХ 1</th><th>ЛХ 2</th><th>ЛХ 3</th><th>+</th><th>−</th></tr></thead>
            <tbody>{rows.map((row, index) => <SeatTableRow key={row.seatNumber} row={row} onChange={(next) => setRows((current) => current.map((item, i) => i === index ? next : item))} onFirstKilled={() => selectFirstKilled(row.seatNumber)} />)}</tbody>
          </table>
        </div>
      </section>
      <div className="button-row"><button className="button primary" disabled={mutation.isPending}>{mutation.isPending ? 'Сохраняем...' : 'Сохранить игру'}</button><Link className="button secondary" to={editing ? `/games/${id}` : '/games'}>Отмена</Link></div>
    </form>
  </main>
}

function SeatTableRow({ row, onChange, onFirstKilled }: { row: GamePlayerWrite; onChange: (row: GamePlayerWrite) => void; onFirstKilled: () => void }) {
  const active = row.username.trim().length > 0
  const update = <K extends keyof GamePlayerWrite>(key: K, value: GamePlayerWrite[K]) => onChange({ ...row, [key]: value })
  return <tr className={active ? 'active-game-seat' : ''}>
    <td><strong>{row.seatNumber}</strong></td>
    <td><input aria-label={`Игрок ${row.seatNumber}`} value={row.username} maxLength={64} list="registered-game-players" autoComplete="off" onChange={(e) => update('username', e.target.value)} placeholder="Ник игрока" /></td>
    <td><select aria-label={`Роль игрока ${row.seatNumber}`} value={row.role} onChange={(e) => update('role', e.target.value)} disabled={!active}><option value="CIVILIAN">Мирный житель</option><option value="SHERIFF">Шериф</option><option value="MAFIA">Мафия</option><option value="DON">Дон</option></select></td>
    <td className="center-cell"><input aria-label={`Первый убитый ${row.seatNumber}`} className="radio-control" type="radio" name="firstKilledSeat" checked={row.firstKilled} disabled={!active} onChange={onFirstKilled} /></td>
    {([1, 2, 3] as const).map((slot) => {
      const key: 'bestMove1' | 'bestMove2' | 'bestMove3' = `bestMove${slot}`
      return <td key={slot}><input aria-label={`ЛХ ${slot} игрока ${row.seatNumber}`} className="best-move-cell" type="number" min={1} max={10} disabled={!active || !row.firstKilled} value={row[key] ?? ''} onChange={(e) => update(key, e.target.value ? Number(e.target.value) : null)} /></td>
    })}
    <td><input aria-label={`Бонус игрока ${row.seatNumber}`} type="number" min={0} step="0.01" disabled={!active} value={row.bonusPoints} onChange={(e) => update('bonusPoints', e.target.value)} /></td>
    <td><input aria-label={`Штраф игрока ${row.seatNumber}`} type="number" min={0} step="0.01" disabled={!active} value={row.penaltyPoints} onChange={(e) => update('penaltyPoints', e.target.value)} /></td>
  </tr>
}

async function loadRegisteredPlayers(): Promise<PlayerListItem[]> {
  const result: PlayerListItem[] = []
  let page = 0
  while (true) {
    const response = await getPlayers({ q: '', page, size: 100, sort: 'username,asc' })
    result.push(...response.items)
    page += 1
    if (page >= response.totalPages) return result
  }
}

function parseId(value: string | null) { const id = Number(value); return Number.isInteger(id) && id > 0 ? id : null }
function lines(value: string) { return value.split(/\r?\n|,/).map((item) => item.trim()).filter(Boolean) }
function Status({ children, error = false }: { children: ReactNode; error?: boolean }) { return <section className={`status-panel${error ? ' error-panel' : ''}`}>{children}</section> }
function message(error: unknown, fallback: string) { return error instanceof ApiClientError ? error.details.message : fallback }
