## 1. Схема

- [ ] 1.1 Завести миграцию `src/main/resources/db/changelog/migrations/0005-theory-materials.yaml`:
      таблица `theory_material` — `id` (autoIncrement, PK), `title` (VARCHAR(300),
      NOT NULL), `node_id` (BIGINT, NOT NULL), `file_key` (VARCHAR(200), nullable),
      `link` (VARCHAR(2000), nullable); внешний ключ на `taxonomy_node` **без
      каскада**; проверочное ограничение «ровно одна из `file_key` и `link`
      заполнена»; колонки владельца нет. Комментарии — по образцу
      `0004-problem-catalog.yaml`. Проверка: `mvn test -Dtest=MigrationsOnStartupTest`
      зелёный, `InconsistentSchemaTest` зелёный.

## 2. Подъём по предкам в дереве

- [ ] 2.1 Добавить `TaxonomyService.ancestry(TaxonomyNodeId)` — узлы от корня
      до запрошенного включительно, с тем же пределом `TaxonomyRepository.MAX_DEPTH`
      и той же ошибкой при его достижении, что стоит сегодня в `path`. Проверка:
      новые случаи в `TaxonomyServiceTest` — цепочка на четырёх уровнях, цепочка
      корня, испорченные данные дают ошибку, а не вечный цикл.
- [ ] 2.2 Перестроить `TaxonomyService.path` на `ancestry`, второй копии подъёма
      не оставлять. Проверка: `TaxonomyServiceTest` и `TaxonomyScreenTest` зелёные;
      тест по образцу `SubtreeWalkIsNotDuplicatedTest` — подъём по родителям
      в исходниках встречается один раз.

## 3. Запись, идентификатор, репозиторий

- [ ] 3.1 Завести `TheoryMaterialId` по образцу `ProblemId`. Проверка:
      `TheoryMaterialIdTest` — отрицательное и нулевое значение отвергаются.
- [ ] 3.2 Завести запись `TheoryMaterial` (идентификатор, название, узел, `FileKey`,
      ссылка) с правилами в компактном конструкторе: название непустое после
      обрезки, узел обязателен, содержимого ровно одно из двух, ссылка — только
      `http://` или `https://`. Проверка: `TheoryMaterialTest` — все пять отказов
      и оба законных состояния.
- [ ] 3.3 Завести `TheoryMaterialRepository` на `JdbcClient` по образцу
      `ProblemRepository`: сохранение, чтение по идентификатору, материалы
      набора узлов, счёт материалов узла, правка, удаление. Ни один метод
      не принимает `UserId`. Проверка: `TheoryMaterialRepositoryTest`
      на Testcontainers.
- [ ] 3.4 Тест изоляции `OwnerIsUnknownToTheoryTest` по образцу
      `OwnerIsUnknownToProblemsTest`: в сигнатурах репозитория и сервиса теории
      `UserId` не встречается, в таблице колонки владельца нет.

## 4. Сервис

- [ ] 4.1 Завести `TheoryService`: заведение, правка названия и узла, замена
      содержимого, удаление; на каждой операции — проверка роли Администратора.
      Порядок работы с файлом по `standards.md`: положить → сохранить → при
      неудаче убрать; заменить → переписать ключ → удалить старый; при удалении
      записи удалить файл. Изображение пережимается, прочее — нет. Проверка:
      `TheoryServiceTest` — отказ Учителю, уборка файла при неудаче сохранения,
      исчезновение прежнего файла при замене и при удалении.
- [ ] 4.2 Добавить `TheoryService.materialsOn(TaxonomyNodeId)`: материалы узла
      и всех его предков, свои первыми, дальше от ближайшего предка к корню,
      внутри узла — по названию; отдавать записью `NodeTheory` с именем
      узла-источника и признаком «свой». Проверка: `TheoryInheritanceTest` —
      материал Раздела виден на Теме, материал Темы на Разделе не виден,
      брат не наследует, порядок соблюдён.
- [ ] 4.3 Добавить выдачу временной подписанной ссылки на файл материала.
      Проверка: случай в `TheoryServiceTest` — ссылка выдаётся, по ней читается
      содержимое.

## 5. Долг дерева

- [ ] 5.1 Завести `TheoryOnNode implements NodeContent`: `on` считает **свои**
      материалы узла и называет их числом, `requiringTopic` не переопределяется.
      Проверка: `TheoryGuardsTheTreeTest` — узел со своими материалами
      не удаляется, узел с одними унаследованными удаляется, потомок у Темы
      с материалами создаётся.
- [ ] 5.2 Тест `TaxonomyKnowsNothingOfTheoryTest` по образцу
      `TaxonomyKnowsNothingOfProblemsTest`: `TaxonomyService` о теории не знает,
      зависимость есть только у контроллера.

## 6. Экран

- [ ] 6.1 Добавить `Addresses.THEORY = "/theory"` и `TheoryController` — формы
      заведения и правки, сохранение, замена содержимого, удаление, переход
      по ссылке на файл; контроллер тонкий. Проверка:
      `TheoryControllerIsThinTest` по образцу `ProblemControllerIsThinTest`.
- [ ] 6.2 Шаблоны `templates/theory/form.html` и `templates/theory/material.html`
      по образцу `problem/`. Проверка: `ServerRenderedPageTest` зелёный.
- [ ] 6.3 Показать материалы выбранного узла в правой части экрана дерева:
      `TaxonomyController` получает `TheoryService`, `taxonomy/tree.html` —
      список с названием, видом содержимого и узлом-источником; список
      показывается и на Разделе. Проверка: `TheoryScreenTest` — список на Теме
      и на Разделе, унаследованное с источником, пустой узел, Учителю действий
      не предлагают.
- [ ] 6.4 Тест доступа `TheoryAccessTest` по образцу `ProblemAccessTest`:
      невошедший не видит материалов, Учитель читает, но не правит,
      Администратор правит.

## 7. Приёмка и документы

- [ ] 7.1 Прогнать `mvn clean package`. Проверка: сборка зелёная.
- [ ] 7.2 Проверить признак готовности карточки вручную: материал крепится
      и к Разделу, и к Теме, запрет «только листья» на теорию не действует —
      покрыто сценариями в `TheoryGuardsTheTreeTest` и `TheoryScreenTest`.
- [ ] 7.3 Правка документов контекста: `domain-model.md` — теория из замысла
      становится существующей сущностью, инвариант 9 подтверждается кодом;
      `CLAUDE.md` — раздел «Статус» и список возможностей `openspec/specs/`;
      `openspec/backlog.md` — строка 10 получает ✅ и ссылку на архив.
      `glossary.md` правки не требует: `TheoryMaterial` в нём уже назван.
      `architecture.md`, `standards.md`, `antipatterns.md` и `strategy.md`
      изменение не затрагивает.
- [ ] 7.4 Перевести [ADR-0032](../../context/adr/0032-teoriya-na-odnom-uzle-i-nasledovanie.md)
      и [ADR-0033](../../context/adr/0033-teoriya-ne-zamorazhivaetsya.md)
      в статус «Принято», пересобрать индекс:
      `python3 openspec/context/adr/build_index.py --check` зелёный.
