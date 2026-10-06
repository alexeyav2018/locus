/*
 * Locus — точечные улучшения поверх серверных страниц (ADR-0020, ADR-0041).
 * Без этого файла каждая страница работает: он ничего не добавляет к функциям,
 * только убирает неудобство. Библиотек и сборки нет.
 */
(function () {
    'use strict';

    // 0. Признак «скрипт работает»: по нему показываются кнопки «Отмена» в блоках.
    document.documentElement.classList.add('js');
    document.addEventListener('click', function (event) {
        var close = event.target.closest && event.target.closest('[data-close]');
        if (close) {
            var block = close.closest('details');
            if (block) {
                block.open = false;
                var summary = block.querySelector('summary');
                if (summary) {
                    summary.focus();
                }
            }
        }
    });

    // 1. Подтверждение необратимых действий: <form data-confirm="Это необратимо.">.
    //    Вопрос называет действие словами с кнопки, а текст берёт из атрибута.
    document.addEventListener('submit', function (event) {
        var form = event.target;
        var note = form.getAttribute && form.getAttribute('data-confirm');
        if (!note) {
            return;
        }
        var label = event.submitter ? event.submitter.textContent.trim() : 'Продолжить';
        if (!window.confirm('«' + label + '». ' + note + ' Продолжить?')) {
            event.preventDefault();
        }
    });

    // 2. Список с множественным выбором: клик по пункту отмечает и снимает его
    //    без Ctrl/⌘ — иначе на компьютере выбор нескольких значений неочевиден.
    document.querySelectorAll('select[multiple]').forEach(function (select) {
        select.addEventListener('mousedown', function (event) {
            var option = event.target;
            if (option.tagName !== 'OPTION' || event.shiftKey) {
                return;
            }
            event.preventDefault();
            var top = select.scrollTop;
            option.selected = !option.selected;
            select.scrollTop = top;
            select.dispatchEvent(new Event('change', {bubbles: true}));
        });
        if (!select.parentElement.querySelector('.multi-hint') && !select.closest('[data-no-hint]')) {
            var hint = document.createElement('p');
            hint.className = 'hint multi-hint';
            hint.textContent = 'Можно отметить несколько: нажимайте на нужные пункты.';
            select.insertAdjacentElement('afterend', hint);
        }
    });

    // 3. Выбранный пункт списка с выбором узла виден сразу: прокрутить к нему.
    document.querySelectorAll('select[multiple]').forEach(function (select) {
        var chosen = select.querySelector('option:checked');
        if (chosen) {
            select.scrollTop = Math.max(0, chosen.offsetTop - 8);
        }
    });

    // 4. Кнопка отправки не нажимается дважды: загрузка снимков идёт долго,
    //    и повторное нажатие приняло бы Работу второй раз.
    document.addEventListener('submit', function (event) {
        if (event.defaultPrevented) {
            return;
        }
        var form = event.target;
        if (form.method && form.method.toLowerCase() !== 'post') {
            return;
        }
        form.querySelectorAll('button[type=submit]').forEach(function (button) {
            window.setTimeout(function () {
                button.disabled = true;
            }, 0);
        });
    });

    // 5. Сборка PDF Задачи (ADR-0044): загрузить выбранные исходники и двигать
    //    строки. Правил здесь нет: строку рисует сервер фрагментом
    //    problem/assembly :: row, отказ — фрагментом с кодом 422. Поле исходника
    //    без имени и в отправку формы не попадает; токен CSRF берётся из скрытого
    //    поля своей формы.
    function clearRefusal(list) {
        var next = list.nextElementSibling;
        if (next && next.classList.contains('assembly-refusal')) {
            next.remove();
        }
    }

    function showRefusal(list, text) {
        clearRefusal(list);
        var refusal = document.createElement('p');
        refusal.className = 'assembly-refusal';
        refusal.setAttribute('role', 'alert');
        refusal.textContent = text;
        list.after(refusal);
    }

    function insertRow(list, html) {
        var template = document.createElement('template');
        template.innerHTML = html.trim();
        var node = template.content.firstElementChild;
        if (node && node.classList.contains('assembly-refusal')) {
            showRefusal(list, node.textContent);
        } else if (node) {
            clearRefusal(list);
            list.append(node);
        }
    }

    function uploadOne(input, list, file) {
        var body = new FormData();
        body.append('slot', input.dataset.assemblySource);
        body.append('source', file);
        var csrf = input.form.querySelector('input[name="_csrf"]');
        if (csrf) {
            body.append('_csrf', csrf.value);
        }
        return fetch(input.dataset.assemblyUrl, {method: 'POST', body: body, credentials: 'same-origin'})
            .then(function (response) {
                if (response.ok || response.status === 422) {
                    return response.text().then(function (html) {
                        insertRow(list, html);
                    });
                }
                if (response.status === 413) {
                    showRefusal(list, 'Файл «' + file.name + '» слишком велик для сборки');
                } else {
                    showRefusal(list, 'Файл «' + file.name + '» не загрузился (код ' + response.status + ')');
                }
            }, function () {
                showRefusal(list, 'Файл «' + file.name + '» не загрузился: нет связи с сервером');
            });
    }

    document.querySelectorAll('input[data-assembly-source]').forEach(function (input) {
        input.multiple = true;
        input.addEventListener('change', function () {
            var list = input.form.querySelector('ol.assembly[data-slot="' + input.dataset.assemblySource + '"]');
            // По одному и по порядку: строки встают в порядке выбора файлов.
            Array.from(input.files).reduce(function (previous, file) {
                return previous.then(function () {
                    return uploadOne(input, list, file);
                });
            }, Promise.resolve()).then(function () {
                input.value = '';
            });
        });
    });

    document.addEventListener('click', function (event) {
        var button = event.target.closest && event.target.closest('button[data-assembly]');
        if (!button) {
            return;
        }
        var row = button.closest('li.assembly-row');
        switch (button.dataset.assembly) {
            case 'up':
                if (row.previousElementSibling) {
                    row.previousElementSibling.before(row);
                }
                break;
            case 'down':
                if (row.nextElementSibling) {
                    row.nextElementSibling.after(row);
                }
                break;
            case 'again':
                row.after(row.cloneNode(true));
                break;
            case 'remove':
                row.remove();
                break;
        }
    });

    // Возврат кнопкой «назад» не должен оставлять кнопки погашенными.
    window.addEventListener('pageshow', function (event) {
        if (event.persisted) {
            document.querySelectorAll('button[type=submit]:disabled').forEach(function (button) {
                button.disabled = false;
            });
        }
    });
})();
