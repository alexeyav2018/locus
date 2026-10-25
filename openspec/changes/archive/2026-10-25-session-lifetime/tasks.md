# Tasks

Документы контекста: правится `openspec/context/architecture.md` (раздел
«Права доступа» — срок сессии входа). `glossary.md`, `domain-model.md`,
`ux-concept.md`, `standards.md`, `antipatterns.md` и `CLAUDE.md`
не затрагиваются: новых терминов, инвариантов и правил нет.

## 1. Срок сессии входа

- [x] 1.1 Задать `server.servlet.session.timeout: 2h` в `src/main/resources/application.yaml`
  с комментарием о причине (вычитка листа дольше получаса без запросов; рабочий день отклонён —
  забытая вкладка); проверка — тест 1.2
- [x] 1.2 Тест в `src/test/java/ru/locus/security/` на `IntegrationTest`: срок сессии контекста
  встроенного Tomcat работающего сервера — 120 минут (design.md, решение 2); проверка —
  `mvn test -Dtest=<тест>` зелёный, а без строки 1.1 — красный
- [x] 1.3 Дописать в `openspec/context/architecture.md`, раздел «Права доступа», срок сессии
  входа — 2 часа бездействия, в `application.yaml`; проверка — абзац на месте, ссылки целы

## 2. Надпись об отказе на сцене рамки и версия

- [x] 2.1 `static/js/locus.js`, `openStage`: элемент `.crop-failed` с надписью «Сессия истекла
  или страница недоступна — войдите заново; несохранённое скопируйте», скрытый по умолчанию;
  на картинке сцены — `error` прячет `.crop-viewport` и показывает надпись, `load` — наоборот;
  поле `Crop` и строка сборки не трогаются (design.md, решение 3)
- [x] 2.2 `static/css/locus.css`: `.crop-failed` и скрытие `.crop-viewport[hidden]`, `.crop-failed[hidden]`;
  проверка — правило есть и не перекрывается `display` соседних правил
- [x] 2.3 Тест в `AssemblyScreenTest` рядом с `scriptBuildsTheZoomPanelAndTheHandles`: скрипт
  слушает `error` и `load` картинки сцены и содержит надпись дословно, стили задают `.crop-failed`;
  проверка — `mvn test -Dtest=AssemblyScreenTest` зелёный
- [x] 2.4 Приёмка: `mvn clean package` зелёный
- [x] 2.5 Поднять версию в `pom.xml` до `3.10.0` и дописать сверху `CHANGELOG.md` строку
  из proposal.md, раздел «Версия»; проверка — `grep -n 3.10.0 pom.xml CHANGELOG.md`

## Workflow follow-up

- `/opsx:archive session-lifetime`, строка бэклога — ✅, коммит архивации, squash в `develop`,
  тег `v3.10.0` на коммит слияния и в `origin`.
