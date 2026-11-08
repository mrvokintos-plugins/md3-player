# Port report

- upstream: exteraless@f83676346
- files: 23
- result: OK

## Warnings

- PlayerSheet.onCustomBackStarted() overrides a fork-only base method; it is never called on inugram
- PlayerSheet.onCustomBackProgressed() overrides a fork-only base method; it is never called on inugram
- PlayerSheet.onCustomBackCancelled() overrides a fork-only base method; it is never called on inugram

## Log

- Md3Player.java: wrap new AudioPlayerAlert -> md3player.hooks.Hooks.classic
- PlayerBarView.java: field NotificationCenter.audioInfoLoaded -> md3player.compat.MediaCompat.AUDIO_INFO_LOADED (x3)
- PlayerColors.java: call theme.isMonet -> md3player.compat.MonetHelper.isMonet
- PlayerMiniView.java: field NotificationCenter.audioInfoLoaded -> md3player.compat.MediaCompat.AUDIO_INFO_LOADED (x3)
- PlayerQueueSheet.java: call MediaController.getInstance().movePlaylistItem -> md3player.compat.MediaCompat.movePlaylistItem
- PlayerSheet.java: field NotificationCenter.audioInfoLoaded -> md3player.compat.MediaCompat.AUDIO_INFO_LOADED (x3)
- PlayerSheet.java: field AudioPlayerAlert.PLAY_ONCE -> md3player.compat.MediaCompat.PLAY_ONCE (x4)
- PlayerSheet.java: drawable photo_timer -> msg_stories_timer
- PlayerSheet.java: wrap new AudioPlayerAlert -> md3player.hooks.Hooks.classic
- config: 32 keys, getters [md3Player, md3MiniPlayer], lists [playerStyleItems]
- strings: 'PlayOnce' is missing in inugram, shipped from exteraless
- strings: 104 keys, languages [en, ar, es, fa, in, it, ja, ko, pl, pt, ru, tr, uk, zh]
- fix PlayerSheet:767: dropped `@Override` of fork-only onCustomBackStarted
- fix PlayerSheet:794: dropped `@Override` of fork-only onCustomBackProgressed
- fix PlayerSheet:804: dropped `@Override` of fork-only onCustomBackCancelled
- fix SeekableFragmentsExtractorsFactory:102: dropped throws of read (stripped by R8 in the base method)
- compile pass 1: auto-fixed 4 error(s)
- fix SeekableFragmentsExtractorsFactory:106: rethrow checked exceptions sneakily
- compile pass 2: auto-fixed 1 error(s)
- compile: ok after 3 pass(es)
