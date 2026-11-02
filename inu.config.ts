import { readFileSync } from 'node:fs'

import { defineConfig } from '@inugram/cli'

const pkgVersion = process.env.MD3_VERSION || (() => {
  try {
    return readFileSync(new URL('./VERSION', import.meta.url), 'utf8').trim()
  } catch {
    return '0.0.0-dev'
  }
})()

// releases channel for update checks (also an Inugram plugin repo); empty disables them
const channel = (process.env.MD3_CHANNEL ?? '').replace(/^@/, '')

export default defineConfig({
  esbuild: (options) => {
    options.define = { ...options.define, MD3_CHANNEL: JSON.stringify(channel) }
  },
  plugins: {
    'md3-player': {
      entry: 'src/index.ts',
      manifest: {
        id: 'com.github.mrvokintos.md3player',
        name: 'MD3 Player',
        author: 'mrvokintos',
        version: pkgVersion,
        description: {
          en: 'Material 3 music player ported from **exteraless**: full-screen player with cover styles, wavy seek bar, '
            + 'synced lyrics (file tags, opt-in LRCLIB/lrcmux), queue with drag-to-reorder, mini player on the main tabs, '
            + 'bar in chats and a style editor. The player runs as bundled Java code (DEX) hooked into the app; '
            + 'online lyrics are fetched by that code directly, only after you allow it.',
          ru: 'Музыкальный плеер в стиле Material 3, портированный из **exteraless**: полноэкранный плеер со стилями обложки, '
            + 'волнистым ползунком, синхронизированной лирикой (из тегов файла и, по согласию, LRCLIB/lrcmux), очередью с '
            + 'перетаскиванием, мини-плеером на главных вкладках, баром в чатах и редактором стиля. Плеер работает как '
            + 'встроенный Java-код (DEX) с хуками в приложение; онлайн-лирику этот код запрашивает сам и только после разрешения.',
        },
        icon: 'inu://unmute',
        grants: ['unsafe.jvm', 'unsafe.xposed', ...(channel ? ['account.read(peers,history)'] : [])],
      },
    },
  },
})
