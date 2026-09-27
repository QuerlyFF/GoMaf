import { useQuery } from '@tanstack/react-query'
import { lazy, Suspense, useEffect, useState, type ReactNode } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'
import { ApiClientError } from '../api/client'
import {
  getPlayer,
  getPlayerAdministration,
  getPlayerGames,
} from '../api/players'
import type { Session } from '../api/types'

const PlayerAdministrationPanel = lazy(() =>
  import('./PlayerAdministrationPanel').then((module) => ({ default: module.PlayerAdministrationPanel })),
)

export function PlayerProfilePage({ session }: { session: Session | undefined }) {
  const params = useParams()
  const navigate = useNavigate()
  const location = useLocation()
  const id = Number(params.id)
  const validId = Number.isInteger(id) && id > 0
  const [gamesPage, setGamesPage] = useState(0)

  const profile = useQuery({
    queryKey: ['player', id],
    queryFn: () => getPlayer(id),
    enabled: validId,
  })
  const games = useQuery({
    queryKey: ['player-games', id, gamesPage],
    queryFn: () => getPlayerGames(id, gamesPage, 20),
    enabled: validId,
  })
  const detailTitle = profile.data?.data.username
  useEffect(() => {
    if (detailTitle) document.title = `${detailTitle} — GoMaf`
  }, [detailTitle])

  const administration = useQuery({
    queryKey: ['player-administration', id],
    queryFn: () => getPlayerAdministration(id),
    enabled: validId && session?.authenticated === true,
    retry: false,
  })

  if (!validId) return <main className="page-shell"><StatusPanel error>Некорректный идентификатор игрока.</StatusPanel></main>
  if (profile.isPending) return <main className="page-shell"><StatusPanel>Загружаем профиль...</StatusPanel></main>
  if (profile.isError) return <main className="page-shell"><StatusPanel error>{errorMessage(profile.error, 'Профиль не найден.')}</StatusPanel></main>

  const player = profile.data.data
  const returnTo = (location.state as { returnTo?: string } | null)?.returnTo ?? '/players'
  const capabilities = administration.data?.data

  return (
    <main className="page-shell profile-page">
      <Link className="back-link" to={returnTo}>Назад к игрокам</Link>

      <section className="profile-social-card">
        <div className="profile-banner-frame">
          <picture className="profile-banner-picture">
            <img
              className="profile-banner"
              src={`${player.bannerUrl}?width=1280`}
              srcSet={`${player.bannerUrl}?width=640 640w, ${player.bannerUrl}?width=768 768w, ${player.bannerUrl}?width=960 960w, ${player.bannerUrl}?width=1280 1280w`}
              sizes="(max-width: 640px) calc(100vw - 26px), (max-width: 720px) calc(100vw - 42px), (max-width: 1024px) 960px, 1280px"
              alt=""
              loading="eager"
              fetchPriority="high"
              decoding="async"
            />
          </picture>
        </div>
        <div className="profile-social-body">
          <picture>
            <source media="(max-width: 430px)" srcSet={`${player.avatarUrl}?size=128 1x, ${player.avatarUrl}?size=256 2x`} />
            <source media="(max-width: 720px)" srcSet={`${player.avatarUrl}?size=256 1x, ${player.avatarUrl}?size=512 2x`} />
            <img
              className="profile-avatar profile-avatar-overlap"
              src={`${player.avatarUrl}?size=256`}
              srcSet={`${player.avatarUrl}?size=256 1x, ${player.avatarUrl}?size=512 2x`}
              alt=""
              width="208"
              height="208"
              decoding="async"
            />
          </picture>
          <div className="profile-social-copy">
            <h1>{player.username}</h1>
            <p className="profile-about">{player.aboutMe || 'Игрок пока ничего о себе не написал.'}</p>
          </div>
        </div>
      </section>

      <section className="profile-stats" aria-label="Статистика игрока">
        <Stat label="Рейтинг" value={formatNumber(player.rating)} />
        <Stat label="Игр" value={String(player.gamesPlayed)} />
        <Stat label="Побед" value={String(player.wins)} />
        <Stat label="Поражений" value={String(player.losses)} />
        <Stat label="Win rate" value={`${Math.round(player.winRate * 100)}%`} />
        <Stat label="Средний балл" value={formatNumber(player.averageScore)} />
      </section>

      {session?.authenticated && administration.isPending && <StatusPanel>Загружаем доступные действия...</StatusPanel>}
      {capabilities && (
        <Suspense fallback={<StatusPanel>Загружаем панель управления...</StatusPanel>}>
          <PlayerAdministrationPanel
            playerId={player.id}
            username={player.username}
            aboutMe={player.aboutMe}
            rating={player.rating}
            capabilities={capabilities}
            onDeleted={() => navigate(returnTo, { replace: true })}
          />
        </Suspense>
      )}

      <section className="section-block">
        <div className="section-heading">
          <div>
            <p className="eyebrow">История</p>
            <h2>Игры</h2>
          </div>
        </div>
        {games.isPending && <StatusPanel>Загружаем историю игр...</StatusPanel>}
        {games.isError && <StatusPanel error>{errorMessage(games.error, 'Не удалось загрузить историю.')}</StatusPanel>}
        {games.data && games.data.items.length === 0 && <StatusPanel>В доступной истории игр пока нет.</StatusPanel>}
        {games.data && games.data.items.length > 0 && (
          <>
            <div className="games-list">
              {games.data.items.map((game) => (
                <article className="game-row" key={game.gameId}>
                  <div>
                    <h3><Link to={`/games/${game.gameId}`}>{game.name}</Link></h3>
                    <p>{formatDate(game.gameDate)} · {game.roleDisplayName}</p>
                  </div>
                  <div className="game-score">
                    <span>{game.won ? 'Победа' : 'Поражение'}</span>
                    <strong>{formatNumber(game.totalPoints)}</strong>
                  </div>
                </article>
              ))}
            </div>
            <nav className="pagination" aria-label="Страницы истории игр">
              <button className="button secondary" disabled={games.data.page <= 0} onClick={() => setGamesPage((value) => Math.max(0, value - 1))}>Назад</button>
              <span>Страница {games.data.totalPages === 0 ? 0 : games.data.page + 1} из {games.data.totalPages}</span>
              <button className="button secondary" disabled={games.data.page + 1 >= games.data.totalPages} onClick={() => setGamesPage((value) => value + 1)}>Вперёд</button>
            </nav>
          </>
        )}
      </section>
    </main>
  )
}


function StatusPanel({ children, error = false }: { children: ReactNode; error?: boolean }) {
  return <section className={`status-panel${error ? ' error-panel' : ''}`} aria-live="polite">{children}</section>
}

function Stat({ label, value }: { label: string; value: string }) {
  return <div><span>{label}</span><strong>{value}</strong></div>
}

function errorMessage(error: unknown, fallback: string) {
  return error instanceof ApiClientError ? error.details.message : fallback
}

function formatNumber(value: number) {
  return new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 2 }).format(value)
}

function formatDate(value: string) {
  const date = new Date(`${value}T00:00:00`)
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('ru-RU').format(date)
}
