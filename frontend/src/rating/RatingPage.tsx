import { useQuery } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router-dom'
import { ApiClientError } from '../api/client'
import { getRating } from '../api/rating'

export function RatingPage() {
  const [params, setParams] = useSearchParams()
  const sort = params.get('sort') === 'weighted' ? 'weighted' : 'points'
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0)
  const query = useQuery({ queryKey: ['rating', sort, page], queryFn: () => getRating(sort, page, 50) })
  const error = query.error instanceof ApiClientError ? query.error.details.message : 'Не удалось загрузить рейтинг.'

  return <main className="page-shell">
    <div className="section-heading"><div><p className="eyebrow">Статистика</p><h1>Рейтинг</h1></div></div>
    <div className="segmented" aria-label="Сортировка рейтинга">
      <button className={sort === 'points' ? 'active' : ''} aria-pressed={sort === 'points'} onClick={() => setParams({ sort: 'points', page: '0' })}>По сумме</button>
      <button className={sort === 'weighted' ? 'active' : ''} aria-pressed={sort === 'weighted'} onClick={() => setParams({ sort: 'weighted', page: '0' })}>Баллы × Winrate</button>
    </div>
    {query.isPending && <section className="status-panel">Загружаем рейтинг...</section>}
    {query.isError && <section className="status-panel error-panel" role="alert">{error}</section>}
    {query.data?.items.length === 0 && <section className="status-panel">Пока нет данных для рейтинга.</section>}
    {query.data && query.data.items.length > 0 && <div className="table-scroll rating-table-scroll">
      <table className="data-table rating-table">
        <thead><tr><th>Место</th><th>Игрок</th><th>Игр</th><th>Побед</th><th>Winrate</th><th>Ср. доп. балл</th><th>Сумма</th><th>Баллы × Winrate</th></tr></thead>
        <tbody>{query.data.items.map((row) => <tr key={`${row.rank}-${row.displayName}`}>
          <td><span className="mobile-table-label">Место</span><span>{row.rank}</span></td>
          <td className="rating-player-cell"><span className="mobile-table-label">Игрок</span><span>{row.userId ? <Link to={`/players/${row.userId}`}>{row.displayName}</Link> : row.displayName}</span></td>
          <td><span className="mobile-table-label">Игр</span><span>{row.games}</span></td>
          <td><span className="mobile-table-label">Побед</span><span>{row.wins}</span></td>
          <td><span className="mobile-table-label">Winrate</span><span>{formatPercent(row.winRate)}</span></td>
          <td><span className="mobile-table-label">Ср. доп.</span><span>{formatNumber(row.averageAdditionalPoints)}</span></td>
          <td><span className="mobile-table-label">Сумма</span><span>{formatNumber(row.totalPoints)}</span></td>
          <td><span className="mobile-table-label">Баллы × Winrate</span><strong>{formatNumber(row.weightedScore)}</strong></td>
        </tr>)}</tbody>
      </table>
    </div>}
    {query.data && <nav className="pagination" aria-label="Страницы рейтинга">
      <button className="button secondary" disabled={page <= 0} onClick={() => setParams({ sort, page: String(page - 1) })}>Назад</button>
      <span>Страница {query.data.totalPages === 0 ? 0 : page + 1} из {query.data.totalPages}</span>
      <button className="button secondary" disabled={page + 1 >= query.data.totalPages} onClick={() => setParams({ sort, page: String(page + 1) })}>Вперёд</button>
    </nav>}
  </main>
}

function formatPercent(value: number) { return `${Math.round(value * 100)}%` }
function formatNumber(value: number) { return new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 2 }).format(value) }
