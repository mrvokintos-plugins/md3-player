# md3-player

Музыкальный плеер из [exteraless](https://github.com/exteraless/exteraless), перенесённый плагином в
[Inugram](https://github.com/teidesu/inugram).

Внутри всё то же, что в exteraless: полноэкранный плеер, мини-плеер на главной, бар в чатах, лирика,
очередь и редактор стиля. Плеер включается для музыки, голосовые открываются в стоковом.

Готовый `.inu.js` лежит в [релизах](../../releases) и в Telegram-канале с релизами. Настройки:
Settings → Plugins → MD3 Player.

## Обновления

Каждый релиз CI отправляет ещё и в Telegram-канал. Если добавить этот канал в Settings → Plugins →
Repositories и поставить плагин оттуда, inugram сам покажет обновление. Кроме того, плагин раз в несколько
часов смотрит канал и, когда выходит новая версия, показывает уведомление с переходом к посту.

Для CI нужны секрет `TELEGRAM_BOT_TOKEN` (бот должен быть админом канала) и переменная
`TELEGRAM_CHANNEL` с юзернеймом канала. Без них релизы выходят только на GitHub, а проверка обновлений в
плагине выключена.

## Как это работает

Плагины Inugram пишутся на JS, а плеер написан на Java, поэтому его код не переписывается руками.

`porter/` берёт исходники `app.exteraless.player` и правит их под Inugram. Правила лежат в
`porter/rules.txt`. Ресурсы ищутся по имени, потому что id в каждой сборке Inugram свои. Форковые
хелперы и методы заменяются на свои из `compat/`. Потом porter компилирует результат против APK Inugram.
R8 в Inugram делает protected-методы публичными и вырезает `throws`; такие ошибки компиляции porter
исправляет сам. Остальное собирается в dex, плагин загружает его и ставит хуки из `src/index.ts`.

Правки, которые exteraless вносит в стоковые классы (бар в `FragmentContextView`, мини-плеер в
`MainTabsActivity`, «играть один раз», перестановка очереди), автоматически не переносятся. Они
написаны заново в `compat/md3player/hooks` и `MediaCompat`.

Раз в день Actions проверяют exteraless и релизы Inugram. Если что-то поменялось, порт собирается
заново и выходит релиз. Если не собирается, создаётся issue `upstream-break` с отчётом porter'а.

## Сборка

Нужны JDK 17+, Node 20+ и pnpm.

```sh
pnpm install
scripts/fetch-tools.sh

# классы Inugram для компиляции, проще всего вытащить APK с телефона
adb pull "$(adb shell pm path desu.inugram | sed 's/package://')" .cache/base.apk
.cache/tools/dex2jar/d2j-dex2jar.sh -f -o .cache/inugram-classpath.jar .cache/base.apk

scripts/build.sh ../exteraless .cache/inugram-classpath.jar
pnpm push
```

Быстро проверить, что dex нормально линкуется с установленной сборкой:

```sh
pnpm exec inu eval md3-player "inu.jvm.cls('md3player.SelfTest').callStatic('run')"
```

## Что не так

- Predictive back в плеере не работает, в стоковом `BottomSheet` для него нет колбэков.
- Если перетащить мини-плеер под поиск, список чатов под ним не сдвигается.
- Онлайн-лирику Java-код запрашивает сам, а не через гранты Inugram. Запрос уходит только после того,
  как разрешишь поиск в плеере или в настройках плагина.

GPL-3.0, как и exteraless.
