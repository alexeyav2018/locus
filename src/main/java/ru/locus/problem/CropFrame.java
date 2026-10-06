package ru.locus.problem;

import java.util.Locale;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

/**
 * Рамка — прямоугольник, которым Администратор обводит на странице
 * источника нужный кусок (ADR-0045). В результат сборки кусок ложится
 * отдельной страницей.
 *
 * <p>Хранится долями ширины и высоты страницы <b>так, как её видит
 * Администратор</b>: с учётом поворота и видимой области, начало — левый
 * верхний угол. Доли, а не точки: рамка не зависит от разрешения показа
 * и одинаково годится для страницы PDF и для картинки (design.md, «Рамка —
 * доли показанной страницы»).
 *
 * @param left   отступ слева, доля ширины
 * @param top    отступ сверху, доля высоты
 * @param width  ширина, доля ширины страницы
 * @param height высота, доля высоты страницы
 */
public record CropFrame(double left, double top, double width, double height) {

    /**
     * Допуск на округление: доли приходят из формы с четырьмя знаками,
     * и рамка «до самого края» может перевалить за единицу на десятитысячную.
     */
    private static final double SLACK = 1e-4;

    private static final String SEPARATOR = ";";

    public CropFrame {
        if (!(width > 0) || !(height > 0)) {
            throw new IllegalArgumentException("Рамка нулевой ширины или высоты: обведите кусок заново");
        }
        if (!(left >= 0) || !(top >= 0) || left + width > 1 + SLACK || top + height > 1 + SLACK) {
            throw new IllegalArgumentException("Рамка выходит за пределы страницы: обведите кусок заново");
        }
        width = Math.min(width, 1 - left);
        height = Math.min(height, 1 - top);
    }

    /**
     * Рамка из поля формы «л;в;ш;в»; пустое поле — рамки нет.
     *
     * <p>Разделитель — точка с запятой, а не запятая: Spring, получив одно
     * значение параметра в список, режет его по запятым (design.md, «Поле
     * формы»).
     *
     * @return рамка или {@code null}, если поле пустое
     * @throws IllegalArgumentException если поле испорчено или рамка неверна
     */
    public static CropFrame parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String[] parts = value.strip().split(SEPARATOR, -1);
        if (parts.length != 4) {
            throw new IllegalArgumentException("Рамка не разбирается: обведите кусок заново");
        }
        double[] numbers = new double[4];
        try {
            for (int i = 0; i < 4; i++) {
                numbers[i] = Double.parseDouble(parts[i].strip());
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Рамка не разбирается: обведите кусок заново");
        }
        return new CropFrame(numbers[0], numbers[1], numbers[2], numbers[3]);
    }

    /** Значение поля формы — обратное {@link #parse}. */
    public String formValue() {
        return String.join(SEPARATOR, format(left), format(top), format(width), format(height));
    }

    /**
     * Прямоугольник рамки в координатах PDF страницы.
     *
     * <p>Администратор видит видимую область страницы {@code visible},
     * повёрнутую на {@code rotation} градусов по часовой стрелке; ось Y
     * у PDF направлена вверх, у показанной картинки — вниз. Самое хрупкое
     * место векторной обрезки — поэтому каждая ветка поворота проверена
     * отдельно ({@code CropFrameTest}).
     *
     * @param rotation значение {@code /Rotate} страницы, кратное 90
     */
    public PDRectangle on(PDRectangle visible, int rotation) {
        float llx = visible.getLowerLeftX();
        float lly = visible.getLowerLeftY();
        float urx = visible.getUpperRightX();
        float ury = visible.getUpperRightY();
        float w = visible.getWidth();
        float h = visible.getHeight();
        double right = left + width;
        double bottom = top + height;
        return switch (Math.floorMod(rotation, 360)) {
            // Без поворота: доли ширины — по X слева направо, доли высоты — по Y сверху вниз.
            case 0 -> box(llx + left * w, ury - bottom * h, llx + right * w, ury - top * h);
            // 90° по часовой: слева направо на экране — Y снизу вверх, сверху вниз — X слева направо.
            case 90 -> box(llx + top * w, lly + left * h, llx + bottom * w, lly + right * h);
            // 180°: слева направо — X справа налево, сверху вниз — Y снизу вверх.
            case 180 -> box(urx - right * w, lly + top * h, urx - left * w, lly + bottom * h);
            // 270°: слева направо — Y сверху вниз, сверху вниз — X справа налево.
            case 270 -> box(urx - bottom * w, ury - right * h, urx - top * w, ury - left * h);
            default -> throw new IllegalArgumentException("Поворот страницы " + rotation + "° не кратен 90");
        };
    }

    private static PDRectangle box(double x1, double y1, double x2, double y2) {
        return new PDRectangle((float) x1, (float) y1, (float) (x2 - x1), (float) (y2 - y1));
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }
}
