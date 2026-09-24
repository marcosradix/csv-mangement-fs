package pt.planet.importfile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pt.planet.exception.InvalidFileException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvHeaderAnalyzerTest {

    private CsvHeaderAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new CsvHeaderAnalyzer();
    }

    @Test
    @DisplayName("Should analyze standard customer_01 headers")
    void testStandardHeaders() {
        List<String> headers = List.of("id", "name", "email", "age", "country");
        CsvHeaderAnalyzer.HeaderAnalysisResult result = analyzer.analyze(headers);

        assertThat(result.hasColumn("id")).isTrue();
        assertThat(result.hasColumn("name")).isTrue();
        assertThat(result.hasColumn("email")).isTrue();
        assertThat(result.hasColumn("age")).isTrue();
        assertThat(result.hasColumn("country")).isTrue();
        assertThat(result.hasColumn("phone")).isFalse();
        assertThat(result.unknownHeaders()).isEmpty();
    }

    @Test
    @DisplayName("Should analyze dynamic customer_03 headers where phone is before email")
    void testDynamicHeaderOrder() {
        List<String> headers = List.of("id", "name", "phone", "email", "age", "country");
        CsvHeaderAnalyzer.HeaderAnalysisResult result = analyzer.analyze(headers);

        assertThat(result.hasColumn("phone")).isTrue();
        assertThat(result.getActualHeader("phone")).isEqualTo("phone");
        assertThat(result.hasColumn("email")).isTrue();
    }

    @Test
    @DisplayName("Should handle case insensitivity and whitespace")
    void testCaseInsensitivityAndWhitespace() {
        List<String> headers = List.of(" ID ", "Name", "eMAIl", " AGE ", "Country", "PHONE");
        CsvHeaderAnalyzer.HeaderAnalysisResult result = analyzer.analyze(headers);

        assertThat(result.hasColumn("id")).isTrue();
        assertThat(result.getActualHeader("id")).isEqualTo("ID");
        assertThat(result.hasColumn("email")).isTrue();
        assertThat(result.getActualHeader("email")).isEqualTo("eMAIl");
    }

    @Test
    @DisplayName("Should detect unknown headers without error")
    void testUnknownHeaders() {
        List<String> headers = List.of("id", "name", "department", "notes");
        CsvHeaderAnalyzer.HeaderAnalysisResult result = analyzer.analyze(headers);

        assertThat(result.unknownHeaders()).containsExactlyInAnyOrder("department", "notes");
    }

    @Test
    @DisplayName("Should throw InvalidFileException when required 'id' header is missing")
    void testMissingIdHeader() {
        List<String> headers = List.of("name", "email", "country");
        assertThatThrownBy(() -> analyzer.analyze(headers))
                .isInstanceOf(InvalidFileException.class)
                .hasMessageContaining("missing required header: 'id'");
    }

    @Test
    @DisplayName("Should throw InvalidFileException when required 'name' header is missing")
    void testMissingNameHeader() {
        List<String> headers = List.of("id", "email", "country");
        assertThatThrownBy(() -> analyzer.analyze(headers))
                .isInstanceOf(InvalidFileException.class)
                .hasMessageContaining("missing required header: 'name'");
    }

    @Test
    @DisplayName("Should throw InvalidFileException on null or empty headers")
    void testEmptyHeaders() {
        assertThatThrownBy(() -> analyzer.analyze(List.of()))
                .isInstanceOf(InvalidFileException.class);
        assertThatThrownBy(() -> analyzer.analyze(null))
                .isInstanceOf(InvalidFileException.class);
    }
}
