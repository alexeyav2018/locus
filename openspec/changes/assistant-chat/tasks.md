# Tasks

Документы `openspec/context/`, которые изменение затрагивает:
`architecture.md` (Помощник доступен учителю со страницы `/assistant`:
самообновление `meta refresh`, вложение Черновиком сборки через вопрос
`TurnAttachments`, ответ Markdown только с внутренними ссылками),
`glossary.md` (Разговор — показанный по умолчанию, прежние; файл
реплики становится Черновиком сборки), `domain-model.md` — Разговор
и Реплика уже описаны, сверить; `ux-concept.md`, `antipatterns.md`,
`scenarios.md`, `strategy.md`, `standards.md`, журнал решений —
не затрагивают. `openspec/backlog.md`: строка `assistant-chat` — ✅
при архивации, карточка `intake-skill` — учитель приходит со страницы
`/assistant`. `CLAUDE.md` — статус при архивации.

## 1. Сервис и репозиторий

- [ ] 1.1 `AssistantRepository`: `findRunningTurn(UserId)` (идущая
      Реплика владельца), `conversationsOf(UserId, int limit)` —
      Разговоры с Репликами, новые по последней Реплике сверху,
      запись `ConversationSummary` (идентификатор, начало последней
      Реплики, первый запрос), `latestConversation(UserId)` — то же
      с пределом 1; все с `user_id` в SQL (design.md Р3, Р4, Р10)
- [ ] 1.2 `OwnerIsRequiredByAssistantTest` — новые методы принимают
      `UserId`; `AssistantIsFilteredByOwnerTest` — чужие Разговоры
      не попадают в `conversationsOf`, `latestConversation`, чужая
      идущая Реплика — в `findRunningTurn`
- [ ] 1.3 `TurnState.tone()` (`info`/`ok`/`warn`/`bad`, design.md Р8)
      и `TurnStep.tool()` — имя без `mcp__locus__`; модульные тесты
- [ ] 1.4 Вопрос `TurnAttachments` и запись `TurnAttachment` (имя,
      поток) в `ru.locus.assistant`; ответчик `AssemblyDraftAttachments`
      в `ru.locus.problem` — `AssemblyDraftService.upload`, строка
      «Загружен черновик сборки N «имя», страниц: K.» (design.md Р5)
- [ ] 1.5 `AssistantService`: `startTurn(conversation, prompt,
      Optional<TurnAttachment>)` — порядок проверок Р5, запрос — текст
      и строка о черновике через пустую строку; прежняя перегрузка
      без вложения остаётся для `TurnRunnerTest`; `runningTurn()`,
      `conversation(Optional<id>, boolean fresh)` — показанный
      Разговор по Р3 на `Clock`, `conversations()` — прежние
      (до 30), `turns(id)`; все под `hasAnyRole('TEACHER',
      'ADMINISTRATOR')`
- [ ] 1.6 Тесты сервиса на `TestClock`: Разговор по умолчанию —
      с идущей Репликой, сегодняшний, вчерашний — пусто, `fresh` —
      пусто, Разговор без Реплик не показан; вложение — черновик
      Администратора заведён, запрос называет его номер; только файл —
      Реплика заведена; пустой запрос без файла и неподходящий файл —
      отказ, Реплики нет; идущая Реплика — отказ до загрузки,
      черновика нет
- [ ] 1.7 `mvn test -Dtest='Assistant*Test,TurnRunnerTest,OwnerIsRequiredByAssistantTest'` зелёный

## 2. Ответ Markdown

- [ ] 2.1 `AnswerMarkdown.html(markdown, back)` — CommonMark
      `escapeHtml`, `sanitizeUrls`, перевод строки — `<br>`, ссылка
      внутренняя (`ReturnTo.safe`) — `<a href>` с заменённым `from`
      и сохранённым якорем, внешняя и `javascript:` — текст и адрес
      в скобках, картинка — подписью (design.md Р7)
- [ ] 2.2 `AnswerMarkdownTest`: абзацы, список, код; `<script>` —
      текстом; `[Задачи](/problems)` — с `from`; ссылка с `from`
      и якорем; `[сайт](https://example.com)`, `//example.com`,
      `javascript:alert(1)` — без `<a`; картинка — подпись без `<img`
- [ ] 2.3 `mvn test -Dtest=AnswerMarkdownTest` зелёный

## 3. Страница, шапка и оформление

- [ ] 3.1 `fragments/shell.html`: параметр `refresh` у `head`
      (`<meta http-equiv="refresh" content="3">`, design.md Р2);
      группа «Кабинет» — учителю и Администратору, пункт «Помощник»
      обоим, прочие — учителю (Р9); `ShellAdvice.sectionOf` — раздел
      `assistant`; `Addresses.ASSISTANT` — комментарий
- [ ] 3.2 `AssistantController`: GET `/assistant` (`conversation`,
      `new`), POST `/assistant/turns` (`@LargeUpload`, отказы —
      та же страница с текстом и запросом), POST
      `/assistant/turns/{id}/stop`; переадресация на
      `/assistant?conversation=N#turn-M`; HTML ответов — `AnswerMarkdown`
      с адресом Разговора; признак обновления (design.md Р1, Р2, Р4)
- [ ] 3.3 `templates/assistant/page.html`: метка `scope--personal`;
      Реплики (запрос, состояние, причина, подсказка «прервано по
      шагам», ответ, ход, итог, время); форма — `textarea`, поле файла
      у Администратора, «Отправить»; «Остановить»; «Новый разговор»;
      «Помощник отвечает в другом разговоре»; «Прежние разговоры»;
      пустое состояние; недоступность без формы (Р8, Р10, Р11)
- [ ] 3.4 `locus.css`: Разговор и Реплика (запрос учителя, ответ,
      ход, итог), содержимое ответа (абзацы, списки, код, цитата);
      проверить на 390 px: поле во всю ширину, кнопки не вылезают
- [ ] 3.5 Двойник команды: слово `ссылки` — ответ с внутренней
      и внешней ссылкой (заготовка в `stream/`)

## 4. Тесты страницы

- [ ] 4.1 `AssistantPageTest` переделать: пункт «Помощник» в шапке
      у Учителя и у Администратора; с двойником — отправка ведёт
      на Разговор в «работаю» с `refresh` и без формы, после
      завершения — ответ и стоимость без `refresh` и с формой;
      «вызовы» — два имени инструментов; «шаги» — подсказка;
      «падение» — причина; «зависание» — «Остановить» даёт
      «остановлено»; внешняя ссылка ответа — текст, внутренняя —
      с `from`; чужой Разговор — 404, чужая остановка — 404; прежние
      Разговоры — ссылками; поле файла — у Администратора, у Учителя
      нет; снимок JPEG Администратора стал его Черновиком сборки;
      пустой запрос — отказ с текстом; без команды — причина, формы
      и «Новый разговор» нет
- [ ] 4.2 `mvn test -Dtest='AssistantPageTest,EveryPageShowsTheVersionTest'` зелёный

## 5. Документы, бэклог и версия

- [ ] 5.1 `architecture.md`: страница Помощника построена —
      самообновление, вложение через `TurnAttachments`, ответ
      Markdown только с внутренними ссылками; `glossary.md`:
      Разговор — показанный по умолчанию и прежние, файл реплики —
      Черновик сборки; `domain-model.md` — сверить
- [ ] 5.2 `openspec/backlog.md`, карточка `intake-skill`: учитель
      приходит со страницы `/assistant`, файл — Черновиком сборки
- [ ] 5.3 `mvn clean package` зелёный
- [ ] 5.4 Поднять версию в `pom.xml` до 3.6.0 и дописать в `CHANGELOG.md`
      строку из proposal.md, раздел «Версия»
