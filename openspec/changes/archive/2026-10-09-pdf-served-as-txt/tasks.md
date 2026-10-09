## 1. Имя файла в отдаче

- [x] 1.1 Тесты в `LocalFileStorageTest`: ответ на ссылку PDF несёт `Content-Disposition` `inline` с именем-ключом `*.pdf`, не `f.txt`; картинка — `inline` с именем `*.jpg`; красные до правки
- [x] 1.2 `FileController.serve` ставит `Content-Disposition: inline` с именем-ключом через `ContentDisposition.inline().filename(...)`; проверка — `get_diagnostics_for_file`
- [x] 1.3 `mvn test -Dtest='LocalFileStorageTest,ObjectFileStorageTest,WorkFilesTest'` зелёный
- [x] 1.4 `mvn clean package` зелёный; документы `openspec/context/` и `CLAUDE.md` не затрагиваются — проверить, что правок нет
- [x] 1.5 Поднять версию в `pom.xml` до 3.0.1 и дописать в `CHANGELOG.md` строку из `proposal.md`, раздел «Версия»
