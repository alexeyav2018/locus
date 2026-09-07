package ru.locus.user;

/** Смена пароля не подтверждена знанием текущего. */
public class WrongPasswordException extends RuntimeException {

    public WrongPasswordException() {
        super("Текущий пароль указан неверно");
    }
}
