export type ApiData<T> = { data: T }

export type ApiError = {
  status: number
  code: string
  message: string
  fieldErrors: Array<{ field: string; code: string; message: string }>
  requestId: string
}

export type Session = {
  authenticated: boolean
  userId: number | null
  username: string | null
  role: 'PLAYER' | 'MODERATOR' | 'ADMIN' | null
  moderatorPermissions: string[]
}


export type CsrfToken = {
  headerName: string
  parameterName: string
  token: string
}

export type ApiPage<T> = {
  items: T[]
  page: number
  size: number
  totalItems: number
  totalPages: number
}

export type PlayerListItem = {
  id: number
  username: string
  gamesPlayed: number
  wins: number
  losses: number
  winRate: number
  averageScore: number
  rating: number
  avatarUrl: string
}

export type PlayerProfile = {
  id: number
  username: string
  aboutMe: string
  gamesPlayed: number
  wins: number
  losses: number
  winRate: number
  averageScore: number
  rating: number
  avatarUrl: string
  bannerUrl: string
  gamesUrl: string
}

export type PlayerGame = {
  gameId: number
  name: string
  gameDate: string
  role: string
  roleDisplayName: string
  totalPoints: number
  additionalPoints: number
  won: boolean
}

export type PlayerAdministrationCapabilities = {
  manageable: boolean
  rating: boolean
  username: boolean
  password: boolean
  avatarReset: boolean
  profileEdit: boolean
  deleteAccount: boolean
  sensitiveActionsRequireCurrentPassword: boolean
}

export type PlayerAdministrationAction = {
  playerId: number
  username: string
  message: string
}

export type SelfProfile = {
  id: number
  username: string
  role: 'PLAYER' | 'MODERATOR' | 'ADMIN'
  aboutMe: string
  gamesPlayed: number
  wins: number
  losses: number
  winRate: number
  averageScore: number
  rating: number
  avatarUrl: string
  bannerUrl: string
  gamesUrl: string
}

export type ProfileAction = {
  message: string
}

export type GameListItem = {
  id: number
  name: string
  gameDate: string
  host: string
  winner: string
  winnerDisplayName: string
  tournamentId: number | null
  tournamentName: string | null
}

export type GamePlayer = {
  seatNumber: number
  userId: number | null
  displayName: string
  role: string
  roleDisplayName: string
  firstKilled: boolean
  bestMove1: number | null
  bestMove2: number | null
  bestMove3: number | null
  victoryPoints: number
  firstKilledPoints: number
  bestMovePoints: number
  bonusPoints: number
  penaltyPoints: number
  additionalPoints: number
  totalPoints: number
  won: boolean
}

export type GameDetail = {
  id: number
  name: string
  gameDate: string
  host: string
  winner: string
  winnerDisplayName: string
  summary: string
  hiddenFromPublic: boolean
  tournamentId: number | null
  tournamentName: string | null
  players: GamePlayer[]
  videoLinks: string[]
  visibilityUsernames: string[]
  manageable: boolean
}

export type GamePlayerWrite = {
  seatNumber: number
  username: string
  role: string
  firstKilled: boolean
  bestMove1: number | null
  bestMove2: number | null
  bestMove3: number | null
  bonusPoints: string
  penaltyPoints: string
}

export type GameWrite = {
  name: string
  gameDate: string
  host: string
  winner: string
  summary: string
  videoLinks: string[]
  tournamentId: number | null
  hiddenFromPublic: boolean
  visibilityUsernames: string[]
  players: GamePlayerWrite[]
}

export type GameAction = {
  gameId: number
  message: string
}

export type ScheduleParticipant = {
  userId: number
  username: string
}

export type ScheduleEntry = {
  id: number
  entryType: 'GAME' | 'EVENT'
  title: string
  scheduledAt: string
  host: string
  description: string
  rated: boolean
  mafiaFormat: 'CLASSIC' | 'CITY'
  mafiaFormatDisplayName: string
  participants: ScheduleParticipant[]
  participating: boolean
  manageable: boolean
}

export type ScheduleWrite = {
  entryType: 'GAME' | 'EVENT'
  title: string
  scheduledAt: string
  host: string
  description: string
  rated: boolean
  mafiaFormat: 'CLASSIC' | 'CITY'
}

export type ScheduleAction = {
  entryId: number
  participating: boolean
  message: string
}

export type TournamentListItem = {
  id: number
  name: string
  dates: string[]
  gameCount: number
  paid: boolean
  participationCost: number | null
  completed: boolean
}

export type TournamentParticipant = {
  userId: number
  username: string
  invited: boolean
  participating: boolean
}

export type TournamentGame = {
  id: number
  name: string
  gameDate: string
  host: string
  hiddenFromPublic: boolean
}

export type TournamentDetail = TournamentListItem & {
  description: string
  participantCount: number
  participating: boolean
  invited: boolean
  manageable: boolean
  invitationRows: TournamentParticipant[]
  games: TournamentGame[]
}

export type TournamentWrite = {
  name: string
  dates: string[]
  gameCount: number
  participationCost: string
  description: string
}

export type TournamentAction = {
  tournamentId: number
  participating: boolean
  message: string
}

export type RatingRow = {
  rank: number
  displayName: string
  userId: number | null
  games: number
  wins: number
  totalPoints: number
  averageAdditionalPoints: number
  winRate: number
  weightedScore: number
}

export type NewsItem = {
  id: number
  title: string
  content: string
  createdAt: string
  manageable: boolean
}

export type RulesSubnav = { anchor: string; title: string }
export type RulesBlock = { type: string; anchor: string; label: string; text: string }
export type RulesSection = { anchor: string; title: string; navigation: RulesSubnav[]; blocks: RulesBlock[] }
export type RulesDocument = { introLines: string[]; sections: RulesSection[] }
export type RulesPageData = { title: string; document: RulesDocument; editableContent: string | null; manageable: boolean }
export type ContentAction = { id: number; message: string }

export type ModeratorScopeDefinition = {
  name: string
  displayName: string
}

export type ModeratorScopeCell = {
  decision: 'INHERIT' | 'ALLOW' | 'DENY'
  effective: boolean
}

export type ModeratorAdminRow = {
  userId: number
  username: string
  scopes: Record<string, ModeratorScopeCell>
}

export type ModeratorDefaults = {
  scopes: ModeratorScopeDefinition[]
  defaults: Record<string, boolean>
}

export type EligibleModeratorPlayer = {
  userId: number
  username: string
}

export type ModeratorAdminAction = {
  userId: number | null
  username: string | null
  message: string
}
