plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.paparazzi) apply false
}

// Code checks, run as the tools' own command-line versions (no build plugins):
//   ./gradlew ktlintCheck    formatting, per .editorconfig
//   ./gradlew ktlintFormat   fixes what ktlintCheck finds
//   ./gradlew detekt         code smells; findings already in the code are in config/detekt/baseline.xml
//   ./gradlew detektBaseline rewrites that baseline (only after deciding the findings are fine)
val ktlint: Configuration by configurations.creating
val detekt: Configuration by configurations.creating

dependencies {
    ktlint("com.pinterest.ktlint:ktlint-cli:${libs.versions.ktlint.get()}") {
        attributes { attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL)) }
    }
    detekt("io.gitlab.arturbosch.detekt:detekt-cli:${libs.versions.detekt.get()}")
}

val kotlinFiles = listOf("app/src/**/*.kt", "*.kts", "app/*.kts")

tasks.register<JavaExec>("ktlintCheck") {
    group = "verification"
    description = "Checks Kotlin formatting against .editorconfig."
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    args(listOf("--relative") + kotlinFiles)
}

tasks.register<JavaExec>("ktlintFormat") {
    group = "formatting"
    description = "Fixes Kotlin formatting to match .editorconfig."
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    args(listOf("--relative", "--format") + kotlinFiles)
}

fun JavaExec.detektCli(vararg extra: String) {
    classpath = detekt
    mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")
    args(
        "--input", "app/src/main/java,app/src/test/java",
        "--config", "config/detekt/detekt.yml",
        "--build-upon-default-config",
        "--baseline", "config/detekt/baseline.xml",
        "--base-path", rootDir.absolutePath,
        "--jvm-target", "17",
        *extra,
    )
}

tasks.register<JavaExec>("detekt") {
    group = "verification"
    description = "Static analysis with detekt; fails on findings not in config/detekt/baseline.xml."
    detektCli()
}

tasks.register<JavaExec>("detektBaseline") {
    group = "verification"
    description = "Rewrites config/detekt/baseline.xml with every current detekt finding."
    detektCli("--create-baseline")
}
