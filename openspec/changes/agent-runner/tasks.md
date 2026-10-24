# Tasks

Документы `openspec/context/`, которые изменение затрагивает:
`architecture.md` (исполнитель построен: рабочее место `agent/`, клетка
флагами, белый список окружения, ключ API или токен подписки, настройки
`locus.agent`), `glossary.md` (Разговор и Реплика — имена в коде
настоящие, состояния Реплики словами, рабочее место), `domain-model.md`
(Разговор и Реплика со строками хода — личный контур), `standards.md`
(процесс агента запускается только в `ru.locus.assistant`; инструменты
в `agent/` называются полным именем `mcp__locus__…`), `antipatterns.md` —
не затрагивает (запрет фоновой работы уже сформулирован с исключением
Реплики), `scenarios.md`, `strategy.md`, `ux-concept.md`, журнал решений —
не затрагивают. `openspec/backlog.md`: карточка `assistant-chat` — таблицы
и тесты изоляции уже есть, состояние «прервано по шагам». `CLAUDE.md` —
статус при архивации.

## 1. Схема, записи и репозиторий

- [x] 1.1 Миграция `0016-agent-runner.yaml`: `assistant_conversation`
      (`id`, `user_id` → `user_`, `session_id` текст, `created_at`;
      уникальность `(id, user_id)`), `assistant_turn` (`id`,
      `user_id`, `conversation_id`, составной ключ `(conversation_id,
      user_id)` → разговор с каскадом, `prompt`, `state`, `answer`,
      `cause`, `input_tokens`, `output_tokens`, `cost_usd` numeric,
      `started_at`, `finished_at`; частичный уникальный индекс
      `(user_id) where state = 'RUNNING'`), `assistant_turn_step`
      (`turn_id`, `user_id`, составной ключ `(turn_id, user_id)` →
      реплика с каскадом, `position`, `tool_name`, `arguments`; ключ
      `(turn_id, position)`); комментарий в шапке —
      личный контур, ADR-0054, design.md Р1, Р3
- [x] 1.2 `ru.locus.assistant`: `AssistantConversationId`,
      `AssistantTurnId`, `TurnState` (семь значений, `word()` по-русски,
      design.md Р2), записи `AssistantConversation`, `AssistantTurn`
      (состояние, ответ, причина, токены, стоимость, время), `TurnStep`
- [x] 1.3 `AssistantRepository` на `JdbcClient`: `createConversation(UserId,
      Instant)`, `findConversation(UserId, id)`, `recordSession(UserId,
      id, session)`, `startTurn(UserId, conversationId, prompt, Instant)`
      (нарушение частичного индекса → `AssistantBusyException`),
      `findTurn(UserId, id)`, `turnsOf(UserId, conversationId)`,
      `addStep(UserId, turnId, toolName, arguments)`, `finishTurn(UserId,
      id, Outcome)` с `where state = 'RUNNING'`, `stepsOf(UserId,
      turnId)`; исключение — `interruptRunning()` без владельца (уборка
      класса ADR-0036); javadoc с границей
- [x] 1.4 `OwnerIsRequiredByAssistantTest` (по образцу
      `OwnerIsRequiredByLessonsTest`): у каждого публичного метода
      репозитория есть `UserId`, кроме поимённо названного
      `interruptRunning`
- [x] 1.5 `AssistantIsFilteredByOwnerTest` на базе: чужой Разговор
      и чужая Реплика не находятся, Реплику не завести в чужом Разговоре
      (ключ базы), чужие строки хода не читаются, `finishTurn` чужой
      не трогает; вторая «работаю» того же учителя — `AssistantBusyException`,
      у другого учителя — заводится
- [x] 1.6 `mvn test -Dtest='OwnerIsRequiredByAssistantTest,AssistantIsFilteredByOwnerTest,MigrationsOnStartupTest'` зелёный

## 2. Рабочее место `agent/` и сторожевой тест навыков

- [x] 2.1 `agent/CLAUDE.md` — общие правила Помощника по-русски:
      действуешь только инструментами Локуса; перед правкой перечитывай;
      тронутое учителем не переписывай; Темы — только листья
      (`mcp__locus__list_topics`), Методы и Характеристики — только
      из словаря (`mcp__locus__list_methods`, `mcp__locus__list_characteristics`);
      отказ инструмента пересказывай учителю, не обходи; инструменты —
      полным именем (design.md Р12)
- [x] 2.2 `agent/.mcp.json`: сервер `locus`, `type: http`,
      `url: ${LOCUS_MCP_URL}`, заголовок `Authorization: Bearer
      ${LOCUS_TURN_TOKEN}`; `agent/.claude/settings.json`:
      `enableAllProjectMcpServers: true`, `permissions.allow:
      ["mcp__locus__*"]`
- [x] 2.3 `pom.xml`: `agent/` ресурсом с `targetPath` `agent`
      (с точечными файлами); проверить, что `.mcp.json`
      и `.claude/settings.json` попали в `target/classes/agent`
- [x] 2.4 `AgentWorkspaces`: `prepare(UserId)` — очистить
      `<workspaces>/<id>/work/`, скопировать `classpath:agent/**`,
      создать `<workspaces>/<id>/config/`; вернуть оба пути
      (design.md Р7); тест: два учителя — разные пути, повторная
      подготовка убирает лишний файл, `.mcp.json` на месте
- [x] 2.5 `AgentSkillsNameRegisteredToolsTest`: все файлы `agent/`
      рабочего дерева, имена `mcp__locus__[a-z0-9_]+`, каждое
      в `ToolRegistry.names()`; проверка выделена функцией и отдельно
      проверена на тексте с выдуманным `mcp__locus__invented_tool` —
      называет его; разрешения `settings.json` — ровно `mcp__locus__*`
- [x] 2.6 `mvn test -Dtest='AgentWorkspacesTest,AgentSkillsNameRegisteredToolsTest'` зелёный

## 3. Разбор `stream-json` и тест-двойник команды

- [x] 3.1 `StreamEvent` (`Init`, `ToolCall`, `Result`, `Other`)
      и `StreamEvent.parse(String)` на Jackson приложения; аргументы
      вызова обрезаются до 500 знаков; токены входа — сумма трёх полей
      (design.md Р8)
- [x] 3.2 `src/test/resources/assistant/stream/` — строки, снятые
      с настоящего вывода Claude Code (init, ответ текстом, вызов
      инструмента, результат инструмента, `rate_limit_event`, итог
      `success`, итог `error_max_turns`); `StreamEventTest` разбирает
      каждую, неразбираемая строка — `Other`
- [x] 3.3 Тест-двойник `src/test/resources/assistant/claude-double`
      (bash, исполняемый в git): `--version` печатает закреплённую
      версию; иначе читает запрос из stdin и по первому слову
      печатает заготовку: `ответ`, `вызовы` (два вызова инструментов
      и ответ), `зависание` (init и `sleep`), `падение` (stderr
      и код 3), `шаги` (итог `error_max_turns`); пишет полученные
      аргументы, рабочий каталог и имена переменных окружения в файл
      рядом с рабочим местом для проверки тестами
- [x] 3.4 `mvn test -Dtest=StreamEventTest` зелёный

## 4. Исполнитель Реплики и доступность

- [x] 4.1 `AgentProperties` (`locus.agent`, design.md Р13)
      и раздел `locus.agent` в `application.yaml` с комментариями;
      тестовые настройки: команда — двойник, предел времени — 3 с,
      рабочие места — во временной папке
- [x] 4.2 `AgentEnvironment` (бин, по умолчанию `System.getenv()`),
      `AssistantAvailability` — чистая проверка: команда, `--version`
      за 10 с, сравнение версии, ключ `ANTHROPIC_API_KEY` или
      `CLAUDE_CODE_OAUTH_TOKEN` (design.md Р6, Р10); `AssistantAvailabilityTest`
      без Spring: нет команды, чужая версия, нет ключа, всё на месте
- [x] 4.3 `TurnRunner`: команда и флаги (design.md Р4), белый список
      окружения (Р5), запрос в stdin, чтение stdout и хвоста stderr
      виртуальными потоками, `waitFor(предел)` → `destroy()` →
      `destroyForcibly()`, исход по таблице Р8, токен на учителя
      со сроком «предел + минута» и отзыв в `finally`; `RunningTurns`
      для остановки и `@PreDestroy`
- [x] 4.4 `AssistantService`: `startTurn(conversationId | пусто, prompt)`
      от `CurrentUser` — проверка доступности и запроса, новый Разговор
      при пустом, `--resume` с сеансом Разговора, строка «работаю»,
      передача исполнителю; `stop(turnId)`; `turn(turnId)`, `steps(turnId)`;
      `AgentStartup` (`ApplicationRunner`): `interruptRunning()`, затем
      доступность
- [x] 4.5 `TurnRunnerTest` с двойником: готово с ответом, токенами
      и стоимостью; две строки хода; прервано по времени в пределе
      с запасом; не удалось с кодом и stderr; прервано по шагам;
      остановлено; вторая при идущей отклонена; у другого учителя идёт;
      вторая Реплика Разговора передаёт `--resume <сеанс первой>`;
      токен после Реплики — `401` на `/mcp`; в окружении процесса нет
      переменной-секрета, заданной Локусу; разные рабочие места у двух
      учителей; после вызова `AgentStartup` строка «работаю» —
      «прервано перезапуском»; пустой и длинный запрос отклонены
- [x] 4.6 Живая сверка клетки на заглушке спайка
      (`~/projects/locus-spike/stub-mcp/server.py`) с установленным
      Claude Code: `--tools ""` оставляет инструменты MCP видимыми
      и вызываемыми, встроенных нет (`tools` в init), `.mcp.json`
      рабочего места подставляет переменные; итог — в design.md
      (Р4, строка «проверено на версии …»); не прошло — клетка
      `--disallowedTools` списком встроенных
- [x] 4.7 `mvn test -Dtest='AssistantAvailabilityTest,TurnRunnerTest'` зелёный

## 5. Страница `/assistant`

- [x] 5.1 `Addresses.ASSISTANT = "/assistant"`; `AssistantController`
      (тонкий) и `templates/assistant/page.html` на общем каркасе:
      «Помощник доступен» с версией либо «Помощник недоступен: причина»;
      пункта в шапке нет (design.md Р11)
- [x] 5.2 `AssistantPageTest`: с двойником — «доступен»; с командой
      по несуществующему пути (`@TestPropertySource`) Локус поднялся,
      страница называет «команда не найдена», остальные страницы
      работают; без входа — на форму входа
- [x] 5.3 `mvn test -Dtest=AssistantPageTest` зелёный

## 6. Документы, бэклог и версия

- [x] 6.1 `architecture.md`: исполнитель построен — рабочее место,
      флаги клетки, белый список окружения, ключ API или токен
      подписки, `locus.agent`; `glossary.md`: Разговор и Реплика —
      имена в коде настоящие, состояния словами; `domain-model.md`:
      Разговор, Реплика, строка хода в личном контуре; `standards.md`:
      процесс агента — только `ru.locus.assistant`, инструменты в `agent/`
      полным именем
- [x] 6.2 `openspec/backlog.md`, карточка `assistant-chat`: таблицы
      и тесты изоляции построены `agent-runner`, состояний семь
- [x] 6.3 `mvn clean package` зелёный
- [x] 6.4 Поднять версию в `pom.xml` до 3.5.0 и дописать в `CHANGELOG.md`
      строку из proposal.md, раздел «Версия»
