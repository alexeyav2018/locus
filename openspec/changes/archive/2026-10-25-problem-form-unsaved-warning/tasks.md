# Tasks

Документы контекста: правится `openspec/context/ux-concept.md` (правило 11 —
форма Задачи примером и выбор формы `sent` по адресу отклонённого действия).
`glossary.md`, `domain-model.md`, `standards.md`, `antipatterns.md`,
`architecture.md` и `CLAUDE.md` не затрагиваются: новых терминов,
инвариантов и правил кода нет.

## 1. Вопрос при уходе с формы Задачи

- [x] 1.1 `templates/problem/form.html`: `data-unsaved` по design.md,
  решения 2 и 3 — основная форма (`sent` при заведении на любой
  перерисовке, при правке — на окончании `/problems/{id}`), формы текста
  слотов (`sent` на своём `…/text` и на `/text/preview` со своим текстом),
  «Записать текст», замены файла и Улики (`sent` на своём действии);
  формы `.md` — без атрибута; комментарий шаблона называет правило
- [x] 1.2 Тест `ProblemFormUnsavedTest`: каждая строка таблицы design.md,
  решение 2 — `read` у прочитанной страницы заведения и правки обоих видов,
  `sent` ровно у формы отправленного действия, у отказа `.md` — ни одной
  `sent`, формы `.md` не помечены; проверка —
  `mvn test -Dtest=ProblemFormUnsavedTest` зелёный
- [x] 1.3 `openspec/context/ux-concept.md`, правило 11: форма Задачи
  примером; на странице с несколькими формами `sent` несёт форма
  отклонённого действия, выбранная шаблоном по `shell.opened`
- [x] 1.4 Приёмка: `mvn clean package` зелёный
- [x] 1.5 Поднять версию в `pom.xml` до `3.14.0` и дописать сверху `CHANGELOG.md`
  строку из proposal.md, раздел «Версия»; проверка — `grep -n 3.14.0 pom.xml CHANGELOG.md`

## Workflow follow-up

- `/opsx:archive problem-form-unsaved-warning`, строка бэклога — ✅, коммит
  архивации, squash в `develop`, тег `v3.14.0` на коммит слияния и в `origin`.
