plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.playtranslate.buildtools.BuildTargetPackKt")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

dependencies {
    implementation(libs.lucene.core)
    implementation(libs.sqlite.jdbc)
    implementation(libs.gson)
}
