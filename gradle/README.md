# How to upgrade the Gradle version

Visit the [Gradle website](https://gradle.org/releases/) and decide the:

 - desired version
 - desired distribution type
 - what is the sha256 for the version and type chosen above

Adjust the following command with tha arguments above and execute it twice:

    ./gradlew wrapper --gradle-version 8.0.2 \
        --distribution-type all \
        --gradle-distribution-sha256-sum 47a5bfed9ef814f90f8debcbbb315e8e7c654109acd224595ea39fca95c5d4da

The first execution should automatically update:

 - `haveno/gradle/wrapper/gradle-wrapper.properties`

The second execution should then update:

 - `haveno/gradle/wrapper/gradle-wrapper.jar`
 - `haveno/gradlew`
 - `haveno/gradlew.bat`

The four updated files are ready to be committed.
