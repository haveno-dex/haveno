# How to upgrade the Gradle version

Visit the [Gradle website](https://gradle.org/releases/) and decide the:

 - desired version
 - desired distribution type
 - what is the sha256 for the version and type chosen above

Adjust the following command with tha arguments above and execute it twice:

    ./gradlew wrapper --gradle-version 8.2.1 \
        --distribution-type all \
        --gradle-distribution-sha256-sum 7c3ad722e9b0ce8205b91560fd6ce8296ac3eadf065672242fd73c06b8eeb6ee

The first execution should automatically update:

 - `haveno/gradle/wrapper/gradle-wrapper.properties`

The second execution should then update:

 - `haveno/gradle/wrapper/gradle-wrapper.jar`
 - `haveno/gradlew`
 - `haveno/gradlew.bat`

The four updated files are ready to be committed.
