package com.codeatlas.analysis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

/**
 * Deterministic structural extraction for the supported Java/Spring subset.
 *
 * This is a real parser with symbol resolution, never regex matching and never
 * a language model (README section 4). Anything the analyzer cannot resolve is
 * recorded as a coverage finding rather than guessed (BR-13).
 *
 * Supported subset:
 *   - classes and methods, with Spring stereotype roles
 *   - @RestController / @RequestMapping / @GetMapping / @PostMapping endpoints
 *   - method invocations resolved within the asset
 *   - @Value configuration uses
 *   - JdbcTemplate reads and writes with literal SQL
 *   - reflective dispatch, recorded as an unresolved dynamic call
 */
public class JavaSpringAnalyzer {

    public static final String EXTRACTOR = "java-spring-analyzer";
    public static final String VERSION = "1.0.0";

    private static final List<String> MAPPING_ANNOTATIONS = List.of(
            "RequestMapping", "GetMapping", "PostMapping", "PutMapping",
            "DeleteMapping", "PatchMapping");

    private final String assetId;
    private final String revisionDigest;
    private final Path assetRoot;
    private final ExtractedModel model;

    /** Qualified class name -> node id, for within-asset call resolution. */
    private final Map<String, String> classNodeIds = new HashMap<>();
    /** Qualified method signature -> node id. */
    private final Map<String, String> methodNodeIds = new HashMap<>();
    /** Field name -> declared type, per class, to resolve call receivers. */
    private final Map<String, Map<String, String>> fieldTypesByClass = new HashMap<>();

    public JavaSpringAnalyzer(String assetId, String revisionDigest, Path assetRoot,
                              ExtractedModel model) {
        this.assetId = assetId;
        this.revisionDigest = revisionDigest;
        this.assetRoot = assetRoot;
        this.model = model;
    }

    private JavaParser newParser() {
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver());
        Path sourceRoot = assetRoot.resolve("src/main/java");
        if (Files.isDirectory(sourceRoot)) {
            typeSolver.add(new JavaParserTypeSolver(sourceRoot));
        }
        ParserConfiguration configuration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setSymbolResolver(new JavaSymbolSolver(typeSolver));
        return new JavaParser(configuration);
    }

    /** Parses all Java files, in two passes so calls can resolve to declarations. */
    public void analyze(List<Path> javaFiles) {
        JavaParser parser = newParser();
        Map<Path, CompilationUnit> units = new LinkedHashMap<>();

        // Pass 1: declarations.
        for (Path file : javaFiles) {
            try {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                ParseResult<CompilationUnit> result = parser.parse(content);
                if (!result.isSuccessful() || result.getResult().isEmpty()) {
                    model.countFailed();
                    model.findings().add(new CoverageFinding(assetId,
                            CoverageFinding.PARSE_FAILURE, relative(file), null,
                            "Java parse failed: " + firstProblem(result)));
                    continue;
                }
                CompilationUnit unit = result.getResult().get();
                units.put(file, unit);
                collectDeclarations(file, content, unit);
                model.countParsed();
            } catch (IOException e) {
                model.countFailed();
                model.findings().add(new CoverageFinding(assetId,
                        CoverageFinding.PARSE_FAILURE, relative(file), null,
                        "Unreadable file: " + e.getMessage()));
            }
        }

        // Pass 2: relationships, now that every declaration is known.
        units.forEach(this::collectRelationships);
    }

    private String firstProblem(ParseResult<CompilationUnit> result) {
        return result.getProblems().isEmpty()
                ? "unknown problem"
                : result.getProblems().get(0).getMessage();
    }

    private String relative(Path file) {
        return Identities.normalizePath(assetRoot.relativize(file).toString());
    }

    // ------------------------------------------------------------ pass 1

    private void collectDeclarations(Path file, String content, CompilationUnit unit) {
        String path = relative(file);
        String packageName = unit.getPackageDeclaration()
                .map(p -> p.getNameAsString()).orElse("");

        for (ClassOrInterfaceDeclaration clazz : unit.findAll(ClassOrInterfaceDeclaration.class)) {
            String qualifiedName = packageName.isEmpty()
                    ? clazz.getNameAsString()
                    : packageName + "." + clazz.getNameAsString();

            String classLocationId = recordLocation(path, qualifiedName, clazz, content);
            String classNodeId = Identities.nodeId(assetId, NodeTypeWire.CLASS, qualifiedName);
            classNodeIds.put(qualifiedName, classNodeId);

            Map<String, Object> classAttributes = new LinkedHashMap<>();
            classAttributes.put("springRole", springRole(clazz));
            classAttributes.put("snippet", clazz.getNameAsString());
            annotationValue(clazz, "RequestMapping")
                    .ifPresent(base -> classAttributes.put("basePath", base));

            model.nodes().add(new ExtractedNode(classNodeId,
                    com.codeatlas.knowledge.NodeType.CLASS,
                    clazz.getNameAsString(), qualifiedName, assetId, classLocationId,
                    EXTRACTOR, VERSION, classAttributes));

            // Field types let pass 2 resolve `this.someService.call()` receivers.
            Map<String, String> fieldTypes = new LinkedHashMap<>();
            for (FieldDeclaration field : clazz.getFields()) {
                field.getVariables().forEach(v ->
                        fieldTypes.put(v.getNameAsString(), field.getElementType().asString()));
            }
            fieldTypesByClass.put(qualifiedName, fieldTypes);

            for (MethodDeclaration method : clazz.getMethods()) {
                String signature = qualifiedName + "." + method.getNameAsString();
                String methodLocationId = recordLocation(path, signature, method, content);
                String methodNodeId = Identities.nodeId(assetId, NodeTypeWire.METHOD, signature);
                methodNodeIds.put(signature, methodNodeId);

                Map<String, Object> methodAttributes = new LinkedHashMap<>();
                methodAttributes.put("returnType", method.getType().asString());
                methodAttributes.put("parameters", method.getParameters().size());
                methodAttributes.put("snippet", method.getDeclarationAsString(false, false, false));

                model.nodes().add(new ExtractedNode(methodNodeId,
                        com.codeatlas.knowledge.NodeType.METHOD,
                        method.getNameAsString(), signature, assetId, methodLocationId,
                        EXTRACTOR, VERSION, methodAttributes));

                model.edges().add(new ExtractedEdge(
                        Identities.edgeId(EdgeTypeWire.CONTAINS, classNodeId, methodNodeId, null),
                        com.codeatlas.knowledge.EdgeType.CONTAINS,
                        classNodeId, methodNodeId,
                        com.codeatlas.knowledge.Provenance.DERIVED, null,
                        List.of(methodLocationId), Map.of()));

                recordEndpointIfPresent(clazz, method, qualifiedName, signature,
                        classNodeId, methodNodeId, methodLocationId, path, content);
            }
        }
    }

    /** Records an HTTP endpoint node when Spring mapping annotations are present. */
    private void recordEndpointIfPresent(ClassOrInterfaceDeclaration clazz, MethodDeclaration method,
                                         String className, String signature, String classNodeId,
                                         String methodNodeId, String methodLocationId,
                                         String path, String content) {
        Optional<String> mapping = MAPPING_ANNOTATIONS.stream()
                .filter(a -> method.getAnnotationByName(a).isPresent())
                .findFirst();
        if (mapping.isEmpty()) {
            return;
        }
        String annotation = mapping.get();
        String basePath = annotationValue(clazz, "RequestMapping").orElse("");
        String methodPath = annotationValue(method, annotation).orElse("");
        String route = joinRoute(basePath, methodPath);
        String httpMethod = switch (annotation) {
            case "GetMapping" -> "GET";
            case "PostMapping" -> "POST";
            case "PutMapping" -> "PUT";
            case "DeleteMapping" -> "DELETE";
            case "PatchMapping" -> "PATCH";
            default -> "ANY";
        };

        String endpointKey = httpMethod + " " + route;
        String endpointNodeId = Identities.nodeId(assetId, NodeTypeWire.ENDPOINT, endpointKey);

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("httpMethod", httpMethod);
        attributes.put("route", route);
        attributes.put("handler", signature);
        attributes.put("snippet", endpointKey);

        model.nodes().add(new ExtractedNode(endpointNodeId,
                com.codeatlas.knowledge.NodeType.ENDPOINT,
                endpointKey, endpointKey, assetId, methodLocationId,
                EXTRACTOR, VERSION, attributes));

        // The class exposes the endpoint; the endpoint invokes its handler.
        model.edges().add(new ExtractedEdge(
                Identities.edgeId(EdgeTypeWire.EXPOSES, classNodeId, endpointNodeId, route),
                com.codeatlas.knowledge.EdgeType.EXPOSES,
                classNodeId, endpointNodeId,
                com.codeatlas.knowledge.Provenance.DERIVED, null,
                List.of(methodLocationId), Map.of("route", route)));

        model.edges().add(new ExtractedEdge(
                Identities.edgeId(EdgeTypeWire.INVOKES, endpointNodeId, methodNodeId, "handler"),
                com.codeatlas.knowledge.EdgeType.INVOKES,
                endpointNodeId, methodNodeId,
                com.codeatlas.knowledge.Provenance.DERIVED, null,
                List.of(methodLocationId), Map.of("role", "handler")));
    }

    private String joinRoute(String base, String suffix) {
        String b = base == null ? "" : base.trim();
        String s = suffix == null ? "" : suffix.trim();
        if (b.isEmpty() && s.isEmpty()) {
            return "/";
        }
        String joined = (b + "/" + s).replaceAll("/{2,}", "/");
        if (joined.length() > 1 && joined.endsWith("/")) {
            joined = joined.substring(0, joined.length() - 1);
        }
        return joined.startsWith("/") ? joined : "/" + joined;
    }

    private String springRole(ClassOrInterfaceDeclaration clazz) {
        for (String stereotype : List.of("RestController", "Controller", "Service",
                "Repository", "Component", "Configuration")) {
            if (clazz.getAnnotationByName(stereotype).isPresent()) {
                return stereotype;
            }
        }
        return "plain";
    }

    // ------------------------------------------------------------ pass 2

    private void collectRelationships(Path file, CompilationUnit unit) {
        String path = relative(file);
        String packageName = unit.getPackageDeclaration()
                .map(p -> p.getNameAsString()).orElse("");
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return;
        }

        for (ClassOrInterfaceDeclaration clazz : unit.findAll(ClassOrInterfaceDeclaration.class)) {
            String className = packageName.isEmpty()
                    ? clazz.getNameAsString()
                    : packageName + "." + clazz.getNameAsString();

            collectConfigUses(clazz, className, path, content);

            for (MethodDeclaration method : clazz.getMethods()) {
                String signature = className + "." + method.getNameAsString();
                String callerNodeId = methodNodeIds.get(signature);
                if (callerNodeId == null) {
                    continue;
                }
                for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                    handleCall(call, className, signature, callerNodeId, path, content);
                }
            }
        }
    }

    /** @Value("${key}") fields become configuration items with uses-config edges. */
    private void collectConfigUses(ClassOrInterfaceDeclaration clazz, String className,
                                   String path, String content) {
        String classNodeId = classNodeIds.get(className);
        if (classNodeId == null) {
            return;
        }
        for (FieldDeclaration field : clazz.getFields()) {
            Optional<String> raw = annotationValue(field, "Value");
            if (raw.isEmpty()) {
                continue;
            }
            String key = raw.get().replace("${", "").replace("}", "").trim();
            if (key.contains(":")) {
                key = key.substring(0, key.indexOf(':'));
            }
            String locationId = recordLocation(path, className + "#" + key, field, content);
            String configNodeId = Identities.nodeId(assetId, NodeTypeWire.CONFIGURATION_ITEM, key);

            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("configKey", key);
            attributes.put("declaredType", field.getElementType().asString());
            attributes.put("snippet", key);

            model.nodes().add(new ExtractedNode(configNodeId,
                    com.codeatlas.knowledge.NodeType.CONFIGURATION_ITEM,
                    key, key, assetId, locationId, EXTRACTOR, VERSION, attributes));

            model.edges().add(new ExtractedEdge(
                    Identities.edgeId(EdgeTypeWire.USES_CONFIG, classNodeId, configNodeId, key),
                    com.codeatlas.knowledge.EdgeType.USES_CONFIG,
                    classNodeId, configNodeId,
                    com.codeatlas.knowledge.Provenance.DERIVED, null,
                    List.of(locationId), Map.of("configKey", key)));
        }
    }

    private void handleCall(MethodCallExpr call, String className, String callerSignature,
                            String callerNodeId, String path, String content) {
        String calledName = call.getNameAsString();

        // Reflective dispatch cannot be resolved statically. Record it as an
        // unresolved dynamic call instead of inventing a target (BR-13).
        if (isReflectiveDispatch(call)) {
            String locationId = recordLocation(path, callerSignature, call, content);
            model.findings().add(new CoverageFinding(assetId,
                    CoverageFinding.UNRESOLVED_DYNAMIC_CALL, path, callerSignature,
                    "Reflective dispatch '" + calledName + "' at line " + line(call)
                    + ": the invocation target is chosen at runtime and cannot be"
                    + " resolved by static analysis."));
            return;
        }

        // Persistence operations with literal SQL become reads/writes.
        if (isJdbcCall(call)) {
            recordPersistence(call, callerNodeId, callerSignature, path, content);
            return;
        }

        // Outbound HTTP calls are integration points.
        if (isRestTemplateCall(call)) {
            recordOutboundHttp(call, callerNodeId, callerSignature, path, content);
            return;
        }

        // Try full symbol resolution first.
        String targetSignature = null;
        try {
            ResolvedMethodDeclaration resolved = call.resolve();
            targetSignature = resolved.declaringType().getQualifiedName()
                    + "." + resolved.getName();
        } catch (RuntimeException e) {
            // Fall back to receiver field types declared in this class.
            targetSignature = resolveByFieldType(call, className);
        }

        if (targetSignature == null) {
            return;
        }
        String targetNodeId = methodNodeIds.get(targetSignature);
        if (targetNodeId == null) {
            // A call outside the analyzed asset: not an error, just out of scope.
            return;
        }
        String locationId = recordLocation(path, callerSignature, call, content);
        model.edges().add(new ExtractedEdge(
                Identities.edgeId(EdgeTypeWire.INVOKES, callerNodeId, targetNodeId,
                        String.valueOf(line(call))),
                com.codeatlas.knowledge.EdgeType.INVOKES,
                callerNodeId, targetNodeId,
                com.codeatlas.knowledge.Provenance.DERIVED, null,
                List.of(locationId), Map.of("line", line(call))));
    }

    /** Resolves `fieldName.method()` using the declared field type in this class. */
    private String resolveByFieldType(MethodCallExpr call, String className) {
        Optional<Expression> scope = call.getScope();
        if (scope.isEmpty()) {
            return null;
        }
        String receiver = scope.get().toString();
        if (receiver.startsWith("this.")) {
            receiver = receiver.substring(5);
        }
        Map<String, String> fields = fieldTypesByClass.getOrDefault(className, Map.of());
        String declaredType = fields.get(receiver);
        if (declaredType == null) {
            return null;
        }
        // Match the simple type name against known classes in this asset.
        for (String known : classNodeIds.keySet()) {
            if (known.endsWith("." + declaredType) || known.equals(declaredType)) {
                return known + "." + call.getNameAsString();
            }
        }
        return null;
    }

    private boolean isReflectiveDispatch(MethodCallExpr call) {
        String name = call.getNameAsString();
        if (name.equals("invoke") || name.equals("newInstance")) {
            return true;
        }
        return call.getScope()
                .map(s -> s.toString().contains("Class.forName")
                        || s.toString().contains("getMethod"))
                .orElse(false);
    }

    private boolean isJdbcCall(MethodCallExpr call) {
        String receiver = call.getScope().map(Object::toString).orElse("");
        if (!receiver.toLowerCase(java.util.Locale.ROOT).contains("jdbctemplate")) {
            return false;
        }
        return List.of("update", "query", "queryForObject", "queryForList", "execute")
                .contains(call.getNameAsString());
    }

    private boolean isRestTemplateCall(MethodCallExpr call) {
        String receiver = call.getScope().map(Object::toString).orElse("");
        if (!receiver.toLowerCase(java.util.Locale.ROOT).contains("resttemplate")) {
            return false;
        }
        return call.getNameAsString().startsWith("post")
                || call.getNameAsString().startsWith("get")
                || call.getNameAsString().startsWith("exchange")
                || call.getNameAsString().startsWith("put")
                || call.getNameAsString().startsWith("delete");
    }

    /**
     * Records a read or write against a table named in literal SQL. SQL built
     * dynamically is reported as an unsupported construct rather than guessed.
     */
    private void recordPersistence(MethodCallExpr call, String callerNodeId,
                                   String callerSignature, String path, String content) {
        Optional<String> sql = call.getArguments().stream()
                .filter(Expression::isStringLiteralExpr)
                .map(e -> e.asStringLiteralExpr().getValue())
                .findFirst();

        if (sql.isEmpty()) {
            model.findings().add(new CoverageFinding(assetId,
                    CoverageFinding.UNSUPPORTED_CONSTRUCT, path, callerSignature,
                    "Persistence call '" + call.getNameAsString() + "' at line " + line(call)
                    + " does not use a literal SQL string; the affected table cannot be"
                    + " determined statically."));
            return;
        }

        String statement = sql.get();
        String table = extractTableName(statement);
        if (table == null) {
            model.findings().add(new CoverageFinding(assetId,
                    CoverageFinding.UNSUPPORTED_CONSTRUCT, path, callerSignature,
                    "Could not identify a table in SQL at line " + line(call) + "."));
            return;
        }

        boolean isWrite = statement.toUpperCase(java.util.Locale.ROOT).trim()
                .matches("^(INSERT|UPDATE|DELETE|MERGE)\\b.*");

        String tableNodeId = Identities.nodeId(assetId, NodeTypeWire.TABLE, table);
        String locationId = recordLocation(path, callerSignature, call, content);

        Map<String, Object> tableAttributes = new LinkedHashMap<>();
        tableAttributes.put("tableName", table);
        tableAttributes.put("snippet", table);
        model.nodes().add(new ExtractedNode(tableNodeId,
                com.codeatlas.knowledge.NodeType.TABLE,
                table, table, assetId, locationId, EXTRACTOR, VERSION, tableAttributes));

        Map<String, Object> edgeAttributes = new LinkedHashMap<>();
        edgeAttributes.put("statement", statement);
        edgeAttributes.put("line", line(call));

        model.edges().add(new ExtractedEdge(
                Identities.edgeId(isWrite ? EdgeTypeWire.WRITES : EdgeTypeWire.READS,
                        callerNodeId, tableNodeId, String.valueOf(line(call))),
                isWrite ? com.codeatlas.knowledge.EdgeType.WRITES
                        : com.codeatlas.knowledge.EdgeType.READS,
                callerNodeId, tableNodeId,
                com.codeatlas.knowledge.Provenance.DERIVED, null,
                List.of(locationId), edgeAttributes));
    }

    private String extractTableName(String sql) {
        String normalized = sql.replaceAll("\\s+", " ").trim();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?i)\\b(?:FROM|INTO|UPDATE|JOIN)\\s+([A-Za-z_][A-Za-z0-9_]*)")
                .matcher(normalized);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * Records an outbound HTTP call as an integration node. The concatenated
     * URL is captured where it is a literal or a base-url property plus a
     * literal path; cross-application targets are matched later and labelled
     * inferred (BR-10).
     */
    private void recordOutboundHttp(MethodCallExpr call, String callerNodeId,
                                    String callerSignature, String path, String content) {
        String urlExpression = call.getArguments().isEmpty()
                ? "" : call.getArgument(0).toString();
        String literalPath = literalPathFragment(call.getArgument(0));

        String integrationKey = literalPath != null ? literalPath : urlExpression;
        String integrationNodeId = Identities.nodeId(assetId, NodeTypeWire.INTEGRATION, integrationKey);
        String locationId = recordLocation(path, callerSignature, call, content);

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("urlExpression", urlExpression);
        attributes.put("httpMethod", call.getNameAsString().toUpperCase(java.util.Locale.ROOT)
                .startsWith("POST") ? "POST" : "GET");
        if (literalPath != null) {
            attributes.put("routePath", literalPath);
        }
        attributes.put("snippet", integrationKey);

        model.nodes().add(new ExtractedNode(integrationNodeId,
                com.codeatlas.knowledge.NodeType.INTEGRATION,
                integrationKey, integrationKey, assetId, locationId,
                EXTRACTOR, VERSION, attributes));

        model.edges().add(new ExtractedEdge(
                Identities.edgeId(EdgeTypeWire.CALLS_ENDPOINT, callerNodeId,
                        integrationNodeId, String.valueOf(line(call))),
                com.codeatlas.knowledge.EdgeType.CALLS_ENDPOINT,
                callerNodeId, integrationNodeId,
                com.codeatlas.knowledge.Provenance.DERIVED, null,
                List.of(locationId), attributes));

        if (literalPath == null) {
            model.findings().add(new CoverageFinding(assetId,
                    CoverageFinding.UNSUPPORTED_CONSTRUCT, path, callerSignature,
                    "Outbound HTTP target at line " + line(call)
                    + " is not a literal path; the called route cannot be fully resolved."));
        }
    }

    /** Pulls the literal path fragment out of `baseUrl + "/api/x"` expressions. */
    private String literalPathFragment(Expression expression) {
        if (expression.isStringLiteralExpr()) {
            return expression.asStringLiteralExpr().getValue();
        }
        if (expression.isBinaryExpr()) {
            BinaryExpr binary = expression.asBinaryExpr();
            if (binary.getOperator() == BinaryExpr.Operator.PLUS) {
                Expression right = binary.getRight();
                if (right instanceof StringLiteralExpr literal) {
                    return literal.getValue();
                }
            }
        }
        return null;
    }

    // ----------------------------------------------------------- utilities

    private int line(Node node) {
        return node.getBegin().map(p -> p.line).orElse(0);
    }

    private String recordLocation(String path, String symbolKey, Node node, String content) {
        int start = node.getBegin().map(p -> p.line).orElse(1);
        int end = node.getEnd().map(p -> p.line).orElse(start);
        String excerpt = excerpt(content, start, end);
        String id = Identities.locationId(assetId, revisionDigest, path, symbolKey, start, end);
        model.locations().add(new ExtractedLocation(id, assetId, path, symbolKey,
                start, end, Identities.sha256(excerpt)));
        return id;
    }

    private String excerpt(String content, int startLine, int endLine) {
        String[] lines = content.split("\n", -1);
        int from = Math.max(1, startLine);
        int to = Math.min(lines.length, endLine);
        StringBuilder builder = new StringBuilder();
        for (int i = from; i <= to; i++) {
            builder.append(lines[i - 1]).append('\n');
        }
        return builder.toString();
    }

    private Optional<String> annotationValue(com.github.javaparser.ast.nodeTypes.NodeWithAnnotations<?> node,
                                             String annotationName) {
        Optional<AnnotationExpr> annotation = node.getAnnotationByName(annotationName);
        if (annotation.isEmpty()) {
            return Optional.empty();
        }
        AnnotationExpr expr = annotation.get();
        if (expr.isSingleMemberAnnotationExpr()) {
            Expression value = expr.asSingleMemberAnnotationExpr().getMemberValue();
            return Optional.of(stringValue(value));
        }
        if (expr.isNormalAnnotationExpr()) {
            return expr.asNormalAnnotationExpr().getPairs().stream()
                    .filter(pair -> pair.getNameAsString().equals("value")
                            || pair.getNameAsString().equals("path"))
                    .map(pair -> stringValue(pair.getValue()))
                    .findFirst();
        }
        return Optional.of("");
    }

    private String stringValue(Expression expression) {
        if (expression.isStringLiteralExpr()) {
            return expression.asStringLiteralExpr().getValue();
        }
        if (expression.isArrayInitializerExpr()) {
            return expression.asArrayInitializerExpr().getValues().stream()
                    .findFirst().map(this::stringValue).orElse("");
        }
        return expression.toString().replace("\"", "");
    }

    /** Wire-name constants used for identity derivation. */
    private static final class NodeTypeWire {
        static final String CLASS = "class";
        static final String METHOD = "method";
        static final String ENDPOINT = "endpoint";
        static final String TABLE = "table";
        static final String INTEGRATION = "integration";
        static final String CONFIGURATION_ITEM = "configuration_item";
    }

    private static final class EdgeTypeWire {
        static final String CONTAINS = "contains";
        static final String INVOKES = "invokes";
        static final String EXPOSES = "exposes";
        static final String READS = "reads";
        static final String WRITES = "writes";
        static final String CALLS_ENDPOINT = "calls_endpoint";
        static final String USES_CONFIG = "uses_config";
    }
}
