import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { lazy, Suspense, useEffect, useState } from 'react'
import { Link, Navigate, NavLink, Route, Routes, useLocation, useNavigate } from 'react-router-dom'
import { getSession, logout } from '../api/client'
import { NewsPage } from '../content/NewsPage'
import { RouteMetadata } from './RouteMetadata'
import './app.css'

const LoginPage = lazy(() => import('../auth/LoginPage').then((module) => ({ default: module.LoginPage })))
const RegisterPage = lazy(() => import('../auth/RegisterPage').then((module) => ({ default: module.RegisterPage })))
const ModeratorAdminPage = lazy(() => import('../admin/ModeratorAdminPage').then((module) => ({ default: module.ModeratorAdminPage })))
const RulesPage = lazy(() => import('../content/RulesPage').then((module) => ({ default: module.RulesPage })))
const GameFormPage = lazy(() => import('../games/GameFormPage').then((module) => ({ default: module.GameFormPage })))
const GameDetailPage = lazy(() => import('../games/GameDetailPage').then((module) => ({ default: module.GameDetailPage })))
const GamesPage = lazy(() => import('../games/GamesPage').then((module) => ({ default: module.GamesPage })))
const PlayerProfilePage = lazy(() => import('../players/PlayerProfilePage').then((module) => ({ default: module.PlayerProfilePage })))
const PlayersPage = lazy(() => import('../players/PlayersPage').then((module) => ({ default: module.PlayersPage })))
const ProfilePage = lazy(() => import('../profile/ProfilePage').then((module) => ({ default: module.ProfilePage })))
const RatingPage = lazy(() => import('../rating/RatingPage').then((module) => ({ default: module.RatingPage })))
const SchedulePage = lazy(() => import('../schedule/SchedulePage').then((module) => ({ default: module.SchedulePage })))
const TournamentFormPage = lazy(() => import('../tournaments/TournamentFormPage').then((module) => ({ default: module.TournamentFormPage })))
const TournamentDetailPage = lazy(() => import('../tournaments/TournamentDetailPage').then((module) => ({ default: module.TournamentDetailPage })))
const TournamentsPage = lazy(() => import('../tournaments/TournamentsPage').then((module) => ({ default: module.TournamentsPage })))

const NAV_ITEMS = [
  ['/news', 'Новости'],
  ['/rules', 'Правила'],
  ['/players', 'Игроки'],
  ['/games', 'Игры'],
  ['/schedule', 'Расписание'],
  ['/tournaments', 'Турниры'],
  ['/rating', 'Рейтинг'],
] as const

function AppHeader() {
  const session = useQuery({ queryKey: ['session'], queryFn: getSession })
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const location = useLocation()
  const [menuOpen, setMenuOpen] = useState(false)
  const mutation = useMutation({
    mutationFn: logout,
    onSuccess: async () => {
      await queryClient.invalidateQueries()
      queryClient.removeQueries({ queryKey: ['player-administration'] })
      setMenuOpen(false)
      navigate('/players')
    },
  })
  const data = session.data?.data

  useEffect(() => setMenuOpen(false), [location.pathname, location.search])
  useEffect(() => {
    if (!menuOpen) return
    const close = (event: KeyboardEvent) => event.key === 'Escape' && setMenuOpen(false)
    window.addEventListener('keydown', close)
    return () => window.removeEventListener('keydown', close)
  }, [menuOpen])

  const navLinks = (onNavigate?: () => void, includePrimary = true) => <>
    {includePrimary && NAV_ITEMS.map(([to, label]) => <NavLink key={to} to={to} onClick={onNavigate}>{label}</NavLink>)}
    {data?.authenticated && <NavLink to="/profile" onClick={onNavigate}>Профиль</NavLink>}
    {data?.role === 'ADMIN' && <NavLink to="/admin/moderators" onClick={onNavigate}>Администрирование</NavLink>}
  </>

  return (
    <header className="site-header">
      <div className="site-header-inner">
        <Link className="brand" to="/news" onClick={() => setMenuOpen(false)}>GoMaf</Link>
        <nav className="site-nav desktop-nav" aria-label="Основная навигация">{navLinks()}</nav>
        <div className="session-nav desktop-session">
          {session.isPending && <span className="muted">Загрузка...</span>}
          {session.isError && <span className="notice error-notice" role="alert">Не удалось проверить сессию.</span>}
          {data?.authenticated ? <>
            <span className="session-user">{data.username}</span>
            <button className="text-button header-action" type="button" disabled={mutation.isPending} onClick={() => mutation.mutate()}>Выйти</button>
          </> : <Link className="header-action" to="/login" state={{ returnTo: `${location.pathname}${location.search}` }}>Войти</Link>}
        </div>
        <button className="menu-toggle" type="button" aria-expanded={menuOpen} aria-controls="mobile-site-menu" onClick={() => setMenuOpen((value) => !value)}>
          <span className="menu-toggle-icon" aria-hidden="true"><span></span><span></span><span></span></span>
          <span>Меню</span>
        </button>
      </div>
      <nav className="mobile-primary-nav" aria-label="Разделы сайта">
        {NAV_ITEMS.map(([to, label]) => <NavLink key={to} to={to} onClick={() => setMenuOpen(false)}>{label}</NavLink>)}
      </nav>
      <div id="mobile-site-menu" className={`mobile-menu${menuOpen ? ' open' : ''}`} hidden={!menuOpen}>
        <nav className="mobile-site-nav" aria-label="Мобильная навигация">{navLinks(() => setMenuOpen(false), false)}</nav>
        <div className="mobile-session">
          {session.isPending && <span className="muted">Загрузка...</span>}
          {session.isError && <span className="notice error-notice" role="alert">Не удалось проверить сессию.</span>}
          {data?.authenticated ? <>
            <span className="session-user">{data.username}</span>
            <button className="button secondary" type="button" disabled={mutation.isPending} onClick={() => mutation.mutate()}>Выйти</button>
          </> : <Link className="button primary" to="/login" state={{ returnTo: `${location.pathname}${location.search}` }} onClick={() => setMenuOpen(false)}>Войти</Link>}
        </div>
      </div>
    </header>
  )
}

function PlayersRoute() {
  const session = useQuery({ queryKey: ['session'], queryFn: getSession })
  return <PlayerProfilePage session={session.data?.data} />
}

function NewsRoute() {
  const session = useQuery({ queryKey: ['session'], queryFn: getSession })
  return <NewsPage session={session.data?.data} />
}

function ProfileRoute() {
  const session = useQuery({ queryKey: ['session'], queryFn: getSession })
  return <ProfilePage session={session.data?.data} />
}

function GamesRoute() {
  const session = useQuery({ queryKey: ['session'], queryFn: getSession })
  return <GamesPage session={session.data?.data} />
}

function GameFormRoute() {
  const session = useQuery({ queryKey: ['session'], queryFn: getSession })
  return <GameFormPage session={session.data?.data} />
}

function ScheduleRoute() {
  const session = useQuery({ queryKey: ['session'], queryFn: getSession })
  return <SchedulePage session={session.data?.data} />
}

function TournamentsRoute() {
  const session = useQuery({ queryKey: ['session'], queryFn: getSession })
  return <TournamentsPage session={session.data?.data} />
}

function TournamentDetailRoute() {
  const session = useQuery({ queryKey: ['session'], queryFn: getSession })
  return <TournamentDetailPage session={session.data?.data} />
}

function TournamentFormRoute() {
  const session = useQuery({ queryKey: ['session'], queryFn: getSession })
  return <TournamentFormPage session={session.data?.data} />
}


function NotFoundRoute() {
  useEffect(() => {
    window.location.replace('/not-found')
  }, [])
  return <main className="page-shell narrow-page"><h1>404</h1><p className="muted">Страница не найдена.</p></main>
}

function ModeratorAdminRoute() {
  const session = useQuery({ queryKey: ['session'], queryFn: getSession })
  return <ModeratorAdminPage session={session.data?.data} />
}

export function App() {
  return (
    <div className="app-frame">
      <AppHeader />
      <RouteMetadata />
      <Suspense fallback={<main className="page-shell"><p className="muted">Загружаем раздел...</p></main>}>
        <Routes>
          <Route path="/" element={<Navigate to="/news" replace />} />
        <Route path="/news" element={<NewsRoute />} />
        <Route path="/rules" element={<RulesPage />} />
        <Route path="/players" element={<PlayersPage />} />
        <Route path="/players/:id" element={<PlayersRoute />} />
        <Route path="/profile" element={<ProfileRoute />} />
        <Route path="/games" element={<GamesRoute />} />
        <Route path="/games/new" element={<GameFormRoute />} />
        <Route path="/games/:id" element={<GameDetailPage />} />
        <Route path="/games/:id/edit" element={<GameFormRoute />} />
        <Route path="/schedule" element={<ScheduleRoute />} />
        <Route path="/tournaments" element={<TournamentsRoute />} />
        <Route path="/tournaments/new" element={<TournamentFormRoute />} />
        <Route path="/tournaments/:id" element={<TournamentDetailRoute />} />
        <Route path="/tournaments/:id/edit" element={<TournamentFormRoute />} />
        <Route path="/rating" element={<RatingPage />} />
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
        <Route path="/admin/moderators" element={<ModeratorAdminRoute />} />
          <Route path="*" element={<NotFoundRoute />} />
        </Routes>
      </Suspense>
    </div>
  )
}
