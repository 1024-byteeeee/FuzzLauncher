# 启动器图标

`src/main/resources/com/fuzzlauncher/launcher.png` 为窗口与任务栏图标，`launcher.ico` 为打包程序图标。ICO 包含 16、24、32、48、64、128、256 像素的透明 PNG 图像条目。

2026-10-29 使用内置 image_gen 编辑现有草方块图标以减少留白，再转换为 PNG 和 ICO 所需尺寸。原图主体在 256×256 画布内的范围为 (32,20)–(219,231)。

生成提示词：

Edit target is the attached existing FuzzLauncher grass block app icon. Only reframe and enlarge this exact existing grass cube for a Windows taskbar application icon. Preserve its exact isometric grass block silhouette, green grass top, brown dirt sides, pixel art identity and orientation. Do not redesign or add any details, frame, background or shadow. Remove excess transparent margins: the visible cube should be centered and span 96 percent of canvas height and nearly the full width consistent with its original aspect ratio. Actual transparent background. Crisp existing retro pixel art edges, no blur, no text. Single icon.
