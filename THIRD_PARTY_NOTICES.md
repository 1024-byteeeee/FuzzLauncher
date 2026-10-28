# Third-party components

Fuzz's source is independently implemented. HMCL and PCL are design references; their source code is not included.

- OpenJDK / Azul Zulu and OpenJFX: GPLv2 with Classpath Exception and component notices; runtime `legal` directories are preserved.
- Gson: Apache-2.0, https://github.com/google/gson
- MSAL4J: MIT, https://github.com/AzureAD/microsoft-authentication-library-for-java
- JNA / JNA Platform: LGPL-2.1-or-later or Apache-2.0, https://github.com/java-native-access/jna
- SLF4J: MIT, https://www.slf4j.org

Dependency JARs retain their original embedded licenses and notices. Gradle resolves transitive dependencies; see `gradlew dependencies` and the JARs in `app/lib`.
Fabric, Forge, NeoForge and their processor artifacts are downloaded from official repositories or the selected mirror at installation time. Their respective licenses continue to apply.
- BMCLAPI public download mirror: https://bmclapi2.bangbang93.com/ — API documentation: https://bmclapidoc.bangbang93.com/
- Nanjing University public Maven library mirror: https://mirror.nju.edu.cn/bmclapi/
UI images are extracted from the user-approved AI-generated reference image.
