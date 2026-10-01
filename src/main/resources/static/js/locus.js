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

    // Возврат кнопкой «назад» не должен оставлять кнопки погашенными.
    window.addEventListener('pageshow', function (event) {
        if (event.persisted) {
            document.querySelectorAll('button[type=submit]:disabled').forEach(function (button) {
                button.disabled = false;
            });
        }
    });
})();
