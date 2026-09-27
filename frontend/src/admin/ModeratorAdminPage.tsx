import { useEffect, useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Navigate } from 'react-router-dom'
import {
  assignModerator,
  getEligibleModeratorPlayers,
  getModeratorDefaults,
  getModerators,
  removeModerator,
  updateModeratorDefaults,
  updateModeratorOverrides,
} from '../api/moderators'
import type { ModeratorAdminRow, Session } from '../api/types'
import { ApiClientError } from '../api/client'

function errorText(error: unknown) {
  return error instanceof ApiClientError ? error.details.message : 'Не удалось выполнить операцию.'
}

function ModeratorCard({ row, scopeNames }: { row: ModeratorAdminRow; scopeNames: Array<{ name: string; displayName: string }> }) {
  const queryClient = useQueryClient()
  const [password, setPassword] = useState('')
  const initial = useMemo(() => Object.fromEntries(scopeNames.map((scope) => [scope.name, row.scopes[scope.name]?.decision ?? 'INHERIT'])), [row, scopeNames])
  const [values, setValues] = useState<Record<string, 'INHERIT' | 'ALLOW' | 'DENY'>>(initial)
  useEffect(() => setValues(initial), [initial])

  const save = useMutation({
    mutationFn: () => updateModeratorOverrides(row.userId, values, password),
    onSuccess: async () => {
      setPassword('')
      await queryClient.invalidateQueries({ queryKey: ['moderator-admin'] })
    },
  })
  const remove = useMutation({
    mutationFn: () => removeModerator(row.userId, password),
    onSuccess: async () => {
      setPassword('')
      await queryClient.invalidateQueries({ queryKey: ['moderator-admin'] })
    },
  })

  return (
    <article className="admin-card moderator-card">
      <div className="section-heading compact-heading"><div><p className="eyebrow">Модератор</p><h3>{row.username}</h3></div></div>
      <div className="scope-grid">
        {scopeNames.map((scope) => {
          const state = row.scopes[scope.name]
          return <label className="field" key={scope.name}>
            <span>{scope.displayName} {state?.effective ? '(разрешено)' : '(запрещено)'}</span>
            <select value={values[scope.name] ?? 'INHERIT'} onChange={(event) => setValues((current) => ({ ...current, [scope.name]: event.target.value as 'INHERIT' | 'ALLOW' | 'DENY' }))}>
              <option value="INHERIT">Наследовать</option><option value="ALLOW">Разрешить</option><option value="DENY">Запретить</option>
            </select>
          </label>
        })}
      </div>
      <label className="field"><span>Текущий пароль администратора</span><input type="password" autoComplete="current-password" value={password} onChange={(event) => setPassword(event.target.value)} /></label>
      {(save.error || remove.error) && <div className="error-notice">{errorText(save.error ?? remove.error)}</div>}
      {(save.data || remove.data) && <div className="success-notice">{(save.data ?? remove.data)?.data.message}</div>}
      <div className="button-row">
        <button className="button primary" type="button" disabled={!password || save.isPending || remove.isPending} onClick={() => save.mutate()}>Сохранить разрешения</button>
        <button className="button danger" type="button" disabled={!password || save.isPending || remove.isPending} onClick={() => remove.mutate()}>Снять права модератора</button>
      </div>
    </article>
  )
}

export function ModeratorAdminPage({ session }: { session?: Session }) {
  const queryClient = useQueryClient()
  const [moderatorSearch, setModeratorSearch] = useState('')
  const [eligibleSearch, setEligibleSearch] = useState('')
  const [defaultsPassword, setDefaultsPassword] = useState('')
  const [assignmentPasswords, setAssignmentPasswords] = useState<Record<number, string>>({})

  const defaultsQuery = useQuery({ queryKey: ['moderator-admin', 'defaults'], queryFn: getModeratorDefaults, enabled: session?.role === 'ADMIN' })
  const moderatorsQuery = useQuery({ queryKey: ['moderator-admin', 'moderators', moderatorSearch], queryFn: () => getModerators(moderatorSearch), enabled: session?.role === 'ADMIN' })
  const eligibleQuery = useQuery({ queryKey: ['moderator-admin', 'eligible', eligibleSearch], queryFn: () => getEligibleModeratorPlayers(eligibleSearch), enabled: session?.role === 'ADMIN' })

  const [defaultValues, setDefaultValues] = useState<Record<string, boolean>>({})
  useEffect(() => { if (defaultsQuery.data?.data.defaults) setDefaultValues(defaultsQuery.data.data.defaults) }, [defaultsQuery.data])

  const saveDefaults = useMutation({
    mutationFn: () => updateModeratorDefaults(defaultValues, defaultsPassword),
    onSuccess: async () => {
      setDefaultsPassword('')
      await queryClient.invalidateQueries({ queryKey: ['moderator-admin'] })
      await queryClient.invalidateQueries({ queryKey: ['session'] })
    },
  })

  const assign = useMutation({
    mutationFn: ({ userId, password }: { userId: number; password: string }) => assignModerator(userId, password),
    onSuccess: async (_, variables) => {
      setAssignmentPasswords((current) => ({ ...current, [variables.userId]: '' }))
      await queryClient.invalidateQueries({ queryKey: ['moderator-admin'] })
    },
  })

  if (session && session.authenticated && session.role !== 'ADMIN') return <Navigate to="/players" replace />
  if (!session?.authenticated) return <main className="page-shell narrow-page"><h1>Управление модераторами</h1><div className="status-panel">Войдите под учетной записью администратора.</div></main>

  const scopes = defaultsQuery.data?.data.scopes ?? []
  return (
    <main className="page-shell admin-console-page">
      <div className="page-heading"><p className="eyebrow">Администрирование</p><h1>Модераторы</h1><p>Назначение модераторов и настройка их прав. Для изменений требуется текущий пароль администратора.</p></div>
      <section className="section-block admin-card admin-card-wide">
        <h2>Общие разрешения</h2>
        {defaultsQuery.isPending && <div className="status-panel">Загрузка...</div>}
        {defaultsQuery.error && <div className="error-notice">{errorText(defaultsQuery.error)}</div>}
        <div className="scope-grid">{scopes.map((scope) => <label className="check-field scope-toggle" key={scope.name}><input type="checkbox" checked={Boolean(defaultValues[scope.name])} onChange={(event) => setDefaultValues((current) => ({ ...current, [scope.name]: event.target.checked }))} /><span>{scope.displayName}</span></label>)}</div>
        <label className="field admin-password-field"><span>Текущий пароль администратора</span><input type="password" autoComplete="current-password" value={defaultsPassword} onChange={(event) => setDefaultsPassword(event.target.value)} /></label>
        {saveDefaults.error && <div className="error-notice">{errorText(saveDefaults.error)}</div>}
        {saveDefaults.data && <div className="success-notice">{saveDefaults.data.data.message}</div>}
        <button className="button primary" type="button" disabled={!defaultsPassword || saveDefaults.isPending || scopes.length === 0} onClick={() => saveDefaults.mutate()}>Сохранить общие разрешения</button>
      </section>
      <section className="section-block">
        <div className="section-heading"><div><h2>Текущие модераторы</h2></div><label className="field compact-search"><span>Поиск</span><input value={moderatorSearch} maxLength={64} onChange={(event) => setModeratorSearch(event.target.value)} /></label></div>
        {moderatorsQuery.isPending && <div className="status-panel">Загрузка...</div>}
        {moderatorsQuery.error && <div className="error-notice">{errorText(moderatorsQuery.error)}</div>}
        <div className="admin-grid">{moderatorsQuery.data?.items.map((row) => <ModeratorCard key={row.userId} row={row} scopeNames={scopes} />)}</div>
        {moderatorsQuery.data && moderatorsQuery.data.items.length === 0 && <div className="status-panel">Модераторы не найдены.</div>}
      </section>
      <section className="section-block">
        <div className="section-heading"><div><h2>Назначить модератора</h2><p className="muted">Показаны игроки, которых можно назначить модераторами.</p></div><label className="field compact-search"><span>Поиск</span><input value={eligibleSearch} maxLength={64} onChange={(event) => setEligibleSearch(event.target.value)} /></label></div>
        {eligibleQuery.error && <div className="error-notice">{errorText(eligibleQuery.error)}</div>}
        <div className="eligible-list">{eligibleQuery.data?.items.map((player) => {
          const password = assignmentPasswords[player.userId] ?? ''
          return <div className="content-card" key={player.userId}><strong>{player.username}</strong><div className="assignment-controls"><input aria-label={`Текущий пароль администратора для ${player.username}`} type="password" autoComplete="current-password" placeholder="Текущий пароль администратора" value={password} onChange={(event) => setAssignmentPasswords((current) => ({ ...current, [player.userId]: event.target.value }))} /><button className="button secondary" type="button" disabled={!password || assign.isPending} onClick={() => assign.mutate({ userId: player.userId, password })}>Назначить</button></div></div>
        })}</div>
        {assign.error && <div className="error-notice">{errorText(assign.error)}</div>}
        {assign.data && <div className="success-notice">{assign.data.data.message}</div>}
      </section>
    </main>
  )
}
