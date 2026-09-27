import { QueryClient } from '@tanstack/react-query'

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      staleTime: 15_000,
      refetchOnWindowFocus: false,
    },
  },
})

// Public read models have different volatility. TanStack Query keeps fresh data
// in memory until staleTime expires, while explicit mutation invalidations still
// refresh these keys immediately.
queryClient.setQueryDefaults(['rules'], { staleTime: 5 * 60_000 })
queryClient.setQueryDefaults(['news'], { staleTime: 60_000 })
queryClient.setQueryDefaults(['players'], { staleTime: 30_000 })
queryClient.setQueryDefaults(['player'], { staleTime: 30_000 })
queryClient.setQueryDefaults(['player-games'], { staleTime: 30_000 })
queryClient.setQueryDefaults(['games'], { staleTime: 30_000 })
queryClient.setQueryDefaults(['game'], { staleTime: 30_000 })
queryClient.setQueryDefaults(['schedule'], { staleTime: 30_000 })
queryClient.setQueryDefaults(['tournaments'], { staleTime: 60_000 })
queryClient.setQueryDefaults(['tournament'], { staleTime: 60_000 })
queryClient.setQueryDefaults(['rating'], { staleTime: 60_000 })
