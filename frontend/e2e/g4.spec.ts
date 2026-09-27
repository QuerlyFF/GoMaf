import { expect, test } from '@playwright/test'

const CAPTCHA = 'ABCDE'
const ADMIN_PASSWORD = 'e2e-admin-password'

async function loginAdmin(page: import('@playwright/test').Page) {
  await page.goto('/login')
  await page.getByLabel('Ник').fill('admin')
  await page.getByLabel('Пароль').fill(ADMIN_PASSWORD)
  await page.getByLabel('Код с картинки').fill(CAPTCHA)
  await page.getByRole('button', { name: 'Войти' }).click()
  await expect(page).toHaveURL(/\/players/)
}

async function noOverflow(page: import('@playwright/test').Page) {
  const amount = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
  expect(amount).toBeLessThanOrEqual(1)
}

function windowOrigin(currentUrl: string) {
  return new URL(currentUrl).origin
}

test.describe.serial('G4 production SPA integration', () => {
  test('Spring serves the embedded SPA and fingerprinted assets with production headers', async ({ page, request }) => {
    const response = await page.goto('/news')
    expect(response?.status()).toBe(200)
    await expect(page.getByRole('heading', { name: 'Новости', exact: true })).toBeVisible()
    const csp = response?.headers()['content-security-policy'] ?? ''
    expect(csp).toContain("script-src 'self'")
    expect(csp).not.toContain('unsafe-inline')
    expect(csp).not.toContain('unsafe-eval')

    const scriptSrc = await page.locator('script[type="module"]').getAttribute('src')
    expect(scriptSrc).toMatch(/^\/assets\/.+[.]js$/)
    const asset = await request.get(scriptSrc!)
    expect(asset.ok()).toBeTruthy()
    expect(asset.headers()['cache-control'] ?? '').toContain('immutable')
  })

  test('SEO discovery, immutable font and real browser 404 contracts are served by Spring', async ({ request }) => {
    const robots = await request.get('/robots.txt')
    expect(robots.status()).toBe(200)
    const robotsText = await robots.text()
    expect(robotsText).toContain('Sitemap: https://gomaf.online/sitemap.xml')
    expect(robotsText).toContain('Disallow: /admin/')
    expect(robotsText).not.toContain('Disallow: /login')

    const sitemap = await request.get('/sitemap.xml')
    expect(sitemap.status()).toBe(200)
    const sitemapText = await sitemap.text()
    expect(sitemapText).toContain('<loc>https://gomaf.online/players</loc>')
    expect(sitemapText).not.toContain('<loc>https://gomaf.online/login</loc>')

    const font = await request.get('/fonts/InterVariable-693b77d4.woff2')
    expect(font.status()).toBe(200)
    expect(font.headers()['cache-control'] ?? '').toContain('immutable')

    const missing = await request.get('/definitely-not-a-browser-route', { maxRedirects: 0 })
    expect(missing.status()).toBe(404)
    expect(missing.headers().location).toBeUndefined()
    expect(missing.headers()['cache-control'] ?? '').toContain('no-store')
    expect(missing.headers()['x-robots-tag'] ?? '').toContain('noindex')
    expect(await missing.text()).toContain('name="robots" content="noindex,nofollow"')
    const notFound = await request.get('/not-found')
    expect(notFound.status()).toBe(404)
    expect(await notFound.text()).toContain('name="robots" content="noindex,nofollow"')
  })

  test('public title and always-visible mobile section navigation remain usable', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto('/news')
    await expect(page).toHaveTitle('Новости — GoMaf')
    const sections = page.getByRole('navigation', { name: 'Разделы сайта' })
    await expect(sections).toBeVisible()
    const routes = [
      ['Новости', '/news', 'Новости — GoMaf'], ['Правила', '/rules', 'Правила игры в мафию — GoMaf'],
      ['Игроки', '/players', 'Игроки — GoMaf'], ['Игры', '/games', 'Игры — GoMaf'],
      ['Расписание', '/schedule', 'Расписание — GoMaf'], ['Турниры', '/tournaments', 'Турниры — GoMaf'],
      ['Рейтинг', '/rating', 'Рейтинг игроков — GoMaf'],
    ] as const
    for (const [label, path, title] of routes) {
      const link = sections.getByRole('link', { name: label, exact: true })
      await expect(link).toBeVisible()
      await link.click()
      await expect(page).toHaveURL(new RegExp(`${path.replace('/', '\\/')}$`))
      await expect(page).toHaveTitle(title)
      await expect(page.locator('link[rel="canonical"]')).toHaveAttribute('href', new RegExp(`${path.replace('/', '\\/')}$`))
      await noOverflow(page)
    }
    const menu = page.getByRole('button', { name: 'Меню' })
    await expect(menu).toBeVisible()
    await menu.click()
    await expect(page.getByRole('link', { name: 'Войти', exact: true })).toBeVisible()
  })

  test('public internal links resolve without broken browser routes', async ({ page, browserName }) => {
    test.skip(browserName !== 'chromium', 'Internal-link crawl is browser-independent and runs once in Chromium.')
    const seedRoutes = ['/news', '/rules', '/players', '/games', '/schedule', '/tournaments', '/rating']
    const paths = new Set<string>(seedRoutes)

    for (const route of seedRoutes) {
      const response = await page.goto(route, { waitUntil: 'domcontentloaded' })
      expect(response?.status()).toBe(200)
      await page.waitForLoadState('networkidle')
      const links = await page.locator('a[href]').evaluateAll((nodes) => nodes.map((node) => (node as HTMLAnchorElement).href))
      for (const href of links) {
        const url = new URL(href)
        if (url.origin === windowOrigin(page.url())) paths.add(url.pathname)
      }
    }

    for (const path of [...paths].sort()) {
      const response = await page.request.get(path)
      expect(response.status(), `broken internal route: ${path}`).toBeLessThan(400)
    }
  })

  test('rules section/article navigation has an independently scrollable table of contents', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 600 })
    await page.goto('/rules')
    const sidebar = page.locator('.rules-sidebar')
    await expect(sidebar).toBeVisible()
    await expect(page.locator('.rules-toc-section').first()).toBeVisible()
    await expect(page.locator('.rules-toc-sub a').first()).toBeVisible()
    const before = await sidebar.evaluate((node) => ({
      overflowY: getComputedStyle(node).overflowY, clientHeight: node.clientHeight, scrollHeight: node.scrollHeight,
    }))
    expect(['auto', 'scroll']).toContain(before.overflowY)
    expect(before.scrollHeight).toBeGreaterThan(before.clientHeight)
    const scrolled = await sidebar.evaluate((node) => { node.scrollTop = node.scrollHeight; return node.scrollTop })
    expect(scrolled).toBeGreaterThan(0)
  })

  test('registration is a React route and keeps the 6-character password minimum', async ({ page }) => {
    await page.goto('/register')
    await expect(page.getByRole('heading', { name: 'Создать аккаунт', exact: true })).toBeVisible()
    const password = page.getByLabel('Пароль', { exact: true })
    await expect(password).toHaveAttribute('minlength', '6')
    await noOverflow(page)
  })

  test('canonical admin reaches protected React console through Spring-served SPA', async ({ page, browserName }) => {
    test.skip(browserName !== 'chromium', 'Cross-browser ADMIN login/console smoke is covered by G3; this duplicate flow runs once because cross-browser ADMIN compatibility is covered by G3.')
    await loginAdmin(page)
    await page.goto('/admin/moderators')
    await expect(page.getByRole('heading', { name: 'Модераторы', exact: true })).toBeVisible()
    await noOverflow(page)
  })

  test('unknown API route never falls back to SPA', async ({ page, browserName }) => {
    test.skip(browserName !== 'chromium', 'Browser-independent authenticated API fallback contract is exercised once in Chromium.')
    await loginAdmin(page)
    const result = await page.evaluate(async () => {
      const response = await fetch('/api/v1/definitely-not-a-route', { credentials: 'same-origin' })
      return {
        status: response.status,
        contentType: response.headers.get('content-type') ?? '',
        json: await response.json(),
      }
    })
    expect(result.status).toBe(404)
    expect(result.contentType).toContain('application/json')
    expect(result.json.code).toBe('API_NOT_FOUND')
  })
})
