#!/usr/bin/env python3
"""Проверка openspec/backlog.md по инвариантам навыка backlog-maintenance.

Навык и проверялка правятся вместе: номер формата записан в обоих
(SKILL.md, metadata.format, и FORMAT ниже). Проверяется то, что проверяемо
механически, — инварианты 1–4 и 6 из §7 навыка; инвариант 5 (дуга прозой)
проверяется только чтением.

    python3 check_backlog.py [путь]        проверить файл (по умолчанию
                                           openspec/backlog.md от текущего каталога)
    python3 check_backlog.py --hook        для хука PostToolUse: вход хука
                                           со стандартного ввода, нарушения —
                                           в stderr с кодом 2
    python3 check_backlog.py --session-start
                                           для хука SessionStart: нарушения —
                                           в stdout с кодом 0

Коды возврата обычного режима: 0 — чисто, 1 — нарушения инвариантов,
3 — номера формата нет или он младше навыка, 4 — формат старше навыка.
"""

import json
import os
import re
import sys
from pathlib import Path

FORMAT = 2
DEFAULT_PATH = "openspec/backlog.md"

FORMAT_RE = re.compile(r"^<!--\s*backlog-format:\s*(\d+)\s*-->\s*$", re.M)
TABLE_SECTION = "## 3. Таблица порядка"
CARDS_SECTION = "## 4. Карточки"
COLUMNS = ["ID", "Класс", "Зависимость", "Не позже", "Стоим.", "Ломает", None, "Статус"]
ID_RE = re.compile(r"^`([a-z0-9][a-z0-9-]*)`$")
ARC_RE = re.compile(r"^`[a-z0-9][a-z0-9-]*`(, `[a-z0-9][a-z0-9-]*`)*$")
CLASSES = {"Л", "Б", "Т", "Д", "—"}
COSTS = {"XS", "S", "M", "L", "XL"}
BREAKS = {"нет": "не ломает", "**да**": "**ломает**"}
STATUSES = {"", "отложено", "ждёт условия", "построено", "на стенде", "отменено", "✅"}
DATE = r"\d{2}\.\d{2}\.\d{4}"
DONE_RE = re.compile(rf"^✅ Выполнено {DATE}(?:, версия \d+\.\d+\.\d+)?\. Change: \[[^\]]+\]\((changes/archive/[^)]+?)/?\)")
CLOSED_RE = re.compile(rf"^✅ Закрыто {DATE} без изменения")
ARCHIVE_PREFIX_RE = re.compile(r"^\d{4}-\d{2}-\d{2}-")

errors = []


def fail(place, message):
    errors.append(f"{place}: {message}")


class FormatMismatch(Exception):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


def check_format(text):
    head = text.split("\n## ", 1)[0]
    match = FORMAT_RE.search(head)
    if not match:
        raise FormatMismatch(3, f"в шапке нет строки <!-- backlog-format: N -->; навык умеет формат {FORMAT}"
                                f" — нужна миграция (§8 навыка), только с согласия пользователя")
    found = int(match.group(1))
    if found < FORMAT:
        raise FormatMismatch(3, f"формат {found} младше навыка ({FORMAT}) — нужна миграция (§8 навыка),"
                                f" только с согласия пользователя")
    if found > FORMAT:
        raise FormatMismatch(4, f"формат {found} старше навыка ({FORMAT}) — навык устарел, файл не править")


def sections(text):
    """Разделы второго уровня: заголовок → текст до следующего."""
    parts = {}
    order = []
    for chunk in re.split(r"^(?=## )", text, flags=re.M)[1:]:
        title = chunk.split("\n", 1)[0].strip()
        parts[title] = chunk.split("\n", 1)[1] if "\n" in chunk else ""
        order.append(title)
    return parts, order


def split_row(line):
    return [cell.strip() for cell in line.strip().strip("|").split("|")]


def parse_table(body):
    lines = [line for line in body.splitlines() if line.strip().startswith("|")]
    if len(lines) < 2:
        fail(TABLE_SECTION, "таблицы нет")
        return []
    header = split_row(lines[0])
    if len(header) != len(COLUMNS):
        fail(TABLE_SECTION, f"в заголовке таблицы {len(header)} колонок, нужно {len(COLUMNS)}")
    else:
        for i, (got, want) in enumerate(zip(header, COLUMNS), 1):
            if want is not None and got != want:
                fail(TABLE_SECTION, f"колонка {i} называется {got!r}, нужно {want!r}")
    rows = []
    for number, line in enumerate(lines[2:], 1):
        cells = split_row(line)
        place = f"строка {number} таблицы"
        if len(cells) != len(COLUMNS):
            fail(place, f"{len(cells)} колонок, нужно {len(COLUMNS)}")
            continue
        match = ID_RE.match(cells[0])
        if not match:
            fail(place, f"ID {cells[0]!r} не в обратных кавычках или не из [a-z0-9-]")
            continue
        item = match.group(1)
        place = f"`{item}`"
        klass, deps, later, cost, breaks, _, status = cells[1:]
        if klass not in CLASSES:
            fail(place, f"класс {klass!r} не из {sorted(CLASSES)}")
        if cost not in COSTS:
            fail(place, f"стоимость {cost!r} не из XS, S, M, L, XL")
        if breaks not in BREAKS:
            fail(place, f"«Ломает» {breaks!r} — нужно «нет» или «**да**»")
        if status not in STATUSES:
            fail(place, f"статус {status!r} не из значений §5 навыка")
        arcs = {}
        for name, cell in (("Зависимость", deps), ("Не позже", later)):
            if cell == "—":
                arcs[name] = []
            elif ARC_RE.match(cell):
                arcs[name] = re.findall(r"`([^`]+)`", cell)
            else:
                fail(place, f"«{name}» {cell!r} — нужно «—» или ID в обратных кавычках через запятую")
                arcs[name] = []
        rows.append({"id": item, "class": klass, "cost": cost, "breaks": breaks, "status": status,
                     "deps": arcs["Зависимость"], "later": arcs["Не позже"]})
    return rows


def check_arcs(rows, archived):
    position = {}
    for index, row in enumerate(rows):
        if row["id"] in position:
            fail(f"`{row['id']}`", "ID повторяется в таблице")
        position.setdefault(row["id"], index)
    for index, row in enumerate(rows):
        place = f"`{row['id']}`"
        for name, targets, must_be_above in (("Зависимость", row["deps"], True),
                                             ("Не позже", row["later"], False)):
            for target in targets:
                if target == row["id"]:
                    fail(place, f"«{name}» указывает на саму строку")
                elif target in position:
                    above = position[target] < index
                    if above != must_be_above:
                        where = "выше" if must_be_above else "ниже"
                        fail(place, f"«{name}» `{target}` должна стоять {where} строки")
                elif target not in archived:
                    fail(place, f"«{name}» `{target}` — нет ни строки таблицы, ни change'а в архиве")


def parse_cards(body):
    cards = []
    blocks = re.split(r"^(?=### )", body, flags=re.M)
    preamble, blocks = blocks[0], blocks[1:]
    for number, block in enumerate(blocks):
        heading, _, text = block.partition("\n")
        previous = (preamble if number == 0 else blocks[number - 1]).rstrip().splitlines()
        cards.append({"heading": heading[4:].strip(), "text": text,
                      "separated": number == 0 or (previous and previous[-1].strip() == "---")})
    return cards


def check_heading(card, row):
    parts = [part.strip() for part in card["heading"].split(" · ")]
    place = f"`{row['id']}`"
    if parts[0] != f"`{row['id']}`":
        fail(place, f"заголовок карточки начинается не с ID: {card['heading']!r}")
        return
    want = ([] if row["class"] == "—" else [row["class"]]) + [row["cost"], BREAKS.get(row["breaks"], "?")]
    got = parts[1:1 + len(want)]
    if got != want:
        fail(place, f"заголовок карточки {' · '.join(got)!r} не зеркалит строку таблицы {' · '.join(want)!r}")
    for note in parts[1 + len(want):]:
        if note in STATUSES - {""} or "✅" in note:
            fail(place, f"в заголовке карточки статус {note!r} — статус живёт только в таблице")


def first_quote(text):
    """Первый абзац карточки, если он блок-цитата, — одной строкой."""
    lines = []
    for line in text.splitlines():
        stripped = line.strip()
        if not lines and not stripped:
            continue
        if not stripped.startswith(">"):
            break
        lines.append(stripped[1:].strip())
    return " ".join(lines)


def check_closure(card, row, root):
    place = f"`{row['id']}`"
    quote = first_quote(card["text"])
    if row["status"] == "✅":
        done = DONE_RE.match(quote)
        if done:
            if not (root / done.group(1)).is_dir():
                fail(place, f"отметка о закрытии ссылается на {done.group(1)}, такой папки нет")
        elif not CLOSED_RE.match(quote):
            fail(place, "статус ✅, но карточка не начинается отметкой о закрытии (§4 навыка)")
    elif re.search(r"^>\s*✅ (Выполнено|Закрыто)", card["text"], re.M):
        fail(place, f"статус {row['status'] or 'пусто'!r}, но в карточке отметка о закрытии")


def check_cards(rows, cards, root):
    table_ids = [row["id"] for row in rows]
    card_ids = []
    for card in cards:
        match = re.match(r"^`([a-z0-9][a-z0-9-]*)`", card["heading"])
        card_ids.append(match.group(1) if match else None)
        if not match:
            fail(CARDS_SECTION, f"заголовок карточки не начинается с ID: {card['heading']!r}")
    for item in sorted(set(table_ids) - set(card_ids)):
        fail(f"`{item}`", "строка таблицы без карточки")
    for item in sorted(set(i for i in card_ids if i) - set(table_ids)):
        fail(f"`{item}`", "карточка без строки таблицы")
    for item in sorted(set(i for i in card_ids if i and card_ids.count(i) > 1)):
        fail(f"`{item}`", "карточка повторяется")
    common = set(table_ids) & set(card_ids)
    table_order = [i for i in table_ids if i in common]
    card_order = [i for i in card_ids if i in common]
    for want, got in zip(table_order, card_order):
        if want != got:
            fail(CARDS_SECTION, f"порядок карточек расходится с таблицей: на месте `{want}` стоит `{got}`")
            break
    by_id = {}
    for card, item in zip(cards, card_ids):
        if item:
            by_id.setdefault(item, card)
            if not card["separated"]:
                fail(f"`{item}`", "перед карточкой нет разделителя ---")
    for row in rows:
        card = by_id.get(row["id"])
        if card:
            check_heading(card, row)
            check_closure(card, row, root)


def check(path):
    """Код возврата и список сообщений."""
    errors.clear()
    text = path.read_text(encoding="utf-8")
    try:
        check_format(text)
    except FormatMismatch as mismatch:
        return mismatch.code, [str(mismatch)]
    parts, order = sections(text)
    for number in (1, 2):
        if not any(title.startswith(f"## {number}. ") for title in order):
            fail("структура", f"нет раздела «## {number}. …»")
    for title in (TABLE_SECTION, CARDS_SECTION):
        if title not in parts:
            fail("структура", f"нет раздела «{title}»")
    if TABLE_SECTION not in parts or CARDS_SECTION not in parts:
        return 1, list(errors)
    root = path.resolve().parent
    archive = root / "changes" / "archive"
    archived = ({ARCHIVE_PREFIX_RE.sub("", p.name) for p in archive.iterdir() if p.is_dir()}
                if archive.is_dir() else set())
    rows = parse_table(parts[TABLE_SECTION])
    check_arcs(rows, archived)
    check_cards(rows, parse_cards(parts[CARDS_SECTION]), root)
    return (1 if errors else 0), list(errors)


def project_backlog(hook_input):
    root = os.environ.get("CLAUDE_PROJECT_DIR") or hook_input.get("cwd") or os.getcwd()
    return Path(root) / DEFAULT_PATH


def report(path, code, messages):
    lines = [f"check_backlog: {path} — {'формат несовместим' if code in (3, 4) else 'нарушены инварианты'}"
             f" (навык backlog-maintenance, §7):"]
    return "\n".join(lines + [f"  {message}" for message in messages])


def main(argv):
    mode = None
    paths = []
    for arg in argv:
        if arg in ("--hook", "--session-start"):
            mode = arg
        elif arg in ("-h", "--help"):
            print(__doc__)
            return 0
        else:
            paths.append(arg)
    if mode is None:
        path = Path(paths[0] if paths else DEFAULT_PATH)
        if not path.is_file():
            print(f"check_backlog: файла {path} нет", file=sys.stderr)
            return 1
        code, messages = check(path)
        if messages:
            print(report(path, code, messages), file=sys.stderr)
        return code

    raw = sys.stdin.read() if not sys.stdin.isatty() else ""
    try:
        hook_input = json.loads(raw) if raw.strip() else {}
    except json.JSONDecodeError:
        hook_input = {}
    if mode == "--hook" and DEFAULT_PATH not in json.dumps(hook_input.get("tool_input", {}), ensure_ascii=False):
        return 0
    path = project_backlog(hook_input)
    if not path.is_file():
        return 0
    code, messages = check(path)
    if not messages:
        return 0
    if mode == "--hook":
        print(report(path, code, messages), file=sys.stderr)
        return 2
    print(report(path, code, messages))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
