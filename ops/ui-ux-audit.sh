#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
python3 - <<'PY'
from pathlib import Path
import re
errors=[]
def need(path, token, label):
    p=Path(path)
    if not p.exists() or token not in p.read_text(encoding='utf-8'):
        errors.append(label)
def forbid_tree(root, pattern, label):
    rx=re.compile(pattern,re.I)
    for p in Path(root).rglob('*'):
        if p.is_file() and p.suffix in {'.ts','.tsx','.css'}:
            if rx.search(p.read_text(encoding='utf-8')):
                errors.append(f'{label}: {p}')

app='frontend/src/app/App.tsx'
for route in ('/news','/rules','/players','/games','/schedule','/tournaments','/rating','/profile','/login','/register','/admin/moderators'):
    need(app, f'path="{route}"', f'missing React route {route}')
need('frontend/src/schedule/SchedulePage.tsx','РАСПИСАНИЕ','schedule heading')
for token in ('Rating','No rating','Классическая','Городская','Участвовать','Игроки'):
    need('frontend/src/schedule/SchedulePage.tsx',token,'schedule UX '+token)
for token in ('video','hidden','visibility'):
    need('frontend/src/games/GameFormPage.tsx',token,'game form '+token)
need('frontend/src/games/GameFormPage.tsx','Array.from({ length: 10','ten-seat game model')
for token in ('game-entry-table','registered-game-players','getPlayers','ЛХ 1','ЛХ 2','ЛХ 3'):
    need('frontend/src/games/GameFormPage.tsx',token,'table-style registered-player game entry '+token)
for token in ('Предстоящие','Заверш'):
    need('frontend/src/tournaments/TournamentsPage.tsx',token,'tournament list '+token)
need('frontend/src/tournaments/TournamentDetailPage.tsx','Участвовать','tournament participation')
need('frontend/src/profile/ProfilePage.tsx','max(500','about limit')
need('frontend/src/auth/RegisterPage.tsx','minLength={6}','password minimum 6')
need('frontend/src/app/app.css','overflow-wrap','long-text wrapping')
for token in ('mobile-primary-nav','Разделы сайта'):
    need('frontend/src/app/App.tsx',token,'always-visible mobile sections '+token)
for token in ('max-height: calc(100dvh - 108px)','overflow-y: auto','scrollbar-gutter: stable'):
    need('frontend/src/app/app.css',token,'scrollable rules table of contents '+token)
need('frontend/src/players/PlayersPage.tsx','Средний доп. балл','player average additional score')
need('frontend/src/players/PlayerProfilePage.tsx','`/games/${game.gameId}`','player history game link')
for token in ('Таблица игроков и очков','Первый убитый','Лучший ход','victoryPoints','additionalPoints'):
    need('frontend/src/games/GameDetailPage.tsx',token,'game detail score table '+token)
for token in ('width="208"','height="208"','profile-avatar','srcSet','size=256'):
    need('frontend/src/players/PlayerProfilePage.tsx',token,'responsive public profile avatar '+token)
for token in ('profile-banner','width=1280','fetchPriority="high"','loading="eager"'):
    need('frontend/src/players/PlayerProfilePage.tsx',token,'responsive LCP profile banner '+token)
need('frontend/src/app/app.css','font-size: clamp(1.9rem, 3.2vw, 2.75rem)','smaller profile nickname')
need('frontend/src/profile/ProfilePage.tsx','После обработки: 512 x 512 px, PNG.','avatar upload output size')
need('frontend/src/profile/ProfilePage.tsx','readAsDataURL(next)','CSP-compatible avatar preview')
need('frontend/src/profile/ProfilePage.tsx','type="range"','avatar zoom control')
need('frontend/src/app/app.css','padding: 28px 32px 30px 276px','compact profile identity layout')
need('frontend/src/app/app.css','min-height: 184px','two-line profile biography space')
need('frontend/src/app/app.css','font-family: "GoMaf Inter"','self-hosted Inter profile typography')
need('frontend/src/players/PlayerProfilePage.tsx','profile-social-copy','banner/avatar/name/biography composition')
need('frontend/e2e/g4.spec.ts','public internal links resolve without broken browser routes','internal-link browser crawl')
need('frontend/e2e/g4.spec.ts','SEO discovery, immutable font and real browser 404 contracts','SEO/404 browser contract')
auth_e2e='frontend/e2e/authenticated-quality.spec.ts'
for token in ('AxeBuilder','loginCredentials','assertKeyboardFocus','390, 844','1440, 900','/admin/moderators','e2e_moderator'):
    need(auth_e2e,token,'authenticated quality evidence '+token)
css=Path('frontend/src/app/app.css').read_text(encoding='utf-8')
breakpoints=[int(v) for v in re.findall(r'@media\s*\(max-width:\s*(\d+)px\)', css)]
for threshold in (900, 640, 430):
    if not any(value <= threshold for value in breakpoints):
        errors.append(f'missing responsive breakpoint at or below {threshold}px')
forbid_tree('frontend/src',r'dangerouslySetInnerHTML','raw HTML rendering forbidden')
forbid_tree('frontend/src',r'<script\b|\beval\s*\(|new\s+Function\s*\(','runtime inline/eval scripting forbidden')
if errors:
    print('UI_UX_AUDIT_FAIL')
    for e in errors: print('FAIL',e)
    raise SystemExit(1)
print('UI_UX_AUDIT_PASS')
print('CHECKS=react-routes,schedule-types,participation,tournaments,private-games,video-links,profile-about,password-6,game-entry-table,registered-player-picker,responsive,csp-safe,authenticated-a11y')
PY
