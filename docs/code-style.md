# 代码格式

本项目的排版参照 `Carpet-AMS-Addition` 中手写的 Java 工具类和业务代码：

- 使用 4 个空格缩进，续行额外缩进 4 个空格，左花括号放在行尾。
- 方法、构造器和调用在长度允许时保持一行，较长表达式再换行；自动格式化参考行宽为 140 字符。
- 条件和循环使用完整花括号，`else if` 保持连续书写。
- 导入依次分为项目内部、其他依赖、Java 标准库和静态导入，组间空一行。
- 保留现有名称、注释内容和字符串，不为了排版改写业务逻辑。
- CSS 每个属性单独一行，块内使用 4 个空格缩进。

IntelliJ IDEA 的格式设置保存在根目录 `.editorconfig`，可配置的 Eclipse JDT Java 格式规则保存在 `packaging/java-format.properties`。
不要使用 Google Java Format 或 AOSP 预设覆盖这些规则；它们的续行缩进和换行策略不同。
