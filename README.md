# CarMediaSession

## use guide
1. add below code in project level build.gradle

<pre>
allprojects {
    ...
    repositories {
        google()
        jcenter()
        maven { url 'https://jitpack.io' }
    }
    ...
}

ext {
    minSdkVersion = 28
    targetSdkVersion = 28
    compileSdkVersion = 28
    androidx_media = '1.2.1'
    framework_jar = "${rootProject.projectDir}/app/libs/framework.jar"
    carlib_jar = "${rootProject.projectDir}/app/libs/car-lib.jar"
}
</pre>

2. add below code in application level build.gradle

<pre>
dependencies {
    ...
    implementation 'com.github.oxsource:CarMediaSession:V1.0.0'
    ...
}
</pre>
