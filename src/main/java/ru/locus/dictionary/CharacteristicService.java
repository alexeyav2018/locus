package ru.locus.dictionary;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ведение словаря Характеристик — симметрично {@link SolutionMethodService}
 * и по тем же правилам: те же требования к имени, то же право правки, то же
 * условие удаления.
 *
 * Словари независимы: одноимённые Метод и Характеристика сосуществуют,
 * и операция над одним словарём второго не касается.
 *
 * Словарь — общая библиотека, по владельцу он <b>не фильтруется</b>
 * (ADR-0004, ADR-0027).
 */
@Service
public class CharacteristicService {

    private final CharacteristicRepository characteristics;

    public CharacteristicService(CharacteristicRepository characteristics) {
        this.characteristics = characteristics;
    }

    /** Весь словарь, по алфавиту. */
    public List<Characteristic> all() {
        return characteristics.findAll();
    }

    public Characteristic characteristic(CharacteristicId id) {
        return existing(id);
    }

    /** Заводит Характеристику. Имя обязательно, не пустое и не занятое. */
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @Transactional
    public CharacteristicId create(String name) {
        String trimmed = requireName(name);
        refuseIfNameTaken(trimmed, null);
        return characteristics.create(trimmed);
    }

    /** Меняет имя Характеристики; идентификатор записи при этом не меняется. */
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @Transactional
    public void rename(CharacteristicId id, String newName) {
        Characteristic characteristic = existing(id);
        String trimmed = requireName(newName);
        refuseIfNameTaken(trimmed, characteristic.id());
        characteristics.rename(characteristic.id(), trimmed);
    }

    /** Снимает Характеристику, если она не используется. */
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @Transactional
    public void delete(CharacteristicId id) {
        Characteristic characteristic = existing(id);
        refuseUnlessUnused(characteristic);
        characteristics.delete(characteristic.id());
    }

    /**
     * Единственная проверка того, что запись не используется: сюда
     * дописывается каждое новое условие, и искать его потом надо в одном
     * месте, а не по всем вызовам удаления.
     *
     * <p>Сегодня условие выполняется тождественно: ссылаться на Характеристику
     * нечему, Задач в системе нет. <b>Появление разметки Задачи обязано
     * пополнить эту проверку</b>: связь {@code problem_characteristic}
     * приходит с работой {@code problem-catalog}.
     *
     * <p>Ячеек владения Характеристика не порождает — в измерении она
     * не участвует (ADR-0009), — поэтому {@code mastery-marks} эту проверку
     * не касается; у Метода, где касается, условие своё
     * ({@link SolutionMethodService}). Но разметку удалённой Характеристики
     * потеря всё равно обедняет молча: задачи перестают находиться по
     * признаку, по которому их искали.
     */
    private void refuseUnlessUnused(Characteristic characteristic) {
        // Ссылаться на запись нечему: таблиц разметки не существует.
    }

    /**
     * Имя не должно повторяться в словаре — сравнение без учёта регистра,
     * тем же правилом, что держит уникальный индекс в схеме.
     *
     * @param keep запись, которая сама себе дублем не считается, либо
     *             {@code null}
     */
    private void refuseIfNameTaken(String name, CharacteristicId keep) {
        boolean taken = characteristics.findByName(name)
                .filter(other -> !other.id().equals(keep))
                .isPresent();
        if (taken) {
            throw new NameAlreadyTakenException(name);
        }
    }

    private static String requireName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Имя Характеристики не может быть пустым");
        }
        return trimmed;
    }

    private Characteristic existing(CharacteristicId id) {
        return characteristics.findById(id).orElseThrow(() -> new IllegalArgumentException(
                "Характеристики с идентификатором " + id.value() + " не существует"));
    }
}
