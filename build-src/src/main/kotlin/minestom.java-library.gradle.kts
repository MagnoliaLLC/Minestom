plugins {
    `java-library`
    id("minestom.style")
}

val javaVersion = System.getenv("JAVA_VERSION") ?: "25"

group = "net.minestom"
version = System.getenv("MINESTOM_VERSION") ?: run {
    val mcVersion = (libs.minestomData.get().version ?: "unknown").substringBefore("-")
    // In CI we publish a monotonically increasing build number so version sorting
    // (Renovate, Maven) is well-defined. Locally we fall back to the commit hash.
    val suffix = System.getenv("BUILD_NUMBER") ?: providers.exec {
        commandLine("git", "rev-parse", "--short=8", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.map { it.trim() }.getOrElse("nogit")
    "$mcVersion-$suffix"
}

configurations.all {
    // We only use Jetbrains Annotations
    exclude("org.checkerframework", "checker-qual")
}

val adventureVersion = libs.adventure.api.get().version ?: ""

repositories {
    val dataVersion = libs.minestomData.get().version ?: ""
    if (dataVersion.endsWith("-dev"))
        mavenLocal()
    if (adventureVersion.endsWith("-SNAPSHOT"))
        maven(url = "https://central.sonatype.com/repository/maven-snapshots/")

    mavenCentral()
}

dependencies {
    // Core dependencies
    api(libs.jetbrainsAnnotations)

    // Testing
    testImplementation(libs.bundles.junit)
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(javaVersion)
    modularity.inferModulePath = true

    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.withType<Javadoc> {
    (options as? StandardJavadocDocletOptions)?.apply {
        encoding = "UTF-8"

        // Custom options
        addBooleanOption("html5", true)
        addStringOption("-release", javaVersion)
        // Links to external javadocs
        links("https://docs.oracle.com/en/java/javase/$javaVersion/docs/api/")
        if (!adventureVersion.endsWith("-SNAPSHOT")) {
            links("https://jd.papermc.io/adventure/${libs.versions.adventure.get()}/")
        }
        links("https://javadoc.io/doc/com.google.code.gson/gson/${libs.versions.gson.get()}/")
        links("https://javadoc.io/doc/org.jetbrains/annotations/${libs.versions.jetbrainsAnnotations.get()}/")

        tags("apiNote:a:API Note:", "implSpec:a:Implementation Requirements:", "implNote:a:Implementation Note:")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()

    // Viewable packets make tracking harder. Could be re-enabled later.
    jvmArgs("-Dminestom.viewable-packet=false")
    jvmArgs("-Dminestom.inside-test=true")
    jvmArgs("-Dminestom.acquirable-strict=true")
    minHeapSize = "512m"
    maxHeapSize = "1024m"
}

tasks.withType<Zip> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
