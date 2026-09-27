import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { ApiClientError } from '../api/client'
import {
  deletePlayer,
  resetPlayerAvatar,
  resetPlayerPassword,
  updatePlayerProfile,
  updatePlayerRating,
  updatePlayerUsername,
} from '../api/players'
import type { PlayerAdministrationCapabilities } from '../api/types'

export function PlayerAdministrationPanel({
  playerId,
  username,
  aboutMe,
  rating,
  capabilities,
  onDeleted,
}: {
  playerId: number
  username: string
  aboutMe: string
  rating: number
  capabilities: PlayerAdministrationCapabilities
  onDeleted: () => void
}) {
  const queryClient = useQueryClient()
  const [notice, setNotice] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  async function refresh() {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['player', playerId] }),
      queryClient.invalidateQueries({ queryKey: ['players'] }),
      queryClient.invalidateQueries({ queryKey: ['player-administration', playerId] }),
    ])
  }

  function result(message: string) {
    setError(null)
    setNotice(message)
  }

  function failed(value: unknown) {
    setNotice(null)
    setError(errorMessage(value, 'Операция не выполнена.'))
  }

  const ratingMutation = useMutation({
    mutationFn: (value: number) => updatePlayerRating(playerId, value),
    onSuccess: async (response) => { result(response.data.message); await refresh() },
    onError: failed,
  })
  const usernameMutation = useMutation({
    mutationFn: (value: { username: string; currentPassword: string }) => updatePlayerUsername(playerId, value.username, value.currentPassword),
    onSuccess: async (response) => { result(response.data.message); await refresh() },
    onError: failed,
  })
  const passwordMutation = useMutation({
    mutationFn: (value: { password: string; currentPassword: string }) => resetPlayerPassword(playerId, value.password, value.currentPassword),
    onSuccess: async (response) => { result(response.data.message); await refresh() },
    onError: failed,
  })
  const avatarMutation = useMutation({
    mutationFn: (value: { currentPassword: string }) => resetPlayerAvatar(playerId, value.currentPassword),
    onSuccess: async (response) => { result(response.data.message); await refresh() },
    onError: failed,
  })
  const profileMutation = useMutation({
    mutationFn: (value: string) => updatePlayerProfile(playerId, value),
    onSuccess: async (response) => { result(response.data.message); await refresh() },
    onError: failed,
  })
  const deleteMutation = useMutation({
    mutationFn: (value: { currentPassword: string; confirmation: string }) => deletePlayer(playerId, value.currentPassword, value.confirmation),
    onSuccess: (response) => { result(response.data.message); queryClient.invalidateQueries({ queryKey: ['players'] }); onDeleted() },
    onError: failed,
  })

  if (!capabilities.manageable && !capabilities.profileEdit) return null

  return (
    <section className="section-block administration-panel">
      <div className="section-heading">
        <div>
          <p className="eyebrow">Управление</p>
          <h2>Администрирование игрока</h2>
        </div>
      </div>
      {notice && <div className="notice success-notice" role="status">{notice}</div>}
      {error && <div className="notice error-notice" role="alert">{error}</div>}
      <div className="admin-grid">
        {capabilities.rating && <RatingForm defaultValue={rating} pending={ratingMutation.isPending} onSubmit={(value) => ratingMutation.mutate(value)} />}
        {capabilities.username && <UsernameForm defaultValue={username} pending={usernameMutation.isPending} onSubmit={(value) => usernameMutation.mutate(value)} />}
        {capabilities.password && <PasswordForm pending={passwordMutation.isPending} onSubmit={(value) => passwordMutation.mutate(value)} />}
        {capabilities.avatarReset && <AvatarResetForm pending={avatarMutation.isPending} onSubmit={(value) => avatarMutation.mutate(value)} />}
        {capabilities.profileEdit && <AboutForm defaultValue={aboutMe} pending={profileMutation.isPending} onSubmit={(value) => profileMutation.mutate(value)} />}
        {capabilities.deleteAccount && <DeleteForm username={username} pending={deleteMutation.isPending} onSubmit={(value) => deleteMutation.mutate(value)} />}
      </div>
    </section>
  )
}

const ratingSchema = z.object({ rating: z.coerce.number().finite('Укажите корректный рейтинг.') })
type RatingValues = z.infer<typeof ratingSchema>
function RatingForm({ defaultValue, pending, onSubmit }: { defaultValue: number; pending: boolean; onSubmit: (rating: number) => void }) {
  const form = useForm<RatingValues>({ resolver: zodResolver(ratingSchema), defaultValues: { rating: defaultValue } })
  return <form className="admin-card" onSubmit={form.handleSubmit((values) => onSubmit(values.rating))}>
    <h3>Рейтинг</h3>
    <label className="field"><span>Новое значение</span><input type="number" step="0.01" {...form.register('rating')} /></label>
    <FieldError message={form.formState.errors.rating?.message} />
    <button className="button primary" disabled={pending}>Сохранить рейтинг</button>
  </form>
}

const usernameSchema = z.object({
  username: z.string().trim().regex(/^[A-Za-zА-Яа-яЁё0-9_]{3,32}$/, 'Ник: 3-32 символа, буквы, цифры и _.'),
  currentPassword: z.string().min(1, 'Введите текущий пароль.'),
})
type UsernameValues = z.infer<typeof usernameSchema>
function UsernameForm({ defaultValue, pending, onSubmit }: { defaultValue: string; pending: boolean; onSubmit: (values: UsernameValues) => void }) {
  const form = useForm<UsernameValues>({ resolver: zodResolver(usernameSchema), defaultValues: { username: defaultValue, currentPassword: '' } })
  return <form className="admin-card" onSubmit={form.handleSubmit(onSubmit)}>
    <h3>Ник</h3>
    <label className="field"><span>Новый ник</span><input autoComplete="off" {...form.register('username')} /></label>
    <FieldError message={form.formState.errors.username?.message} />
    <label className="field"><span>Ваш текущий пароль</span><input type="password" autoComplete="current-password" {...form.register('currentPassword')} /></label>
    <FieldError message={form.formState.errors.currentPassword?.message} />
    <button className="button primary" disabled={pending}>Изменить ник</button>
  </form>
}

const passwordSchema = z.object({
  password: z.string().min(6, 'Минимум 6 символов.').max(64, 'Максимум 64 символа.').refine((value) => new TextEncoder().encode(value).length <= 72, 'Пароль превышает 72 UTF-8 байта.'),
  currentPassword: z.string().min(1, 'Введите текущий пароль.'),
})
type PasswordValues = z.infer<typeof passwordSchema>
function PasswordForm({ pending, onSubmit }: { pending: boolean; onSubmit: (values: PasswordValues) => void }) {
  const form = useForm<PasswordValues>({ resolver: zodResolver(passwordSchema), defaultValues: { password: '', currentPassword: '' } })
  return <form className="admin-card" onSubmit={form.handleSubmit(onSubmit)}>
    <h3>Сброс пароля</h3>
    <label className="field"><span>Новый пароль</span><input type="password" autoComplete="new-password" {...form.register('password')} /></label>
    <FieldError message={form.formState.errors.password?.message} />
    <label className="field"><span>Ваш текущий пароль</span><input type="password" autoComplete="current-password" {...form.register('currentPassword')} /></label>
    <FieldError message={form.formState.errors.currentPassword?.message} />
    <button className="button primary" disabled={pending}>Сменить пароль</button>
  </form>
}

const avatarSchema = z.object({ currentPassword: z.string().min(1, 'Введите текущий пароль.') })
type AvatarValues = z.infer<typeof avatarSchema>
function AvatarResetForm({ pending, onSubmit }: { pending: boolean; onSubmit: (values: AvatarValues) => void }) {
  const form = useForm<AvatarValues>({ resolver: zodResolver(avatarSchema), defaultValues: { currentPassword: '' } })
  return <form className="admin-card" onSubmit={form.handleSubmit(onSubmit)}>
    <h3>Аватар</h3>
    <p>Удалить текущий аватар и вернуть стандартное изображение.</p>
    <label className="field"><span>Текущий пароль</span><input type="password" autoComplete="current-password" {...form.register('currentPassword')} /></label>
    <FieldError message={form.formState.errors.currentPassword?.message} />
    <button className="button secondary" disabled={pending}>Сбросить аватар</button>
  </form>
}

const aboutSchema = z.object({ aboutMe: z.string().max(500, 'Максимум 500 символов.') })
type AboutValues = z.infer<typeof aboutSchema>
function AboutForm({ defaultValue, pending, onSubmit }: { defaultValue: string; pending: boolean; onSubmit: (about: string) => void }) {
  const form = useForm<AboutValues>({ resolver: zodResolver(aboutSchema), defaultValues: { aboutMe: defaultValue } })
  return <form className="admin-card admin-card-wide" onSubmit={form.handleSubmit((values) => onSubmit(values.aboutMe))}>
    <h3>Описание профиля</h3>
    <label className="field"><span>О себе</span><textarea rows={5} {...form.register('aboutMe')} /></label>
    <FieldError message={form.formState.errors.aboutMe?.message} />
    <button className="button primary" disabled={pending}>Сохранить описание</button>
  </form>
}

const deleteSchema = z.object({ currentPassword: z.string().min(1, 'Введите текущий пароль.'), confirmation: z.string().min(1, 'Введите ник игрока.') })
type DeleteValues = z.infer<typeof deleteSchema>
function DeleteForm({ username, pending, onSubmit }: { username: string; pending: boolean; onSubmit: (values: DeleteValues) => void }) {
  const form = useForm<DeleteValues>({ resolver: zodResolver(deleteSchema), defaultValues: { currentPassword: '', confirmation: '' } })
  return <form className="admin-card danger-card" onSubmit={form.handleSubmit(onSubmit)}>
    <h3>Удаление аккаунта</h3>
    <p>История игр сохранится. Для подтверждения введите ник <strong>{username}</strong>.</p>
    <label className="field"><span>Ник игрока</span><input autoComplete="off" {...form.register('confirmation')} /></label>
    <FieldError message={form.formState.errors.confirmation?.message} />
    <label className="field"><span>Ваш текущий пароль</span><input type="password" autoComplete="current-password" {...form.register('currentPassword')} /></label>
    <FieldError message={form.formState.errors.currentPassword?.message} />
    <button className="button danger" disabled={pending || form.watch('confirmation') !== username}>Удалить аккаунт</button>
  </form>
}

function FieldError({ message }: { message?: string }) {
  return message ? <p className="field-error">{message}</p> : null
}


function errorMessage(error: unknown, fallback: string) {
  return error instanceof ApiClientError ? error.details.message : fallback
}
