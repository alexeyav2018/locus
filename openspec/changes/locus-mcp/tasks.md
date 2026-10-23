# Tasks

Документы `openspec/context/`, которые изменение затрагивает:
`architecture.md` (сервер MCP построен: адрес, транспорт без сессий,
токен, вторая цепочка безопасности), `glossary.md` (токен Реплики
`TurnToken`, реестр `ToolRegistry`, пометка «пока кода нет» у таблицы
Помощника), `standards.md` (SDK MCP только в `ru.locus.assistant`;
инструменты тонкие; реестр сторожится тестом), `antipatterns.md`
(инструмент со своей проверкой прав или репозиторием). `domain-model.md`,
`scenarios.md`, `strategy.md`, `ux-concept.md`, журнал решений —
не затрагивают. `CLAUDE.md` — статус при архивации.

## 1. Зависимость, абстракция инструмента и реестр

- [x] 1.1 `pom.xml`: `mcp-bom` 2.1.0 в `dependencyManagement`, зависимость
      `io.modelcontextprotocol.sdk:mcp` с комментарием (ADR-0054, видна
      только `ru.locus.assistant`); `mvn -q dependency:resolve` проходит
- [x] 1.2 `Addresses.MCP = "/mcp"` с javadoc: адрес сервера MCP, замок —
      токен Реплики, не сеанс
- [x] 1.3 `ru.locus.assistant`: `Tool` (имя, описание, схема, обработчик),
      `ToolResult` (`json`, `image`, `refusal`), `Arguments`
      (`requireLong`, `optionalLong`, `longs`, `requireString`,
      `optionalString`, `requireInt`, `optionalInt`, вложенные объекты
      и списки объектов для строк сборки и Рамки; отсутствующий
      или не того типа аргумент — `IllegalArgumentException`, называющая
      имя), `Schema` (конструкторы JSON-схем), интерфейс `Tools`,
      `ToolRegistry` (собирает `List<Tools>`, отдаёт `tools()` и `names()`,
      при повторе имени падает при старте с внятным текстом); javadoc
      с ссылками на design.md Р6
- [x] 1.4 `ArgumentsTest`: число из JSON приходит `Integer`, `Long`
      и `Double` — все читаются; отсутствующий обязательный — отказ
      с именем; список чисел; вложенный объект; `ToolRegistryTest`
      (без Spring): два `Tools` с одинаковым именем — отказ; имена
      проверяются на латиницу и `snake_case`
- [x] 1.5 `mvn test -Dtest='ArgumentsTest,ToolRegistryTest'` зелёный

## 2. Токен Реплики, цепочка безопасности и сервер MCP

- [x] 2.1 `TurnToken` (значение, не пароль: `toString` не раскрывает),
      `TurnTokens`: `issue(UserId, Duration)`, `resolve(String)`,
      `revoke(TurnToken)`; истёкший удаляется при разрешении; `Clock`
      приложения; javadoc — ADR-0057, design.md Р3
- [x] 2.2 `TurnTokenFilter` (`OncePerRequestFilter` на `Addresses.MCP`):
      `Authorization: Bearer` → `TurnTokens.resolve` → `UserRepository.findById`
      → `UsernamePasswordAuthenticationToken.authenticated(login, null,
      ROLE_…)` в `SecurityContextHolder`; иначе `401` с
      `WWW-Authenticate: Bearer` и без перенаправления
- [x] 2.3 `McpSecurityConfiguration`: вторая `SecurityFilterChain`
      с `@Order(1)`, `securityMatcher(Addresses.MCP)`, без CSRF, `STATELESS`,
      `anyRequest().authenticated()`, фильтр перед `AuthorizationFilter`,
      точка входа `401`; основная цепочка `SecurityConfig` не меняется
- [x] 2.4 `McpServerConfiguration`: `HttpServletStatelessServerTransport`
      (`JacksonMcpJsonMapper` на `JsonMapper` приложения, `messageEndpoint`
      `/mcp`, `contextExtractor` кладёт `Authentication` из
      `SecurityContextHolder`), `ServletRegistrationBean` на `/mcp`,
      `McpServer.sync(transport)` с `serverInfo("locus", версия
      из `BuildProperties`)`, `capabilities(tools)` и инструментами
      реестра; обёртка инструмента: `Arguments` из `CallToolRequest`,
      `Authentication` из контекста в `SecurityContextHolder` на время
      вызова, `ToolResult` → `CallToolResult`, отказ сервиса → `isError`
      с текстом, прочее → `isError` с общим текстом и записью в журнал
      (design.md Р5)
- [x] 2.5 `TurnTokensTest`: выпуск → разрешение; отзыв → пусто; истечение
      по `TestClock` → пусто; неизвестный → пусто
- [x] 2.6 `TurnTokenGuardsTheServerTest` (`IntegrationTest`, HTTP на
      `/mcp`): без заголовка — `401`; отозванный — `401`; истёкший — `401`;
      живой токен Учителя — клиент SDK (`McpClient.sync` на
      `HttpClientStreamableHttpTransport` с заголовком) инициализируется
      и перечисляет инструменты; список совпадает с `ToolRegistry.names()`;
      страницы по-прежнему требуют входа формой (`/` без сеанса —
      перенаправление на `/login`, не `401`)
- [x] 2.7 `mvn test -Dtest='TurnTokensTest,TurnTokenGuardsTheServerTest,LoginTest,RoleAccessTest'`
      зелёный

## 3. Инструменты библиотеки

- [x] 3.1 `TaxonomyTools implements Tools`: `list_topics` —
      `TaxonomyService.topicPaths()` → `[{id, path}]`
- [x] 3.2 `DictionaryTools implements Tools`: `list_methods`,
      `list_characteristics` → `[{id, name}]`
- [x] 3.3 `ProblemTools implements Tools`: `find_problems` (аргументы
      `node`, `methods`, `methods_match` `all|any`, `characteristics`,
      `characteristics_match`, `part` `FIRST|SECOND` → `ProblemFilter`
      → `ProblemService.search` → `[{number, part, topics, methods,
      characteristics}]`); `get_problem` (`ProblemService.problems(List.of(id))`
      + `FilePages.outline` на оба слота → номер, Часть, Темы `{id, path}`,
      Методы и Характеристики `{id, name}`, `condition_pages`,
      `solution_pages`); `get_problem_page` (`slot` `condition|solution`,
      `page` → `FilePages.page` → JPEG, пусто — отказ «страницы нет»);
      `create_problem` (`part`, `topics`, `methods`, `characteristics`,
      `condition` и `solution` — списки строк `{draft, from, to, frame?}`,
      `frame` — `{left, top, width, height}` → `PdfAssemblyOrder` →
      `ProblemService.create` → `{number}`); описания инструментов
      по-русски, схемы через `Schema`
- [x] 3.4 `LibraryToolsTest` (`IntegrationTest`, через `ToolRegistry`
      и `LoggedIn`, обстановка `TestLibrary`): `list_topics` отдаёт лист
      с полным путём и не отдаёт Раздел; словари целиком; `find_problems`
      по Разделу и Методу — Задачи поддерева; `get_problem` — разметка
      и число страниц; несуществующий номер — отказ с текстом
      «не существует»; `get_problem_page` — JPEG, страница вне файла —
      отказ; два Учителя получают одинаковый `find_problems`
      (фильтра по владельцу нет)
- [x] 3.5 `CreateProblemToolTest`: Администратор с черновиком сборки
      (`AssemblyDraftService.upload`) заводит Задачу с Рамками — номер
      в ответе, PDF собраны, черновик удалён; без Метода — отказ
      с текстом сервиса; Раздел вместо Темы — отказ; Учитель без роли
      Администратора — отказ «нет прав» (`AccessDeniedException` →
      `isError`)
- [x] 3.6 `mvn test -Dtest='LibraryToolsTest,CreateProblemToolTest,ProblemServiceTest,ProblemSearchTest'`
      зелёный

## 4. Инструменты Черновиков сборки

- [ ] 4.1 `AssemblyDraftRepository.findByOwner(UserId)` — в порядке
      загрузки; `AssemblyDraftRepositoryTest` — чужие не возвращаются;
      `OwnerIsRequiredByDraftsTest` зелёный без правок
- [ ] 4.2 `PdfAssembly.previewPiece(file, name, kind, page, frame)`:
      отрисовка `LARGE`, вырез по долям Рамки, ужатие до `NORMAL.longSide`
      при превышении; `PdfAssemblyTest` — пропорции куска соответствуют
      Рамке, кусок во всю страницу не крупнее 1600 px, Рамка вне
      страницы — отказ
- [ ] 4.3 `AssemblyDraftService.own()` (Администратор, `sweep`, владелец
      из `CurrentUser`) и `previewPiece(id, page, frame)` (`Optional`,
      пусто для чужого, несуществующего и страницы вне черновика);
      `DraftsAreFilteredByOwnerTest` — чужой черновик не в `own()`,
      `previewPiece` чужого пуст
- [ ] 4.4 `AssemblyDraftTools implements Tools`: `list_assembly_drafts`
      → `[{id, name, kind, pages}]`; `get_assembly_draft_page` (`draft`,
      `page` → `preview(NORMAL)` → JPEG); `crop_assembly_draft_page`
      (`draft`, `page`, `frame` → `previewPiece` → JPEG); пусто — отказ
      «черновика или страницы нет», одинаковый для чужого
      и несуществующего
- [ ] 4.5 `AssemblyDraftToolsTest` (`IntegrationTest`, через `ToolRegistry`
      и `LoggedIn`): список А без черновика Б; страница своего — JPEG;
      чужой и несуществующий — один и тот же текст отказа; кусок по Рамке
      левой верхней четверти — JPEG с пропорциями Рамки; Учитель без роли
      Администратора — отказ «нет прав»; без аргумента `page` — отказ,
      называющий аргумент
- [ ] 4.6 `mvn test -Dtest='AssemblyDraftRepositoryTest,PdfAssemblyTest,DraftsAreFilteredByOwnerTest,AssemblyDraftToolsTest,OwnerIsRequiredByDraftsTest,AssemblyDraftServiceTest'`
      зелёный

## 5. Сторожевые тесты, документы и приёмка

- [ ] 5.1 `ToolsAreThinTest`: `TaxonomyTools`, `DictionaryTools`,
      `ProblemTools`, `AssemblyDraftTools` не держат и не получают
      репозиториев и `FileStorage` (по образцу `ProblemControllerIsThinTest`);
      `ToolRegistryTest` дополнен: реестр приложения — ровно десять
      названных имён (спека, «Инструментов личного контура и глаголов
      человека нет»)
- [ ] 5.2 `EveryToolAnswersTheClientTest` (`IntegrationTest`): клиент SDK
      с живым токеном Администратора перечисляет инструменты и зовёт
      каждый из реестра по HTTP с годными аргументами — ни одного
      `isError`; картинки приходят `ImageContent` с `image/jpeg`;
      инструмент реестра, не позванный тестом, роняет его
- [ ] 5.3 `architecture.md`: «Общая форма» и «Права доступа» — сервер
      MCP построен (`/mcp`, без сессий, токен Реплики, вторая цепочка
      безопасности, перенос личности в поток обработчика), «Слои» —
      строка «Инструменты MCP» без пометки замысла; `glossary.md`:
      `TurnToken` и `ToolRegistry` в таблице Помощника, пометка «пока кода
      нет» снята у построенного; `standards.md`: SDK MCP только
      в `ru.locus.assistant`, инструмент — тонкая обёртка сервиса,
      реестр и тонкость сторожатся тестами; `antipatterns.md`: «Инструмент
      MCP со своей проверкой прав или репозиторием» в разделе «Права
      и границы данных»
- [ ] 5.4 `mvn clean package` зелёный
- [ ] 5.5 Поднять версию в `pom.xml` до 3.3.0 и дописать в `CHANGELOG.md`
      строку из `proposal.md`, раздел «Версия»
