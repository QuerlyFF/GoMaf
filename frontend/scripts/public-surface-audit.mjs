import fs from 'node:fs'
import path from 'node:path'

const root = process.cwd()
const mode = process.argv.includes('--dist') ? 'dist' : 'source'
const forbidden = [
  /GoMaf\s+v\d+/i,
  /\bWP\d{2}-\d+\b/i,
  /\bPREDEPLOY\b/i,
  /browser storage/i,
  /служебн(?:ых|ые) рол/i,
  /внутренн(?:их|ие) данных аккаунта/i,
  /ADMIN MFA/i,
  /Recovery codes/i,
  /otpauth URI/i,
  /otpauth:\/\//i,
  /mfaSecret/i,
  /otpauthUri/i,
  /TOTP\s*\/\s*recovery code/i,
  /TOTP или recovery code ADMIN/i,
  /MODERATOR overrides/i,
  /Показываются только аккаунты PLAYER/i,
  /Сессия создаётся только после/i,
  /безопасно перекодировано на сервере/i,
]
function walk(dir, accept) {
  const files = []
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const file = path.join(dir, entry.name)
    if (entry.isDirectory()) files.push(...walk(file, accept))
    else if (accept(file)) files.push(file)
  }
  return files
}

const files = mode === 'dist'
  ? walk(path.join(root, 'dist'), file => /\.(?:html|js|css)$/.test(file))
  : [path.join(root, 'index.html'), ...walk(path.join(root, 'src'), file => file.endsWith('.tsx'))]

const failures = []
for (const file of files) {
  const text = fs.readFileSync(file, 'utf8')
  for (const pattern of forbidden) {
    if (pattern.test(text)) failures.push(`${path.relative(root, file)}: ${pattern}`)
  }
}
const indexPath = path.join(root, mode === 'dist' ? 'dist/index.html' : 'index.html')
const index = fs.readFileSync(indexPath, 'utf8')
if (!/<title>GoMaf<\/title>/.test(index)) failures.push(`${path.relative(root, indexPath)}: title must be exactly GoMaf`)
if (mode === 'source' && !/name="description" content="GoMaf — /.test(index)) failures.push('index.html: public description missing')
if (mode === 'dist') {
  const maps = walk(path.join(root, 'dist'), file => file.endsWith('.map'))
  if (maps.length) failures.push(`dist contains source maps: ${maps.map(file => path.relative(root, file)).join(', ')}`)
}

if (failures.length) {
  console.error('PUBLIC_SURFACE_AUDIT_FAIL')
  for (const failure of failures) console.error(failure)
  process.exit(1)
}
console.log(`PUBLIC_SURFACE_AUDIT_PASS mode=${mode} files=${files.length}`)
