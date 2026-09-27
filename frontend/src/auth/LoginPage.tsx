import { useMutation, useQueryClient } from '@tanstack/react-query'
import { FormEvent, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { ApiClientError, login } from '../api/client'

export function LoginPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const queryClient = useQueryClient()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [captcha, setCaptcha] = useState('')
  const [captchaVersion, setCaptchaVersion] = useState(0)
  const [localError, setLocalError] = useState<string | null>(null)
  const registered = Boolean((location.state as { registered?: boolean } | null)?.registered)
  const target = (location.state as { returnTo?: string } | null)?.returnTo ?? '/players'

  const mutation = useMutation({
    mutationFn: async () => login(username, password, captcha),
    onSuccess: async (result) => {
      setLocalError(null)
      if (!result.data.authenticated) {
        setLocalError('Сервер вернул неизвестное состояние входа.')
        return
      }
      await queryClient.invalidateQueries()
      navigate(target, { replace: true })
    },
    onError: () => {
      setCaptcha('')
      setCaptchaVersion((value) => value + 1)
    },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    mutation.mutate()
  }

  const requestError = mutation.error instanceof ApiClientError
    ? mutation.error.details.message
    : mutation.isError ? 'Не удалось выполнить вход.' : null

  return (
    <main className="page-shell narrow-page">
      <Link className="back-link" to="/players">Назад к игрокам</Link>
      <section className="login-card">
        <p className="eyebrow">Вход</p>
        <h1>Войти в аккаунт</h1>
        <p>Введите ник и пароль, чтобы войти.</p>
        {registered && <div className="notice success-notice">Аккаунт создан. Теперь войдите.</div>}
        {(requestError || localError) && <div className="notice error-notice" role="alert">{requestError ?? localError}</div>}
        <form className="stack-form" onSubmit={submit}>
          <label className="field"><span>Ник</span><input value={username} onChange={(event) => setUsername(event.target.value)} autoComplete="username" required /></label>
          <label className="field"><span>Пароль</span><input type="password" value={password} onChange={(event) => setPassword(event.target.value)} autoComplete="current-password" required /></label>
          <div className="captcha-row">
            <img src={`/captcha/login?v=${captchaVersion}`} width="180" height="58" alt="Код проверки" />
            <button className="text-button" type="button" onClick={() => setCaptchaVersion((value) => value + 1)}>Обновить код</button>
          </div>
          <label className="field"><span>Код с картинки</span><input value={captcha} onChange={(event) => setCaptcha(event.target.value.toUpperCase())} maxLength={5} autoComplete="off" required /></label>
          <button className="button primary" disabled={mutation.isPending}>Войти</button>
        </form>
        <p className="muted">Нет аккаунта? <Link to="/register">Зарегистрироваться</Link></p>
      </section>
    </main>
  )
}
