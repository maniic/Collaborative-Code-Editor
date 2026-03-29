package com.collabeditor.execution.service;

import com.collabeditor.execution.config.ExecutionProperties;
import com.collabeditor.execution.model.ExecutionSourceSnapshot;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Resolves the exact runtime contract for each supported language.
 *
 * <p>Maps {@code PYTHON} to a plain-script execution and {@code JAVA}
 * to a compile-and-run pipeline with strict {@code Main} entrypoint validation.
 */
@Service
public class ExecutionLanguageSpecResolver {

    private static final Pattern PACKAGE_DECLARATION = Pattern.compile("(?m)^\\s*package\\s+");
    private static final Pattern MAIN_CLASS_PATTERN = Pattern.compile("(?s)public\\s+class\\s+Main\\b");
    private static final Pattern MAIN_METHOD_PATTERN =
            Pattern.compile("(?s)public\\s+static\\s+void\\s+main\\s*\\(\\s*String\\s*\\[\\s*]\\s+args\\s*\\)");

    private final ExecutionProperties properties;

    public ExecutionLanguageSpecResolver(ExecutionProperties properties) {
        this.properties = properties;
    }

    /**
     * Resolved language specification for a sandbox run.
     *
     * @param image         Docker image to use
     * @param sourceFilename source file name inside the input mount
     * @param command       container entrypoint command
     * @param env           environment variables for the container
     */
    public record LanguageSpec(
            String image,
            String sourceFilename,
            List<String> command,
            Map<String, String> env
    ) {}

    /**
     * Resolves the language spec for the given snapshot.
     *
     * <p>For {@code JAVA}, validates that the source contains a package-less
     * {@code public class Main} with {@code public static void main(String[] args)}.
     *
     * @param snapshot the canonical source snapshot
     * @return the resolved language spec
     * @throws IllegalArgumentException if the language is unsupported or Java source is invalid
     */
    public LanguageSpec resolve(ExecutionSourceSnapshot snapshot) {
        String language = snapshot.language().toUpperCase();

        return switch (language) {
            case "PYTHON" -> resolvePython();
            case "JAVA" -> resolveJava(snapshot.sourceCode());
            default -> throw new IllegalArgumentException("Unsupported language: " + language);
        };
    }

    private LanguageSpec resolvePython() {
        return new LanguageSpec(
                properties.getPythonImage(),
                "main.py",
                List.of("python", "/input/main.py"),
                Map.of("PYTHONDONTWRITEBYTECODE", "1")
        );
    }

    private LanguageSpec resolveJava(String sourceCode) {
        validateJavaSource(sourceCode);

        return new LanguageSpec(
                properties.getJavaImage(),
                "Main.java",
                List.of("sh", "-lc",
                        "cp /input/Main.java /workspace/Main.java && javac -d /workspace/out /workspace/Main.java && java -cp /workspace/out Main"),
                Map.of()
        );
    }

    /**
     * Validates that Java source contains a package-less {@code public class Main}
     * with {@code public static void main(String[] args)}.
     *
     * @param sourceCode the Java source to validate
     * @throws IllegalArgumentException if the source does not meet the contract
     */
    private void validateJavaSource(String sourceCode) {
        if (sourceCode == null || sourceCode.isBlank()) {
            throw new IllegalArgumentException(
                    "Java source is empty. Expected a package-less public class Main with public static void main(String[] args).");
        }

        if (PACKAGE_DECLARATION.matcher(sourceCode).find()) {
            throw new IllegalArgumentException(
                    "Java source must be package-less. Remove the package declaration.");
        }

        if (!MAIN_CLASS_PATTERN.matcher(sourceCode).find()) {
            throw new IllegalArgumentException(
                    "Java source must contain a package-less 'public class Main'. "
                            + "Found no 'public class Main' declaration.");
        }

        if (!MAIN_METHOD_PATTERN.matcher(sourceCode).find()) {
            throw new IllegalArgumentException(
                    "Java source must contain 'public static void main(String[] args)' in the Main class.");
        }
    }
}
