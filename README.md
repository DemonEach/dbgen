# dbgen

A cli tool to generate data for database

### Requirements

- Java 21  
- Gradle

### Currently supported databases

- Postgresql
    - Generation strategies:
      - With csv file via "COPY" (WIP)
      - Bulk inserts
      - Inserts

### How to launch

1. Compile with `gradlew build`
2. Get your `app.jar` file
3. Create `application.yaml` and place it at same folder as jar file at step 2
4. You can see example configuration for `application-example.yaml` in root folder of the project with documentation for each field
5. Launch `app.jar` file with `java -jar app.jar`