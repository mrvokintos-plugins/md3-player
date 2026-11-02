// MD3 Player: the exteraless Material 3 music player, compiled to DEX by the porter (see porter/) and wired into
// inugram with xposed hooks. All hot-path hooks are routines that call static Java entry points in the DEX
// (compat/md3player/hooks), so the JS engine is never entered from app threads.

import dex from './assets/md3player.dex'
import { channel, currentVersion, isNewer, latestRelease, openRelease, startUpdateChecks } from './updates.js'

const jvm = inu.jvm
const xposed = inu.xposed

jvm.loadDex(dex)

// Captured by the routines below. Typed `any`: routines call Java members the typings do not know.
const Hooks: any = jvm.cls('md3player.hooks.Hooks')
const ContextBar: any = jvm.cls('md3player.hooks.ContextBar')
const TabsHost: any = jvm.cls('md3player.hooks.TabsHost')
const MediaCompat: any = jvm.cls('md3player.compat.MediaCompat')
const Entry = jvm.cls('md3player.Entry')
const Res = jvm.cls('md3player.compat.Res')

const AudioPlayerAlert: any = jvm.cls('org.telegram.ui.Components.AudioPlayerAlert')
const BAR_HEIGHT: number = ContextBar.callStatic('barHeight')

const disposers: Disposer[] = []

function hook(className: string, method: string, spec: inu.xposed.MethodHook, constructors = false): void {
  try {
    const cls = jvm.cls(className)
    disposers.push(constructors ? xposed.hookAllConstructors(cls, spec) : xposed.hookAllOverloads(cls, method, spec))
  } catch (e) {
    console.error(`md3player: cannot hook ${className}.${method}:`, e)
  }
}

// ------------------------------------------------------------------ opening the player

hook('org.telegram.ui.ActionBar.BaseFragment', 'showDialog', {
  before: inu.xposed.routine((ctx) => {
    const replacement = Hooks.replaceDialog(ctx.args[0])
    if (replacement !== null) ctx.args[0] = replacement
  }),
})

hook('org.telegram.ui.ActionBar.BottomSheet', 'show', {
  before: inu.xposed.routine((ctx) => {
    if (ctx.thisObject instanceof AudioPlayerAlert && Hooks.interceptShow(ctx.thisObject)) ctx.setReturnValue(null)
  }),
})

// ------------------------------------------------------------------ bar in chats / no stock bar on main tabs

hook('org.telegram.ui.Components.FragmentContextView', 'checkPlayer', {
  before: inu.xposed.routine((ctx) => {
    ContextBar.beforeCheckPlayer(ctx.thisObject)
  }),
  after: inu.xposed.routine((ctx) => {
    ContextBar.afterCheckPlayer(ctx.thisObject)
  }),
})

hook('org.telegram.ui.Components.FragmentContextView', 'updateStyle', {
  after: inu.xposed.routine((ctx) => {
    ContextBar.afterUpdateStyle(ctx.thisObject)
  }),
})

hook('org.telegram.ui.Components.FragmentContextView', 'getStyleHeight', {
  before: inu.xposed.routine((ctx) => {
    if (ContextBar.isMd3(ctx.thisObject)) ctx.setReturnValue(BAR_HEIGHT)
  }),
})

hook('org.telegram.ui.Components.FragmentContextView', 'dispatchDraw', {
  before: inu.xposed.routine((ctx) => {
    ContextBar.beforeDraw(ctx.thisObject)
  }),
  after: inu.xposed.routine((ctx) => {
    ContextBar.afterDraw(ctx.thisObject)
  }),
})

// ------------------------------------------------------------------ mini player on the main tabs

hook('org.telegram.ui.MainTabsActivity', 'createView', {
  after: inu.xposed.routine((ctx) => {
    TabsHost.attach(ctx.thisObject)
  }),
})

hook('org.telegram.ui.MainTabsActivity', 'onResume', {
  after: inu.xposed.routine((ctx) => {
    TabsHost.resume(ctx.thisObject)
  }),
})

hook('org.telegram.ui.MainTabsActivity', 'onFragmentDestroy', {
  after: inu.xposed.routine((ctx) => {
    TabsHost.destroy(ctx.thisObject)
  }),
})

// ------------------------------------------------------------------ MediaController behaviour

hook('org.telegram.messenger.MediaController', 'sortPlaylist', {
  before: inu.xposed.routine((ctx) => {
    if (MediaCompat.shouldSkipSort()) ctx.setReturnValue(null)
  }),
})

hook('org.telegram.messenger.MediaController', 'clearPlaylist', {
  after: inu.xposed.routine(() => {
    MediaCompat.onPlaylistCleared()
  }),
})

hook('org.telegram.messenger.MediaController', 'playNextMessageWithoutOrder', {
  before: inu.xposed.routine((ctx) => {
    if (MediaCompat.onTrackEnd(ctx.args[0])) ctx.setReturnValue(null)
  }),
})

hook('org.telegram.messenger.MediaController', 'playMessage', {
  after: inu.xposed.routine(() => {
    MediaCompat.applyMusicSpeed()
  }),
})

hook('org.telegram.messenger.MediaController', 'setPlaybackSpeed', {
  after: inu.xposed.routine(() => {
    MediaCompat.applyMusicSpeed()
  }),
})

hook('androidx.media3.exoplayer.source.ProgressiveMediaSource$Factory', '<init>', {
  after: inu.xposed.routine((ctx) => {
    if (ctx.args.length === 1) MediaCompat.patchProgressiveFactory(ctx.thisObject, ctx.args[0])
  }),
}, true)

hook('org.telegram.messenger.MusicPlayerService', 'onStartCommand', {
  after: inu.xposed.routine(() => {
    Hooks.onServiceCommand()
  }),
})

Entry.callStatic('init')
console.log(`md3player: loaded, ported from exteraless@${Entry.callStatic('version')}, ${disposers.length} hooks`)

// ------------------------------------------------------------------ settings

function str(key: string): string {
  return Res.callStatic('getString', Res.callStatic('sid', key))
}

const LOCAL: Record<string, Record<string, string>> = {
  en: {
    miniHint: 'Floating player above the tabs on the main screen',
    chatBar: 'Player bar in chats',
    chatBarHint: 'MD3 bar instead of the stock one at the top of chats',
    updates: 'Updates',
    check: 'Check for updates',
    checking: 'Checking…',
    upToDate: 'You have the latest version',
    available: 'Version %s is available',
    open: 'Open',
    failed: 'Could not check',
    channel: 'Releases channel: @%s',
  },
  ru: {
    miniHint: 'Плавающий плеер над вкладками на главном экране',
    chatBar: 'Строка плеера в чатах',
    chatBarHint: 'MD3-строка вместо стоковой вверху чатов',
    updates: 'Обновления',
    check: 'Проверить обновления',
    checking: 'Проверяю…',
    upToDate: 'Установлена последняя версия',
    available: 'Доступна версия %s',
    open: 'Открыть',
    failed: 'Не удалось проверить',
    channel: 'Канал с релизами: @%s',
  },
}

function local(key: string, arg?: string): string {
  const lang = inu.info().language.split(/[-_]/)[0]
  const text = (LOCAL[lang] ?? LOCAL.en)[key] ?? LOCAL.en[key] ?? key
  return arg === undefined ? text : text.replace('%s', arg)
}

function toggle(key: string, label: string, subtitle?: string): inu.UIElement {
  return inu.ui.check({
    id: key,
    text: label,
    subtitle,
    multiline: subtitle !== undefined,
    checked: Entry.callStatic('getBool', key),
    onChange: (checked) => {
      Entry.callStatic('setBool', key, checked)
      settings.invalidate()
    },
  })
}

const settings = inu.ui.settingsPage({
  title: 'MD3 Player',
  items: () => [
    inu.ui.header(str('OEPlayerMusic')),
    toggle('OEAppearanceMd3Player', str('OEAppearanceMd3Player')),
    toggle('OEAppearanceMd3MiniPlayer', str('OEAppearanceMd3MiniPlayer'), local('miniHint')),
    toggle('MD3PlayerChatBar', local('chatBar'), local('chatBarHint')),
    toggle('OEPlayerLrclibAllowed', str('OEPlayerFindLyrics'), str('OEPlayerNoLyricsText')),
    inu.ui.button({
      id: 'style',
      text: str('OEPlayerCustomize'),
      icon: inu.icons.common('edit'),
      onClick: () => Entry.callStatic('openStyleEditor', inu.android.getCurrentFragment()),
    }),
    ...updateRows(),
    inu.ui.separator(`${currentVersion()} · exteraless@${Entry.callStatic('version')}`),
  ],
})

let updateStatus = ''

function updateRows(): inu.UIElement[] {
  if (!channel) return []
  return [
    inu.ui.header(local('updates')),
    inu.ui.button({
      id: 'check-updates',
      text: local('check'),
      subtitle: updateStatus || local('channel', channel),
      icon: inu.icons.common('refresh'),
      onClick: () => {
        updateStatus = local('checking')
        settings.invalidate()
        latestRelease()
          .then((release) => {
            if (release && isNewer(release.version, currentVersion())) {
              updateStatus = local('available', release.version)
              openRelease(release)
            } else {
              updateStatus = local('upToDate')
            }
          })
          .catch((e) => {
            console.warn('md3player: update check failed', e)
            updateStatus = local('failed')
          })
          .finally(() => settings.invalidate())
      },
    }),
  ]
}
disposers.push(inu.registerSettings(settings))

startUpdateChecks({ available: v => local('available', v), open: local('open') })

inu.onUnload(() => {
  for (const dispose of disposers.splice(0)) {
    try {
      dispose()
    } catch (e) {
      console.error('md3player: dispose failed', e)
    }
  }
  Entry.callStatic('shutdown')
})
