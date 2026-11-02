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

/**
 * Like Inugram's own repo check: the newest release post is an update whenever its version differs from the
 * installed one. Version numbers are not compared, so a renumbered release still reaches everyone.
 */
export function isNewer(candidate: string, current: string): boolean {
  return candidate !== current
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
