#!/usr/bin/env python3
"""Сборка индекса журнала ADR и проверка его целостности.

Источник правды — frontmatter каждой записи NNNN-*.md. Скрипт пересобирает
таблицу между маркерами BEGIN INDEX / END INDEX в README.md и проверяет то,
что глазами не ловится: нумерацию, статусы, ссылки и двусторонность связей.

    python3 build_index.py            пересобрать индекс
    python3 build_index.py --check    только проверить, ничего не писать
                                      (ненулевой код возврата, если расходится)
"""

import re
import sys
from datetime import date
from pathlib import Path

ADR_DIR = Path(__file__).resolve().parent
README = ADR_DIR / "README.md"
BEGIN = "<!-- BEGIN INDEX: сгенерировано build_index.py, руками не править -->"
END = "<!-- END INDEX -->"

STATUSES = {"Предложено", "Принято", "Заменено", "Отменено"}
AREAS = {"продукт", "модель", "техника", "процесс"}
FILENAME_RE = re.compile(r"^(\d{4})-[a-z0-9-]+\.md$")
LIST_RE = re.compile(r"^\[(.*)\]$")

errors = []


def fail(record, message):
    errors.append(f"{record}: {message}")


def parse_frontmatter(path):
    text = path.read_text(encoding="utf-8")
    if not text.startswith("---\n"):
        fail(path.name, "нет frontmatter")
        return None
    end = text.find("\n---\n", 4)
    if end == -1:
        fail(path.name, "frontmatter не закрыт")
        return None
    fields = {}
    for line in text[4:end].split("\n"):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if ":" not in line:
            fail(path.name, f"строка frontmatter без двоеточия: {line!r}")
            continue
        key, _, value = line.partition(":")
        fields[key.strip()] = value.strip().strip('"').strip("'")
    return fields


def parse_ids(record, key, raw):
    match = LIST_RE.match(raw or "[]")
    if not match:
        fail(record, f"поле {key} должно быть списком в квадратных скобках, получено {raw!r}")
        return []
    body = match.group(1).strip()
    if not body:
        return []
    ids = []
    for chunk in body.split(","):
        chunk = chunk.strip()
        if not chunk.isdigit():
            fail(record, f"поле {key}: {chunk!r} не число")
            continue
        ids.append(int(chunk))
    return ids


def load():
    records = {}
    for path in sorted(ADR_DIR.glob("*.md")):
        if path.name in ("README.md", "TEMPLATE.md"):
            continue
        match = FILENAME_RE.match(path.name)
        if not match:
            fail(path.name, "имя файла не в формате NNNN-краткое-имя.md")
            continue
        fields = parse_frontmatter(path)
        if fields is None:
            continue
        record = path.name

        raw_id = fields.get("id", "")
        if not raw_id.isdigit():
            fail(record, f"поле id отсутствует или не число: {raw_id!r}")
            continue
        adr_id = int(raw_id)
        if adr_id != int(match.group(1)):
            fail(record, f"id={adr_id} не совпадает с номером в имени файла {match.group(1)}")

        title = fields.get("title", "").strip()
        if not title:
            fail(record, "пустое поле title")

        status = fields.get("status", "")
        if status not in STATUSES:
            fail(record, f"недопустимый статус {status!r}; допустимы: {', '.join(sorted(STATUSES))}")

        area = fields.get("area", "")
        if area not in AREAS:
            fail(record, f"недопустимая область {area!r}; допустимы: {', '.join(sorted(AREAS))}")

        raw_date = fields.get("date", "")
        try:
            parsed_date = date.fromisoformat(raw_date)
            if parsed_date > date.today():
                fail(record, f"дата {raw_date} из будущего")
        except ValueError:
            fail(record, f"дата {raw_date!r} не в формате ГГГГ-ММ-ДД")
            parsed_date = None

        supersedes = parse_ids(record, "supersedes", fields.get("supersedes"))
        superseded_by = parse_ids(record, "superseded_by", fields.get("superseded_by"))

        if status == "Заменено" and not superseded_by:
            fail(record, "статус «Заменено» требует непустого superseded_by")
        if status == "Отменено" and superseded_by:
            fail(record, "статус «Отменено» означает отсутствие замены, superseded_by должен быть пуст")
        if superseded_by and status not in ("Заменено", "Отменено"):
            fail(record, f"superseded_by заполнен, но статус {status!r} этого не отражает")

        if adr_id in records:
            fail(record, f"дубль номера {adr_id:04d}")
            continue

        records[adr_id] = {
            "id": adr_id,
            "file": path.name,
            "title": title,
            "status": status,
            "area": area,
            "date": parsed_date,
            "raw_date": raw_date,
            "supersedes": supersedes,
            "superseded_by": superseded_by,
        }
    return records


def check_links(records):
    if records:
        expected = set(range(1, max(records) + 1))
        missing = sorted(expected - set(records))
        if missing:
            errors.append("дыры в нумерации: " + ", ".join(f"{n:04d}" for n in missing))

    for adr_id, rec in sorted(records.items()):
        for key, mirror in (("supersedes", "superseded_by"), ("superseded_by", "supersedes")):
            for other in rec[key]:
                if other not in records:
                    fail(rec["file"], f"{key} ссылается на несуществующую запись {other:04d}")
                    continue
                if adr_id not in records[other][mirror]:
                    fail(
                        rec["file"],
                        f"односторонняя связь: {key}=[{other:04d}], "
                        f"но в {records[other]['file']} нет {mirror}=[{adr_id:04d}]",
                    )


def render(records):
    lines = [
        "| № | Название | Область | Статус | Дата |",
        "| --- | --- | --- | --- | --- |",
    ]
    for adr_id, rec in sorted(records.items()):
        status = rec["status"]
        if rec["superseded_by"]:
            status += " " + ", ".join(f"{n:04d}" for n in rec["superseded_by"])
        lines.append(
            f"| [{adr_id:04d}]({rec['file']}) | {rec['title']} | "
            f"{rec['area']} | {status} | {rec['raw_date']} |"
        )
    return "\n".join(lines)


def main():
    check_only = "--check" in sys.argv[1:]
    records = load()
    check_links(records)

    if errors:
        print("Журнал ADR не прошёл проверку:", file=sys.stderr)
        for line in errors:
            print("  - " + line, file=sys.stderr)
        return 1

    text = README.read_text(encoding="utf-8")
    if BEGIN not in text or END not in text:
        print(f"В {README.name} не найдены маркеры индекса", file=sys.stderr)
        return 1

    head, rest = text.split(BEGIN, 1)
    _, tail = rest.split(END, 1)
    updated = head + BEGIN + "\n" + render(records) + "\n" + END + tail

    if updated == text:
        print(f"Индекс актуален: записей — {len(records)}")
        return 0
    if check_only:
        print("Индекс в README.md разошёлся с записями; выполните build_index.py", file=sys.stderr)
        return 1

    README.write_text(updated, encoding="utf-8")
    print(f"Индекс пересобран: записей — {len(records)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
