import { useMutation } from '@tanstack/react-query'
import { FormEvent, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { ApiClientError, registerAccount } from '../api/client'

export function RegisterPage() {
  const navigate = useNavigate()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [passwordConfirm, setPasswordConfirm] = useState('')
  const [captcha, setCaptcha] = useState('')
  const [captchaVersion, setCaptchaVersion] = useState(0)

  const mutation = useMutation({
    mutationFn: () => registerAccount(username, password, passwordConfirm, captcha),
    onSuccess: () => navigate('/login', { replace: true, state: { registered: true } }),
    onError: () => {
      setCaptcha('')
      setCaptchaVersion((value) => value + 1)
    },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    mutation.mutate()
  }

  const error = mutation.error instanceof ApiClientError
    ? mutation.error.details.message
    : mutation.isError ? 'Не удалось зарегистрировать аккаунт.' : null

  return (
    <main className="page-shell narrow-page">
      <Link className="back-link" to="/players">Назад к игрокам</Link>
      <section className="login-card">
        <p className="eyebrow">Регистрация</p>
        <h1>Создать аккаунт</h1>
        <p>Пароль должен содержать не менее 6 символов. После регистрации войдите в аккаунт.</p>
        {error && <div className="notice error-notice" role="alert">{error}</div>}
        <form className="stack-form" onSubmit={submit}>
          <label className="field"><span>Ник</span><input value={username} onChange={(event) => setUsername(event.target.value)} autoComplete="username" required /></label>
          <label className="field"><span>Пароль</span><input type="password" value={password} onChange={(event) => setPassword(event.target.value)} autoComplete="new-password" minLength={6} maxLength={64} required /></label>
          <label className="field"><span>Повторите пароль</span><input type="password" value={passwordConfirm} onChange={(event) => setPasswordConfirm(event.target.value)} autoComplete="new-password" minLength={6} maxLength={64} required /></label>
          <div className="captcha-row">
            <img src={`/captcha/register?v=${captchaVersion}`} width="180" height="58" alt="Код проверки" />
            <button className="text-button" type="button" onClick={() => setCaptchaVersion((value) => value + 1)}>Обновить код</button>
          </div>
          <label className="field"><span>Код с картинки</span><input value={captcha} onChange={(event) => setCaptcha(event.target.value.toUpperCase())} maxLength={5} autoComplete="off" required /></label>
          <button className="button primary" disabled={mutation.isPending}>Создать аккаунт</button>
        </form>
        <p className="muted">Уже есть аккаунт? <Link to="/login">Войти</Link></p>
      </section>
    </main>
  )
}
