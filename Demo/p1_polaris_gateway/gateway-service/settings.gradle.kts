pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
    maven {
      url = uri("../../../src_ref/.m2/repository")
      mavenContent { releasesOnly() }
    }
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    mavenCentral()
    maven {
      url = uri("../../../src_ref/.m2/repository")
      mavenContent { releasesOnly() }
    }
  }
}

rootProject.name = "p1-polaris-gateway-service"
