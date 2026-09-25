package pt.planet.exception;

public class InvalidExportFormatException extends InvalidFileException {

    public InvalidExportFormatException(String message) {
        super(message);
    }

    public InvalidExportFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
