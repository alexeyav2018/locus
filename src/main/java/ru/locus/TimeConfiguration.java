package ru.locus;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Часы приложения — один бин {@link Clock} на всё, что зависит
 * от «сегодня».
 *
 * Первый потребитель — «не сдано» у Задания: срок прошёл, если сегодняшняя
 * дата позже срока (ADR-0016). Состояние это вычисляется при показе,
 * а не хранится, и единственный его вход извне — часы. Через бин, а не
 * через {@code LocalDate.now()} по месту: тест на «сдвиг системного времени
 * меняет состояние без единого действия» должен уметь сдвинуть часы,
 * а {@code now()} без аргумента сдвинуть нечем.
 *
 * Зона — системная, а не UTC: «сегодня» здесь — календарная дата учителя,
 * а не момент. Часы хранилища файлов ({@code FileStorageConfiguration},
 * {@code Clock.systemUTC()}) намеренно не трогаются: там срок жизни ссылки —
 * момент, и зона ему безразлична; вторые часы на границе суток разошлись бы
 * с первыми ровно там, где для срока Задания это важно.
 */
@Configuration
public class TimeConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
