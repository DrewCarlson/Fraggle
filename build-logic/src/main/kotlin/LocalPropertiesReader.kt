import org.gradle.api.file.Directory
import java.util.Properties

fun gradleLocalProperties(projectRootDir: Directory): Properties {
    val properties = Properties()
    val localProperties = projectRootDir.file("local.properties").asFile
    if (localProperties.exists()) {
        localProperties.bufferedReader().use(properties::load)
    } else {
        error("Gradle local.properties file not found at $localProperties")
    }
    return properties
}