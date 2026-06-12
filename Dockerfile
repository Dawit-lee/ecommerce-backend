# ===== 1단계: 빌드 (JDK로 jar 생성) =====
FROM eclipse-temurin:24-jdk AS build
WORKDIR /app

# 의존성 먼저 받아서 캐시 활용 (소스만 바뀌면 이 단계는 재사용)
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw dependency:go-offline -B

# 소스 복사 후 빌드 (테스트는 빌드 단계에서 제외 — CI에서 따로 돌림)
COPY src ./src
RUN ./mvnw clean package -DskipTests -B

# ===== 2단계: 실행 (가벼운 JRE 이미지에 jar만 복사) =====
FROM eclipse-temurin:24-jre
WORKDIR /app

# 보안: root가 아닌 일반 유저로 실행
RUN useradd -r -u 1001 appuser
COPY --from=build /app/target/sb-ecom-0.0.1-SNAPSHOT.jar app.jar
USER appuser

# EB/배포 환경에서 기대하는 포트
EXPOSE 5000

ENTRYPOINT ["java", "-jar", "app.jar"]
