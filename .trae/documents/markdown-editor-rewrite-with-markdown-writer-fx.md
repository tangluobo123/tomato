# 参考 markdown-writer-fx 全量重写 Markdown 编辑器

## Context（背景）

当前项目的 Markdown 编辑器是单文件实现 [MarkdownEditorPane.java](file:///d:/Data/Git/tangluobo/tomata/src/main/java/com/tangluobo/tomato/module/connect/MarkdownEditorPane.java)（2449 行，约 111KB），使用 RichTextFX `InlineCssTextArea` + commonmark + 自研正则高亮 + 自研 TextFlow/VBox 原生预览。问题：

- **高亮粗糙**：基于正则 `MD_PATTERN`，无法精确识别嵌套结构、表格、任务列表等
- **单文件巨类**：2449 行混合了编辑器、高亮、预览、查找替换、矩形选择、S3/OSS 存储、打印、工具栏等所有逻辑
- **缺少高级编辑能力**：无行号 gutter、无段落覆盖层、无空白字符显示、无智能编辑（SmartEdit）、无 AST 视图、无拼写检查

参考项目 `D:\Data\Git\github\markdown-writer-fx` 是纯原生 JavaFX 实现（RichTextFX `GenericStyledArea`，**非 webview** 编辑器），基于 flexmark AST 精确高亮，模块化组织（editor/preview/options/spellchecker/dialogs/controls/util/addons）。用户已确认全量移植其架构。

## 用户决策

| 决策点 | 选择 |
|--------|------|
| 重写范围 | 全量移植（编辑器核心 + 预览非webview部分 + 选项 + 对话框 + 控件 + 拼写检查 + 工具） |
| 解析器 | flexmark 0.64.0 全套扩展替换 commonmark |
| 依赖 | 完整对齐（flexmark/controlsfx/miglayout/wellbehavedfx/reactfx/flowless/undofx/languagetool/guava） |
| 代码组织 | 拆分为多文件，在 `module/connect/markdown` 下建子包 |
| 预览 | **跳过 WebView**，保留当前原生 TextFlow/VBox 预览（可选中复制、支持表格）；可移植 HTML 源码预览 + AST 预览 |
| 拼写检查 | 移植（引入 LanguageTool 6.0 + guava） |
| 项目管理 | **跳过**（Options 中项目级选项改为全局选项） |

## 必须保留的 API 契约（调用方依赖）

调用方：[S3FileBrowserPane.java](file:///d:/Data/Git/tangluobo/tomata/src/main/java/com/tangluobo/tomato/module/connect/S3FileBrowserPane.java)、[SFTPFileBrowserPane.java](file:///d:/Data/Git/tangluobo/tomata/src/main/java/com/tangluobo/tomato/module/connect/SFTPFileBrowserPane.java)、[LocalDirectoryConnectHandler.java](file:///d:/Data/Git/tangluobo/tomata/src/main/java/com/tangluobo/tomato/module/connect/handler/LocalDirectoryConnectHandler.java)

新实现必须保留：
- `extends BorderPane`（调用方直接 `tab.setContent(editor)` 或 `setCenter(editor)`）
- 构造器：`MarkdownEditorPane(ConnectionConfig, String bucket, String key, String displayName, String initialContent)`、`MarkdownEditorPane(String displayName, String initialContent, Storage storage)`
- 方法：`save()`、`isModified()`、`getDisplayName()`、`getDisplayTitle()`、`setOnTitleChange(Consumer<String>)`、`showFindReplace(boolean)`、`goToMatch(boolean)`、`replaceCurrent()`、`replaceAll()`、`exportHtml()`、`exportPdf()`
- 静态方法：`loadMarkdownContent(ConnectionConfig, String, String, BiConsumer<String,String>)`
- 嵌套类型：`Storage` 接口、`Mode` 枚举（EDIT/EDIT_PREVIEW/PREVIEW）、`InlineStyle` 静态类、`Switch` 静态类

## 必须保留的当前独有功能

- S3/OSS 存储（`Storage` 接口 + `ConnectionConfig` 委托）
- Alt+鼠标矩形选择（列选择、删除、多行插入）
- 打印 PDF（PrinterJob）
- 三模式切换（EDIT / EDIT_PREVIEW / PREVIEW）
- 工具栏（FontAwesomeIcon 按钮）
- iOS 风格 Switch 控件
- 原生 TextFlow/VBox + GridPane 预览（可选中复制、支持表格）

## 目标包结构

新建根包：`com.tangluobo.tomato.module.connect.markdown`

```
module/connect/markdown/
├── MarkdownEditorPane.java          # 主类，extends BorderPane，保留 API 契约
├── editor/
│   ├── MarkdownTextArea.java        # extends GenericStyledArea（支持 EmbeddedImage）
│   ├── LineNumberGutterFactory.java # 行号 gutter
│   ├── ParagraphOverlayGraphicFactory.java  # 段落覆盖层工厂
│   ├── WhitespaceOverlayFactory.java        # 空白字符显示
│   ├── SmartEdit.java               # 智能编辑
│   ├── SmartEditActions.java        # 智能编辑动作/右键菜单
│   ├── FindReplacePane.java         # 查找替换面板（内嵌底部）
│   ├── EmbeddedImage.java           # 嵌入图片
│   └── EmbeddedImageOps.java        # 嵌入图片 SegmentOps
├── highlighter/
│   ├── MarkdownSyntaxHighlighter.java  # 基于 flexmark AST 的高亮
│   └── SyntaxHighlighter.java          # 通用语法高亮（HTML 源码预览用）
├── preview/
│   ├── MarkdownPreviewPane.java     # 预览面板（HTML源码/AST/原生TextFlow）
│   ├── FlexmarkPreviewRenderer.java # flexmark HTML 渲染器
│   ├── HtmlSourcePreview.java       # HTML 源码预览（PreviewStyledTextArea）
│   ├── PreviewStyledTextArea.java   # 预览用 StyledTextArea
│   ├── ASTPreview.java              # AST 树预览
│   └── NativePreviewRenderer.java   # 保留当前原生 TextFlow/VBox 预览（适配）
├── options/
│   ├── Options.java                 # 全局选项（java.util.prefs.Preferences）
│   ├── OptionsDialog.java           # 选项对话框
│   ├── GeneralOptionsPane.java      # 通用选项面板
│   ├── EditorOptionsPane.java       # 编辑器选项面板
│   ├── MarkdownOptionsPane.java     # Markdown 选项面板
│   ├── MarkdownExtensionsPane.java  # Markdown 扩展面板
│   ├── MarkdownExtensions.java      # flexmark 扩展配置
│   ├── SpellCheckerOptionsPane.java # 拼写检查选项面板
│   └── StylesheetsOptionsPane.java  # 样式表选项面板
├── dialogs/
│   ├── LinkDialog.java              # 插入链接对话框
│   └── ImageDialog.java             # 插入图片对话框
├── controls/
│   ├── BottomSlidePane.java         # 底部滑动面板
│   ├── BrowseDirectoryButton.java   # 浏览目录按钮
│   ├── BrowseFileButton.java        # 浏览文件按钮
│   ├── EscapeTextField.java         # ESC 退出文本框
│   ├── FileTreeView.java            # 文件树视图
│   ├── FileTreeCell.java            # 文件树单元格
│   ├── FileTreeItem.java            # 文件树项
│   ├── IntSpinner.java              # 整数微调器
│   └── WebHyperlink.java            # 超链接（保留，但不依赖 webview）
├── spellchecker/
│   ├── SpellChecker.java            # 拼写检查器
│   ├── SpellCheckerOverlayFactory.java  # 拼写错误覆盖层
│   ├── SpellProblem.java            # 拼写问题
│   ├── SpellRange.java              # 拼写范围
│   ├── SpellBlockProblems.java      # 段落拼写问题
│   ├── GlobalLanguageTool.java      # LanguageTool 全局实例
│   └── UserDictionary.java          # 用户词典
├── util/
│   ├── Utils.java                   # 工具类
│   ├── ResultCacheEx.java           # 结果缓存
│   ├── CommonmarkSourcePositions.java  # commonmark 源码位置
│   ├── Item.java                    # 项
│   ├── Range.java                   # 范围
│   ├── StageState.java              # 窗口状态
│   ├── Addons.java                  # 插件加载
│   ├── Action.java                  # 动作
│   ├── ActionUtils.java             # 动作工具
│   ├── PrefsBooleanProperty.java    # Preferences 布尔属性
│   ├── PrefsIntegerProperty.java    # Preferences 整数属性
│   ├── PrefsStringProperty.java     # Preferences 字符串属性
│   ├── PrefsStringsProperty.java    # Preferences 字符串列表属性
│   └── PrefsEnumProperty.java       # Preferences 枚举属性
└── addons/
    ├── MarkdownSyntaxHighlighterAddon.java  # 语法高亮插件
    ├── PreviewRendererAddon.java            # 预览渲染插件
    ├── PreviewViewAddon.java                # 预览视图插件
    ├── SmartFormatAddon.java                # 智能格式化插件
    └── SpellCheckerAddon.java               # 拼写检查插件
```

**不移植**：`preview/WebViewPreview.java`、`preview/ExternalPreview.java`（外部浏览器）、`preview/CommonmarkPreviewRenderer.java`（改用 flexmark）、`projects/*`、`preview/prism/*`（160+ JS 文件）。

## 实施阶段

### 阶段 0：依赖引入与资源准备

**pom.xml 新增依赖**（[pom.xml](file:///d:/Data/Git/tangluobo/tomata/pom.xml)）：

```xml
<!-- flexmark 全套扩展 -->
<dependency>
  <groupId>com.vladsch.flexmark</groupId>
  <artifactId>flexmark</artifactId>
  <version>0.64.0</version>
</dependency>
<dependency>
  <groupId>com.vladsch.flexmark</groupId>
  <artifactId>flexmark-ext-abbreviation</artifactId>
  <version>0.64.0</version>
</dependency>
<!-- + ext-anchorlink, ext-aside, ext-autolink, ext-definition, ext-footnotes,
     ext-gfm-strikethrough, ext-gfm-tasklist, ext-tables, ext-toc, ext-wikilink,
     ext-yaml-front-matter -->

<!-- controlsfx（PopOver 等） -->
<dependency>
  <groupId>org.controlsfx</groupId>
  <artifactId>controlsfx</artifactId>
  <version>11.2.1</version>
</dependency>

<!-- miglayout -->
<dependency>
  <groupId>com.miglayout</groupId>
  <artifactId>miglayout-javafx</artifactId>
  <version>11.3</version>
</dependency>

<!-- wellbehavedfx（richtextfx 已传递依赖，显式声明） -->
<dependency>
  <groupId>org.fxmisc.wellbehaved</groupId>
  <artifactId>wellbehavedfx</artifactId>
  <version>0.3.3</version>
</dependency>

<!-- LanguageTool（拼写检查） -->
<dependency>
  <groupId>org.languagetool</groupId>
  <artifactId>language-all</artifactId>
  <version>6.0</version>
</dependency>
<dependency>
  <groupId>com.google.guava</groupId>
  <artifactId>guava</artifactId>
  <version>31.1-jre</version>
</dependency>

<!-- cssfx（运行时 CSS 热重载，可选） -->
<dependency>
  <groupId>fr.brouillard.oss</groupId>
  <artifactId>cssfx</artifactId>
  <version>11.5.1</version>
</dependency>
```

**注意**：richtextfx 0.11.7 已引入，其传递依赖包含 reactfx、flowless、undofx。wellbehavedfx 需显式声明。

**资源复制**（从 `D:\Data\Git\github\markdown-writer-fx\src\main\resources\org\markdownwriterfx\` 复制到 `d:\Data\Git\tangluobo\tomata\src\main\resources\markdown\`）：
- `editor/MarkdownEditor.css` - 编辑器样式（行号、段落、高亮 CSS class）
- `prism.css` - 编辑器内代码块高亮样式（不依赖 JS，仅 CSS class）
- `MarkdownWriter.css` - 主样式
- `MarkdownExtensions.properties` - flexmark 扩展配置
- `messages.properties` - 国际化字符串
- `markdown-writer-fx-32.png`、`markdown-writer-fx-128.png` - 图标
- **不复制** `preview/prism/`（160+ JS 文件，WebView 预览用，已跳过）、`preview/preview.js`、`preview/markdownpad-github.css`（WebView 预览样式）

### 阶段 1：核心编辑器移植（editor 包）

从 markdown-writer-fx 复制并适配包名 `org.markdownwriterfx.editor` → `com.tangluobo.tomato.module.connect.markdown.editor`：

1. **MarkdownTextArea.java**：继承 `GenericStyledArea<Collection<String>, Either<String, EmbeddedImage>, Collection<String>>`，支持嵌入图片。适配 Java 24 语法。
2. **LineNumberGutterFactory.java**：行号 gutter 工厂，基于 paragraphIndex 显示行号。
3. **ParagraphOverlayGraphicFactory.java**：段落覆盖层工厂，管理 gutter 和 overlay。
4. **WhitespaceOverlayFactory.java**：空白字符（空格、Tab）可视化。
5. **SmartEdit.java** + **SmartEditActions.java**：智能编辑（自动续行、列表缩进、加粗/斜体/代码包裹、链接/图片插入、表格操作等）+ 右键菜单初始化。
6. **FindReplacePane.java**：查找替换面板（内嵌底部，支持正则、大小写、多命中导航）。**替换当前独立 Stage 的查找替换实现**。
7. **EmbeddedImage.java** + **EmbeddedImageOps.java**：编辑器内嵌图片显示。

### 阶段 2：高亮引擎移植（highlighter 包）

1. **MarkdownSyntaxHighlighter.java**：从 markdown-writer-fx 复制，基于 flexmark AST 的精确高亮。用 `StyleSpans<Collection<String>>` 应用 CSS class（而非 inline css）。支持所有 flexmark 节点类型（标题、粗体、斜体、删除线、链接、图片、代码、代码块、引用、列表、表格、任务列表、脚注、定义列表、HTML、YAML front-matter 等）。
2. **SyntaxHighlighter.java**：通用语法高亮接口（用于 HTML 源码预览）。

**关键改动**：当前 `InlineCssTextArea` 用 inline css 高亮，markdown-writer-fx 用 `StyleClassedTextArea` 模式（CSS class）。需要把编辑器从 `InlineCssTextArea` 改为基于 `GenericStyledArea` 的 `MarkdownTextArea`（用 CSS class）。这会影响 `MarkdownEditor.css` 的加载方式。

### 阶段 3：预览移植（preview 包，跳过 WebView）

1. **MarkdownPreviewPane.java**：预览面板，支持类型切换。**移除 `Web` 和 `External` 类型**，保留 `Source`（HTML 源码）、`Ast`（AST 树）、新增 `Native`（当前原生 TextFlow/VBox）。
2. **FlexmarkPreviewRenderer.java**：flexmark HTML 渲染器（替换当前 commonmark HtmlRenderer）。
3. **HtmlSourcePreview.java** + **PreviewStyledTextArea.java**：HTML 源码高亮预览。
4. **ASTPreview.java**：AST 树预览（TreeView 展示 flexmark AST）。
5. **NativePreviewRenderer.java**：**保留并重构当前原生预览**（TextFlow/VBox + GridPane 表格），从当前 MarkdownEditorPane.java 的 `renderMarkdown` 等方法抽取。改为基于 flexmark AST 遍历渲染（替换 commonmark NodeRenderer）。

### 阶段 4：选项系统移植（options 包）

1. **Options.java**：基于 `java.util.prefs.Preferences`，节点改为 `com/tangluobo/tomato/markdown`。**移除项目级选项**（`getProjectOptions`、`activeProjectProperty`），改为全局选项。保留：fontFamily、fontSize、lineSeparator、encoding、markdownFileExtensions、markdownExtensions、markdownRenderer（移除 CommonMark 选项，只保留 FlexMark）、showLineNo、showWhitespace、showImagesEmbedded、emphasisMarker、strongEmphasisMarker、bulletListMarker、wrapLineLength、formatOnSave、spellChecker、grammarChecker、language、userDictionary、disabledRules、additionalCSS、addonsPath。
2. **OptionsDialog.java** + 各 `*OptionsPane.java`：选项对话框。
3. **MarkdownExtensions.java** + **MarkdownExtensionsPane.java**：flexmark 扩展配置（基于 `MarkdownExtensions.properties`）。
4. **Prefs*Property.java**（5 个）：Preferences 包装为 JavaFX Property。

### 阶段 5：对话框/控件/工具移植（dialogs/controls/util 包）

1. **dialogs/LinkDialog.java**、**ImageDialog.java**：插入链接/图片对话框（MigLayout 布局）。
2. **controls/**：BottomSlidePane（底部滑动面板，用于 FindReplacePane）、BrowseDirectoryButton、BrowseFileButton、EscapeTextField、FileTreeView/Cell/Item、IntSpinner、WebHyperlink。
3. **util/**：Utils、ResultCacheEx、CommonmarkSourcePositions（保留，commonmark 仍可用于源码位置）、Item、Range、StageState、Addons、Action、ActionUtils。

### 阶段 6：拼写检查移植（spellchecker 包）

1. **GlobalLanguageTool.java**：LanguageTool 全局实例（懒加载，按 `Options.language` 初始化）。
2. **SpellChecker.java**：拼写检查器，订阅 `markdownASTProperty`，后台线程检查，结果通过 `SpellBlockProblems` 缓存。
3. **SpellCheckerOverlayFactory.java**：在编辑器段落上叠加拼写错误下划线（红色波浪线）。
4. **UserDictionary.java**：用户词典（文件存储）。
5. **SpellProblem.java**、**SpellRange.java**、**SpellBlockProblems.java**：数据模型。

**Native Image 注意**：LanguageTool 用反射加载规则类，需在 `reachability-metadata.json` 中补充 `org.languagetool.*` 的反射元数据。可能需要 `--initialize-at-run-time=org.languagetool.*` 配置。

### 阶段 7：主类 MarkdownEditorPane 重写与适配

重写 [MarkdownEditorPane.java](file:///d:/Data/Git/tangluobo/tomata/src/main/java/com/tangluobo/tomato/module/connect/MarkdownEditorPane.java)：

- `extends BorderPane`（保留契约）
- 内部委托：`MarkdownTextArea`（编辑器）、`MarkdownPreviewPane`（预览）、`FindReplacePane`（查找替换）、`SpellChecker`（拼写检查）、`SmartEdit`（智能编辑）
- 布局：顶部工具栏（保留当前 FontAwesomeIcon 工具栏）+ 中间 SplitPane（编辑器 | 预览）+ 底部 FindReplacePane（BottomSlidePane）
- **保留当前独有功能**：
  - `Storage` 接口 + S3/OSS 构造器
  - `Mode` 枚举 + 三模式切换（EDIT/EDIT_PREVIEW/PREVIEW）
  - Alt+鼠标矩形选择（从当前实现抽取到独立方法/内部类）
  - `exportPdf()`（PrinterJob）
  - `exportHtml()`（改用 flexmark HtmlRenderer）
  - `InlineStyle` 静态类
  - `Switch` 静态类
- **保留所有 API 契约方法**（见上）
- 加载 `markdown/MarkdownEditor.css`、`markdown/prism.css`

### 阶段 8：调用方适配与编译验证

调用方无需改动（API 契约保留）。仅验证编译：
1. `mvn clean compile` 编译通过
2. 检查 IDE 诊断无错误
3. 启动应用，从 S3/SFTP/本地目录打开 .md 文件，验证：
   - 编辑器正常显示、语法高亮（flexmark AST）
   - 行号 gutter、段落覆盖层、空白字符显示
   - 工具栏按钮（加粗/斜体/链接/图片/代码等）正常
   - 三模式切换（EDIT/EDIT_PREVIEW/PREVIEW）
   - 查找替换面板（底部滑动）
   - 智能编辑（自动续行、列表缩进）
   - 拼写检查（红色下划线、右键建议）
   - 保存到 S3/OSS/本地
   - 导出 HTML/PDF
   - Alt+鼠标矩形选择

### 阶段 9：GraalVM Native Image 元数据收集

1. 运行 `mvn -Pnative package` 触发 exec-maven-plugin 收集元数据
2. 手动点击所有 markdown 编辑器功能（语法高亮、查找替换、拼写检查、导出、三模式切换等）
3. 检查生成的 `reachability-metadata.json`，确认 flexmark/controlsfx/miglayout/languagetool 的反射元数据已收集
4. 可能需要在 pom.xml 的 `<buildArgs>` 中补充：
   - `--initialize-at-run-time=org.languagetool.*`（LanguageTool 需运行时初始化）
   - `--initialize-at-run-time=com.vladsch.flexmark.*`（如需）
5. 验证 native image 打包成功并运行

## 关键风险与缓解

1. **Java 版本差异**：markdown-writer-fx 用 Java 19，当前项目用 Java 24。需适配 record、sealed class、switch 表达式等新语法。flexmark 0.64.0 和 controlsfx 11.2.1 应兼容 Java 24。
2. **Native Image 兼容性**：LanguageTool 是最大风险。如打包失败，可回退为"拼写检查功能在 native image 下禁用"（运行时检测 `org.graalvm.nativeimage.ImageInfo.inImageRuntimeCode()`）。
3. **API 契约破坏**：调用方依赖 `extends BorderPane` 和具体方法签名。重写时必须逐个对照保留。
4. **预览渲染差异**：当前原生预览基于 commonmark NodeRenderer，改用 flexmark AST 后需重写遍历逻辑。表格、代码块、任务列表等渲染需逐一验证。
5. **CSS class vs inline css**：从 `InlineCssTextArea` 改为 `StyleClassedTextArea` 模式后，高亮样式从 inline css 改为 CSS class，需确保 `MarkdownEditor.css` 正确加载。

## 验证清单

- [ ] `mvn clean compile` 编译通过，无错误
- [ ] IDE 诊断无错误
- [ ] 从 S3 打开 .md 文件，编辑器正常显示
- [ ] 从 SFTP 打开 .md 文件，编辑器正常显示
- [ ] 从本地目录打开 .md 文件，编辑器正常显示
- [ ] 语法高亮（标题、粗体、斜体、代码、链接、图片、表格、任务列表、脚注、YAML front-matter）
- [ ] 行号 gutter 显示/隐藏
- [ ] 空白字符显示/隐藏
- [ ] 工具栏按钮全部可用（加粗、斜体、代码、链接、图片、标题、列表、引用、表格等）
- [ ] 三模式切换（EDIT / EDIT_PREVIEW / PREVIEW）
- [ ] 查找替换（正则、大小写、多命中导航、替换/全部替换）
- [ ] 智能编辑（列表自动续行、缩进、加粗包裹等）
- [ ] Alt+鼠标矩形选择、删除、多行插入
- [ ] 拼写检查（红色下划线、右键建议、用户词典）
- [ ] 保存到 S3/OSS/本地文件
- [ ] 标题更新（modified 标记 `*`、保存后恢复）
- [ ] 导出 HTML
- [ ] 导出/打印 PDF
- [ ] Tab 关闭时检查 modified 并提示保存
- [ ] Options 对话框（字体、行号、空白、扩展、拼写检查等）
- [ ] `mvn -Pnative package` native image 打包成功
- [ ] native image 运行时所有功能正常

## 文件影响范围

**新增文件**（约 50+ 个 Java 文件）：见"目标包结构"

**修改文件**：
- [pom.xml](file:///d:/Data/Git/tangluobo/tomata/pom.xml) - 新增依赖
- [MarkdownEditorPane.java](file:///d:/Data/Git/tangluobo/tomata/src/main/java/com/tangluobo/tomato/module/connect/MarkdownEditorPane.java) - 重写（保留 API 契约）
- 调用方（S3FileBrowserPane/SFTPFileBrowserPane/LocalDirectoryConnectHandler）- 原则上不改，仅在 API 签名有微调时适配

**新增资源**：
- `src/main/resources/markdown/MarkdownEditor.css`
- `src/main/resources/markdown/prism.css`
- `src/main/resources/markdown/MarkdownWriter.css`
- `src/main/resources/markdown/MarkdownExtensions.properties`
- `src/main/resources/markdown/messages.properties`
- `src/main/resources/markdown/markdown-writer-fx-32.png`、`markdown-writer-fx-128.png`

**删除资源**：
- `src/main/resources/css/markdown-editor-toolbar.css`（合并到 MarkdownWriter.css）

## 实施顺序建议

由于工程规模庞大（50+ 新文件），建议分批次实施，每批次完成后编译验证：

1. **批次 1**：阶段 0（依赖 + 资源）+ 阶段 1 核心（MarkdownTextArea + LineNumberGutterFactory + ParagraphOverlayGraphicFactory + WhitespaceOverlayFactory）
2. **批次 2**：阶段 1 剩余（SmartEdit + SmartEditActions + FindReplacePane + EmbeddedImage）+ 阶段 2（高亮引擎）
3. **批次 3**：阶段 4（选项系统）+ 阶段 5（对话框/控件/工具）
4. **批次 4**：阶段 3（预览）+ 阶段 6（拼写检查）
5. **批次 5**：阶段 7（主类重写 + 适配独有功能）
6. **批次 6**：阶段 8（编译验证 + 功能测试）
7. **批次 7**：阶段 9（native image 元数据 + 打包验证）

每个批次实施后运行 `mvn clean compile` 确保编译通过，再进入下一批次。
