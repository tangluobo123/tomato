package com.tangluobo.tomato.module.tools.extractor;

import com.tangluobo.tomato.module.tools.extractor.core.FileScanner;
import com.tangluobo.tomato.module.tools.extractor.core.ScanResult;
import com.tangluobo.tomato.module.tools.extractor.format.FormatCategory;
import com.tangluobo.tomato.module.tools.extractor.format.FormatRegistry;
import com.tangluobo.tomato.module.tools.extractor.pe.PEFile;
import com.tangluobo.tomato.module.tools.extractor.pe.PEResourceExtractor;
import com.tangluobo.tomato.module.tools.extractor.utils.FileUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * MultiExtractor 命令行逻辑封装.
 *
 * <p>用法:
 * <pre>
 *   Main.run(new String[]{...});
 *
 *   模式:
 *     scan    - 仅扫描并列出发现的资源 (默认)
 *     extract - 扫描并提取到 --out 指定目录
 *     pe      - 仅提取 PE 资源 (从 .exe/.dll/.scr 中提取图标/位图等)
 *     list    - 列出所有支持的格式
 *
 *   选项:
 *     -o, --out &lt;dir&gt;       输出目录 (extract 模式)
 *     -r, --recursive       递归扫描目录
 *     --formats &lt;ext,ext&gt;   仅扫描指定扩展名格式 (如 png,jpg,ico)
 *     --categories &lt;cat&gt;   仅扫描指定分类 (GFX,MUSIC,VIDEO,DOCUMENTS,FONTS,ARCHIVE,OTHER)
 *     --pe                  同时尝试解析 PE 资源
 *     --unpack              解包 ZIP 文件并扫描内容
 *     --pe-scan             扫描 PE 资源段内的资源数据 (Qt 应用必备)
 *     --overwrite           覆盖已存在文件
 *     --min-size &lt;bytes&gt;    最小资源大小 (默认 16)
 *     --max-file-size &lt;b&gt;   最大源文件大小 (默认 512MB)
 *     -v, --verbose         详细输出
 *     -h, --help            显示帮助
 * </pre>
 */
public class Extractor {

    enum Mode { SCAN, EXTRACT, PE, LIST }

    public void run(String[] args) {
        if (args.length == 0) {
            printHelp();
            return;
        }

        Mode mode = Mode.SCAN;
        Path source = null;
        Path outDir = null;
        boolean recursive = false;
        boolean peAlso = false;
        boolean overwrite = false;
        boolean verbose = false;
        boolean unpack = false;
        boolean peScan = true;
        boolean dedup = false;
        long minSize = 16;
        long maxFileSize = 512L * 1024 * 1024;
        Set<String> formatFilter = null;
        Set<FormatCategory> catFilter = null;

        try {
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "-h":
                    case "--help":
                        printHelp();
                        return;
                    case "scan":
                    case "extract":
                    case "pe":
                    case "list":
                        mode = Mode.valueOf(a.toUpperCase());
                        break;
                    case "-o":
                    case "--out":
                        outDir = Paths.get(args[++i]);
                        break;
                    case "-r":
                    case "--recursive":
                        recursive = true;
                        break;
                    case "--pe":
                        peAlso = true;
                        break;
                    case "--unpack":
                        unpack = true;
                        break;
                    case "--pe-scan":
                        peScan = true;
                        break;
                    case "--no-pe-scan":
                        peScan = false;
                        break;
                    case "--dedup":
                        dedup = true;
                        break;
                    case "--overwrite":
                        overwrite = true;
                        break;
                    case "-v":
                    case "--verbose":
                        verbose = true;
                        break;
                    case "--min-size":
                        minSize = Long.parseLong(args[++i]);
                        break;
                    case "--max-file-size":
                        maxFileSize = Long.parseLong(args[++i]);
                        break;
                    case "--formats":
                        formatFilter = new HashSet<>();
                        for (String e : args[++i].split(",")) {
                            formatFilter.add(e.trim().toLowerCase());
                        }
                        break;
                    case "--categories":
                        catFilter = new HashSet<>();
                        for (String c : args[++i].split(",")) {
                            catFilter.add(FormatCategory.valueOf(c.trim().toUpperCase()));
                        }
                        break;
                    default:
                        if (!a.startsWith("-")) {
                            source = Paths.get(a);
                        } else {
                            System.err.println("Unknown option: " + a);
                            printHelp();
                            return;
                        }
                }
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            System.err.println("Missing value for option");
            printHelp();
            return;
        } catch (IllegalArgumentException e) {
            System.err.println("Invalid value: " + e.getMessage());
            return;
        }

        FormatRegistry registry = new FormatRegistry();
        if (formatFilter != null) {
            registry.enableOnly(formatFilter);
        } else if (catFilter != null) {
            registry.enableAll();
            registry.enableOnly(new HashSet<>());
            for (FormatCategory c : catFilter) {
                registry.enableCategory(c);
            }
        }

        if (mode == Mode.LIST) {
            listFormats(registry);
            return;
        }

        if (source == null) {
            System.err.println("Error: source file or directory is required.");
            printHelp();
            return;
        }
        if (!Files.exists(source)) {
            System.err.println("Error: source does not exist: " + source);
            return;
        }

        if (mode == Mode.PE) {
            runPeExtract(source, outDir, recursive, overwrite, verbose);
            return;
        }

        FileScanner scanner = new FileScanner(registry);
        scanner.setVerbose(verbose);
        scanner.setMinResourceSize(minSize);
        scanner.setMaxFileSize(maxFileSize);
        scanner.setUnpackArchives(unpack);
        scanner.setPeScan(peScan);

        List<ScanResult> results = new ArrayList<>();
        try {
            if (Files.isDirectory(source)) {
                results = scanner.scanDirectory(source, recursive);
            } else {
                results = scanner.scan(source);
            }
        } catch (IOException e) {
            System.err.println("Scan error: " + e.getMessage());
            return;
        }

        printResults(results);

        if (mode == Mode.EXTRACT) {
            if (outDir == null) {
                outDir = Paths.get(System.getProperty("user.dir"), "extracted");
            }
            System.out.println();
            System.out.println("Output directory: " + outDir);
            com.tangluobo.tomato.module.tools.extractor.core.Extractor extractor = new com.tangluobo.tomato.module.tools.extractor.core.Extractor(outDir, overwrite, verbose, dedup);
            extractor.extractAll(results);
        }

        if (peAlso) {
            System.out.println();
            System.out.println("--- PE Resource Extraction ---");
            runPeExtract(source, outDir == null ? Paths.get(System.getProperty("user.dir"), "extracted_pe") : outDir.resolve("pe"),
                    recursive, overwrite, verbose);
        }

        System.out.println();
        System.out.printf("Total resources found: %d%n", results.size());
    }

    private void printResults(List<ScanResult> results) {
        if (results.isEmpty()) {
            System.out.println("No resources found.");
            return;
        }
        System.out.println();
        System.out.println("--- Scan Results ---");
        System.out.printf("%-6s %-12s %-10s %s%n", "TYPE", "OFFSET", "SIZE", "SOURCE");
        int limit = Math.min(results.size(), 200);
        for (int i = 0; i < limit; i++) {
            ScanResult r = results.get(i);
            System.out.printf("%-6s 0x%-10X %-10s %s%n",
                    r.getFormat().getExtension(),
                    r.getOffset(),
                    FileUtils.humanSize(r.getSize()),
                    r.getArchiveEntry() != null ? r.getArchiveEntry()
                            : (r.getSourceFile() == null ? "<mem>" : r.getSourceFile().getFileName()));
        }
        if (results.size() > limit) {
            System.out.printf("... and %d more (use extract mode to write them)%n", results.size() - limit);
        }
    }

    private void runPeExtract(Path source, Path outDir, boolean recursive,
                              boolean overwrite, boolean verbose) {
        List<Path> peFiles = new ArrayList<>();
        if (Files.isDirectory(source)) {
            try {
                Files.walk(source, recursive ? Integer.MAX_VALUE : 1)
                        .filter(Files::isRegularFile)
                        .forEach(peFiles::add);
            } catch (IOException e) {
                System.err.println("Walk error: " + e.getMessage());
            }
        } else {
            peFiles.add(source);
        }

        List<Path> peTargets = new ArrayList<>();
        for (Path p : peFiles) {
            String name = p.getFileName().toString().toLowerCase();
            if (name.endsWith(".exe") || name.endsWith(".dll") || name.endsWith(".scr")
                    || name.endsWith(".fon") || name.endsWith(".sys") || name.endsWith(".ocx")) {
                peTargets.add(p);
            }
        }

        if (peTargets.isEmpty()) {
            System.out.println("No PE files found.");
            return;
        }

        if (outDir == null) {
            outDir = Paths.get(System.getProperty("user.dir"), "extracted_pe");
        }
        try {
            FileUtils.ensureDir(outDir);
        } catch (IOException e) {
            System.err.println("Cannot create output dir: " + e.getMessage());
            return;
        }

        PEResourceExtractor peExtractor = new PEResourceExtractor();
        int total = 0;
        for (Path peFile : peTargets) {
            if (verbose) {
                System.out.println("[pe] " + peFile);
            }
            try (PEFile pe = new PEFile(peFile)) {
                List<PEResourceExtractor.ExtractedResource> resList = peExtractor.extract(pe);
                if (resList.isEmpty()) {
                    if (verbose) System.out.println("    (no resources)");
                    continue;
                }
                String baseName = stripExt(peFile.getFileName().toString());
                for (PEResourceExtractor.ExtractedResource r : resList) {
                    String ext = r.extension.toLowerCase();
                    Path peOutDir = outDir.resolve(ext);
                    FileUtils.ensureDir(peOutDir);
                    Path outFile = peOutDir.resolve(baseName + "_" + r.name + "." + r.extension);
                    int n = 1;
                    while (Files.exists(outFile) && !overwrite) {
                        outFile = peOutDir.resolve(baseName + "_" + r.name + "_" + (n++) + "." + r.extension);
                    }
                    try (OutputStream os = Files.newOutputStream(outFile,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                        os.write(r.data);
                    }
                    total++;
                    if (verbose) {
                        System.out.printf("    [ok] %s (%s)%n", outFile.getFileName(),
                                FileUtils.humanSize(r.data.length));
                    }
                }
            } catch (IOException e) {
                if (verbose) {
                    System.err.println("    [error] " + e.getMessage());
                }
            }
        }
        System.out.println();
        System.out.printf("PE resources extracted: %d%n", total);
    }

    private void listFormats(FormatRegistry registry) {
        System.out.println("Supported formats:");
        for (FormatCategory cat : FormatCategory.values()) {
            System.out.println();
            System.out.println("[" + cat.getDisplayName() + "]");
            for (com.tangluobo.tomato.module.tools.extractor.format.FileFormatInfo info : registry.getAllFormats()) {
                if (info.getCategory() == cat) {
                    System.out.printf("  %-6s %s%n", info.getExtension(), info.getDescription());
                }
            }
        }
    }

    private String stripExt(String name) {
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }

    private void printHelp() {
        System.out.println("MultiExtractor - Java implementation");
        System.out.println("Extracts embedded resources from binary files based on file signatures");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java -jar multiextractor.jar [mode] [options] <file|dir>");
        System.out.println();
        System.out.println("Modes:");
        System.out.println("  scan      Scan and list resources (default)");
        System.out.println("  extract   Scan and extract to output directory");
        System.out.println("  pe        Extract PE resources only (.exe/.dll/.scr)");
        System.out.println("  list      List all supported formats");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -o, --out <dir>       Output directory (extract mode)");
        System.out.println("  -r, --recursive       Recursively scan directories");
        System.out.println("  --formats <ext,ext>   Only scan these formats (e.g. png,jpg,ico)");
        System.out.println("  --categories <cat>    Only scan these categories (GFX,MUSIC,VIDEO,...)");
        System.out.println("  --pe                   Also extract PE resources");
        System.out.println("  --unpack               Unpack ZIP archives and scan contents");
        System.out.println("  --pe-scan              Scan PE .rsrc section data for embedded formats (default on)");
        System.out.println("  --no-pe-scan           Disable PE .rsrc section scanning");
        System.out.println("  --dedup                Deduplicate by source+offset (default off)");
        System.out.println("  --overwrite            Overwrite existing files");
        System.out.println("  --min-size <bytes>     Min resource size (default 16)");
        System.out.println("  --max-file-size <b>    Max source file size (default 512MB)");
        System.out.println("  -v, --verbose          Verbose output");
        System.out.println("  -h, --help             Show this help");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java -jar multiextractor.jar scan game.exe");
        System.out.println("  java -jar multiextractor.jar extract -o out/ -r assets/");
        System.out.println("  java -jar multiextractor.jar extract -o out/ --unpack --pe-scan -r firefox/");
        System.out.println("  java -jar multiextractor.jar pe app.exe -o pe_out/");
        System.out.println("  java -jar multiextractor.jar list");
    }
}