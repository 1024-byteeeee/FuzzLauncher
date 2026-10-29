# Third-party components

Fuzz's source is independently implemented. HMCL and PCL are design references; their source code is not included.

- OpenJDK / Azul Zulu and OpenJFX: GPLv2 with Classpath Exception and component notices. Runtime documentation is excluded from distribution packages.
- Gson: Apache-2.0, https://github.com/google/gson
- MSAL4J: MIT, https://github.com/AzureAD/microsoft-authentication-library-for-java
- JNA / JNA Platform: LGPL-2.1-or-later or Apache-2.0, https://github.com/java-native-access/jna
- SLF4J: MIT, https://www.slf4j.org

Dependency JARs retain their original embedded licenses and notices. Gradle resolves transitive dependencies; see `gradlew dependencies` and the JARs in `app/lib`.
The installer uses the Simplified Chinese translation for Inno Setup 6.5.0+, maintained by Zhenghan Yang (Kira): https://github.com/jrsoftware/issrc/blob/6ef32198ef1f7b7b375cd4b6b90896c2a58eb4c2/Files/Languages/ChineseSimplified.isl
Fabric, Forge, NeoForge and their processor artifacts are downloaded from official repositories or the selected mirror at installation time. Their respective licenses continue to apply.
- BMCLAPI public download mirror: https://bmclapi2.bangbang93.com/ — API documentation: https://bmclapidoc.bangbang93.com/
- Tencent Cloud public Maven mirror: https://mirrors.cloud.tencent.com/nexus/repository/maven-public/
- NeoForge fallback CDN: https://neoforged.forgecdn.net/
Loader logos identify their respective projects and remain the property of their respective owners:
The `*-retro.png` variants are AI-assisted Windows 95 style adaptations of these logos.

- Fabric: https://github.com/FabricMC/fabricmc.net/blob/HEAD/assets/logo.png
- Forge: https://github.com/MinecraftForge/Documentation/blob/HEAD/forge_theme/images/android-chrome-192x192.png
- NeoForge: https://github.com/neoforged/Documentation/blob/HEAD/static/img/neoforged.png

Other UI images are extracted from the user-approved AI-generated reference image.
