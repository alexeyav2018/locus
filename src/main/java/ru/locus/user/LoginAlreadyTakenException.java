package ru.locus.user;

/** Имя входа уникально в системе; попытка занять занятое отклоняется. */
public class LoginAlreadyTakenException extends RuntimeException {

    private final String login;

    public LoginAlreadyTakenException(String login) {
        super("Имя входа «" + login + "» уже занято");
        this.login = login;
    }

    public String login() {
        return login;
    }
}
