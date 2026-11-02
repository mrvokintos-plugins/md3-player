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
          en: 'Material 3 music player from exteraless. @mrvokintos_plugins',
          ru: 'Музыкальный плеер Material 3 из exteraless. @mrvokintos_plugins',
        },
        icon: 'inu://unmute',
        grants: ['unsafe.jvm', 'unsafe.xposed', ...(channel ? ['account.read(peers,history)'] : [])],
      },
    },
  },
})
