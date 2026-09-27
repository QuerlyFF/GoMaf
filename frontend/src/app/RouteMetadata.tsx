import { useEffect } from 'react'
import { useLocation } from 'react-router-dom'

type Metadata = {
  title: string
  description: string
  canonicalPath?: string
  indexable: boolean
}

const DEFAULT_DESCRIPTION = 'GoMaf — игры, расписание, турниры и рейтинг клуба.'

export function RouteMetadata() {
  const { pathname } = useLocation()

  useEffect(() => {
    const metadata = metadataFor(pathname)
    document.title = metadata.title
    setMeta('description', metadata.description)
    setMeta('robots', metadata.indexable ? 'index,follow' : 'noindex,nofollow')

    const existing = document.querySelector<HTMLLinkElement>('link[rel="canonical"]')
    if (!metadata.indexable || !metadata.canonicalPath) {
      existing?.remove()
      return
    }
    const canonical = existing ?? document.head.appendChild(document.createElement('link'))
    canonical.rel = 'canonical'
    canonical.href = `${window.location.origin}${metadata.canonicalPath}`
  }, [pathname])

  return null
}

function setMeta(name: string, content: string) {
  const existing = document.querySelector<HTMLMetaElement>(`meta[name="${name}"]`)
  const meta = existing ?? document.head.appendChild(document.createElement('meta'))
  meta.name = name
  meta.content = content
}

function metadataFor(path: string): Metadata {
  if (/^\/players\/\d+$/.test(path)) {
    return publicMetadata('Профиль игрока — GoMaf', 'Профиль игрока GoMaf: статистика и история игр.', path)
  }
  if (/^\/games\/\d+\/edit$/.test(path)) return privateMetadata('Редактирование игры — GoMaf')
  if (/^\/games\/\d+$/.test(path)) {
    return publicMetadata('Игра — GoMaf', 'Результат игры GoMaf: состав, роли и игровая статистика.', path)
  }
  if (/^\/tournaments\/\d+\/edit$/.test(path)) return privateMetadata('Редактирование турнира — GoMaf')
  if (/^\/tournaments\/\d+$/.test(path)) {
    return publicMetadata('Турнир — GoMaf', 'Турнир GoMaf: даты, участники и связанные игры.', path)
  }

  switch (path) {
    case '/':
    case '/news':
      return publicMetadata('Новости — GoMaf', 'Новости игрового клуба GoMaf: события, объявления и обновления сообщества.', '/news')
    case '/rules':
      return publicMetadata('Правила игры в мафию — GoMaf', 'Правила классической мафии клуба GoMaf: роли, порядок игры и игровые ситуации.', '/rules')
    case '/players':
      return publicMetadata('Игроки — GoMaf', 'Игроки GoMaf: профили, рейтинг, статистика и история игр.', '/players')
    case '/games':
      return publicMetadata('Игры — GoMaf', 'История игр GoMaf: составы, результаты и игровая статистика.', '/games')
    case '/schedule':
      return publicMetadata('Расписание — GoMaf', 'Актуальное расписание игр и мероприятий клуба GoMaf.', '/schedule')
    case '/tournaments':
      return publicMetadata('Турниры — GoMaf', 'Турниры GoMaf: даты, участники, игры и результаты.', '/tournaments')
    case '/rating':
      return publicMetadata('Рейтинг игроков — GoMaf', 'Рейтинг и статистика игроков клуба GoMaf по сыгранным партиям.', '/rating')
    case '/login':
      return privateMetadata('Вход — GoMaf')
    case '/register':
      return privateMetadata('Регистрация — GoMaf')
    case '/profile':
      return privateMetadata('Профиль — GoMaf')
    case '/admin/moderators':
      return privateMetadata('Администрирование — GoMaf')
    case '/games/new':
      return privateMetadata('Новая игра — GoMaf')
    case '/tournaments/new':
      return privateMetadata('Новый турнир — GoMaf')
    default:
      return privateMetadata('GoMaf')
  }
}

function publicMetadata(title: string, description: string, canonicalPath: string): Metadata {
  return { title, description, canonicalPath, indexable: true }
}

function privateMetadata(title: string): Metadata {
  return { title, description: DEFAULT_DESCRIPTION, indexable: false }
}
