import { expect, test, type Page, type TestInfo } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'

const CAPTCHA = 'ABCDE'
const MODERATOR_PASSWORD = 'e2e-moderator-password'
const ADMIN_PASSWORD = 'e2e-admin-password'

async function loginCredentials(page: Page, username: string, password: string) {
  await page.goto('/login')
  await page.getByLabel('Ник').fill(username)
  await page.getByLabel('Пароль').fill(password)
  await page.getByLabel('Код с картинки').fill(CAPTCHA)
  await page.getByRole('button', { name: 'Войти' }).click()
}

async function loginModerator(page: Page) {
  await loginCredentials(page, 'e2e_moderator', MODERATOR_PASSWORD)
  await expect(page).toHaveURL(/\/players/)
  const session = await page.request.get('/api/v1/session')
  expect(session.ok()).toBeTruthy()
  const body = await session.json() as { data: { authenticated: boolean; username: string | null } }
  expect(body.data.authenticated).toBe(true)
  expect(body.data.username).toBe('e2e_moderator')
}

async function assertVisualHealth(page: Page) {
  await expect(page.locator('main')).toBeVisible()
  await expect.poll(async () => page.evaluate(() => Array.from(document.images)
    .filter((image) => !image.complete || image.naturalWidth === 0).length), { timeout: 5_000 }).toBe(0)
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
  expect(overflow).toBeLessThanOrEqual(1)
}

async function assertAxe(page: Page) {
  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    .analyze()
  expect(results.violations, JSON.stringify(results.violations, null, 2)).toEqual([])
}

async function assertKeyboardFocus(page: Page) {
  await page.locator('main').click({ position: { x: 1, y: 1 } })
  await page.keyboard.press('Tab')
  const focus = await page.evaluate(() => {
    const el = document.activeElement as HTMLElement | null
    if (!el || el === document.body) return null
    const style = getComputedStyle(el)
    return { outline: style.outlineStyle, width: style.outlineWidth, shadow: style.boxShadow }
  })
  expect(focus).not.toBeNull()
  expect(
    focus!.outline !== 'none' && focus!.width !== '0px' || focus!.shadow !== 'none',
    'keyboard focus must be visibly indicated',
  ).toBeTruthy()
}

async function capture(page: Page, testInfo: TestInfo, name: string) {
  await page.screenshot({ path: testInfo.outputPath(`${name}.png`), fullPage: false })
}

test.describe('authenticated moderator visual and accessibility evidence', () => {
  test('moderator profile and player management are accessible at mobile and desktop', async ({ page, browserName }, testInfo) => {
    test.setTimeout(120_000)
    await page.setViewportSize({ width: 1440, height: 900 })
    await loginModerator(page)

    for (const [width, height] of [[390, 844], [1440, 900]] as const) {
      await page.setViewportSize({ width, height })
      await page.goto('/profile')
      await expect(page.getByRole('heading', { name: 'e2e_moderator', exact: true })).toBeVisible()
      await assertVisualHealth(page)
      await assertAxe(page)
      await assertKeyboardFocus(page)
      await capture(page, testInfo, `${browserName}-moderator-profile-${width}x${height}`)

      await page.goto('/players?q=e2e_target')
      await page.getByRole('link', { name: 'e2e_target', exact: true }).click()
      await expect(page.getByRole('heading', { name: 'Администрирование игрока' })).toBeVisible()
      await expect(page.getByRole('button', { name: 'Сохранить рейтинг' })).toBeVisible()
      await assertVisualHealth(page)
      await assertAxe(page)
      await assertKeyboardFocus(page)
      await capture(page, testInfo, `${browserName}-moderator-player-management-${width}x${height}`)
    }
  })
})

test.describe.serial('canonical ADMIN login and console quality evidence', () => {
  test('ADMIN login and console pass visual, axe and keyboard gates', async ({ page, browserName }, testInfo) => {
    test.skip(browserName !== 'chromium', 'Cross-browser ADMIN login/console compatibility is already covered by G3; visual evidence runs once.')
    test.setTimeout(90_000)
    await page.setViewportSize({ width: 1440, height: 900 })
    await loginCredentials(page, 'admin', ADMIN_PASSWORD)
    await expect(page).toHaveURL(/\/players/)
    await expect(page.getByRole('banner').locator('.session-user:visible')).toHaveText('admin')
    await assertVisualHealth(page)
    await assertAxe(page)
    await assertKeyboardFocus(page)
    await capture(page, testInfo, 'chromium-admin-login-1440x900')

    await page.goto('/admin/moderators')
    await expect(page.getByRole('heading', { name: 'Модераторы', exact: true })).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Общие разрешения', exact: true })).toBeVisible()
    await assertVisualHealth(page)
    await assertAxe(page)
    await assertKeyboardFocus(page)
    await capture(page, testInfo, 'chromium-admin-console-1440x900')

    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto('/admin/moderators')
    await expect(page.getByRole('heading', { name: 'Модераторы', exact: true })).toBeVisible()
    await assertVisualHealth(page)
    await assertAxe(page)
    await assertKeyboardFocus(page)
    await capture(page, testInfo, 'chromium-admin-console-390x844')
  })
})
