import type { FormEvent, ReactNode } from 'react'
import { useEffect, useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { ApiClientError } from '../api/client'
import { createScheduleEntry, deleteScheduleEntry, getSchedule, toggleScheduleParticipation, updateScheduleEntry } from '../api/schedule'
import type { ScheduleEntry, ScheduleWrite, Session } from '../api/types'

type ScheduleFilter = 'ALL' | 'GAME' | 'EVENT'

const WEEKDAYS = ['Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Вс']

export function SchedulePage({ session }: { session: Session | undefined }) {
  const queryClient = useQueryClient()
  const [currentWeekStart, setCurrentWeekStart] = useState(() => startOfWeek(new Date()))
  const scheduleRange = useMemo(() => apiWeekRange(currentWeekStart), [currentWeekStart])
  const schedule = useQuery({
    queryKey: ['schedule', scheduleRange.from, scheduleRange.to],
    queryFn: () => getSchedule(scheduleRange.from, scheduleRange.to),
  })
  const [selectedDayKey, setSelectedDayKey] = useState(() => localDateKey(new Date()))
  const [selectedEntryId, setSelectedEntryId] = useState<number | null>(null)
  const [filter, setFilter] = useState<ScheduleFilter>('ALL')
  const [editing, setEditing] = useState<ScheduleEntry | null>(null)
  const [editorOpen, setEditorOpen] = useState(false)
  const [editorDate, setEditorDate] = useState('')
  const [notice, setNotice] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const canManage = session?.role === 'ADMIN' || session?.moderatorPermissions.includes('SCHEDULE_MANAGE') === true

  const filteredEntries = useMemo(() => {
    const entries = schedule.data ?? []
    if (filter === 'ALL') return entries
    return entries.filter((entry) => entry.entryType === filter)
  }, [filter, schedule.data])

  const groupedEntries = useMemo(() => {
    const groups = new Map<string, ScheduleEntry[]>()
    for (const entry of filteredEntries) {
      const key = entryDateKey(entry)
      const bucket = groups.get(key)
      if (bucket) bucket.push(entry)
      else groups.set(key, [entry])
    }
    for (const items of groups.values()) {
      items.sort((a, b) => a.scheduledAt.localeCompare(b.scheduledAt))
    }
    return groups
  }, [filteredEntries])

  const weekDays = useMemo(() => buildWeekDays(currentWeekStart), [currentWeekStart])
  const selectedEntry = schedule.data?.find((entry) => entry.id === selectedEntryId) ?? null
  const weekEntriesCount = filteredEntries.filter((entry) => isInWeek(entryDate(entry), currentWeekStart)).length

  useEffect(() => {
    if (selectedEntryId === null) return
    function closeOnEscape(event: KeyboardEvent) {
      if (event.key === 'Escape') setSelectedEntryId(null)
    }
    window.addEventListener('keydown', closeOnEscape)
    return () => window.removeEventListener('keydown', closeOnEscape)
  }, [selectedEntryId])

  const participation = useMutation({
    mutationFn: toggleScheduleParticipation,
    onSuccess: async (response) => {
      setError(null)
      setNotice(response.data.message)
      await queryClient.invalidateQueries({ queryKey: ['schedule'] })
    },
    onError: (value) => fail(value, setNotice, setError),
  })

  const save = useMutation({
    mutationFn: ({ id, body }: { id: number | null; body: ScheduleWrite }) => id ? updateScheduleEntry(id, body) : createScheduleEntry(body),
    onSuccess: async (response) => {
      setError(null)
      setNotice(response.data.message)
      setEditing(null)
      setEditorOpen(false)
      setSelectedEntryId(response.data.entryId)
      await queryClient.invalidateQueries({ queryKey: ['schedule'] })
    },
    onError: (value) => fail(value, setNotice, setError),
  })

  const remove = useMutation({
    mutationFn: deleteScheduleEntry,
    onSuccess: async (response) => {
      setError(null)
      setNotice(response.data.message)
      setEditing(null)
      setEditorOpen(false)
      setSelectedEntryId(null)
      await queryClient.invalidateQueries({ queryKey: ['schedule'] })
    },
    onError: (value) => fail(value, setNotice, setError),
  })

  function selectDay(day: Date) {
    setSelectedDayKey(localDateKey(day))
  }

  function openNew(day?: Date) {
    const target = day ?? dateFromKey(selectedDayKey)
    setCurrentWeekStart(startOfWeek(target))
    setSelectedDayKey(localDateKey(target))
    setEditing(null)
    setEditorDate(dateToInputAt(target, 19, 0))
    setEditorOpen(true)
    setSelectedEntryId(null)
    setError(null)
  }

  function openEdit(entry: ScheduleEntry) {
    setEditing(entry)
    setEditorDate(toInputDate(entry.scheduledAt))
    setEditorOpen(true)
    setSelectedEntryId(null)
    setError(null)
  }

  function closeEditor() {
    setEditing(null)
    setEditorOpen(false)
  }

  function changeWeek(offset: number) {
    const next = new Date(currentWeekStart)
    next.setDate(next.getDate() + offset * 7)
    const nextWeekStart = startOfWeek(next)
    setCurrentWeekStart(nextWeekStart)
    setSelectedDayKey(localDateKey(nextWeekStart))
    setSelectedEntryId(null)
  }

  function showCurrentWeek() {
    const today = new Date()
    setCurrentWeekStart(startOfWeek(today))
    setSelectedDayKey(localDateKey(today))
    setSelectedEntryId(null)
  }

  return <main className="page-shell schedule-page">
    <div className="section-heading schedule-page-heading">
      <div><p className="eyebrow">Клуб</p><h1>РАСПИСАНИЕ</h1></div>
      {canManage && <button type="button" className="button primary" onClick={() => openNew()}>Добавить</button>}
    </div>

    {notice && <Status>{notice}</Status>}
    {error && <Status error>{error}</Status>}

    {editorOpen && <ScheduleEditor
      key={(editing?.id ?? 'new') + '-' + editorDate}
      entry={editing}
      initialScheduledAt={editorDate}
      pending={save.isPending}
      onCancel={closeEditor}
      onSubmit={(body) => save.mutate({ id: editing?.id ?? null, body })}
    />}

    {schedule.isPending && <Status>Загружаем расписание...</Status>}
    {schedule.isError && <Status error>{message(schedule.error, 'Не удалось загрузить расписание.')}</Status>}
    {schedule.data?.length === 0 && <Status>Расписание пока пустое.</Status>}

    {!schedule.isPending && !schedule.isError && <section className="schedule-calendar-shell schedule-week-shell" aria-label="Календарь расписания">
      <div className="schedule-calendar-toolbar">
        <div className="calendar-month-nav">
          <button type="button" className="button secondary" onClick={() => changeWeek(-1)}>Предыдущая</button>
          <button type="button" className="text-button calendar-today" onClick={showCurrentWeek}>Эта неделя</button>
          <button type="button" className="button secondary" onClick={() => changeWeek(1)}>Следующая</button>
        </div>

        <div className="calendar-month-heading">
          <h2>{formatWeekRange(currentWeekStart)}</h2>
          <span>{weekEntriesCount === 0 ? 'На этой неделе нет записей' : formatEntryCount(weekEntriesCount)}</span>
        </div>

        <div className="segmented schedule-filter" aria-label="Фильтр расписания">
          <button type="button" className={filter === 'ALL' ? 'active' : ''} onClick={() => setFilter('ALL')}>Все</button>
          <button type="button" className={filter === 'GAME' ? 'active' : ''} onClick={() => setFilter('GAME')}>Игры</button>
          <button type="button" className={filter === 'EVENT' ? 'active' : ''} onClick={() => setFilter('EVENT')}>Мероприятия</button>
        </div>
      </div>

      <div className="schedule-calendar-weekdays" aria-hidden="true">
        {WEEKDAYS.map((day) => <span key={day}>{day}</span>)}
      </div>

      <div className="schedule-calendar-grid schedule-week-grid">
        {weekDays.map((day, index) => {
          const key = localDateKey(day)
          const entries = groupedEntries.get(key) ?? []
          const today = key === localDateKey(new Date())
          const selected = key === selectedDayKey
          const className = [
            'schedule-calendar-day',
            'schedule-week-day',
            today ? 'today' : '',
            selected ? 'selected' : '',
          ].filter(Boolean).join(' ')

          return <article className={className} key={key}>
            <div className="calendar-day-header">
              <button
                type="button"
                className="calendar-day-number"
                aria-label={formatDayAria(day)}
                onClick={() => selectDay(day)}
              >
                <span>{day.getDate()}</span>
                <span className="calendar-day-weekday-mobile">{WEEKDAYS[index]}</span>
                {today && <small>Сегодня</small>}
              </button>
              {canManage && <button
                type="button"
                className="calendar-day-add"
                aria-label={'Добавить запись на ' + formatDayAria(day)}
                onClick={() => openNew(day)}
              >+</button>}
            </div>

            <div className="calendar-day-events">
              {entries.map((entry) => <button
                type="button"
                key={entry.id}
                className={'calendar-event ' + (entry.entryType === 'EVENT' ? 'event' : 'game')}
                title={entry.title}
                onClick={() => {
                  setSelectedDayKey(key)
                  setSelectedEntryId(entry.id)
                }}
              >
                <span className="calendar-event-time">{formatTime(entry.scheduledAt)}</span>
                <span className="calendar-event-title">{entry.title}</span>
              </button>)}
              {entries.length === 0 && <span className="calendar-day-empty">Свободно</span>}
            </div>
          </article>
        })}
      </div>
    </section>}


    {selectedEntry && <ScheduleEntryDialog
      entry={selectedEntry}
      authenticated={session?.authenticated === true}
      pending={participation.isPending || remove.isPending}
      onClose={() => setSelectedEntryId(null)}
      onToggle={() => participation.mutate(selectedEntry.id)}
      onEdit={() => openEdit(selectedEntry)}
      onDelete={() => {
        if (window.confirm('Удалить «' + selectedEntry.title + '»?')) remove.mutate(selectedEntry.id)
      }}
    />}
  </main>
}

function ScheduleEntryDialog({ entry, authenticated, pending, onClose, onToggle, onEdit, onDelete }: {
  entry: ScheduleEntry
  authenticated: boolean
  pending: boolean
  onClose: () => void
  onToggle: () => void
  onEdit: () => void
  onDelete: () => void
}) {
  return <div
    className="schedule-modal-backdrop"
    role="presentation"
    onMouseDown={(event) => {
      if (event.target === event.currentTarget) onClose()
    }}
  >
    <section className="schedule-detail-modal" role="dialog" aria-modal="true" aria-labelledby={'schedule-detail-title-' + entry.id}>
      <div className="schedule-detail-top">
        <div>
          <p className="eyebrow">{entry.entryType === 'EVENT' ? 'Мероприятие' : 'Игра'}</p>
          <h2 id={'schedule-detail-title-' + entry.id}>{entry.title}</h2>
        </div>
        <button type="button" className="text-button schedule-modal-close" onClick={onClose}>Закрыть</button>
      </div>

      <div className="schedule-detail-date">
        <strong>{formatDate(entry.scheduledAt)}</strong>
        <span>{formatTime(entry.scheduledAt)}</span>
      </div>

      <div className="schedule-detail-content">
        <p><strong>Проводит:</strong> {entry.host}</p>
        {entry.entryType === 'GAME' && <div className="tag-row">
          <span className="tag">{entry.rated ? 'Rating' : 'No rating'}</span>
          <span className="tag">{entry.mafiaFormatDisplayName}</span>
        </div>}
        {entry.description && <p className="pre-wrap schedule-description">{entry.description}</p>}

        {entry.entryType === 'GAME' && <div className="schedule-detail-participants">
          <h3>Игроки: {entry.participants.length}</h3>
          <div className="participant-list">
            {entry.participants.length
              ? entry.participants.map((participant) => <Link key={participant.userId} to={'/players/' + participant.userId}>{participant.username}</Link>)
              : <span className="muted">Пока никто не записался.</span>}
          </div>
        </div>}
      </div>

      <div className="schedule-detail-actions">
        {entry.entryType === 'GAME' && (authenticated
          ? <button type="button" className={'button ' + (entry.participating ? 'secondary' : 'primary')} disabled={pending} onClick={onToggle}>{entry.participating ? 'Не участвую' : 'Участвовать'}</button>
          : <Link className="button primary" to="/login" state={{ returnTo: '/schedule' }}>Войти для участия</Link>)}
        {entry.manageable && <>
          <button type="button" className="button secondary" disabled={pending} onClick={onEdit}>Изменить</button>
          <button type="button" className="button danger" disabled={pending} onClick={onDelete}>Удалить</button>
        </>}
      </div>
    </section>
  </div>
}

function ScheduleEditor({ entry, initialScheduledAt, pending, onCancel, onSubmit }: {
  entry: ScheduleEntry | null
  initialScheduledAt: string
  pending: boolean
  onCancel: () => void
  onSubmit: (body: ScheduleWrite) => void
}) {
  const [type, setType] = useState<'GAME' | 'EVENT'>(entry?.entryType ?? 'GAME')

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    onSubmit({
      entryType: type,
      title: String(form.get('title') ?? '').trim(),
      scheduledAt: String(form.get('scheduledAt') ?? ''),
      host: String(form.get('host') ?? '').trim(),
      description: String(form.get('description') ?? ''),
      rated: type === 'GAME' && form.get('rated') === 'true',
      mafiaFormat: type === 'GAME' && form.get('mafiaFormat') === 'CITY' ? 'CITY' : 'CLASSIC',
    })
  }

  return <form className="form-card schedule-editor" onSubmit={submit}>
    <div className="section-heading">
      <div><p className="eyebrow">Управление</p><h2>{entry ? 'Изменить запись' : 'Добавить в расписание'}</h2></div>
      <button type="button" className="text-button" onClick={onCancel}>Отмена</button>
    </div>

    <div className="segmented">
      <button type="button" className={type === 'GAME' ? 'active' : ''} onClick={() => setType('GAME')}>Игра</button>
      <button type="button" className={type === 'EVENT' ? 'active' : ''} onClick={() => setType('EVENT')}>Мероприятие</button>
    </div>

    <div className="form-grid two-columns">
      <label className="field">
        <span>{type === 'EVENT' ? 'Название мероприятия' : 'Название игры'}</span>
        <input name="title" required maxLength={120} defaultValue={entry?.title ?? ''} />
      </label>
      <label className="field">
        <span>Дата и время</span>
        <input name="scheduledAt" type="datetime-local" required defaultValue={entry ? toInputDate(entry.scheduledAt) : initialScheduledAt} />
      </label>
      <label className="field">
        <span>Кто проводит</span>
        <input name="host" required maxLength={80} defaultValue={entry?.host ?? ''} />
      </label>
      {type === 'GAME' && <>
        <label className="field">
          <span>Рейтинг</span>
          <select name="rated" defaultValue={entry?.rated === false ? 'false' : 'true'}>
            <option value="true">Rating</option>
            <option value="false">No rating</option>
          </select>
        </label>
        <label className="field">
          <span>Формат</span>
          <select name="mafiaFormat" defaultValue={entry?.mafiaFormat ?? 'CLASSIC'}>
            <option value="CLASSIC">Классическая Мафия</option>
            <option value="CITY">Городская Мафия</option>
          </select>
        </label>
      </>}
    </div>

    <label className="field">
      <span>Описание</span>
      <textarea name="description" rows={type === 'EVENT' ? 7 : 4} maxLength={10000} defaultValue={entry?.description ?? ''} />
    </label>

    <button className="button primary" disabled={pending}>{pending ? 'Сохраняем...' : entry ? 'Сохранить изменения' : type === 'EVENT' ? 'Добавить мероприятие' : 'Добавить игру'}</button>
  </form>
}

function startOfWeek(value: Date) {
  const result = new Date(value.getFullYear(), value.getMonth(), value.getDate())
  const offset = (result.getDay() + 6) % 7
  result.setDate(result.getDate() - offset)
  return result
}

function apiWeekRange(weekStart: Date) {
  const start = startOfWeek(weekStart)
  const end = new Date(start)
  end.setDate(end.getDate() + 7)
  return {
    from: localDateKey(start) + 'T00:00:00',
    to: localDateKey(end) + 'T00:00:00',
  }
}

function buildWeekDays(weekStart: Date) {
  return Array.from({ length: 7 }, (_, index) => {
    const day = new Date(weekStart)
    day.setDate(day.getDate() + index)
    return day
  })
}

function isInWeek(value: Date, weekStart: Date) {
  const start = startOfWeek(weekStart)
  const end = new Date(start)
  end.setDate(end.getDate() + 7)
  return value >= start && value < end
}

function entryDate(entry: ScheduleEntry) {
  const value = new Date(entry.scheduledAt)
  return Number.isNaN(value.getTime()) ? dateFromKey(entry.scheduledAt.slice(0, 10)) : value
}

function entryDateKey(entry: ScheduleEntry) {
  const raw = entry.scheduledAt.slice(0, 10)
  if (/^\d{4}-\d{2}-\d{2}$/.test(raw)) return raw
  return localDateKey(entryDate(entry))
}

function localDateKey(value: Date) {
  const year = String(value.getFullYear())
  const month = String(value.getMonth() + 1).padStart(2, '0')
  const day = String(value.getDate()).padStart(2, '0')
  return year + '-' + month + '-' + day
}

function dateFromKey(value: string) {
  const [year, month, day] = value.split('-').map(Number)
  if (!year || !month || !day) return new Date()
  return new Date(year, month - 1, day)
}

function dateToInputAt(value: Date, hour: number, minute: number) {
  return localDateKey(value) + 'T' + String(hour).padStart(2, '0') + ':' + String(minute).padStart(2, '0')
}

function formatWeekRange(weekStart: Date) {
  const weekEnd = new Date(weekStart)
  weekEnd.setDate(weekEnd.getDate() + 6)
  const startMonth = new Intl.DateTimeFormat('ru-RU', { month: 'long' }).format(weekStart)
  const endMonth = new Intl.DateTimeFormat('ru-RU', { month: 'long' }).format(weekEnd)
  const sameYear = weekStart.getFullYear() === weekEnd.getFullYear()
  const sameMonth = sameYear && weekStart.getMonth() === weekEnd.getMonth()
  if (sameMonth) return weekStart.getDate() + '-' + weekEnd.getDate() + ' ' + endMonth + ' ' + weekEnd.getFullYear()
  if (sameYear) return weekStart.getDate() + ' ' + startMonth + ' - ' + weekEnd.getDate() + ' ' + endMonth + ' ' + weekEnd.getFullYear()
  return weekStart.getDate() + ' ' + startMonth + ' ' + weekStart.getFullYear() + ' - ' + weekEnd.getDate() + ' ' + endMonth + ' ' + weekEnd.getFullYear()
}

function formatDayAria(value: Date) {
  return new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'long', year: 'numeric' }).format(value)
}

function formatEntryCount(value: number) {
  const mod10 = value % 10
  const mod100 = value % 100
  const noun = mod10 === 1 && mod100 !== 11 ? 'запись' : mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14) ? 'записи' : 'записей'
  return value + ' ' + noun
}

function fail(value: unknown, setNotice: (v: string | null) => void, setError: (v: string | null) => void) {
  setNotice(null)
  setError(message(value, 'Операция не выполнена.'))
}

function message(error: unknown, fallback: string) {
  return error instanceof ApiClientError ? error.details.message : fallback
}

function Status({ children, error = false }: { children: ReactNode; error?: boolean }) {
  return <section className={'status-panel' + (error ? ' error-panel' : '')}>{children}</section>
}

function toInputDate(value?: string) {
  return value ? value.slice(0, 16) : ''
}

function formatDate(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('ru-RU', { day: '2-digit', month: 'long', year: 'numeric' }).format(date)
}

function formatTime(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '' : new Intl.DateTimeFormat('ru-RU', { hour: '2-digit', minute: '2-digit' }).format(date)
}
