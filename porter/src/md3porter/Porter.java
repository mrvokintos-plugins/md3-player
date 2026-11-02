package md3porter;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Position;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Ports the exteraless MD3 player (app.exteraless.player) into the md3player plugin dex.
 *
 * <pre>
 * java -cp javaparser.jar:porter md3porter.Porter \
 *   --upstream exteraless --classpath android.jar:inugram.jar:mcu.jar \
 *   --compat compat --rules porter/rules.txt --out generated --classes build/classes --sha abc123
 * </pre>
 *
 * Steps: textual FQN renames, AST rewrites (resources, strings, fork-only APIs), code generation
 * (strings table, config), then a compile loop that repairs known R8 artifacts of the classpath
 * (widened access, stripped throws clauses). Anything it cannot handle fails loudly.
 */
public final class Porter {

    private static final String PLAYER_DIR = "TMessagesProj/src/main/java/app/exteraless/player";
    private static final String RES_DIR = "TMessagesProj/src/main/res";
    private static final String CONFIG_KT = "TMessagesProj/src/main/kotlin/app/exteraless/appearance/AppearanceConfig.kt";
    private static final String FORK_STRINGS = "strings_oe_player.xml";
    private static final String OUT_PACKAGE = "md3player.player";
    private static final String RES = "md3player.compat.Res";
    private static final Set<String> STRING_METHODS = Set.of("getString", "formatString", "formatPluralString", "getPluralString");
    private static final Pattern R_REF = Pattern.compile("^(?:org\\.telegram\\.messenger\\.)?R\\.(string|drawable|raw)\\.(\\w+)$");
    private static final Pattern R_LEFTOVER = Pattern.compile("(?<![\\w.])R\\.\\w+\\.\\w+");

    // ---------------------------------------------------------------- args / state

    private final Map<String, String> args;
    private final Rules rules;
    private final Path upstream;
    private final Path out;
    private final List<String> problems = new ArrayList<>();
    private final List<String> log = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    private final Set<String> stockStrings;
    private final Set<String> stockDrawables;
    private final Set<String> stockRaw;
    /** key -> (lang -> text) for strings the plugin ships itself. */
    private final Map<String, Map<String, String>> forkStrings;
    private final Set<String> usedForkStrings = new LinkedHashSet<>();
    private final Set<String> extraStrings = new LinkedHashSet<>();

    private final Map<Path, CompilationUnit> units = new LinkedHashMap<>();
    private final Map<Path, Path> outPaths = new HashMap<>();
    private final Set<Node> wrapped = new HashSet<>();

    private Porter(Map<String, String> args) throws Exception {
        this.args = args;
        this.rules = Rules.load(Paths.get(require("rules")));
        this.upstream = Paths.get(require("upstream"));
        this.out = Paths.get(require("out"));
        URLClassLoader loader = classpathLoader(require("classpath"));
        this.stockStrings = rFields(loader, "string");
        this.stockDrawables = rFields(loader, "drawable");
        this.stockRaw = rFields(loader, "raw");
        this.forkStrings = loadStrings(upstream.resolve(RES_DIR), FORK_STRINGS, null);
        ParserConfiguration config = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17)
                .setTabSize(8);
        StaticJavaParser.setConfiguration(config);
    }

    public static void main(String[] argv) throws Exception {
        Map<String, String> args = new HashMap<>();
        for (int i = 0; i + 1 < argv.length; i += 2) {
            if (!argv[i].startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument " + argv[i]);
            }
            args.put(argv[i].substring(2), argv[i + 1]);
        }
        Porter porter = new Porter(args);
        boolean ok = porter.run();
        System.exit(ok ? 0 : 1);
    }

    private String require(String key) {
        String value = args.get(key);
        if (value == null) {
            throw new IllegalArgumentException("missing --" + key);
        }
        return value;
    }

    // ---------------------------------------------------------------- pipeline

    private boolean run() throws Exception {
        Path playerDir = upstream.resolve(PLAYER_DIR);
        if (!Files.isDirectory(playerDir)) {
            throw new IOException("no player sources at " + playerDir);
        }
        deleteRecursively(out);
        Path javaOut = out.resolve("java");

        List<Path> sources;
        try (Stream<Path> s = Files.list(playerDir)) {
            sources = s.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
        }
        for (Path source : sources) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            text = rules.renameFqns(text);
            CompilationUnit cu = LexicalPreservingPrinter.setup(StaticJavaParser.parse(text));
            transform(cu, source.getFileName().toString());
            Path target = javaOut.resolve(OUT_PACKAGE.replace('.', '/')).resolve(source.getFileName().toString());
            units.put(target, cu);
            outPaths.put(target, source);
        }

        generateConfig(javaOut);
        generateStrings(javaOut);
        generatePortInfo(javaOut);
        writeUnits();
        checkLeftovers();
        if (!problems.isEmpty()) {
            return report(false);
        }
        boolean compiled = compileLoop();
        return report(compiled);
    }

    // ---------------------------------------------------------------- AST transforms

    private void transform(CompilationUnit cu, String file) {
        boolean staticStringImport = cu.getImports().stream().anyMatch(i -> i.isStatic()
                && (i.getNameAsString().equals("org.telegram.messenger.LocaleController") && i.isAsterisk()
                || STRING_METHODS.stream().anyMatch(m -> i.getNameAsString().equals("org.telegram.messenger.LocaleController." + m))));
        Set<String> localMethods = cu.findAll(MethodDeclaration.class).stream()
                .map(MethodDeclaration::getNameAsString).collect(Collectors.toSet());

        // fork-only fields (NotificationCenter.audioInfoLoaded, ...)
        for (FieldAccessExpr fa : cu.findAll(FieldAccessExpr.class)) {
            if (fa.getParentNode().isEmpty()) {
                continue;
            }
            String text = fa.toString();
            for (Map.Entry<String, String> rule : rules.fields.entrySet()) {
                if (text.equals(rule.getKey()) || text.endsWith("." + rule.getKey())) {
                    fa.replace(StaticJavaParser.parseExpression(rule.getValue()));
                    log.add(file + ": field " + text + " -> " + rule.getValue());
                    break;
                }
            }
        }

        // R.string/drawable/raw -> runtime lookups by name (resource ids differ between builds)
        for (FieldAccessExpr fa : cu.findAll(FieldAccessExpr.class)) {
            if (fa.getParentNode().isEmpty() || fa.findAncestor(FieldAccessExpr.class).isPresent()
                    && R_REF.matcher(fa.findAncestor(FieldAccessExpr.class).get().toString()).matches()) {
                continue;
            }
            Matcher m = R_REF.matcher(fa.toString());
            if (!m.matches()) {
                continue;
            }
            String kind = m.group(1);
            String name = m.group(2);
            String replacement;
            switch (kind) {
                case "string":
                    resolveString(name, file);
                    replacement = RES + ".sid(\"" + name + "\")";
                    break;
                case "drawable":
                    replacement = RES + ".drawable(\"" + resolveResource(name, stockDrawables, "drawable", file) + "\")";
                    break;
                default:
                    replacement = RES + ".raw(\"" + resolveResource(name, stockRaw, "raw", file) + "\")";
                    break;
            }
            fa.replace(StaticJavaParser.parseExpression(replacement));
        }

        // LocaleController.getString & co -> Res, which also knows the plugin's own strings
        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            if (!STRING_METHODS.contains(call.getNameAsString())) {
                continue;
            }
            if (call.getScope().isPresent()) {
                String scope = call.getScope().get().toString();
                if (!scope.equals("LocaleController") && !scope.equals("org.telegram.messenger.LocaleController")) {
                    continue;
                }
            } else if (!staticStringImport || localMethods.contains(call.getNameAsString())) {
                continue;
            }
            call.setScope(StaticJavaParser.parseExpression(RES));
        }

        // fork-only methods on stock classes
        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            if (call.getParentNode().isEmpty()) {
                continue;
            }
            Rules.CallRule rule = rules.calls.get(call.getNameAsString());
            if (rule == null || call.getScope().isEmpty()) {
                continue;
            }
            int dot = rule.target.lastIndexOf('.');
            MethodCallExpr replacement = new MethodCallExpr(StaticJavaParser.parseExpression(rule.target.substring(0, dot)), rule.target.substring(dot + 1));
            NodeList<Expression> newArgs = new NodeList<>();
            if (rule.receiverAsArg) {
                newArgs.add(call.getScope().get().clone());
            }
            for (Expression a : call.getArguments()) {
                newArgs.add(a.clone());
            }
            replacement.setArguments(newArgs);
            log.add(file + ": call " + call.getScope().get() + "." + call.getNameAsString() + " -> " + rule.target);
            call.replace(replacement);
        }

        // new AudioPlayerAlert(...) -> Hooks.classic(new AudioPlayerAlert(...))
        for (ObjectCreationExpr oce : cu.findAll(ObjectCreationExpr.class)) {
            String target = rules.wrapNew.get(oce.getType().getNameAsString());
            if (target == null || oce.getParentNode().isEmpty()) {
                continue;
            }
            int dot = target.lastIndexOf('.');
            MethodCallExpr call = new MethodCallExpr(StaticJavaParser.parseExpression(target.substring(0, dot)), target.substring(dot + 1));
            oce.replace(call);
            call.addArgument(oce);
            log.add(file + ": wrap new " + oce.getType().getNameAsString() + " -> " + target);
        }

        // string literals
        for (StringLiteralExpr lit : cu.findAll(StringLiteralExpr.class)) {
            String value = lit.getValue();
            String replaced = value;
            for (Map.Entry<String, String> rule : rules.literals.entrySet()) {
                replaced = replaced.replace(rule.getKey(), rule.getValue());
            }
            if (!replaced.equals(value)) {
                lit.setValue(replaced);
            }
        }

        // R8 widened protected members of the app to public; overriding them must stay public too
        for (MethodDeclaration md : cu.findAll(MethodDeclaration.class)) {
            if (md.hasModifier(Modifier.Keyword.PROTECTED)) {
                md.setModifier(Modifier.Keyword.PROTECTED, false);
                md.setModifier(Modifier.Keyword.PUBLIC, true);
            }
        }

        for (ImportDeclaration imp : cu.getImports()) {
            for (String prefix : rules.forbid) {
                if (imp.getNameAsString().startsWith(prefix)) {
                    problems.add(file + ": unmapped import " + imp.getNameAsString() + " (add an `fqn` rule and a compat class)");
                }
            }
        }
    }

    private void resolveString(String name, String file) {
        if (forkStrings.containsKey(name)) {
            usedForkStrings.add(name);
        } else if (!stockStrings.contains(name)) {
            extraStrings.add(name);
        }
    }

    private String resolveResource(String name, Set<String> stock, String kind, String file) {
        if (stock.contains(name)) {
            return name;
        }
        String fallback = rules.drawables.get(name);
        if (fallback != null && stock.contains(fallback)) {
            log.add(file + ": " + kind + " " + name + " -> " + fallback);
            return fallback;
        }
        problems.add(file + ": " + kind + " '" + name + "' does not exist in inugram (add a `drawable " + name + " <stock>` rule)");
        return name;
    }

    // ---------------------------------------------------------------- generated sources

    private static final Pattern KT_FIELD = Pattern.compile(
            "@JvmField\\s+val\\s+(\\w+)\\s*=\\s*addConfig\\(\\s*\"([^\"]+)\"\\s*,\\s*ConfigItem\\.configType(Bool|Int|String)\\s*,\\s*([^)\\n]+?)\\s*\\)");
    private static final Pattern KT_GETTER = Pattern.compile(
            "fun\\s+(\\w+)\\(\\)\\s*:\\s*(Boolean|Int|String)\\s*\\{([^}]*)\\}");
    private static final Pattern KT_LIST = Pattern.compile(
            "fun\\s+(\\w+)\\(\\)\\s*:\\s*List<ConfigItem>\\s*\\{[^}]*?listOf\\(([^)]*)\\)", Pattern.DOTALL);
    private static final Pattern CONFIG_REF = Pattern.compile("\\bAppearanceConfig\\.(\\w+)(\\s*\\()?");

    private void generateConfig(Path javaOut) throws IOException {
        String kt = Files.readString(upstream.resolve(CONFIG_KT), StandardCharsets.UTF_8);
        Map<String, String[]> fields = new LinkedHashMap<>();
        Matcher m = KT_FIELD.matcher(kt);
        while (m.find()) {
            fields.put(m.group(1), new String[]{m.group(2), m.group(3), m.group(4).trim()});
        }
        Map<String, String[]> getters = new LinkedHashMap<>();
        m = KT_GETTER.matcher(kt);
        while (m.find()) {
            Matcher ret = Pattern.compile("return\\s+(\\w+)\\.(Bool|Int|String)\\(\\)").matcher(m.group(3));
            if (ret.find()) {
                getters.put(m.group(1), new String[]{m.group(2), ret.group(1), ret.group(2)});
            }
        }
        Map<String, List<String>> lists = new LinkedHashMap<>();
        m = KT_LIST.matcher(kt);
        while (m.find()) {
            List<String> names = new ArrayList<>();
            for (String part : m.group(2).split(",")) {
                if (!part.isBlank()) {
                    names.add(part.trim());
                }
            }
            lists.put(m.group(1), names);
        }

        Set<String> needFields = new LinkedHashSet<>();
        Set<String> needGetters = new LinkedHashSet<>();
        Set<String> needLists = new LinkedHashSet<>();
        boolean ensureLoaded = false;
        for (CompilationUnit cu : units.values()) {
            Matcher r = CONFIG_REF.matcher(cu.toString());
            while (r.find()) {
                String name = r.group(1);
                boolean call = r.group(2) != null;
                if (!call) {
                    needFields.add(name);
                } else if (name.equals("ensureLoaded")) {
                    ensureLoaded = true;
                } else if (lists.containsKey(name)) {
                    needLists.add(name);
                    needFields.addAll(lists.get(name));
                } else if (getters.containsKey(name)) {
                    needGetters.add(name);
                    needFields.add(getters.get(name)[1]);
                } else {
                    problems.add("AppearanceConfig." + name + "() is used by the player but could not be parsed from AppearanceConfig.kt");
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append(header()).append("package md3player.gen;\n\n")
                .append("import md3player.compat.ConfigItem;\n\n")
                .append("/** Player settings parsed from exteraless AppearanceConfig.kt. Same keys, stored in the plugin's prefs. */\n")
                .append("public final class AppearanceConfig {\n\n")
                .append("    private AppearanceConfig() {\n    }\n\n");
        List<String> all = new ArrayList<>();
        for (String name : needFields) {
            String[] f = fields.get(name);
            if (f == null) {
                problems.add("AppearanceConfig." + name + " is used by the player but is not an addConfig(...) field in AppearanceConfig.kt");
                continue;
            }
            String value = f[2];
            if (!value.matches("true|false|-?\\d+|\"[^\"]*\"")) {
                problems.add("AppearanceConfig." + name + " has a non-literal default `" + value + "`");
                continue;
            }
            sb.append("    public static final ConfigItem ").append(name).append(" = new ConfigItem(\"")
                    .append(f[0]).append("\", ConfigItem.configType").append(f[1]).append(", ").append(value).append(");\n");
            all.add(name);
        }
        sb.append("\n    public static final ConfigItem[] ALL = {").append(String.join(", ", all)).append("};\n\n");
        sb.append("    public static void ensureLoaded() {\n    }\n");
        for (String name : needGetters) {
            String[] g = getters.get(name);
            String type = g[0].equals("Boolean") ? "boolean" : g[0].equals("Int") ? "int" : "String";
            sb.append("\n    public static ").append(type).append(' ').append(name).append("() {\n        return ")
                    .append(g[1]).append('.').append(g[2]).append("();\n    }\n");
        }
        for (String name : needLists) {
            sb.append("\n    public static java.util.List<ConfigItem> ").append(name).append("() {\n        return java.util.Arrays.asList(")
                    .append(String.join(", ", lists.get(name))).append(");\n    }\n");
        }
        sb.append("}\n");
        if (!ensureLoaded) {
            log.add("config: ensureLoaded() unused");
        }
        write(javaOut.resolve("md3player/gen/AppearanceConfig.java"), sb.toString());
        log.add("config: " + all.size() + " keys, getters " + needGetters + ", lists " + needLists);
    }

    private void generateStrings(Path javaOut) throws Exception {
        Map<String, Map<String, String>> table = new TreeMap<>();
        // every plugin-owned string ships, the plugin script uses some that only exteraless settings used
        table.putAll(forkStrings);
        if (!extraStrings.isEmpty()) {
            Map<String, Map<String, String>> found = loadStrings(upstream.resolve(RES_DIR), null, extraStrings);
            for (String key : extraStrings) {
                Map<String, String> values = found.get(key);
                if (values == null || !values.containsKey("en")) {
                    problems.add("string '" + key + "' exists neither in inugram nor in exteraless resources");
                } else {
                    table.put(key, values);
                    log.add("strings: '" + key + "' is missing in inugram, shipped from exteraless");
                }
            }
        }
        Set<String> langs = new java.util.TreeSet<>();
        for (Map<String, String> values : table.values()) {
            langs.addAll(values.keySet());
        }
        langs.remove("en");
        List<String> langList = new ArrayList<>();
        langList.add("en");
        langList.addAll(langs);

        StringBuilder sb = new StringBuilder();
        sb.append(header()).append("package md3player.gen;\n\n")
                .append("/** Plugin-owned strings (exteraless strings_oe_player.xml and strings missing in inugram). */\n")
                .append("public final class Strings {\n\n    private Strings() {\n    }\n\n")
                .append("    public static final String[] KEYS = {\n");
        for (String key : table.keySet()) {
            sb.append("            ").append(javaString(key)).append(",\n");
        }
        sb.append("    };\n\n    public static final String[] LANGS = {");
        sb.append(langList.stream().map(Porter::javaString).collect(Collectors.joining(", "))).append("};\n\n");
        sb.append("    /** VALUES[lang][key]; null means 'fall back to English'. */\n");
        sb.append("    public static final String[][] VALUES = {\n");
        for (String lang : langList) {
            sb.append("            {\n");
            for (Map.Entry<String, Map<String, String>> e : table.entrySet()) {
                String v = e.getValue().get(lang);
                sb.append("                    ").append(v == null ? "null" : javaString(v)).append(",\n");
            }
            sb.append("            },\n");
        }
        sb.append("    };\n}\n");
        write(javaOut.resolve("md3player/gen/Strings.java"), sb.toString());
        log.add("strings: " + table.size() + " keys, languages " + langList);
    }

    private void generatePortInfo(Path javaOut) throws IOException {
        String sha = args.getOrDefault("sha", "unknown");
        write(javaOut.resolve("md3player/gen/PortInfo.java"), header() + "package md3player.gen;\n\n"
                + "public final class PortInfo {\n\n    private PortInfo() {\n    }\n\n"
                + "    public static final String UPSTREAM_SHA = " + javaString(sha) + ";\n}\n");
    }

    private String header() {
        return "// Generated by md3-player porter from exteraless@" + args.getOrDefault("sha", "unknown") + ". Do not edit.\n";
    }

    // ---------------------------------------------------------------- compile loop

    private boolean compileLoop() throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("porter needs a JDK, not a JRE");
        }
        Path classes = Paths.get(args.getOrDefault("classes", out.resolve("classes").toString()));
        for (int pass = 1; pass <= 10; pass++) {
            deleteRecursively(classes);
            Files.createDirectories(classes);
            List<File> files = new ArrayList<>();
            try (Stream<Path> s = Files.walk(out.resolve("java"))) {
                s.filter(p -> p.toString().endsWith(".java")).forEach(p -> files.add(p.toFile()));
            }
            String compat = args.get("compat");
            if (compat != null) {
                try (Stream<Path> s = Files.walk(Paths.get(compat))) {
                    s.filter(p -> p.toString().endsWith(".java")).forEach(p -> files.add(p.toFile()));
                }
            }
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            boolean ok;
            try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
                List<String> options = List.of("-nowarn", "-encoding", "UTF-8", "--release", "17", "-proc:none",
                        "-Xmaxerrs", "100000", "-cp", require("classpath"), "-d", classes.toString());
                ok = compiler.getTask(null, fm, diagnostics, options, null, fm.getJavaFileObjectsFromFiles(files)).call();
            }
            if (ok) {
                log.add("compile: ok after " + pass + " pass(es)");
                return true;
            }
            int fixed = 0;
            List<String> unfixable = new ArrayList<>();
            // javac's diagnostic order differs between machines; keep fixes and the report deterministic
            List<Diagnostic<? extends JavaFileObject>> sorted = new ArrayList<>(diagnostics.getDiagnostics());
            sorted.sort(Comparator.<Diagnostic<? extends JavaFileObject>, String>comparing(
                            d -> d.getSource() == null ? "" : d.getSource().getName())
                    .thenComparingLong(Diagnostic::getLineNumber)
                    .thenComparingLong(Diagnostic::getColumnNumber));
            for (Diagnostic<? extends JavaFileObject> d : sorted) {
                if (d.getKind() != Diagnostic.Kind.ERROR) {
                    continue;
                }
                Path file = d.getSource() == null ? null : Paths.get(d.getSource().toUri()).toAbsolutePath().normalize();
                CompilationUnit cu = file == null ? null : unitFor(file);
                if (cu != null && fix(cu, d)) {
                    fixed++;
                } else {
                    unfixable.add(describe(d));
                }
            }
            if (fixed == 0) {
                problems.addAll(unfixable);
                return false;
            }
            log.add("compile pass " + pass + ": auto-fixed " + fixed + " error(s)");
            writeUnits();
        }
        problems.add("compile loop did not converge");
        return false;
    }

    private CompilationUnit unitFor(Path file) {
        for (Map.Entry<Path, CompilationUnit> e : units.entrySet()) {
            if (e.getKey().toAbsolutePath().normalize().equals(file)) {
                return e.getValue();
            }
        }
        return null;
    }

    private boolean fix(CompilationUnit cu, Diagnostic<? extends JavaFileObject> d) {
        Position pos = new Position((int) d.getLineNumber(), (int) d.getColumnNumber());
        String code = d.getCode();
        String where = typeName(cu) + ":" + d.getLineNumber();
        switch (code) {
            case "compiler.err.override.weaker.access": {
                MethodDeclaration md = innermost(cu, MethodDeclaration.class, pos);
                if (md == null) return false;
                md.setModifier(Modifier.Keyword.PROTECTED, false);
                md.setModifier(Modifier.Keyword.PRIVATE, false);
                md.setModifier(Modifier.Keyword.PUBLIC, true);
                log.add("fix " + where + ": made " + md.getNameAsString() + " public");
                return true;
            }
            case "compiler.err.override.meth.doesnt.throw": {
                MethodDeclaration md = innermost(cu, MethodDeclaration.class, pos);
                if (md == null || md.getThrownExceptions().isEmpty()) return false;
                md.setThrownExceptions(new NodeList<>());
                log.add("fix " + where + ": dropped throws of " + md.getNameAsString() + " (stripped by R8 in the base method)");
                return true;
            }
            case "compiler.err.unreported.exception.need.to.catch.or.throw": {
                Node callable = innermostCallable(cu, pos);
                if (callable == null || wrapped.contains(callable)) return false;
                if (!sneakyWrap(callable)) return false;
                wrapped.add(callable);
                log.add("fix " + where + ": rethrow checked exceptions sneakily");
                return true;
            }
            case "compiler.err.except.never.thrown.in.try": {
                CatchClause cc = innermost(cu, CatchClause.class, pos);
                if (cc == null) return false;
                cc.getParameter().setType(StaticJavaParser.parseClassOrInterfaceType("Exception"));
                log.add("fix " + where + ": catch Exception (checked exception stripped by R8)");
                return true;
            }
            case "compiler.err.method.does.not.override.superclass": {
                // the base hook exists only in the exteraless fork; the method stays as dead code
                MethodDeclaration md = innermost(cu, MethodDeclaration.class, pos);
                if (md == null || !md.getAnnotationByName("Override").isPresent()) return false;
                md.getAnnotationByName("Override").get().remove();
                warnings.add(typeName(cu) + "." + md.getNameAsString()
                        + "() overrides a fork-only base method; it is never called on inugram");
                log.add("fix " + where + ": dropped `@Override` of fork-only " + md.getNameAsString());
                return true;
            }
            case "compiler.err.except.already.caught": {
                CatchClause cc = innermost(cu, CatchClause.class, pos);
                if (cc == null) return false;
                cc.remove();
                log.add("fix " + where + ": removed duplicate catch");
                return true;
            }
            default:
                return false;
        }
    }

    private static String typeName(CompilationUnit cu) {
        return cu.getTypes().isEmpty() ? "?" : cu.getType(0).getNameAsString();
    }

    private boolean sneakyWrap(Node callable) {
        BlockStmt body;
        if (callable instanceof MethodDeclaration) {
            body = ((MethodDeclaration) callable).getBody().orElse(null);
        } else if (callable instanceof ConstructorDeclaration) {
            return false; // super() must stay first
        } else if (callable instanceof LambdaExpr && ((LambdaExpr) callable).getBody() instanceof BlockStmt) {
            body = (BlockStmt) ((LambdaExpr) callable).getBody();
        } else {
            return false;
        }
        if (body == null) return false;
        BlockStmt replacement = StaticJavaParser.parseBlock(
                "{ try { } catch (Throwable md3Throwable) { throw md3player.compat.Sneaky.raise(md3Throwable); } }");
        TryStmt tryStmt = (TryStmt) replacement.getStatement(0);
        tryStmt.setTryBlock(body.clone());
        body.replace(replacement);
        return true;
    }

    private static Node innermostCallable(CompilationUnit cu, Position pos) {
        Node best = null;
        for (Node n : cu.findAll(Node.class, n -> n instanceof MethodDeclaration || n instanceof ConstructorDeclaration || n instanceof LambdaExpr)) {
            if (contains(n, pos) && (best == null || contains(best, n))) {
                best = n;
            }
        }
        return best;
    }

    private static <T extends Node> T innermost(CompilationUnit cu, Class<T> type, Position pos) {
        T best = null;
        for (T n : cu.findAll(type)) {
            if (contains(n, pos) && (best == null || contains(best, n))) {
                best = n;
            }
        }
        return best;
    }

    private static boolean contains(Node outer, Node inner) {
        return inner.getRange().isPresent() && contains(outer, inner.getRange().get().begin) && contains(outer, inner.getRange().get().end);
    }

    private static boolean contains(Node n, Position p) {
        if (n.getRange().isEmpty()) return false;
        Position b = n.getRange().get().begin;
        Position e = n.getRange().get().end;
        boolean afterBegin = p.line > b.line || p.line == b.line && p.column >= b.column;
        boolean beforeEnd = p.line < e.line || p.line == e.line && p.column <= e.column;
        return afterBegin && beforeEnd;
    }

    private static String describe(Diagnostic<? extends JavaFileObject> d) {
        String file = d.getSource() == null ? "?" : Paths.get(d.getSource().toUri()).getFileName().toString();
        return file + ":" + d.getLineNumber() + ": [" + d.getCode() + "] " + d.getMessage(Locale.ROOT).replace('\n', ' ');
    }

    // ---------------------------------------------------------------- io / checks

    private void writeUnits() throws IOException {
        for (Map.Entry<Path, CompilationUnit> e : units.entrySet()) {
            String code;
            try {
                code = LexicalPreservingPrinter.print(e.getValue());
            } catch (RuntimeException ex) {
                code = e.getValue().toString();
            }
            write(e.getKey(), header() + code);
        }
    }

    private void checkLeftovers() throws IOException {
        for (Path p : units.keySet()) {
            String code = Files.readString(p, StandardCharsets.UTF_8);
            for (String prefix : rules.forbid) {
                int at = code.indexOf(prefix);
                if (at >= 0) {
                    problems.add(p.getFileName() + ": still references " + prefix + " near `" + snippet(code, at) + "`");
                }
            }
            Matcher m = R_LEFTOVER.matcher(code);
            while (m.find()) {
                problems.add(p.getFileName() + ": unsupported resource reference " + m.group() + " (extend the porter)");
            }
        }
    }

    private boolean report(boolean ok) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# Port report\n\n- upstream: exteraless@").append(args.getOrDefault("sha", "unknown")).append('\n')
                .append("- files: ").append(units.size()).append('\n')
                .append("- result: ").append(ok ? "OK" : "FAILED").append("\n\n");
        if (!problems.isEmpty()) {
            sb.append("## Problems\n\n");
            for (String p : problems) sb.append("- ").append(p).append('\n');
            sb.append('\n');
        }
        if (!warnings.isEmpty()) {
            sb.append("## Warnings\n\n");
            for (String w : warnings) sb.append("- ").append(w).append('\n');
            sb.append('\n');
        }
        sb.append("## Log\n\n");
        Map<String, Integer> counted = new LinkedHashMap<>();
        for (String l : log) counted.merge(l, 1, Integer::sum);
        for (Map.Entry<String, Integer> e : counted.entrySet()) {
            sb.append("- ").append(e.getKey());
            if (e.getValue() > 1) sb.append(" (x").append(e.getValue()).append(')');
            sb.append('\n');
        }
        write(out.resolve("port-report.md"), sb.toString());
        System.out.print(sb);
        return ok && problems.isEmpty();
    }

    private static String snippet(String code, int at) {
        int s = Math.max(0, at - 30);
        int e = Math.min(code.length(), at + 50);
        return code.substring(s, e).replace('\n', ' ');
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (Stream<Path> s = Files.walk(path)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                Files.delete(p);
            }
        }
    }

    private static String javaString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\t': sb.append("\\t"); break;
                case '\r': sb.append("\\r"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    // ---------------------------------------------------------------- classpath / resources

    private static URLClassLoader classpathLoader(String classpath) throws Exception {
        List<URL> urls = new ArrayList<>();
        for (String part : classpath.split(File.pathSeparator)) {
            if (!part.isEmpty()) urls.add(new File(part).toURI().toURL());
        }
        return new URLClassLoader(urls.toArray(new URL[0]), null);
    }

    private static Set<String> rFields(ClassLoader loader, String kind) {
        Set<String> names = new HashSet<>();
        try {
            Class<?> r = Class.forName("org.telegram.messenger.R$" + kind, false, loader);
            for (Field f : r.getDeclaredFields()) names.add(f.getName());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("classpath has no org.telegram.messenger.R$" + kind + "; is the inugram jar on --classpath?");
        }
        return names;
    }

    /**
     * Reads android string resources. With {@code fileName} only that file in every values* dir is read;
     * otherwise every strings*.xml is scanned for {@code only} keys.
     */
    private static Map<String, Map<String, String>> loadStrings(Path resDir, String fileName, Set<String> only) throws Exception {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        List<Path> dirs;
        try (Stream<Path> s = Files.list(resDir)) {
            dirs = s.filter(p -> p.getFileName().toString().startsWith("values")).sorted().collect(Collectors.toList());
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        for (Path dir : dirs) {
            String lang = langOf(dir.getFileName().toString());
            if (lang == null) continue;
            List<Path> files;
            try (Stream<Path> s = Files.list(dir)) {
                files = s.filter(p -> {
                    String n = p.getFileName().toString();
                    return fileName != null ? n.equals(fileName) : n.startsWith("strings") && n.endsWith(".xml");
                }).sorted().collect(Collectors.toList());
            }
            for (Path f : files) {
                Document doc = factory.newDocumentBuilder().parse(f.toFile());
                org.w3c.dom.NodeList list = doc.getElementsByTagName("string");
                for (int i = 0; i < list.getLength(); i++) {
                    Element el = (Element) list.item(i);
                    String name = el.getAttribute("name");
                    if (only != null && !only.contains(name)) continue;
                    result.computeIfAbsent(name, k -> new LinkedHashMap<>()).putIfAbsent(lang, unescape(el.getTextContent()));
                }
            }
        }
        return result;
    }

    private static String langOf(String dir) {
        if (dir.equals("values")) return "en";
        String q = dir.substring("values-".length());
        String first = q.split("-")[0];
        return first.matches("[a-z]{2,3}") ? first : null;
    }

    static String unescape(String raw) {
        String s = raw;
        boolean quoted = s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"");
        if (quoted) {
            s = s.substring(1, s.length() - 1);
        } else {
            s = s.replaceAll("\\s+", " ").trim();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (i + 4 < s.length()) {
                            sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        }
                        break;
                    default: sb.append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- rules

    static final class Rules {
        static final class CallRule {
            final String target;
            final boolean receiverAsArg;

            CallRule(String target, boolean receiverAsArg) {
                this.target = target;
                this.receiverAsArg = receiverAsArg;
            }
        }

        final List<String[]> fqns = new ArrayList<>();
        final Map<String, String> fields = new LinkedHashMap<>();
        final Map<String, CallRule> calls = new LinkedHashMap<>();
        final Map<String, String> wrapNew = new LinkedHashMap<>();
        final Map<String, String> literals = new LinkedHashMap<>();
        final Map<String, String> drawables = new LinkedHashMap<>();
        final List<String> forbid = new ArrayList<>();

        static Rules load(Path file) throws IOException {
            Rules r = new Rules();
            int lineNo = 0;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                lineNo++;
                int hash = line.indexOf('#');
                if (hash >= 0) line = line.substring(0, hash);
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] p = line.split("\\s+");
                try {
                    switch (p[0]) {
                        case "fqn": r.fqns.add(new String[]{p[1], p[2]}); break;
                        case "field": r.fields.put(p[1], p[2]); break;
                        case "call": r.calls.put(p[1], new CallRule(p[3], p[2].equals("arg"))); break;
                        case "wrapnew": r.wrapNew.put(p[1], p[2]); break;
                        case "literal": r.literals.put(p[1], p[2]); break;
                        case "drawable": r.drawables.put(p[1], p[2]); break;
                        case "forbid": r.forbid.add(p[1]); break;
                        default: throw new IllegalArgumentException("unknown rule kind " + p[0]);
                    }
                } catch (ArrayIndexOutOfBoundsException e) {
                    throw new IllegalArgumentException(file + ":" + lineNo + ": not enough arguments");
                }
            }
            r.fqns.sort((a, b) -> b[0].length() - a[0].length());
            return r;
        }

        String renameFqns(String text) {
            for (String[] f : fqns) {
                text = text.replace(f[0], f[1]);
            }
            return text;
        }
    }
}
