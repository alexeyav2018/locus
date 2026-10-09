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
    //    problem/assembly :: row, отказ — фрагментом с кодом 422. Токен CSRF
    //    берётся из скрытого поля своей формы.
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
        // Поле слота одно (ADR-0049) и носит имя слота, чтобы без скрипта
        // отправиться готовым PDF. Со скриптом файл уходит только в черновик:
        // имя снимается, иначе форма понесла бы его ещё и готовым файлом,
        // и сервер отклонил бы слот с двумя способами сразу.
        input.removeAttribute('name');
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
                row.after(copyOf(row));
                break;
            case 'remove':
                row.remove();
                break;
        }
    });

    // 6. Рамка у строки сборки (ADR-0045): показать страницу черновика и обвести
    //    на ней кусок мышью или пальцем. Скрипт только пишет доли «л;в;ш;в»
    //    в скрытое поле строки; что рамка в пределах страницы и стоит на одной
    //    странице, проверяет сервер. Без скрипта поле пустое — целые страницы.
    var MOST_PAGES_TO_SPLIT = 10;

    function field(row, suffix) {
        return row.querySelector('input[name$="' + suffix + '"]');
    }

    function pageOf(row) {
        return field(row, 'From').value;
    }

    function stageOf(row) {
        return row.querySelector('.crop-stage');
    }

    function note(row, text) {
        var old = row.querySelector('.crop-note');
        if (old) {
            old.remove();
        }
        if (text) {
            var hint = document.createElement('p');
            hint.className = 'hint crop-note';
            hint.textContent = text;
            stageOf(row).before(hint);
        }
    }

    function drawFrame(row) {
        var stage = stageOf(row);
        var frame = stage.querySelector('.crop-frame');
        var parts = field(row, 'Crop').value.split(';').map(Number);
        var whole = parts.length !== 4 || parts.some(isNaN);
        frame.hidden = whole;
        stage.querySelector('.crop-whole').hidden = !whole;
        if (!whole) {
            frame.style.left = parts[0] * 100 + '%';
            frame.style.top = parts[1] * 100 + '%';
            frame.style.width = parts[2] * 100 + '%';
            frame.style.height = parts[3] * 100 + '%';
        }
    }

    function showPage(row) {
        var stage = stageOf(row);
        var image = stage.querySelector('img');
        if (image) {
            image.src = row.dataset.previewUrl + pageOf(row) + (stage.dataset.large ? '?large=true' : '');
        }
    }

    // Масштаб сцены (crop-frame-zoom, design.md, решение 2): при 100 % страница
    // вписана в окно, при большем — холсту задаётся ширина «вписанная × масштаб»,
    // окно прокручивается. Рамка стоит в процентах холста, и масштаб её не сбивает.
    var ZOOM_STEPS = [1, 1.5, 2, 3, 4];
    var HANDLES = ['nw', 'n', 'ne', 'e', 'se', 's', 'sw', 'w'];

    function zoom(row, step) {
        var stage = stageOf(row);
        var viewport = stage.querySelector('.crop-viewport');
        var canvas = stage.querySelector('.crop-canvas');
        var from = parseInt(stage.dataset.zoom || '0', 10);
        var to = Math.min(ZOOM_STEPS.length - 1, Math.max(0, from + step));
        if (from === 0) {
            stage.dataset.fit = canvas.getBoundingClientRect().width;
        }
        var fit = parseFloat(stage.dataset.fit);
        if (to === from || !(fit > 0)) {
            return;
        }
        var middleX = (viewport.scrollLeft + viewport.clientWidth / 2) / viewport.scrollWidth;
        var middleY = (viewport.scrollTop + viewport.clientHeight / 2) / viewport.scrollHeight;
        stage.dataset.zoom = to;
        canvas.classList.toggle('crop-zoomed', to > 0);
        canvas.style.width = to > 0 ? fit * ZOOM_STEPS[to] + 'px' : '';
        if (to > 0 && !stage.dataset.large) {
            stage.dataset.large = 'true';
            showPage(row);
        }
        viewport.scrollLeft = middleX * viewport.scrollWidth - viewport.clientWidth / 2;
        viewport.scrollTop = middleY * viewport.scrollHeight - viewport.clientHeight / 2;
        stage.querySelector('.crop-zoom').textContent = ZOOM_STEPS[to] * 100 + ' %';
        stage.querySelector('[data-crop-zoom="-1"]').disabled = to === 0;
        stage.querySelector('[data-crop-zoom="1"]').disabled = to === ZOOM_STEPS.length - 1;
    }

    // Режим (решение 3): «Рисовать» — касание ставит рамку, «Двигать» —
    // касание прокручивает окно силами браузера, мышь тянет прокрутку скриптом.
    function mode(row, moving) {
        var stage = stageOf(row);
        stage.querySelector('.crop-canvas').classList.toggle('crop-moving', moving);
        stage.querySelectorAll('[data-crop-mode]').forEach(function (button) {
            button.setAttribute('aria-pressed', String((button.dataset.cropMode === 'move') === moving));
        });
    }

    function openStage(row) {
        var stage = stageOf(row);
        if (!stage.firstChild) {
            stage.innerHTML = '<div class="crop-toolbar">'
                + '<button type="button" class="btn-sm" data-crop-zoom="-1" aria-label="Уменьшить" disabled>−</button>'
                + '<span class="crop-zoom" aria-live="polite">100 %</span>'
                + '<button type="button" class="btn-sm" data-crop-zoom="1" aria-label="Увеличить">+</button>'
                + '<span class="crop-mode" role="group" aria-label="Режим">'
                + '<button type="button" class="btn-sm" data-crop-mode="draw" aria-pressed="true">Рисовать</button>'
                + '<button type="button" class="btn-sm" data-crop-mode="move" aria-pressed="false">Двигать</button>'
                + '</span></div>'
                + '<div class="crop-viewport"><div class="crop-canvas">'
                + '<img alt="страница не показывается" draggable="false">'
                + '<div class="crop-frame" hidden>'
                + HANDLES.map(function (handle) {
                    return '<span class="crop-handle" data-handle="' + handle + '"></span>';
                }).join('')
                + '</div></div></div>'
                + '<p class="hint crop-whole">Рамки нет — берётся вся страница.</p>';
            listenForFrame(row, stage.querySelector('.crop-viewport'), stage.querySelector('.crop-canvas'));
        }
        stage.hidden = false;
        showPage(row);
        drawFrame(row);
    }

    function listenForFrame(row, viewport, canvas) {
        var start = null;
        var drag = null;
        var handle = null;

        function share(event) {
            var box = canvas.getBoundingClientRect();
            return {
                x: Math.min(1, Math.max(0, (event.clientX - box.left) / box.width)),
                y: Math.min(1, Math.max(0, (event.clientY - box.top) / box.height))
            };
        }

        // Рамка из двух противоположных углов: перетянутая через сторону
        // выворачивается, мельче 0,5 % не записывается.
        function put(a, b) {
            var frame = [Math.min(a.x, b.x), Math.min(a.y, b.y),
                Math.abs(b.x - a.x), Math.abs(b.y - a.y)];
            if (frame[2] > 0.005 && frame[3] > 0.005) {
                field(row, 'Crop').value = frame.map(function (value) {
                    return value.toFixed(4);
                }).join(';');
                drawFrame(row);
            }
        }

        // Ручка (решение 4): угол двигает две стороны, сторона — одну;
        // противоположные стороны держатся на месте.
        function pull(event) {
            var point = share(event);
            var a = {x: handle.left, y: handle.top};
            var b = {x: handle.right, y: handle.bottom};
            if (handle.name.indexOf('w') >= 0) {
                a.x = point.x;
            }
            if (handle.name.indexOf('e') >= 0) {
                b.x = point.x;
            }
            if (handle.name.indexOf('n') >= 0) {
                a.y = point.y;
            }
            if (handle.name.indexOf('s') >= 0) {
                b.y = point.y;
            }
            put(a, b);
        }

        canvas.addEventListener('pointerdown', function (event) {
            if (event.button !== 0) {
                return;
            }
            var grip = event.target.closest('.crop-handle');
            if (grip) {
                var parts = field(row, 'Crop').value.split(';').map(Number);
                handle = {name: grip.dataset.handle, left: parts[0], top: parts[1],
                    right: parts[0] + parts[2], bottom: parts[1] + parts[3]};
            } else if (canvas.classList.contains('crop-moving')) {
                if (event.pointerType !== 'mouse') {
                    return;
                }
                drag = {x: event.clientX, y: event.clientY, left: viewport.scrollLeft, top: viewport.scrollTop};
            } else {
                start = share(event);
            }
            event.preventDefault();
            canvas.setPointerCapture(event.pointerId);
        });
        canvas.addEventListener('pointermove', function (event) {
            if (handle) {
                pull(event);
            } else if (drag) {
                viewport.scrollLeft = drag.left - (event.clientX - drag.x);
                viewport.scrollTop = drag.top - (event.clientY - drag.y);
            } else if (start) {
                put(start, share(event));
            }
        });
        ['pointerup', 'pointercancel'].forEach(function (type) {
            canvas.addEventListener(type, function (event) {
                if (type === 'pointerup') {
                    if (handle) {
                        pull(event);
                    } else if (start) {
                        put(start, share(event));
                    }
                }
                start = null;
                drag = null;
                handle = null;
            });
        });
    }

    // Копия строки — без рамки и без открытой сцены: второй кусок той же
    // страницы обводится заново.
    function copyOf(row) {
        var copy = row.cloneNode(true);
        field(copy, 'Crop').value = '';
        var stage = stageOf(copy);
        stage.innerHTML = '';
        stage.hidden = true;
        delete stage.dataset.zoom;
        delete stage.dataset.fit;
        delete stage.dataset.large;
        var hint = copy.querySelector('.crop-note');
        if (hint) {
            hint.remove();
        }
        return copy;
    }

    // «Обвести» на диапазоне разбивает его на постраничные строки: рамка стоит
    // на одной странице, а молча выбросить остальные страницы диапазона нельзя.
    function crop(row) {
        var from = parseInt(field(row, 'From').value, 10);
        var to = parseInt(field(row, 'To').value, 10);
        if (isNaN(from) || isNaN(to) || from >= to) {
            if (!isNaN(from)) {
                field(row, 'To').value = from;
            }
            note(row, '');
            openStage(row);
            return;
        }
        if (to - from + 1 > MOST_PAGES_TO_SPLIT) {
            note(row, 'Обвести можно не больше ' + MOST_PAGES_TO_SPLIT
                + ' страниц за раз: сузьте диапазон «с … по …».');
            return;
        }
        var last = row;
        for (var page = from; page <= to; page++) {
            var single = copyOf(row);
            field(single, 'From').value = page;
            field(single, 'To').value = page;
            last.after(single);
            last = single;
            openStage(single);
        }
        row.remove();
    }

    document.addEventListener('click', function (event) {
        var button = event.target.closest && event.target.closest('button[data-assembly]');
        if (!button) {
            return;
        }
        var row = button.closest('li.assembly-row');
        if (button.dataset.assembly === 'crop') {
            crop(row);
        } else if (button.dataset.assembly === 'whole') {
            field(row, 'Crop').value = '';
            if (!stageOf(row).hidden) {
                drawFrame(row);
            }
        }
    });

    document.addEventListener('click', function (event) {
        var button = event.target.closest && event.target.closest('button[data-crop-zoom], button[data-crop-mode]');
        if (!button) {
            return;
        }
        var row = button.closest('li.assembly-row');
        if (button.dataset.cropZoom) {
            zoom(row, parseInt(button.dataset.cropZoom, 10));
        } else {
            mode(row, button.dataset.cropMode === 'move');
        }
    });

    // Правка «с» или «по» при открытой сцене: рамка от другой страницы
    // бессмысленна — второе поле выравнивается, картинка меняется, рамка снимается.
    document.addEventListener('change', function (event) {
        var input = event.target;
        var row = input.closest && input.closest('li.assembly-row');
        if (!row || !/(From|To)$/.test(input.name) || stageOf(row).hidden) {
            return;
        }
        field(row, /From$/.test(input.name) ? 'To' : 'From').value = input.value;
        field(row, 'Crop').value = '';
        showPage(row);
        drawFrame(row);
    });

    // Форма, перерисованная после отказа, показывает поставленные рамки.
    document.querySelectorAll('li.assembly-row').forEach(function (row) {
        if (field(row, 'Crop') && field(row, 'Crop').value) {
            openStage(row);
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
