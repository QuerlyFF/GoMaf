import { Buffer } from 'node:buffer'
import { expect, test } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'

const publicRoutes = [
  ['home', '/'],
  ['news', '/news'],
  ['rules', '/rules'],
  ['rating', '/rating'],
  ['players', '/players'],
  ['games', '/games'],
  ['schedule', '/schedule'],
  ['tournaments', '/tournaments'],
  ['login', '/login'],
  ['register', '/register'],
] as const

const requiredViewports = [
  [320, 568], [360, 640], [375, 667], [390, 844],
  [412, 915], [430, 932], [768, 1024], [1024, 768],
  [1280, 720], [1365, 767], [1440, 900], [1920, 1080],
  [667, 375], [844, 390],
] as const

const visualCaptchaPng = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=',
  'base64',
)
test.describe('responsive evidence', () => {
  for (const [width, height] of requiredViewports) {
    test(`${width}x${height} public routes have no horizontal overflow`, async ({ page, browserName }, testInfo) => {
      test.skip(browserName !== 'chromium', 'Exhaustive screenshot matrix is captured once in Chromium.')
      test.setTimeout(90_000)
      await page.route('**/captcha/**', async (route) => {
        await route.fulfill({ status: 200, contentType: 'image/png', body: visualCaptchaPng })
      })
      await page.setViewportSize({ width, height })
      for (const [name, path] of publicRoutes) {
        const response = await page.goto(path, { waitUntil: 'domcontentloaded' })
        expect(response?.ok(), `${path} at ${width}x${height}`).toBeTruthy()
        await expect(page.locator('main')).toBeVisible()
        await expect.poll(async () => page.evaluate(() => Array.from(document.images)
          .filter((image) => !image.complete || image.naturalWidth === 0).length), { timeout: 5_000 })
          .toBe(0)
        const metrics = await page.evaluate(() => ({
          viewport: document.documentElement.clientWidth,
          page: document.documentElement.scrollWidth,
        }))
        expect(metrics.page, `${path} horizontal overflow at ${width}x${height}`)
          .toBeLessThanOrEqual(metrics.viewport + 1)
        await page.screenshot({
          path: testInfo.outputPath(`visual-${name}-${width}x${height}.png`),
          fullPage: true,
        })
      }
    })
  }
})


test('public internal links resolve without dead destinations', async ({ page, request, browserName }) => {
  test.skip(browserName !== 'chromium', 'Internal-link crawl is deterministic and only needs one browser engine.')
  test.setTimeout(90_000)
  const discovered = new Set<string>()
  let origin = ''

  for (const [, path] of publicRoutes) {
    const response = await page.goto(path, { waitUntil: 'domcontentloaded' })
    expect(response?.ok(), `seed route ${path}`).toBeTruthy()
    origin ||= new URL(page.url()).origin
    await expect(page.locator('main')).toBeVisible()
    const hrefs = await page.locator('a[href]').evaluateAll((links) => links.map((link) => (link as HTMLAnchorElement).href))
    for (const href of hrefs) {
      const url = new URL(href)
      if (url.origin !== origin) continue
      if (!['http:', 'https:'].includes(url.protocol)) continue
      if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/captcha/')) continue
      url.hash = ''
      discovered.add(`${url.pathname}${url.search}`)
    }
  }

  expect(discovered.size).toBeGreaterThan(0)
  for (const href of [...discovered].sort()) {
    const response = await request.get(href, { maxRedirects: 5 })
    expect(response.status(), `dead internal link ${href}`).toBeLessThan(400)
  }
})

test.describe('axe accessibility', () => {
  for (const [name, path] of publicRoutes) {
    test(`${name} has no WCAG A/AA axe violations`, async ({ page }) => {
      await page.setViewportSize({ width: 1440, height: 900 })
      const response = await page.goto(path, { waitUntil: 'domcontentloaded' })
      expect(response?.ok()).toBeTruthy()
      await expect(page.locator('main')).toBeVisible()
      const results = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze()
      expect(results.violations, JSON.stringify(results.violations, null, 2)).toEqual([])
    })
  }
})

test('keyboard focus is visible on primary public navigation and auth forms', async ({ page }) => {
  for (const path of ['/', '/login', '/register']) {
    await page.goto(path, { waitUntil: 'domcontentloaded' })
    await expect(page.locator('main')).toBeVisible()
    await page.keyboard.press('Tab')
    const focus = await page.evaluate(() => {
      const el = document.activeElement as HTMLElement | null
      if (!el || el === document.body) return null
      const style = getComputedStyle(el)
      return { tag: el.tagName, outline: style.outlineStyle, outlineWidth: style.outlineWidth, boxShadow: style.boxShadow }
    })
    expect(focus, `${path} has no keyboard focus target`).not.toBeNull()
    expect(
      focus!.outline !== 'none' && focus!.outlineWidth !== '0px' || focus!.boxShadow !== 'none',
      `${path} first keyboard focus has no visible outline/box-shadow`,
    ).toBeTruthy()
  }
})

test.describe('cross-browser responsive smoke', () => {
  for (const [width, height] of [[320, 568], [768, 1024], [1365, 767]] as const) {
    test(`${width}x${height} Firefox/WebKit public routes stay usable`, async ({ page, browserName }, testInfo) => {
      test.skip(browserName === 'chromium', 'Chromium is covered by the exhaustive 14-viewport matrix.')
      test.setTimeout(90_000)
      await page.route('**/captcha/**', async (route) => {
        await route.fulfill({ status: 200, contentType: 'image/png', body: visualCaptchaPng })
      })
      await page.setViewportSize({ width, height })
      for (const [name, path] of publicRoutes) {
        const response = await page.goto(path, { waitUntil: 'domcontentloaded' })
        expect(response?.ok(), `${browserName} ${path} at ${width}x${height}`).toBeTruthy()
        await expect(page.locator('main')).toBeVisible()
        await expect.poll(async () => page.evaluate(() => Array.from(document.images)
          .filter((image) => image.loading !== 'lazy' && (!image.complete || image.naturalWidth === 0)).length), { timeout: 5_000 })
          .toBe(0)
        const lazyImages = page.locator('img[loading="lazy"]')
        if (await lazyImages.count()) {
          const firstLazyImage = lazyImages.first()
          await firstLazyImage.scrollIntoViewIfNeeded()
          await expect.poll(async () => firstLazyImage.evaluate((image) =>
            image.complete && image.naturalWidth > 0), { timeout: 5_000 }).toBeTruthy()
          await page.evaluate(() => window.scrollTo(0, 0))
        }
        const overflow = await page.evaluate(() =>
          document.documentElement.scrollWidth - document.documentElement.clientWidth)
        expect(overflow, `${browserName} ${path} horizontal overflow at ${width}x${height}`)
          .toBeLessThanOrEqual(1)
        await page.screenshot({ path: testInfo.outputPath(`cross-${name}-${width}x${height}.png`), fullPage: false })
      }
    })
  }
})

test('real CAPTCHA PNG decodes in each browser engine', async ({ page }) => {
  const response = await page.goto('/', { waitUntil: 'domcontentloaded' })
  expect(response?.ok()).toBeTruthy()
  await page.evaluate(() => {
    document.body.innerHTML = '<img id="real-captcha" src="/captcha/login?browser-decode=1" alt="CAPTCHA">'
  })
  const captcha = page.locator('#real-captcha')
  await expect.poll(async () => captcha.evaluate((image) => (image as HTMLImageElement).naturalWidth), { timeout: 5_000 }).toBeGreaterThan(0)
  await expect.poll(async () => captcha.evaluate((image) => (image as HTMLImageElement).naturalHeight), { timeout: 5_000 }).toBeGreaterThan(0)
})
