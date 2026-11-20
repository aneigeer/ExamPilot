# 多阶段构建：无需宿主机预装 JDK / Maven，直接 docker compose up -d --build 即可运行
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
ENV TZ=Asia/Shanghai
COPY --from=builder /build/target/exam-prep-backend-1.0.0-SNAPSHOT.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar", "--server.port=8080"]
