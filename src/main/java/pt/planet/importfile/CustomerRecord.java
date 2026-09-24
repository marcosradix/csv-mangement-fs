package pt.planet.importfile;

public record CustomerRecord(
        Long id,
        String name,
        String email,
        Integer age,
        String country,
        String phone,
        int rowNumber,
        String rawData
) {
}
