# Build từ thư mục gốc repo để module server lấy được common và statics/words_en.csv.
FROM maven:3-eclipse-temurin-25-noble AS build
WORKDIR /app
COPY source/pom.xml source/pom.xml
COPY source/common source/common
COPY source/server source/server
# Maven cần đọc POM của đủ các module trong reactor, dù không build client JavaFX.
COPY source/client/pom.xml source/client/pom.xml
COPY statics/words_en.csv statics/words_en.csv
RUN mvn -B -f source/pom.xml -pl server -am package -DskipTests

# Container chạy chỉ cần Java 25 và JAR đã chứa common, Jackson, kho từ vựng.
FROM eclipse-temurin:25-jre-noble
WORKDIR /app
COPY --from=build /app/source/server/target/skribbl-server.jar app.jar
USER 10001:10001
EXPOSE 8888
ENTRYPOINT ["java", "-jar", "app.jar"]
