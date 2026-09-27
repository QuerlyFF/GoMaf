import { useEffect, type ReactNode } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ApiClientError } from '../api/client'
import { deleteGame, getGame } from '../api/games'

export function GameDetailPage() {
  const params = useParams()
  const id = Number(params.id)
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const game = useQuery({ queryKey: ['game', id], queryFn: () => getGame(id), enabled: Number.isInteger(id) && id > 0 })
  const detailTitle = game.data?.data.name
  useEffect(() => {
    if (detailTitle) document.title = `${detailTitle} — GoMaf`
  }, [detailTitle])

  const remove = useMutation({
    mutationFn: () => deleteGame(id),
    onSuccess: async () => { await queryClient.invalidateQueries({ queryKey: ['games'] }); navigate('/games', { replace: true }) },
  })
  if (!Number.isInteger(id) || id < 1) return <main className="page-shell"><Status error>Некорректный идентификатор игры.</Status></main>
  if (game.isPending) return <main className="page-shell"><Status>Загружаем игру...</Status></main>
  if (game.isError) return <main className="page-shell"><Status error>{message(game.error, 'Игра не найдена.')}</Status></main>
  const data = game.data.data
  return <main className="page-shell">
    <Link className="back-link" to="/games">Назад к играм</Link>
    <div className="section-heading">
      <div><p className="eyebrow">{formatDate(data.gameDate)}</p><h1>{data.name}</h1><p>Ведущий: {data.host} · Победили: {data.winnerDisplayName}</p></div>
      {data.manageable && <div className="button-row"><Link className="button secondary" to={`/games/${data.id}/edit`}>Редактировать</Link><button className="button danger" disabled={remove.isPending} onClick={() => { if (window.confirm(`Удалить игру «${data.name}»?`)) remove.mutate() }}>Удалить</button></div>}
    </div>
    {remove.isError && <Status error>{message(remove.error, 'Не удалось удалить игру.')}</Status>}
    {data.summary && <section className="section-block"><h2>Описание</h2><p className="pre-wrap">{data.summary}</p></section>}
    <section className="section-block">
      <h2>Игроки</h2>
      <div className="game-detail-table" role="region" aria-label="Таблица игроков и очков" tabIndex={0}>
        <table className="data-table">
          <thead><tr><th>№</th><th>Игрок</th><th>Роль</th><th><abbr title="Первый убитый">ПУ</abbr></th><th><abbr title="Лучший ход">ЛХ</abbr></th><th>Победа</th><th>Доп. балл</th><th>Всего</th></tr></thead>
          <tbody>{data.players.map((row) => <tr className={row.won ? 'winner-row' : undefined} key={row.seatNumber}>
            <td>{row.seatNumber}</td>
            <td>{row.userId ? <Link to={`/players/${row.userId}`}>{row.displayName}</Link> : row.displayName}</td>
            <td>{row.roleDisplayName}</td>
            <td>{row.firstKilled ? 'Да' : '—'}</td>
            <td>{formatBestMove(row.bestMove1, row.bestMove2, row.bestMove3)}</td>
            <td>{formatNumber(row.victoryPoints)}</td>
            <td>{formatNumber(row.additionalPoints)}</td>
            <td><strong>{formatNumber(row.totalPoints)}</strong></td>
          </tr>)}</tbody>
        </table>
      </div>
    </section>
    {data.videoLinks.length > 0 && <section className="section-block"><h2>Видеозаписи</h2><div className="link-list">{data.videoLinks.map((url) => <a key={url} href={url} target="_blank" rel="noreferrer">{url}</a>)}</div></section>}
    {data.tournamentName && <section className="status-panel">Турнир: {data.tournamentName}</section>}
  </main>
}

function Status({ children, error = false }: { children: ReactNode; error?: boolean }) { return <section className={`status-panel${error ? ' error-panel' : ''}`}>{children}</section> }
function message(error: unknown, fallback: string) { return error instanceof ApiClientError ? error.details.message : fallback }
function formatDate(value: string) { const d = new Date(`${value}T00:00:00`); return Number.isNaN(d.getTime()) ? value : new Intl.DateTimeFormat('ru-RU').format(d) }
function formatNumber(value: number) { return new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 2 }).format(value) }
function formatBestMove(a: number | null, b: number | null, c: number | null) { const seats = [a, b, c].filter((value): value is number => value != null); return seats.length ? seats.join(', ') : '—' }
