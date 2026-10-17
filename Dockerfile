FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src src
RUN mvn package -DskipTests -B

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app app
USER app
COPY --from=build /app/target/reconciliation-api-1.0.0.jar app.jar
EXPOSE 8110
ENTRYPOINT ["java", "-jar", "app.jar"]
