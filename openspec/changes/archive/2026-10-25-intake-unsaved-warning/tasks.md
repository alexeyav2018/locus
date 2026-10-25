# Tasks

Документы контекста: правится `openspec/context/ux-concept.md` (правила
для новых экранов — форма с долгим вводом помечается `data-unsaved`)
и `openspec/backlog.md` (карточка `problem-form-unsaved-warning`,
design.md, решение 4). `glossary.md`, `domain-model.md`, `standards.md`,
`antipatterns.md`, `architecture.md` и `CLAUDE.md` не затрагиваются:
новых терминов, инвариантов и правил кода нет.

## 1. Вопрос при уходе с несохранённым

- [x] 1.1 `static/js/locus.js`, новый последний раздел: снимок каждой
  `form[data-unsaved]` — пары «имя, значение» `FormData` в порядке документа,
  файл — имя и размер; `beforeunload` спрашивает (`preventDefault`,
  `returnValue = ''`), если хоть одна форма со значением `sent` или со снимком,
  отличным от текущего; не отменённый `submit` любой формы снимает вопрос
  (design.md, решения 1–3); проверка — тест 1.4
- [x] 1.2 `templates/problem/intake.html`: форме правки —
  `th:attr="data-unsaved=${sent} ? 'sent' : 'read'"`, форме удаления — ничего;
  комментарий шаблона называет атрибут
- [x] 1.3 `ProblemDraftController.refusedSent` кладёт в модель `sent = true`;
  прочие пути страницы — без признака
- [x] 1.4 Тесты: в `ProblemIntakeScreenTest` — страница несёт
  `data-unsaved="read"` на форме правки и одну такую форму, после отказа разметкой —
  `data-unsaved="sent"`; тест скрипта (рядом, чтением `locus.js`) —
  `beforeunload`, `form[data-unsaved]`, `'sent'`, снятие по `submit`;
  проверка — `mvn test -Dtest=ProblemIntakeScreenTest` зелёный
- [x] 1.5 `openspec/context/ux-concept.md`, «Правила для новых экранов»:
  форма с долгим вводом, где уходить приходится, — `data-unsaved="read"`;
  страница после отказа с присланным — `data-unsaved="sent"`
- [x] 1.6 `openspec/backlog.md`: карточка и строка таблицы
  `problem-form-unsaved-warning` (Т, S, зависимость `intake-unsaved-warning`)
  по design.md, решение 4
- [x] 1.7 Приёмка: `mvn clean package` зелёный
- [x] 1.8 Поднять версию в `pom.xml` до `3.11.0` и дописать сверху `CHANGELOG.md`
  строку из proposal.md, раздел «Версия»; проверка — `grep -n 3.11.0 pom.xml CHANGELOG.md`

## Workflow follow-up

- `/opsx:archive intake-unsaved-warning`, строка бэклога — ✅, коммит архивации,
  squash в `develop`, тег `v3.11.0` на коммит слияния и в `origin`.
