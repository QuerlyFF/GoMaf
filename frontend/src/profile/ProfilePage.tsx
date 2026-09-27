import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState, type ReactNode } from 'react'
import { useForm } from 'react-hook-form'
import { Link, Navigate } from 'react-router-dom'
import { z } from 'zod'
import { ApiClientError } from '../api/client'
import {
  getSelfProfile,
  getSelfProfileGames,
  resetSelfAvatar,
  resetSelfBanner,
  updateSelfAbout,
  uploadSelfAvatar,
  uploadSelfBanner,
} from '../api/profile'
import type { Session } from '../api/types'

const aboutSchema = z.object({ aboutMe: z.string().max(500, 'Максимум 500 символов.') })
type AboutValues = z.infer<typeof aboutSchema>
const MAX_BANNER_BYTES = 5 * 1024 * 1024
const MAX_AVATAR_BYTES = 2 * 1024 * 1024
const AVATAR_OUTPUT_SIDE = 512

export function ProfilePage({ session }: { session: Session | undefined }) {
  const queryClient = useQueryClient()
  const [gamesPage, setGamesPage] = useState(0)
  const [notice, setNotice] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const profile = useQuery({
    queryKey: ['self-profile'],
    queryFn: getSelfProfile,
    enabled: session?.authenticated === true,
  })
  const games = useQuery({
    queryKey: ['self-profile-games', gamesPage],
    queryFn: () => getSelfProfileGames(gamesPage, 20),
    enabled: session?.authenticated === true,
  })

  const aboutMutation = useMutation({
    mutationFn: updateSelfAbout,
    onSuccess: async (response) => {
      setError(null)
      setNotice(response.data.message)
      await queryClient.invalidateQueries({ queryKey: ['self-profile'] })
      if (session?.userId) await queryClient.invalidateQueries({ queryKey: ['player', session.userId] })
    },
    onError: (value) => fail(value, setNotice, setError),
  })
  const avatarMutation = useMutation({
    mutationFn: uploadSelfAvatar,
    onSuccess: async (response) => {
      setError(null)
      setNotice(response.data.message)
      await invalidateAvatarQueries(queryClient, session?.userId)
    },
    onError: (value) => fail(value, setNotice, setError),
  })
  const resetAvatarMutation = useMutation({
    mutationFn: resetSelfAvatar,
    onSuccess: async (response) => {
      setError(null)
      setNotice(response.data.message)
      await invalidateAvatarQueries(queryClient, session?.userId)
    },
    onError: (value) => fail(value, setNotice, setError),
  })

  const bannerMutation = useMutation({
    mutationFn: uploadSelfBanner,
    onSuccess: async (response) => {
      setError(null)
      setNotice(response.data.message)
      await invalidateAvatarQueries(queryClient, session?.userId)
    },
    onError: (value) => fail(value, setNotice, setError),
  })
  const resetBannerMutation = useMutation({
    mutationFn: resetSelfBanner,
    onSuccess: async (response) => {
      setError(null)
      setNotice(response.data.message)
      await invalidateAvatarQueries(queryClient, session?.userId)
    },
    onError: (value) => fail(value, setNotice, setError),
  })

  if (session && !session.authenticated) return <Navigate to="/login" replace state={{ returnTo: '/profile' }} />
  if (!session || profile.isPending) return <main className="page-shell"><StatusPanel>Загружаем профиль...</StatusPanel></main>
  if (profile.isError) return <main className="page-shell"><StatusPanel error>{errorMessage(profile.error, 'Не удалось загрузить профиль.')}</StatusPanel></main>

  const data = profile.data.data
  const mediaVersion = encodeURIComponent(String(profile.dataUpdatedAt))
  const publicAvatarUrl = `/api/v1/players/${data.id}/avatar`
  const avatarSrc = `${publicAvatarUrl}?size=256&v=${mediaVersion}`
  const avatarSrcSet = `${publicAvatarUrl}?size=256&v=${mediaVersion} 1x, ${publicAvatarUrl}?size=512&v=${mediaVersion} 2x`
  const avatarCompactSrcSet = `${publicAvatarUrl}?size=128&v=${mediaVersion} 1x, ${publicAvatarUrl}?size=256&v=${mediaVersion} 2x`
  const bannerSrc = `${data.bannerUrl}?width=1280&v=${mediaVersion}`

  return (
    <main className="page-shell profile-page">
      <section className="profile-social-card">
        <div className="profile-banner-frame">
          <picture className="profile-banner-picture">
            <source media="(max-width: 720px)" srcSet={`${data.bannerUrl}?width=640&v=${mediaVersion}`} />
            <source media="(max-width: 1024px)" srcSet={`${data.bannerUrl}?width=960&v=${mediaVersion}`} />
            <img
              className="profile-banner"
              src={bannerSrc}
              alt=""
              loading="eager"
              fetchPriority="high"
              decoding="async"
            />
          </picture>
        </div>
        <div className="profile-social-body">
          <picture>
            <source media="(max-width: 430px)" srcSet={avatarCompactSrcSet} />
            <source media="(max-width: 720px)" srcSet={avatarSrcSet} />
            <img
              className="profile-avatar profile-avatar-overlap"
              src={avatarSrc}
              srcSet={avatarSrcSet}
              alt=""
              width="208"
              height="208"
              decoding="async"
            />
          </picture>
          <div className="profile-social-copy">
            <h1>{data.username}</h1>
            <p className="profile-about">{data.aboutMe || 'Вы пока ничего о себе не написали.'}</p>
          </div>
        </div>
      </section>

      <section className="profile-stats" aria-label="Моя статистика">
        <Stat label="Рейтинг" value={formatNumber(data.rating)} />
        <Stat label="Игр" value={String(data.gamesPlayed)} />
        <Stat label="Побед" value={String(data.wins)} />
        <Stat label="Поражений" value={String(data.losses)} />
        <Stat label="Win rate" value={`${Math.round(data.winRate * 100)}%`} />
        <Stat label="Средний балл" value={formatNumber(data.averageScore)} />
      </section>

      {notice && <StatusPanel>{notice}</StatusPanel>}
      {error && <StatusPanel error>{error}</StatusPanel>}

      <section className="section-block">
        <div className="section-heading"><div><p className="eyebrow">Настройки</p><h2>Профиль</h2></div></div>
        <div className="admin-grid profile-settings-grid">
          <AboutSelfForm
            defaultValue={data.aboutMe}
            pending={aboutMutation.isPending}
            onSubmit={(value) => aboutMutation.mutate(value)}
          />
          <AvatarForm
            pending={avatarMutation.isPending || resetAvatarMutation.isPending}
            onUpload={(file) => avatarMutation.mutate(file)}
            onReset={() => resetAvatarMutation.mutate()}
          />
          <BannerForm
            pending={bannerMutation.isPending || resetBannerMutation.isPending}
            onUpload={(file) => bannerMutation.mutate(file)}
            onReset={() => resetBannerMutation.mutate()}
          />
        </div>
      </section>

      <section className="section-block">
        <div className="section-heading"><div><p className="eyebrow">История</p><h2>Мои игры</h2></div></div>
        {games.isPending && <StatusPanel>Загружаем историю игр...</StatusPanel>}
        {games.isError && <StatusPanel error>{errorMessage(games.error, 'Не удалось загрузить историю.')}</StatusPanel>}
        {games.data && games.data.items.length === 0 && <StatusPanel>В доступной истории игр пока нет.</StatusPanel>}
        {games.data && games.data.items.length > 0 && (
          <>
            <div className="games-list">
              {games.data.items.map((game) => (
                <article className="game-row" key={game.gameId}>
                  <div><h3><Link to={`/games/${game.gameId}`}>{game.name}</Link></h3><p>{formatDate(game.gameDate)} · {game.roleDisplayName}</p></div>
                  <div className="game-score"><span>{game.won ? 'Победа' : 'Поражение'}</span><strong>{formatNumber(game.totalPoints)}</strong></div>
                </article>
              ))}
            </div>
            <nav className="pagination" aria-label="Страницы моей истории игр">
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

function AboutSelfForm({ defaultValue, pending, onSubmit }: { defaultValue: string; pending: boolean; onSubmit: (value: string) => void }) {
  const form = useForm<AboutValues>({ resolver: zodResolver(aboutSchema), values: { aboutMe: defaultValue } })
  return (
    <form className="admin-card admin-card-wide" onSubmit={form.handleSubmit((values) => onSubmit(values.aboutMe))}>
      <h3>О себе</h3>
      <label className="field"><span>Описание</span><textarea rows={6} {...form.register('aboutMe')} /></label>
      {form.formState.errors.aboutMe?.message && <p className="field-error">{form.formState.errors.aboutMe.message}</p>}
      <div className="field-help">До 500 символов.</div>
      <button className="button primary" disabled={pending}>Сохранить</button>
    </form>
  )
}

function AvatarForm({ pending, onUpload, onReset }: { pending: boolean; onUpload: (file: File) => void; onReset: () => void }) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null)
  const imageRef = useRef<HTMLImageElement | null>(null)
  const [sourceDimensions, setSourceDimensions] = useState<{ width: number; height: number } | null>(null)
  const [zoom, setZoom] = useState(100)
  const [fileError, setFileError] = useState<string | null>(null)

  useEffect(() => {
    const image = imageRef.current
    const canvas = canvasRef.current
    if (!image || !canvas || !sourceDimensions) return

    canvas.width = AVATAR_OUTPUT_SIDE
    canvas.height = AVATAR_OUTPUT_SIDE
    const context = canvas.getContext('2d')
    if (!context) return

    const fitScale = Math.min(AVATAR_OUTPUT_SIDE / sourceDimensions.width, AVATAR_OUTPUT_SIDE / sourceDimensions.height)
    const drawScale = fitScale * zoom / 100
    const drawWidth = sourceDimensions.width * drawScale
    const drawHeight = sourceDimensions.height * drawScale
    const x = (AVATAR_OUTPUT_SIDE - drawWidth) / 2
    const y = (AVATAR_OUTPUT_SIDE - drawHeight) / 2

    context.clearRect(0, 0, AVATAR_OUTPUT_SIDE, AVATAR_OUTPUT_SIDE)
    context.imageSmoothingEnabled = true
    context.imageSmoothingQuality = 'high'
    context.drawImage(image, x, y, drawWidth, drawHeight)
  }, [sourceDimensions, zoom])

  function chooseFile(next: File | null) {
    if (!next) {
      imageRef.current = null
      setSourceDimensions(null)
      setZoom(100)
      setFileError(null)
      return
    }
    if (next.size > MAX_AVATAR_BYTES) {
      setFileError('Аватар должен быть не больше 2 МБ.')
      return
    }
    if (!['image/png', 'image/jpeg'].includes(next.type)) {
      setFileError('Разрешены PNG и JPEG.')
      return
    }

    const reader = new FileReader()
    reader.onerror = () => {
      imageRef.current = null
      setSourceDimensions(null)
      setFileError('Не удалось прочитать изображение.')
    }
    reader.onload = () => {
      if (typeof reader.result !== 'string') {
        setFileError('Не удалось прочитать изображение.')
        return
      }
      const image = new Image()
      image.onload = () => {
        imageRef.current = image
        setSourceDimensions({ width: image.naturalWidth, height: image.naturalHeight })
        setZoom(100)
        setFileError(null)
      }
      image.onerror = () => {
        imageRef.current = null
        setSourceDimensions(null)
        setFileError('Не удалось открыть изображение.')
      }
      image.src = reader.result
    }
    reader.readAsDataURL(next)
  }

  function uploadPreparedAvatar() {
    const canvas = canvasRef.current
    if (!canvas || !sourceDimensions) return
    canvas.toBlob((blob) => {
      if (!blob) {
        setFileError('Не удалось подготовить аватар.')
        return
      }
      if (blob.size > MAX_AVATAR_BYTES) {
        setFileError('После обработки аватар получился больше 2 МБ.')
        return
      }
      setFileError(null)
      onUpload(new File([blob], 'avatar.png', { type: 'image/png' }))
    }, 'image/png')
  }

  const placed = sourceDimensions ? avatarPlacedSize(sourceDimensions.width, sourceDimensions.height, zoom) : null

  return (
    <section className="admin-card admin-card-wide avatar-editor-card">
      <h3>Аватар</h3>
      <p>PNG или JPEG, максимум 2 МБ. Масштаб можно настроить до загрузки, поэтому изображение не обязано обрезаться по краям.</p>
      <label className="field"><span>Файл</span><input type="file" accept="image/png,image/jpeg,.png,.jpg,.jpeg" onChange={(event) => chooseFile(event.target.files?.[0] ?? null)} /></label>
      {fileError && <p className="field-error">{fileError}</p>}

      {sourceDimensions && (
        <div className="avatar-editor">
          <canvas ref={canvasRef} className="avatar-editor-preview" role="img" aria-label="Предпросмотр аватара" />
          <div className="avatar-editor-controls">
            <div className="avatar-editor-metadata">
              <span>Исходник: {sourceDimensions.width} x {sourceDimensions.height} px</span>
              {placed && <span>Размер изображения в холсте: {placed.width} x {placed.height} px</span>}
            </div>
            <label className="field avatar-zoom-field">
              <span>Масштаб: {zoom}%</span>
              <input type="range" min="100" max="250" step="5" value={zoom} disabled={pending} onChange={(event) => setZoom(Number(event.target.value))} />
            </label>
            <div className="field-help">100% полностью вписывает изображение в квадрат. Увеличение приближает его относительно центра.</div>
            <button className="button secondary" type="button" disabled={pending || zoom === 100} onClick={() => setZoom(100)}>Вписать полностью</button>
          </div>
        </div>
      )}

      <div className="button-row avatar-upload-row">
        <button className="button primary" type="button" disabled={pending || !sourceDimensions} onClick={uploadPreparedAvatar}>Загрузить аватар</button>
        <span className="field-help avatar-upload-size">После обработки: 512 x 512 px, PNG.</span>
        <button className="button secondary" type="button" disabled={pending} onClick={onReset}>Сбросить</button>
      </div>
    </section>
  )
}

function avatarPlacedSize(width: number, height: number, zoom: number) {
  const fitScale = Math.min(AVATAR_OUTPUT_SIDE / width, AVATAR_OUTPUT_SIDE / height)
  const drawScale = fitScale * zoom / 100
  return {
    width: Math.round(width * drawScale),
    height: Math.round(height * drawScale),
  }
}

function BannerForm({ pending, onUpload, onReset }: { pending: boolean; onUpload: (file: File) => void; onReset: () => void }) {
  const [file, setFile] = useState<File | null>(null)
  const [fileError, setFileError] = useState<string | null>(null)

  function chooseFile(next: File | null) {
    if (!next) {
      setFile(null)
      setFileError(null)
      return
    }
    if (next.size > MAX_BANNER_BYTES) {
      setFile(null)
      setFileError('Баннер должен быть не больше 5 МБ.')
      return
    }
    if (!['image/png', 'image/jpeg', 'image/gif'].includes(next.type)) {
      setFile(null)
      setFileError('Разрешены PNG, JPEG и GIF.')
      return
    }
    setFile(next)
    setFileError(null)
  }

  return (
    <section className="admin-card admin-card-wide profile-banner-form">
      <h3>Баннер профиля</h3>
      <p>PNG, JPEG или GIF. Максимальный размер файла 5 МБ. Рекомендуем пропорции около 3:1.</p>
      <label className="field"><span>Файл</span><input type="file" accept="image/png,image/jpeg,image/gif,.png,.jpg,.jpeg,.gif" onChange={(event) => chooseFile(event.target.files?.[0] ?? null)} /></label>
      {fileError && <p className="field-error">{fileError}</p>}
      <div className="button-row">
        <button className="button primary" type="button" disabled={pending || !file} onClick={() => file && onUpload(file)}>Загрузить баннер</button>
        <button className="button secondary" type="button" disabled={pending} onClick={onReset}>Сбросить баннер</button>
      </div>
    </section>
  )
}

async function invalidateAvatarQueries(queryClient: ReturnType<typeof useQueryClient>, userId: number | null | undefined) {
  await queryClient.invalidateQueries({ queryKey: ['self-profile'] })
  if (userId) {
    await queryClient.invalidateQueries({ queryKey: ['player', userId] })
    await queryClient.invalidateQueries({ queryKey: ['players'] })
  }
}

function fail(value: unknown, setNotice: (value: string | null) => void, setError: (value: string | null) => void) {
  setNotice(null)
  setError(errorMessage(value, 'Операция не выполнена.'))
}

function errorMessage(error: unknown, fallback: string) {
  return error instanceof ApiClientError ? error.details.message : fallback
}

function StatusPanel({ children, error = false }: { children: ReactNode; error?: boolean }) {
  return <section className={`status-panel${error ? ' error-panel' : ''}`} aria-live="polite">{children}</section>
}

function Stat({ label, value }: { label: string; value: string }) {
  return <div><span>{label}</span><strong>{value}</strong></div>
}

function formatNumber(value: number) {
  return new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 2 }).format(value)
}

function formatDate(value: string) {
  const date = new Date(`${value}T00:00:00`)
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('ru-RU').format(date)
}
