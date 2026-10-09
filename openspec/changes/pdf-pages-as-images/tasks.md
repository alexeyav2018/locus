# Tasks

Документы `openspec/context/`, которые изменение затрагивает: ADR-0052
(новая, заведена в propose; при архивации — «Принято»), `architecture.md`
(у хранилища есть чтение; PDF на странице просмотра — картинками),
`ux-concept.md` (абзац о встроенном PDF и запасной кнопке),
`CLAUDE.md` (статус и сводка решений — при архивации). `glossary.md`,
`domain-model.md`, `scenarios.md`, `strategy.md`, `standards.md`,
`antipatterns.md` — не затрагивают.

## 1. Чтение из хранилища

- [ ] 1.1 `FileStorage.read(FileKey)` → `Optional<byte[]>` с javadoc
      (пусто — «файла нет», недоступность — `FileStorageUnavailableException`);
      поправить javadoc интерфейса о «трёх операциях» и ссылку на ADR-0052
- [ ] 1.2 `LocalFileStorage.read` — публичная реализация (javadoc
      «не часть FileStorage» убрать); `ObjectFileStorage.read` — `GetObject`,
      `NoSuchKeyException` → пусто, `SdkException` → недоступность
- [ ] 1.3 `FileStorageContractTest`: положенный файл читается тем же
      содержимым; удалённый и отсутствующий — пусто; `UnavailableStorageTest`:
      чтение при недоступном хранилище — исключение, а не пусто
- [ ] 1.4 `mvn test -Dtest='*FileStorage*Test,UnavailableStorageTest,OwnerIsUnknownToStorageTest'` зелёный

## 2. Отрисовщик страниц

- [ ] 2.1 `ru.locus.file.PdfPages`: `outline(byte[])` → `Optional<PdfOutline>`
      (общее число страниц, размеры первых `SHOWN = 20` в пикселях
      отрисовки; неразбираемый и запароленный — пусто) и
      `render(byte[], int page)` → JPEG (ширина ≤ 1600 px, ≤ 200 dpi,
      по видимой области и с поворотом; страницы нет — пусто);
      проверить — `PdfPagesTest`: число страниц, размеры, предел ширины
      и dpi, мусор вместо PDF, страница вне файла
- [ ] 2.2 `ru.locus.file.FilePages` (компонент над `FileStorage`
      и `PdfPages`): `outline(FileKey)`, `page(FileKey, int)` и
      `etag(FileKey)` (хеш ключа, не сам ключ); файла нет в хранилище —
      пусто; проверить — `FilePagesTest` на файловом хранилище

## 3. Картинки страниц на странице просмотра

- [ ] 3.1 `FileView`: для PDF — список страниц (адрес, ширина, высота),
      общее число страниц, признак «не разобрался»; фабрика принимает
      адрес страницы просмотра для построения адресов картинок
- [ ] 3.2 Обработчики картинки страницы: `ProblemController`
      (`/condition/pages/{n}`, `/solution/pages/{n}`), `TheoryController`
      (`/view/pages/{n}`), `StudentWorkController`
      (`/files/{fileId}/pages/{n}`); запись — через свой сервис, как
      у страницы просмотра; ответ `image/jpeg`, `Cache-Control: private,
      no-cache`, `ETag`, `304` по `If-None-Match` до чтения файла; всё
      отсутствующее — `404`
- [ ] 3.3 `file/viewer.html`: столбец `<img loading="lazy">` вместо
      `<object>`, строка «Показаны первые 20 из N страниц», сообщение
      «Показать этот PDF на странице не удалось», «Открыть PDF» всегда;
      правила `.viewer-pages` в `locus.css`, правило `.viewer object` убрать
- [ ] 3.4 Тесты страницы просмотра: PDF Задачи, теории и Работы — есть
      `<img` страниц и нет `<object`; длинный PDF (30 страниц) — 20 картинок
      и «из 30»; неразбираемый PDF теории — сообщение и «Открыть PDF»
- [ ] 3.5 Тесты адресов картинок: страница Задачи, теории и своей Работы —
      `200 image/jpeg`; **картинка страницы чужой Работы — 404, как
      несуществующей** (изоляция по владельцу); страница вне файла — 404;
      повтор с `If-None-Match` — 304; после замены условия Задачи `ETag`
      другой

## 4. Рамка, документы, приёмка

- [ ] 4.1 `SecurityConfig`: `frameOptions().deny()` и комментарий;
      `FileViewerScreenTest` — проверка заголовка на `DENY`
- [ ] 4.2 `architecture.md`: у `FileStorage` есть чтение (отрисовка
      страниц и отдача файловым хранилищем), PDF на странице просмотра —
      картинками со ссылкой на ADR-0052; `ux-concept.md` — абзац о PDF
      на странице просмотра
- [ ] 4.3 `mvn clean package` зелёный
- [ ] 4.4 Поднять версию в `pom.xml` до 3.1.0 и дописать в `CHANGELOG.md`
      строку из `proposal.md`, раздел «Версия»
