import { expect, test, type Page } from '@playwright/test'

const CAPTCHA = 'ABCDE'
const ADMIN_PASSWORD = 'e2e-admin-password'

async function loginAdmin(page: Page) {
  await page.goto('/login')
  await page.getByLabel('Ник').fill('admin')
  await page.getByLabel('Пароль').fill(ADMIN_PASSWORD)
  await page.getByLabel('Код с картинки').fill(CAPTCHA)
  await page.getByRole('button', { name: 'Войти' }).click()
  await expect(page).toHaveURL(/\/players/)
  await expect(page.getByRole('banner').locator('.session-user:visible')).toHaveText('admin')
}

async function noOverflow(page: Page) {
  const amount = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
  expect(amount).toBeLessThanOrEqual(1)
}

test.describe.serial('G3 migrated domains', () => {
  test('anonymous domain routes render at desktop and mobile without horizontal overflow', async ({ page }) => {
    const routes = [
      ['/news', 'Новости'],
      ['/games', 'Игры'],
      ['/schedule', 'РАСПИСАНИЕ'],
      ['/tournaments', 'Турниры'],
      ['/rating', 'Рейтинг'],
    ] as const

    for (const width of [390, 1440]) {
      await page.setViewportSize({ width, height: 900 })
      for (const [path, heading] of routes) {
        await page.goto(path)
        await expect(page.getByRole('heading', { name: heading, exact: true })).toBeVisible()
        await noOverflow(page)
      }
      await page.goto('/rules')
      await expect(page.locator('h1').first()).toBeVisible()
      await noOverflow(page)
    }
  })

  test('authenticated profile route and canonical admin console render through React UI', async ({ page }) => {
    await loginAdmin(page)
    await page.goto('/profile')
    await expect(page.getByRole('heading', { name: 'admin', exact: true })).toBeVisible()
    await expect(page.getByText('О себе', { exact: true })).toBeVisible()

    await page.goto('/admin/moderators')
    await expect(page.getByRole('heading', { name: 'Модераторы', exact: true })).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Общие разрешения', exact: true })).toBeVisible()
    await expect(page.getByText('Новости', { exact: true }).first()).toBeVisible()
    await noOverflow(page)
  })
})
