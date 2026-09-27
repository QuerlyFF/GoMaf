import { render, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, expect, test } from 'vitest'
import { RouteMetadata } from './RouteMetadata'

beforeEach(() => {
  document.head.innerHTML = '<title>GoMaf</title><meta name="description" content="old"><link rel="canonical" href="https://gomaf.online/old">'
})

test('public route updates title description canonical and indexing state', async () => {
  render(<MemoryRouter initialEntries={['/players']}><RouteMetadata /></MemoryRouter>)

  await waitFor(() => expect(document.title).toBe('Игроки — GoMaf'))
  expect(document.querySelector<HTMLMetaElement>('meta[name="description"]')?.content).toContain('профили')
  expect(document.querySelector<HTMLMetaElement>('meta[name="robots"]')?.content).toBe('index,follow')
  expect(new URL(document.querySelector<HTMLLinkElement>('link[rel="canonical"]')!.href).pathname).toBe('/players')
})

test('private route removes stale canonical and becomes noindex', async () => {
  render(<MemoryRouter initialEntries={['/login']}><RouteMetadata /></MemoryRouter>)

  await waitFor(() => expect(document.title).toBe('Вход — GoMaf'))
  expect(document.querySelector<HTMLMetaElement>('meta[name="robots"]')?.content).toBe('noindex,nofollow')
  expect(document.querySelector('link[rel="canonical"]')).toBeNull()
})
