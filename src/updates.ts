// Update notifications: CI posts every release to a Telegram channel (also usable as an Inugram plugin repo,
// Settings > Plugins > Repositories). This checks the channel and offers to open the newest post.

declare const MD3_CHANNEL: string

const CHECK_EVERY_MS = 6 * 60 * 60 * 1000
const SEEN_KEY = 'md3.updates.notified'
const VERSION_RE = /MD3 Player v?(\d+(?:\.\d+)+)(?:\+(\w+))?/

export interface Release {
  version: string
  dialogId: number
  messageId: number
}

export const channel: string = typeof MD3_CHANNEL === 'string' ? MD3_CHANNEL.replace(/^@/, '') : ''

export function currentVersion(): string {
  return inu.info().header.version?.[0] ?? '0'
}

/** 0.1.0.20261102+abc -> [0, 1, 0, 20261102] */
function parts(version: string): number[] {
  return version.split('+')[0].split('.').map(n => Number.parseInt(n, 10) || 0)
}

export function isNewer(candidate: string, current: string): boolean {
  if (candidate === current) return false
  const a = parts(candidate)
  const b = parts(current)
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    const d = (a[i] ?? 0) - (b[i] ?? 0)
    if (d !== 0) return d > 0
  }
  // same numbers, different build: the channel only ever gets newer posts
  return true
}

export async function latestRelease(): Promise<Release | null> {
  if (!channel) return null
  const messages = await inu.account().getHistory(`@${channel}`, { limit: 20, force: true })
  for (const message of messages) {
    const name = message.document?._ === 'document'
      ? message.document.attributes.find(a => a._ === 'documentAttributeFilename')
      : undefined
    if (!name || name._ !== 'documentAttributeFilename' || !name.file_name.endsWith('.inu.js')) continue
    const match = VERSION_RE.exec(message.text)
    const dialogId = message.dialogId
    if (!match || dialogId === null) continue
    return { version: match[2] ? `${match[1]}+${match[2]}` : match[1], dialogId, messageId: message.id }
  }
  return null
}

export function openRelease(release: Release): void {
  inu.ui.openPage({ type: 'chat', dialogId: release.dialogId, messageId: release.messageId })
}

async function check(texts: { available: (v: string) => string, open: string }): Promise<void> {
  const release = await latestRelease()
  if (!release || !isNewer(release.version, currentVersion())) return
  if (localStorage.getItem(SEEN_KEY) === release.version) return
  localStorage.setItem(SEEN_KEY, release.version)
  const result = await inu.ui.bulletin({
    text: texts.available(release.version),
    icon: inu.icons.common('download'),
    duration: 8000,
    button: texts.open,
  })
  if (result === 'button' || result === 'clicked') openRelease(release)
}

export function startUpdateChecks(texts: { available: (v: string) => string, open: string }): void {
  if (!channel) return
  let timer: ReturnType<typeof setTimeout> | undefined
  const run = (): void => {
    check(texts)
      .catch(e => console.warn('md3player: update check failed', e))
      .finally(() => {
        timer = setTimeout(run, CHECK_EVERY_MS)
      })
  }
  // give the app a moment to finish starting
  timer = setTimeout(run, 15_000)
  inu.onUnload(() => clearTimeout(timer))
}
