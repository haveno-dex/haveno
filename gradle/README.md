# How to upgrade the Gradle version

Visit the [Gradle website](https://gradle.org/releases/) and decide the:

 - desired version
 - desired distribution type
 - what is the sha256 for the version and type chosen above

Adjust the following command with tha arguments above and execute it twice:

    ./gradlew wrapper --gradle-version 7.3.3 \
        --distribution-type all \
        --gradle-distribution-sha256-sum c9490e938b221daf0094982288e4038deed954a3f12fb54cbf270ddf4e37d879

The first execution should automatically update:

 - `haveno/gradle/wrapper/gradle-wrapper.properties`

The second execution should then update:

 - `haveno/gradle/wrapper/gradle-wrapper.jar`
 - `haveno/gradlew`
 - `haveno/gradlew.bat`

The four updated files are ready to be committed.
