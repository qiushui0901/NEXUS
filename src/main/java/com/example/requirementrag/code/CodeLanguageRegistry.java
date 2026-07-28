package com.example.requirementrag.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.treesitter.TreeSitterGo;
import org.treesitter.TreeSitterJava;
import org.treesitter.TreeSitterKotlin;
import org.treesitter.TreeSitterPython;
import org.treesitter.TreeSitterTypescript;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Extension registry and native parser capability probe. */
@Component
public class CodeLanguageRegistry {
    private static final Logger log = LoggerFactory.getLogger(CodeLanguageRegistry.class);
    private final Map<CodeLanguage, TreeSitterLanguageAdapter> adapters = new EnumMap<>(CodeLanguage.class);
    private final List<CodeScanDiagnostic> capabilities;

    public CodeLanguageRegistry() {
        List<AdapterRegistration> registrations = List.of(
                new AdapterRegistration(CodeLanguage.JAVA, new TreeSitterLanguageAdapter(CodeLanguage.JAVA,
                        TreeSitterJava::new,
                        Set.of("class_declaration", "interface_declaration", "enum_declaration", "record_declaration"),
                        Set.of("method_declaration", "constructor_declaration"),
                        Set.of("method_invocation", "object_creation_expression"))),
                new AdapterRegistration(CodeLanguage.GO, new TreeSitterLanguageAdapter(CodeLanguage.GO,
                        TreeSitterGo::new, Set.of("type_spec"),
                        Set.of("function_declaration", "method_declaration"), Set.of("call_expression"))),
                new AdapterRegistration(CodeLanguage.PYTHON, new TreeSitterLanguageAdapter(CodeLanguage.PYTHON,
                        TreeSitterPython::new, Set.of("class_definition"),
                        Set.of("function_definition"), Set.of("call"))),
                new AdapterRegistration(CodeLanguage.TYPESCRIPT,
                        new TreeSitterLanguageAdapter(CodeLanguage.TYPESCRIPT, TreeSitterTypescript::new,
                                Set.of("class_declaration", "interface_declaration", "enum_declaration"),
                                Set.of("function_declaration", "method_definition"),
                                Set.of("call_expression", "new_expression"))),
                new AdapterRegistration(CodeLanguage.KOTLIN,
                        new TreeSitterLanguageAdapter(CodeLanguage.KOTLIN, TreeSitterKotlin::new,
                                Set.of("class_declaration", "object_declaration"),
                                Set.of("function_declaration"), Set.of("call_expression"))));
        java.util.ArrayList<CodeScanDiagnostic> status = new java.util.ArrayList<>();
        for (AdapterRegistration registration : registrations) {
            try {
                registration.adapter().verifyAvailable();
                adapters.put(registration.language(), registration.adapter());
            }
            catch (LinkageError | RuntimeException exception) {
                log.warn("Tree-sitter {} capability disabled: {}", registration.language().id(),
                        exception.getMessage());
                status.add(new CodeScanDiagnostic(registration.language().id(), "", "LANGUAGE_DISABLED",
                        "Parser unavailable: " + exception.getClass().getSimpleName()));
            }
        }
        capabilities = List.copyOf(status);
    }

    public boolean supports(String path) {
        return adapters.containsKey(CodeLanguage.fromPath(path));
    }

    Optional<TreeSitterLanguageAdapter> adapter(String path) {
        return Optional.ofNullable(adapters.get(CodeLanguage.fromPath(path)));
    }

    public List<CodeScanDiagnostic> capabilityDiagnostics() {
        return capabilities;
    }

    private record AdapterRegistration(CodeLanguage language, TreeSitterLanguageAdapter adapter) {
    }
}
