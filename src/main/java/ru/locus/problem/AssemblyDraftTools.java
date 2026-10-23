package ru.locus.problem;

import static ru.locus.problem.PdfAssembly.PreviewSize.NORMAL;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import ru.locus.assistant.Arguments;
import ru.locus.assistant.Schema;
import ru.locus.assistant.Tool;
import ru.locus.assistant.ToolResult;
import ru.locus.assistant.Tools;

/**
 * Инструменты Помощника над Черновиками сборки: список своих, страница
 * картинкой и кусок страницы по Рамке (ADR-0056; locus-mcp, design.md,
 * Р4, Р7, Р8).
 *
 * <p>Черновик — личное у загрузившего (ADR-0044), но личное здесь держит
 * не инструмент, а {@link AssemblyDraftService}, как и у
 * {@link AssemblyDraftController}: владельца он берёт из вошедшего, права
 * проверяет его {@code @PreAuthorize}, а чужой черновик отдаёт тем же
 * пустым, что и несуществующий. Инструмент только переводит аргументы
 * и укладывает ответ. Отказ у чужого, несуществующего и страницы вне
 * черновика один, и идентификатора в его тексте нет: по ответу нельзя
 * узнать, что черновик с таким номером у кого-то есть.
 *
 * <p>Загрузки здесь нет: у процесса в клетке нет файлов (ADR-0057) —
 * черновик кладёт форма, а Помощник находит его списком (design.md, Р4).
 */
@Component
public class AssemblyDraftTools implements Tools {

    /** Один отказ на чужой черновик, несуществующий и страницу вне черновика. */
    static final String NOT_FOUND =
            "Черновика сборки с таким идентификатором у вас нет либо в нём нет такой страницы";

    private final AssemblyDraftService drafts;

    public AssemblyDraftTools(AssemblyDraftService drafts) {
        this.drafts = drafts;
    }

    @Override
    public List<Tool> tools() {
        return List.of(listDrafts(), getPage(), cropPage());
    }

    private Tool listDrafts() {
        return new Tool("list_assembly_drafts",
                "Черновики сборки вызывающего (только Администратор) в порядке загрузки: "
                        + "идентификатор, имя исходника, вид (IMAGE — картинка, PDF — сборник) "
                        + "и число страниц. Черновик — снимок листа или PDF, загруженный на странице "
                        + "Локуса; из его страниц и кусков собираются PDF Задачи в create_problem. "
                        + "Чужих черновиков в списке нет.",
                Schema.object().build(),
                arguments -> ToolResult.json(drafts.own().stream()
                        .map(draft -> new Entry(draft.id().value(), draft.originalName(),
                                draft.kind().name(), draft.pageCount()))
                        .toList()));
    }

    private Tool getPage() {
        return new Tool("get_assembly_draft_page",
                "Страница своего Черновика сборки картинкой JPEG — как её видит Администратор "
                        + "на странице сборки: с поворотом и по видимой области. Мелкое — крупнее "
                        + "в crop_assembly_draft_page.",
                Schema.object()
                        .property("draft", Schema.integer("идентификатор Черновика сборки из list_assembly_drafts"))
                        .property("page", Schema.integer("номер страницы, с единицы; у картинки — 1"))
                        .required("draft", "page")
                        .build(),
                arguments -> {
                    AssemblyDraftId id = new AssemblyDraftId(arguments.requireLong("draft"));
                    int page = arguments.requireInt("page");
                    return drafts.preview(id, page, NORMAL)
                            .map(ToolResult::image)
                            .orElse(ToolResult.refusal(NOT_FOUND));
                });
    }

    private Tool cropPage() {
        return new Tool("crop_assembly_draft_page",
                "Кусок страницы своего Черновика сборки по Рамке — картинкой JPEG крупнее, чем "
                        + "та же область на целой странице: так читается мелкий почерк. Рамка — доли "
                        + "ширины и высоты страницы от левого верхнего угла, как страница показана "
                        + "в get_assembly_draft_page; та же Рамка в строке create_problem кладёт этот "
                        + "кусок в PDF Задачи.",
                Schema.object()
                        .property("draft", Schema.integer("идентификатор Черновика сборки из list_assembly_drafts"))
                        .property("page", Schema.integer("номер страницы, с единицы; у картинки — 1"))
                        .property("frame", frameSchema())
                        .required("draft", "page", "frame")
                        .build(),
                arguments -> {
                    AssemblyDraftId id = new AssemblyDraftId(arguments.requireLong("draft"));
                    int page = arguments.requireInt("page");
                    CropFrame frame = frame(arguments.requireObject("frame"));
                    return drafts.previewPiece(id, page, frame)
                            .map(ToolResult::image)
                            .orElse(ToolResult.refusal(NOT_FOUND));
                });
    }

    /** Схема Рамки — одна на кусок страницы здесь и на строку сборки в {@code create_problem}. */
    static Map<String, Object> frameSchema() {
        return Schema.object()
                .description("Рамка — кусок страницы долями её ширины и высоты от левого верхнего угла, "
                        + "как страница показана в get_assembly_draft_page")
                .property("left", Schema.number("отступ слева, доля ширины страницы от 0 до 1"))
                .property("top", Schema.number("отступ сверху, доля высоты страницы от 0 до 1"))
                .property("width", Schema.number("ширина куска, доля ширины страницы"))
                .property("height", Schema.number("высота куска, доля высоты страницы"))
                .required("left", "top", "width", "height")
                .build();
    }

    /** Рамка из вложенного объекта аргументов; вышедшую за страницу отклоняет сама Рамка. */
    static CropFrame frame(Arguments frame) {
        return new CropFrame(frame.requireDouble("left"), frame.requireDouble("top"),
                frame.requireDouble("width"), frame.requireDouble("height"));
    }

    /** Черновик для модели: идентификатор, имя исходника, вид и число страниц. */
    public record Entry(long id, String name, String kind, int pages) {
    }
}
