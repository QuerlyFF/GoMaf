import { expect, test, type Page } from '@playwright/test'

const ADMIN_PASSWORD = 'e2e-admin-password'
const MODERATOR_PASSWORD = 'e2e-moderator-password'
const CAPTCHA = 'ABCDE'

async function login(page: Page, username: string, password: string) {
  await page.goto('/login')
  await expect(page.getByRole('heading', { name: 'Войти в аккаунт' })).toBeVisible()
  await page.getByLabel('Ник').fill(username)
  await page.getByLabel('Пароль').fill(password)
  await page.getByLabel('Код с картинки').fill(CAPTCHA)
  await page.getByRole('button', { name: 'Войти' }).click()
  await expect(page).toHaveURL(/\/players/)
  await expect(page.getByRole('banner').locator('.session-user:visible')).toHaveText(username)
}

async function logout(page: Page) {
  const button = page.getByRole('button', { name: 'Выйти' })
  if (await button.isVisible()) {
    await button.click()
    await expect(page).toHaveURL(/\/players/)
  }
}

async function openPlayer(page: Page, username: string) {
  await page.goto(`/players?q=${encodeURIComponent(username)}`)
  const link = page.getByRole('link', { name: username, exact: true })
  await expect(link).toBeVisible()
  await link.click()
  await expect(page.getByRole('heading', { name: username, exact: true })).toBeVisible()
}

async function expectNoHorizontalOverflow(page: Page) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
  expect(overflow).toBeLessThanOrEqual(1)
}

test.describe.serial('Players vertical slice', () => {
  test('anonymous player list and profile', async ({ page }) => {
    await page.goto('/players?q=e2e_target')
    await expect(page.getByRole('heading', { name: 'Игроки клуба' })).toBeVisible()
    await expect(page.getByRole('link', { name: 'e2e_target', exact: true })).toBeVisible()
    await page.getByRole('link', { name: 'e2e_target', exact: true }).click()
    await expect(page.getByRole('heading', { name: 'e2e_target', exact: true })).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Администрирование игрока' })).toHaveCount(0)
  })

  test('login and admin edit through React UI', async ({ page, browserName }) => {
    test.skip(browserName !== 'chromium', 'Cross-browser ADMIN login/console smoke is covered by G3; mutation UI runs once because cross-browser ADMIN compatibility is covered by G3.')
    await login(page, 'admin', ADMIN_PASSWORD)
    await openPlayer(page, 'e2e_target')
    await expect(page.getByRole('heading', { name: 'Администрирование игрока' })).toBeVisible()
    const ratingInput = page.getByLabel('Новое значение')
    await ratingInput.fill('1888.5')
    await page.getByRole('button', { name: 'Сохранить рейтинг' }).click()
    await expect(page.getByRole('status')).toContainText('Рейтинг игрока сохранён')
    await expect(page.getByText(/1[\s\u00A0\u202F]?888,5/)).toBeVisible()
  })

  test('moderator allowed operation and denied target operation', async ({ page }) => {
    await logout(page)
    await login(page, 'e2e_moderator', MODERATOR_PASSWORD)
    await openPlayer(page, 'e2e_target')
    await expect(page.getByRole('button', { name: 'Сохранить рейтинг' })).toBeVisible()
    await page.getByLabel('Новое значение').fill('1666')
    await page.getByRole('button', { name: 'Сохранить рейтинг' }).click()
    await expect(page.getByRole('status')).toContainText('Рейтинг игрока сохранён')

    await openPlayer(page, 'e2e_other_moderator')
    await expect(page.getByRole('heading', { name: 'Администрирование игрока' })).toHaveCount(0)

    const csrf = await page.request.get('/api/v1/csrf')
    expect(csrf.ok()).toBeTruthy()
    const csrfBody = await csrf.json() as { data: { headerName: string; token: string } }
    const targetSearch = await page.request.get('/api/v1/players?q=e2e_other_moderator')
    const targetBody = await targetSearch.json() as { items: Array<{ id: number }> }
    expect(targetBody.items).toHaveLength(1)
    const denied = await page.request.patch(`/api/v1/players/${targetBody.items[0].id}/rating`, {
      headers: { [csrfBody.data.headerName]: csrfBody.data.token },
      data: { rating: 1999 },
    })
    expect(denied.status()).toBe(403)
    const deniedBody = await denied.json() as { code: string }
    expect(deniedBody.code).toBe('PLAYER_ADMIN_FORBIDDEN')
  })

  test('long username and responsive widths have no document overflow', async ({ page }) => {
    await logout(page)
    const longUsername = 'e2e_very_long_player_name_123456'
    for (const width of [390, 1024, 1440]) {
      await page.setViewportSize({ width, height: 900 })
      await page.goto(`/players?q=${encodeURIComponent(longUsername)}`)
      await expect(page.getByRole('link', { name: longUsername, exact: true })).toBeVisible()
      await expectNoHorizontalOverflow(page)
      await page.getByRole('link', { name: longUsername, exact: true }).click()
      await expect(page.getByRole('heading', { name: longUsername, exact: true })).toBeVisible()
      await expectNoHorizontalOverflow(page)
    }
  })
})
