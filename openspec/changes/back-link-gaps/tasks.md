# Tasks

## 1. `ReturnTo`: возврат в адрес действия и в переадресацию

- [x] 1.1 `ReturnTo.carried(address, from)` — дописывает `from=<закодированный>`
  к адресу, если `from` проходит `safe`, перед якорем; иначе адрес как есть
  (design.md, Р1); javadoc класса — переадресация по `from` теперь есть
  (ADR-0060)
- [x] 1.2 `ReturnTo.location(safe)` — кодирует недопустимые в адресе символы
  в UTF-8 `%XX`, `%XX` оставляет (design.md, Р1)
- [x] 1.3 Тесты в `ReturnToTest`: `carried` с внутренним, внешним, пустым
  `from`, с якорем и с уже имеющимся запросом; `location` с кириллицей,
  пробелом, `{}` и вложенным `%2F`; проверка — `mvn test -Dtest=ReturnToTest`
  зелёный

## 2. Словари и рубрикатор

- [x] 2.1 `dictionary/dictionaries.html`: «← Назад» по `shell.back` (только
  с ним); ссылки выбора записи и все формы несут `from=${shell.back}`
  (design.md, Р2)
- [x] 2.2 `DictionaryController`: POST-обработчики принимают `from`
  и переадресуют через `ReturnTo.carried` (design.md, Р3)
- [x] 2.3 `taxonomy/tree.html` и `taxonomy/branches.html`: «← Назад»
  по `shell.back`; ссылки узлов и все формы (заведение, имя, перемещение,
  удаление, распределение, корень) несут `from=${shell.back}`
- [x] 2.4 `TaxonomyController`: POST-обработчики принимают `from`,
  `atNode(id, from)` и ветка «к корню» — через `ReturnTo.carried`
- [x] 2.5 Тесты: `DictionaryScreenTest` — с `from` есть «← Назад» на него,
  без `from` нет; ссылки выбора и формы несут `from`; заведение Метода
  с `from` переадресует с `from`; отказ (занятое имя) показывает «назад»
  на `from`. `TaxonomyScreenTest` — то же для узла, заведения Темы и удаления;
  проверка — `mvn test -Dtest='DictionaryScreenTest,TaxonomyScreenTest,RestructureScreenTest'`
  зелёный

## 3. Поиск Задач и список Заданий

- [x] 3.1 `problem/search.html`: «← Назад» по `shell.back` только с ним;
  форма отбора — скрытое поле `from` при `shell.back`; «Сбросить» несёт
  `from` (design.md, Р2, Р7)
- [x] 3.2 `assignment/list.html`: то же — «← Назад», скрытое поле в форме
  отбора, «Сбросить» с `from`; период срока — `dueFrom`/`dueTo`
  (design.md, Р9)
- [x] 3.3 Тесты: `ProblemSearchScreenTest` — с `from` «← Назад» на него
  и скрытое поле, без `from` ни того, ни другого; тест списка Заданий —
  то же; проверка — `mvn test -Dtest='ProblemSearchScreenTest,AssignmentScreenTest'`
  зелёный

## 4. Черновик Задачи

- [x] 4.1 `ProblemDraftController.write` — переадресация на черновик
  с `from` (design.md, Р6); форма удаления в `problem/intake.html`
  шлёт `from=${shell.back}`
- [x] 4.2 Утверждение («Сохранить как Задачу», «Взять снимок как есть»)
  и удаление — переход по design.md, Р4: без оставшихся черновиков —
  по возврату списка через `ReturnTo.location`; `from` — сам список —
  на него с `created`; иначе — список с `from` и `created` (у удаления
  без `created`)
- [x] 4.3 Список черновиков читает `created`, кладёт Задачу в модель
  (нет такой — ничего); `problem/intake-list.html` — надпись «Заведена
  Задача № N» ссылкой на неё с `from=${shell.here}` (design.md, Р5)
- [x] 4.4 Тесты в `ProblemIntakeScreenTest`: запись с `from` переадресует
  на черновик с `from`; отказ разметкой показывает «назад» на `from`;
  утверждение при оставшемся черновике — список с `from` и надписью
  со ссылкой на Задачу; черновик, открытый из списка, — без вложения
  списка в список; последний черновик — переадресация на `from`
  (Разговор), без `from` — на `/assistant`; удаление при оставшихся —
  список с `from` без надписи; `created` с несуществующей Задачей —
  без надписи и без отказа; проверка — `mvn test -Dtest=ProblemIntakeScreenTest`
  зелёный

## 5. Документы, приёмка, версия

- [ ] 5.1 `openspec/context/ux-concept.md`: раздел 11 — `from` переживает
  действие, переход прямо по `from` — только без следующей страницы
  (ADR-0060); правило 8 для новых экранов — формы страницы и ссылки
  на себя несут `from=${shell.back}`, GET-форма — скрытым полем (design.md, Р8)
- [ ] 5.2 Приёмка: `mvn clean package` зелёный
- [ ] 5.3 Поднять версию в `pom.xml` до `3.12.0` и дописать сверху
  `CHANGELOG.md` строку из proposal.md; проверка —
  `grep -n 3.12.0 pom.xml CHANGELOG.md`

## Workflow follow-up

- `/opsx:archive back-link-gaps`: ADR-0060 — «Принято», `build_index.py`,
  строка бэклога — ✅, коммит архивации, squash в `develop`, тег `v3.12.0`
  и в `origin`.
